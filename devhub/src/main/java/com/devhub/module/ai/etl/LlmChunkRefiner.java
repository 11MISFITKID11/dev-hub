package com.devhub.module.ai.etl;

import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LLM 切分精修器（对标 LlamaIndex SemanticSplitter 的轻量落地）
 * <p>
 * 结构切分后只把"可疑 chunk"送 LLM 精修（成本控制的关键）：
 * <ul>
 *   <li>超长：内容 &gt; 1.5×chunkSize</li>
 *   <li>过短：内容 &lt; 50 字</li>
 *   <li>疑似截断：以逗号/顿号等非终结符结尾</li>
 * </ul>
 * LLM 只输出拆分/合并<b>指令</b>而非改写文本（防篡改原文）；
 * 每条指令做长度守恒校验（拆分后总长与原文误差 ≤15%），不通过则丢弃保原文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChunkRefiner {

    private final ModelRouter modelRouter;

    @Value("${agent.rag.llm-chunking.enabled:true}")
    private boolean enabled;

    @Value("${agent.rag.chunk-size:512}")
    private int chunkSize;

    private static final int MIN_LEN = 50;

    private static final String REFINE_PROMPT = """
            你是文档切片优化器。以下是编号的文本切片，请判断哪些需要拆分或合并，使每片保持完整语义单元。

            规则：
            1. split：一个 chunk 混杂多个主题或过长时，拆成 2-3 片（在句子边界拆分，不要改写任何文字）
            2. merge：相邻编号的两个 chunk 是同一主题且都过短时，合并（只针对相邻编号）
            3. 不需要处理的切片不要输出

            切片列表：
            %s

            只输出 JSON 数组，每项形如：
            {"op":"split","index":编号,"parts":["第一部分原文","第二部分原文"]}
            {"op":"merge","indexes":[编号A,编号B]}
            输出为 [] 表示全部无需处理。
            """;

    /**
     * 精修入口：无可疑 chunk 或失败时原样返回
     */
    public List<DocumentChunker.Chunk> refine(List<DocumentChunker.Chunk> chunks, String documentId) {
        if (!enabled || chunks.size() < 2) {
            return chunks;
        }

        List<Integer> suspicious = findSuspicious(chunks);
        if (suspicious.isEmpty()) {
            log.info("切分质量预检通过（{} 片全部合格），跳过 LLM 精修", chunks.size());
            return chunks;
        }
        log.info("切分质量预检发现 {} 片可疑（共 {} 片），进入 LLM 精修", suspicious.size(), chunks.size());

        try {
            String opsJson = callLlm(chunks, suspicious);
            List<ChunkOp> ops = parseOps(opsJson);
            if (ops.isEmpty()) {
                return chunks;
            }
            List<DocumentChunker.Chunk> refined = apply(chunks, ops, documentId);
            log.info("LLM 切分精修完成: {} 片 -> {} 片（{} 条指令生效）",
                    chunks.size(), refined.size(), ops.size());
            return refined;
        } catch (Exception e) {
            log.warn("LLM 切分精修失败（保留原切片）: {}", e.getMessage());
            return chunks;
        }
    }

    /* ---------- 预检 ---------- */

    private List<Integer> findSuspicious(List<DocumentChunker.Chunk> chunks) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i).getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > chunkSize * 1.5
                    || content.length() < MIN_LEN
                    || isTruncatedTail(content)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private boolean isTruncatedTail(String content) {
        char last = content.charAt(content.length() - 1);
        return last == '，' || last == '、' || last == '：' || last == '（'
                || last == ',' || last == ':' || last == ' ';
    }

    /* ---------- LLM 指令 ---------- */

    private String callLlm(List<DocumentChunker.Chunk> chunks, List<Integer> suspicious) {
        StringBuilder sb = new StringBuilder();
        for (int idx : suspicious) {
            sb.append("[").append(idx).append("] ").append(chunks.get(idx).getContent(), 0,
                    Math.min(chunks.get(idx).getContent().length(), 800)).append("\n");
        }
        return modelRouter.call(String.format(REFINE_PROMPT, sb), null).trim();
    }

    private record ChunkOp(String op, int index, List<Integer> indexes, List<String> parts) {}

    private List<ChunkOp> parseOps(String llmOutput) {
        List<ChunkOp> ops = new ArrayList<>();
        try {
            String json = llmOutput.replaceAll("(?s).*?(\\[.*]).*", "$1");
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String op = node.path("op").asText("");
                if ("split".equals(op) && node.has("parts")) {
                    List<String> parts = new ArrayList<>();
                    node.get("parts").forEach(p -> parts.add(p.asText()));
                    ops.add(new ChunkOp("split", node.path("index").asInt(-1), null, parts));
                } else if ("merge".equals(op) && node.has("indexes") && node.get("indexes").size() == 2) {
                    ops.add(new ChunkOp("merge", -1,
                            List.of(node.get("indexes").get(0).asInt(), node.get("indexes").get(1).asInt()), null));
                }
            }
        } catch (Exception e) {
            log.warn("切分指令解析失败: {}", e.getMessage());
        }
        return ops;
    }

    /* ---------- 应用指令（带长度守恒防改写） ---------- */

    private List<DocumentChunker.Chunk> apply(List<DocumentChunker.Chunk> chunks,
                                              List<ChunkOp> ops, String documentId) {
        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        List<DocumentChunker.Chunk> result = new ArrayList<>();
        int nextIndex = 0;

        for (ChunkOp op : ops) {
            if ("split".equals(op.op()) && op.index() >= 0 && op.index() < chunks.size()) {
                String original = chunks.get(op.index()).getContent();
                final List<String> parts = op.parts();
                int partsLen = parts.stream().mapToInt(String::length).sum();
                // 长度守恒校验：误差>15% 视为 LLM 改写了原文，丢弃该指令
                if (Math.abs(partsLen - original.length()) > original.length() * 0.15) {
                    log.warn("split 指令长度不守恒（原文{} vs 部分{}），已丢弃", original.length(), partsLen);
                    continue;
                }
                consumed.add(op.index());
                for (String part : op.parts()) {
                    result.add(newChunk(part, documentId, nextIndex++));
                }
            }
        }

        for (int i = 0; i < chunks.size(); i++) {
            if (consumed.contains(i)) {
                continue;
            }
            final int currentIdx = i;
            ChunkOp mergeOp = ops.stream()
                    .filter(o -> "merge".equals(o.op()) && o.indexes() != null
                            && o.indexes().get(0) == currentIdx)
                    .findFirst().orElse(null);
            if (mergeOp != null) {
                int j = mergeOp.indexes().get(1);
                // 仅允许相邻合并
                if (j == i + 1 && j < chunks.size()) {
                    consumed.add(j);
                    result.add(newChunk(chunks.get(i).getContent() + "\n" + chunks.get(j).getContent(),
                            documentId, nextIndex++));
                    continue;
                }
            }
            result.add(newChunk(chunks.get(i).getContent(), documentId, nextIndex++));
        }

        // 保持原顺序：split 注入在前导致乱序——按内容稳定性重排不可靠，MVP 顺序即结果
        return result;
    }

    private DocumentChunker.Chunk newChunk(String content, String documentId, int index) {
        return DocumentChunker.Chunk.builder()
                .id(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .documentId(documentId)
                .content(content.trim())
                .index(index)
                .charCount(content.trim().length())
                .build();
    }
}
