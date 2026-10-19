package com.devhub.module.ai.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入防护（架构对标 NeMo Guardrails 的三层 rail + LLM Guard 的 Scanner 概念）
 * <ul>
 *   <li>Input rail：用户消息注入检测（窄模式启发式，LLM Guard 用 DeBERTa 分类器，
 *       Java 栈无本地推理，以高置信模式库等价实现）</li>
 *   <li>Retrieval rail：检索内容中和——指令样文本是数据不是指令（OpenAI 指令层级实践）</li>
 *   <li>Output rail：canary 哨兵检测系统提示词泄露（Rebuff 思路）</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptGuard {

    /** 埋在系统提示词里的哨兵：出现在输出中即判定泄露（Rebuff canary） */
    public static final String CANARY = "GUARD-9f2c-canary";

    /**
     * 高置信注入模式（刻意收窄，避免误杀正常提问）：
     * 覆盖"覆盖指令""套取系统提示词""越狱模式"三类典型攻击
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 覆盖/忽略既有指令
            Pattern.compile("忽略[(之前|以上|前面|上述|一切|所有)].{0,16}(指令|规则|要求|提示|设定)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(ignore|disregard|forget).{0,24}(previous|above|prior|all).{0,20}(instruction|rule|prompt)", Pattern.CASE_INSENSITIVE),
            // 套取系统提示词/初始指令
            Pattern.compile("(输出|打印|显示|复述|泄露|reveal|show|print).{0,12}(你的)?(完整)?(系统)?(提示词|初始指令|system prompt|instructions)", Pattern.CASE_INSENSITIVE),
            // 角色覆盖/越狱
            Pattern.compile("你(现在)?(是一个|扮演|充当).{0,16}(系统|开发者|无限制|不受限|DAN)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(developer mode|DAN mode|jailbreak|越狱模式|开发者模式)", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 检索内容中的指令样模式：命中即把该行当数据处理（中和），防止间接注入
     */
    private static final List<Pattern> DOC_INJECTION_PATTERNS = List.of(
            Pattern.compile("(忽略|无视|撤销).{0,12}(指令|规则|提示|设定|系统)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(ignore|disregard|override).{0,20}(instruction|rule|prompt|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(你现在是|你的新角色|从现在开始你是|你的任务是回答)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system prompt|系统提示词|初始指令).{0,10}(是|为|如下|：|:)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(开发者模式|developer mode|DAN)", Pattern.CASE_INSENSITIVE)
    );

    private static final String NEUTRALIZED_MARK = "〔该行含疑似注入指令，已按数据隔离中和〕";

    @Value("${agent.security.input-action:block}")
    private String inputAction;

    @Value("${agent.security.strip-injected-lines:true}")
    private boolean stripInjectedLines;

    public record ScanResult(boolean blocked, List<String> hits) {}

    /**
     * Input rail：检测用户消息中的注入攻击
     *
     * @return blocked=true 表示命中高置信模式且策略为 block
     */
    public ScanResult scanInput(String message) {
        List<String> hits = match(message, INJECTION_PATTERNS);
        boolean blocked = !hits.isEmpty() && "block".equalsIgnoreCase(inputAction);
        if (!hits.isEmpty()) {
            log.warn("输入安全栏触发: hits={}, action={}, message={}",
                    hits, blocked ? "block" : "log", abbreviate(message));
        }
        return new ScanResult(blocked, hits);
    }

    /**
     * Retrieval rail：中和检索内容中的指令样文本（整行替换为标记，保持行数不破坏结构）
     */
    public String sanitizeRetrieved(String content) {
        if (content == null || content.isBlank() || !stripInjectedLines) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean touched = false;
        for (String line : lines) {
            if (!match(line, DOC_INJECTION_PATTERNS).isEmpty()) {
                sb.append(NEUTRALIZED_MARK);
                touched = true;
            } else {
                sb.append(line);
            }
            sb.append("\n");
        }
        if (touched) {
            log.warn("检索内容安全栏：中和了 {} 处疑似注入行", countMark(sb));
            return sb.substring(0, sb.length() - 1);
        }
        return content;
    }

    /**
     * Output rail：检测系统提示词泄露（canary 哨兵出现在输出中）
     */
    public boolean leaksCanary(String output) {
        return output != null && output.contains(CANARY);
    }

    private List<String> match(String text, List<Pattern> patterns) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                hits.add(p.pattern());
            }
        }
        return hits;
    }

    private int countMark(StringBuilder sb) {
        int count = 0;
        int idx = 0;
        while ((idx = sb.indexOf(NEUTRALIZED_MARK, idx)) >= 0) {
            count++;
            idx += NEUTRALIZED_MARK.length();
        }
        return count;
    }

    private String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
