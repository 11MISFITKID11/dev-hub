package com.devhub.module.ai.etl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档切片器
 * <p>
 * 结构感知切片（参考 LlamaIndex NodeParser 思路）：
 * 优先按文档结构边界切分——Markdown 标题、中文"第X章/条/节"、
 * 中文序号（一、二、）、数字序号（1. 2.）；
 * 无结构特征时回退到段落感知的定长切片（带重叠）。
 */
@Slf4j
@Component
public class DocumentChunker {

    @Value("${agent.rag.chunk-size:512}")
    private int chunkSize;

    @Value("${agent.rag.chunk-overlap:64}")
    private int chunkOverlap;

    /** 子块目标长度（Parent-Child 检索：小块匹配、大块生成） */
    @Value("${agent.rag.child-size:200}")
    private int childSize;

    /**
     * 结构边界：行首的标题/条款/序号标记
     */
    private static final Pattern STRUCTURE_PATTERN = Pattern.compile(
            "^(#{1,4}\\s+\\S.*)"                              // Markdown 标题
                    + "|(第[一二三四五六七八九十百千\\d]+[章条节部分篇][\\s：:.、]*)"  // 第X章/条/节
                    + "|([一二三四五六七八九十]+[、.])"        // 中文序号
                    + "|(\\(\\d+\\)|（\\d+\\)|\\d+[、.])"      // 数字序号
                    + "|(\\*\\*[^*]+\\*\\*$)"                  // 加粗独立行（常见小标题）
    );

    /**
     * 一级标题（章级）：Markdown #/##、第X章/篇/部分、中文顿号序号短行（"一、编程规约"）。
     * 用于 RAGFlow Laws 式的上下文继承——每个 chunk 前置所属章节路径，
     * 脱离原文也能定位条款位置。短行+无句末标点的启发式与序号列表项区分。
     */
    private static final Pattern HEADING_L1_PATTERN = Pattern.compile(
            "^(#{1,2}\\s+\\S.+)"
                    + "|(第[一二三四五六七八九十百千\\d]+[章篇部分][\\s：:.、]?)"
                    + "|([一二三四五六七八九十]+、[^，。；？！]{2,28})$"
    );

    /**
     * 二级标题（节级）：Markdown ###/####、第X节、短行中文/数字序号标题
     * （如"(一) 命名风格"）。标题启发式同 RAGFlow：行短且不含句末标点，
     * 借此与序号列表项（"1. 【强制】长文本……"）区分。
     */
    private static final Pattern HEADING_L2_PATTERN = Pattern.compile(
            "^(#{3,4}\\s+\\S.+)"
                    + "|(第[一二三四五六七八九十百千\\d]+节[\\s：:.、]?)"
                    + "|(\\*\\*[^*]{2,40}\\*\\*$)"
    );
    private static final Pattern NUMBERED_SHORT_TITLE = Pattern.compile(
            "^([一二三四五六七八九十]+、|\\(?[一二三四五六七八九十\\d]+[)）、.]|\\(\\d+\\)|（\\d+\\)|\\d+[、.])[^，。；？！,:；]{2,30}$"
    );

    /** 标题注入的最大长度（防止异常长标题污染 chunk） */
    private static final int HEADING_MAX_CHARS = 60;

    /** 父块最小长度：短于此的段并入相邻块，不独立成块 */
    private static final int MIN_PARENT_CHARS = 80;

    /**
     * 将文本切分为 Chunk 列表
     *
     * @param text       原始文本
     * @param documentId 文档 ID
     * @return Chunk 列表
     */
    public List<Chunk> chunk(String text, String documentId) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Chunk> chunks = structuredChunk(text, documentId);
        if (chunks.isEmpty()) {
            chunks = paragraphChunk(text, documentId);
        }
        // 真实网页正文常混入空段（cookie 提示/隐私横幅等）：空/纯空白切片必须剔除，
        // 否则向量化批量调用整批失败（embed 不接受空文本）
        chunks = chunks.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .toList();

