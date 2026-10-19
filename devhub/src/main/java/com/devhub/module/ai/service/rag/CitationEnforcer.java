package com.devhub.module.ai.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用执行器（方案二，对标 FastGPT/Dify 的引用标注 + LlamaIndex CitationQueryEngine）
 * <p>
 * 与生成提示词的 [n] 标注规范配套，对答案做逐句核查：
 * 无引用标记的"实质陈述句"（长句、非标题/引导语）视为无据内容，
 * 由编排层决定重生成或剥离，防止模型夹带资料之外的知识。
 */
@Slf4j
@Component
public class CitationEnforcer {

    /** 形如 [1] / [1][3] 的引用标记 */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[\\d+]");

    /** 实质陈述句的最小长度（短句多为引导/过渡语，不强制引用） */
    private static final int MIN_CLAIM_CHARS = 15;

    /**
     * 校验结果
     */
    public record Result(int totalClaims, int citedClaims, List<String> uncitedSentences) {
        public double citedRatio() {
            return totalClaims == 0 ? 1.0 : (double) citedClaims / totalClaims;
        }

        public boolean pass() {
            return uncitedSentences.isEmpty();
        }
    }

    /**
     * 逐句检查答案的引用覆盖情况
     */
    public Result check(String answer) {
        if (answer == null || answer.isBlank()) {
            return new Result(0, 0, List.of());
        }
        List<String> uncited = new ArrayList<>();
        int total = 0;
        int cited = 0;
        for (String sentence : splitSentences(answer)) {
            if (!isClaim(sentence)) {
                continue;
            }
            total++;
            if (CITATION_MARKER.matcher(sentence).find()) {
                cited++;
            } else {
                uncited.add(sentence.trim());
            }
        }
        return new Result(total, cited, uncited);
    }

    /**
     * 剥离无引用的实质陈述句（重生成后仍未达标的兜底处理）。
     * 只剥离"无引用且内容上也不被资料支撑"的句子——
     * 漏标但内容确实来自资料的句子（如命令名引导句）保留，避免误伤关键信息。
     *
     * @param answer  答案文本
     * @param context 编号参考资料原文（用于判断句子是否有据）
     */
    public String strip(String answer, String context) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        StringBuilder kept = new StringBuilder();
        for (String sentence : splitSentences(answer)) {
            if (isClaim(sentence) && !CITATION_MARKER.matcher(sentence).find()
                    && !contextuallySupported(sentence, context)) {
                log.info("剥离无据句子: {}", trim(sentence, 60));
                continue;
            }
            kept.append(sentence);
        }
        return kept.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 句子内容是否被资料支撑：抽取英文词与中文二元词组，按重叠率判断
     */
    private boolean contextuallySupported(String sentence, String context) {
        if (context == null || context.isBlank()) {
            return false;
        }
        java.util.Set<String> tokens = new java.util.HashSet<>();
        Matcher en = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{1,20}").matcher(sentence);
        while (en.find()) {
            tokens.add(en.group().toLowerCase());
        }
        Matcher zh = Pattern.compile("[\\u4e00-\\u9fa5]{2}").matcher(sentence);
        while (zh.find()) {
            tokens.add(zh.group());
        }
        if (tokens.isEmpty()) {
            return true; // 无可判内容词（纯引导语），保守保留
        }
        long hit = tokens.stream().filter(context::contains).count();
        return (double) hit / tokens.size() >= 0.3;
    }

    /**
     * 构造重生成提示（带上首轮未引用的句子，指导模型修正）
     */
    public String retryPrompt(String question, String numberedContext, List<String> uncitedSentences) {
        StringBuilder feedback = new StringBuilder();
        for (String s : uncitedSentences) {
            feedback.append("  - ").append(trim(s, 80)).append("\n");
        }
        return String.format("""
                你是企业知识库问答助手。你的上一版回答中，以下句子没有标注来源编号，涉嫌使用了参考资料之外的内容：

                %s
                请重新回答用户问题。要求：
                1. 只使用参考资料中的信息，每个事实要点句末标注来源编号（如 [1]）
                2. 无法从资料得到支撑的内容直接删掉，不要改写保留
                3. 资料不足以回答时回复"知识库中暂无相关信息"

                参考资料：
                %s

                用户问题：%s

                请回答：
                """, feedback, numberedContext, question);
    }

    /**
     * 按中英文句末标点和换行切句，保留原分隔符
     */
    private List<String> splitSentences(String text) {
        List<String> parts = new ArrayList<>();
        Matcher m = Pattern.compile("[^。！？!?\n]*(?:[。！？!?]|\\n+|$)").matcher(text);
        while (m.find()) {
            String part = m.group();
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    /**
     * 是否为需要引用的"实质陈述句"：足够长的普通文本行。
     * 标题行（#/数字编号开头）、以冒号结尾的引导行、列表符号行不算。
     */
    private boolean isClaim(String sentence) {
        String s = sentence.trim();
        if (s.length() < MIN_CLAIM_CHARS) {
            return false;
        }
        if (s.startsWith("#") || s.startsWith("*") || s.startsWith("-")) {
            return false;
        }
        if (s.endsWith("：") || s.endsWith(":")) {
            return false;
        }
        // "1. **xxx**" 型标题行（其后通常紧跟冒号或本身是列表头）
        if (s.matches("^\\d+[.、)].{0,20}$")) {
            return false;
        }
        return true;
    }

    private String trim(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