        log.info("文档切片完成: documentId={}, totalChunks={}, avgChunkSize={}",
                documentId, chunks.size(),
                chunks.isEmpty() ? 0 : chunks.stream().mapToInt(c -> c.getContent().length()).average().orElse(0));
        return chunks;
    }

    /**
     * 结构感知切片：按结构行分段，再把相邻段聚合到不超过 chunkSize
     */
    private List<Chunk> structuredChunk(String text, String documentId) {
        String[] lines = text.split("\n");
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (isStructureBoundary(trimmed) && current.length() > 0) {
                segments.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (!trimmed.isEmpty()) {
                current.append(line.trim()).append("\n");
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString().trim());
        }

        if (segments.size() <= 1) {
            return List.of();
        }

        // 聚合相邻段：结构段 + 后续无结构段，直到超过 chunkSize
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int index = 0;
        String l1Heading = null; // 章级标题（一、编程规约 / 第X章）
        String l2Heading = null; // 节级标题（(一) 命名风格 / 第X节）

        for (String segment : segments) {
            String heading = headingOf(segment);
            if (heading != null) {
                if (heading.startsWith("L1:")) {
                    l1Heading = heading.substring(3);
                    l2Heading = null;
                } else {
                    l2Heading = heading.substring(3);
                }
            }
            String headingPath = joinHeadingPath(l1Heading, l2Heading);

            // 单段超长时强制切分
            if (segment.length() > chunkSize) {
                if (buffer.length() > 0) {
                    chunks.add(createChunk(enrich(buffer.toString(), headingPath), documentId, index++));
                    buffer = new StringBuilder();
                }
                for (String part : splitLongText(segment)) {
                    chunks.add(createChunk(enrich(part, headingPath), documentId, index++));
                }
                continue;
            }

            // 短段（<80字）不触发切分，强制并入当前块——避免标题/过渡句成为独立碎块
            if (buffer.length() + segment.length() + 1 > chunkSize && buffer.length() > 0
                    && segment.length() >= MIN_PARENT_CHARS) {
                chunks.add(createChunk(enrich(buffer.toString(), headingPath), documentId, index++));
                // 保留少量尾部重叠以维持上下文
                String tail = buffer.length() > chunkOverlap
                        ? buffer.substring(buffer.length() - chunkOverlap) : buffer.toString();
                buffer = new StringBuilder(tail.trim()).append("\n");
            }
            buffer.append(segment).append("\n");
        }
        if (buffer.length() > 0 && buffer.toString().trim().length() > 0) {
            chunks.add(createChunk(enrich(buffer.toString(), joinHeadingPath(l1Heading, l2Heading)), documentId, index));
        }
        return chunks;
    }

    /**
     * 段落的层级标题（仅看首行），非标题段返回 null；
     * 返回值带 "L1:"/"L2:" 前缀标识层级
     */
    private String headingOf(String segment) {
        int nl = segment.indexOf('\n');
        String firstLine = (nl > 0 ? segment.substring(0, nl) : segment).trim();
        if (firstLine.isEmpty()) {
            return null;
        }
        String tier = null;
        if (matchesAtStart(HEADING_L1_PATTERN, firstLine)) {
            tier = "L1";
        } else if (matchesAtStart(HEADING_L2_PATTERN, firstLine)
                || matchesAtStart(NUMBERED_SHORT_TITLE, firstLine)) {
            tier = "L2";
        }
        if (tier == null) {
            return null;
        }
        String heading = firstLine.replaceAll("\\*+", "").trim();
        if (heading.length() > HEADING_MAX_CHARS) {
            heading = heading.substring(0, HEADING_MAX_CHARS);
        }
        return tier + ":" + heading;
    }

    private boolean matchesAtStart(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.find() && m.start() == 0;
    }

    private String joinHeadingPath(String l1, String l2) {
        if (l1 == null || l1.isBlank()) {
            return l2;
        }
        if (l2 == null || l2.isBlank() || l2.equals(l1)) {
            return l1;
        }
        return l1 + " · " + l2;
    }

    /**
     * 上下文继承：chunk 未携带所属标题时前置【标题】，脱离原文仍可定位条款出处
     */
    private String enrich(String content, String heading) {
        if (heading == null || heading.isBlank() || content.startsWith(heading)) {
            return content;
        }
        return "【" + heading + "】" + content;
    }

    private boolean isStructureBoundary(String line) {
        if (line.isEmpty()) {
            return false;
        }
        Matcher m = STRUCTURE_PATTERN.matcher(line);
        return m.find() && m.start() == 0;
    }

    /**
     * 段落感知的定长切片（原算法，作为无结构文档的回退）
     */
    private List<Chunk> paragraphChunk(String text, String documentId) {
        List<Chunk> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n");
        StringBuilder buffer = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            if (buffer.length() + paragraph.length() + 1 > chunkSize && buffer.length() > 0) {
                chunks.add(createChunk(buffer.toString(), documentId, chunkIndex++));
                int overlapStart = Math.max(0, buffer.length() - chunkOverlap);
                buffer = new StringBuilder(buffer.substring(overlapStart));
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(paragraph);
        }
        if (buffer.length() > 0) {
            chunks.add(createChunk(buffer.toString(), documentId, chunkIndex));
        }
        return chunks;
    }

    private List<String> splitLongText(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize - chunkOverlap) {
            int end = Math.min(i + chunkSize, text.length());
            parts.add(text.substring(i, end));
            if (end >= text.length()) {
                break;
            }
        }
        return parts;
    }

    /**
     * Parent-Child 二级拆分（LlamaIndex small-to-big / LangChain ParentDocumentRetriever 借鉴）：
     * 把父块（结构感知切片结果）按句边界拆为 ~childSize 的子块。
     * 子块用于向量检索（语义集中），命中后由检索层替换回父块进生成（上下文完整）。
     */
    public List<Chunk> splitToChildren(List<Chunk> parents) {
        List<Chunk> children = new ArrayList<>();
        for (Chunk parent : parents) {
            if (parent.getContent().length() <= childSize * 1.2) {
                // 父块本身够小：作为自己的子块（新 id，父块行仍在 MySQL 供统一展开路径）
                children.add(parent.toBuilder()
                        .id(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                        .parentId(parent.getId())
                        .chunkType(0)
                        .build());
                continue;
            }
            List<String> sentences = splitSentences(parent.getContent());
            StringBuilder buf = new StringBuilder();
            int index = 0;
            for (String sentence : sentences) {
                if (buf.length() + sentence.length() > childSize && buf.length() > 0) {
                    children.add(Chunk.builder()
                            .id(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                            .documentId(parent.getDocumentId())
                            .content(buf.toString().trim())
                            .index(index++)
                            .charCount(buf.length())
                            .parentId(parent.getId())
                            .chunkType(0)
                            .build());
                    buf = new StringBuilder();
                }
                buf.append(sentence);
            }
            if (buf.length() > 0) {
                children.add(Chunk.builder()
                        .id(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                        .documentId(parent.getDocumentId())
                        .content(buf.toString().trim())
                        .index(index)
                        .charCount(buf.length())
                        .parentId(parent.getId())
                        .chunkType(0)
                        .build());
            }
        }
        return children;
    }

    /** 按中英文句末标点切句并保留分隔符 */
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

    private Chunk createChunk(String content, String documentId, int index) {
        return Chunk.builder()
                .id(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .documentId(documentId)
                .content(content.trim())
                .index(index)
                .charCount(content.trim().length())
                .build();
    }

    @Data
    @Builder(toBuilder = true)
    @AllArgsConstructor
    public static class Chunk {
        private String id;
        private String documentId;
        private String content;
        private int index;
        private int charCount;
        /** Parent-Child：子块指向父块；父块自身为 null */
        private String parentId;
        /** 0=子块（进向量库） 1=父块（仅 MySQL，供检索命中后展开） */
        private Integer chunkType;
    }
}
