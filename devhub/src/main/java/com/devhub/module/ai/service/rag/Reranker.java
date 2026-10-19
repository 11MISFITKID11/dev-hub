package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 重排序服务（两阶段检索的第二阶段，架构对标 QAnything / Dify）
 * <p>
 * provider 可配置：
 * <ul>
 *   <li>zhipu —— 智谱官方 Rerank API（cross-encoder，query+文档联合编码打分，精度高，按量计费）</li>
 *   <li>llm —— glm-4-flash 单次调用批量打分（免费，精度略低）</li>
 * </ul>
 * zhipu 模式失败时自动降级 llm 模式，保证检索链路可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Reranker {

    private final ModelRouter modelRouter;

    @Value("${agent.rag.rerank.provider:llm}")
    private String provider;

    @Value("${agent.rag.rerank.model:rerank}")
    private String rerankModel;

    @Value("${agent.rag.rerank.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String rerankBaseUrl;

    @Value("${agent.rag.rerank.api-key:}")
    private String rerankApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** zhipu rerank 单条候选的长度上限（API 限制 4096 字符，留余量） */
    private static final int ZHIPU_DOC_MAX_CHARS = 2000;

    private static final String RERANK_PROMPT = """
            你是搜索结果重排器。请评估以下每个文档与用户查询的相关性，给出 0-10 的整数评分。

            用户查询: %s

            候选文档（编号从1开始）:
            %s

            请只输出一个 JSON 数组，包含 %d 个整数评分，按文档编号顺序排列。
            示例: [8, 3, 6]。10 表示完全相关，0 表示完全不相关。不要输出其他任何内容。
            """;

    /**
     * 对候选池全量打分排序（不截断——最终保留条数由编排层按 agent.rag.top-k 决定，
     * 这是 QAnything 两阶段架构的职责划分：重排只负责排序质量）
     *
     * @param query   用户查询
     * @param results RRF 融合后的候选池（通常 candidate-n=15 条）
     * @return 按相关性降序的完整列表
     */
    public List<MultiRetriever.RetrievalResult> rerank(String query, List<MultiRetriever.RetrievalResult> results) {
        if (results.size() <= 1) {
            log.debug("候选池过小({}), 跳过重排", results.size());
            return results;
        }

        log.info("开始重排序: provider={}, query={}, candidates={}", provider, truncate(query, 80), results.size());

        List<MultiRetriever.RetrievalResult> reranked;
        if ("zhipu".equalsIgnoreCase(provider) && rerankApiKey != null && !rerankApiKey.isBlank()) {
            try {
                reranked = zhipuRerank(query, results);
            } catch (Exception e) {
                log.warn("智谱 Rerank 失败，降级为 LLM 重排: {}", e.getMessage());
                reranked = llmRerank(query, results);
            }
        } else {
            reranked = llmRerank(query, results);
        }

        log.info("重排序完成: 候选 {} 条已按相关性排序", reranked.size());
        return reranked;
    }

    /**
     * 智谱官方 Rerank API：cross-encoder 直接输出 0-1 相关性得分
     */
    private List<MultiRetriever.RetrievalResult> zhipuRerank(String query, List<MultiRetriever.RetrievalResult> results) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", rerankModel);
        body.put("query", query);
        // 必须显式传 top_n（实测缺省时智谱返回空 results），全量返回后由本地截断 topN
        body.put("top_n", results.size());
        ArrayNode documents = body.putArray("documents");
        for (MultiRetriever.RetrievalResult r : results) {
            documents.add(truncate(r.getContent(), ZHIPU_DOC_MAX_CHARS));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rerankBaseUrl.replaceAll("/$", "") + "/rerank"))
                .header("Authorization", "Bearer " + rerankApiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("zhipu rerank HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode resultsArr = root.get("results");
        if (resultsArr == null || !resultsArr.isArray() || resultsArr.isEmpty()) {
            throw new IllegalStateException("zhipu rerank 响应缺少 results");
        }

        double[] scores = new double[results.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = 0.5; // 未被返回的候选给中性分，保持稳定排序
        }
        for (JsonNode item : resultsArr) {
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < scores.length && item.has("relevance_score")) {
                scores[index] = Math.max(0, Math.min(1, item.get("relevance_score").asDouble()));
            }
        }

        log.info("智谱 Rerank 打分: {}", scores);
        return applyScores(results, scores);
    }

    /**
     * 单次 LLM 调用为全部候选打分（免费回退路径）
     */
    private List<MultiRetriever.RetrievalResult> llmRerank(String query, List<MultiRetriever.RetrievalResult> results) {
        List<Double> scores = batchScore(query, results);
        double[] primitive = new double[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            // LLM 评分 0-10，归一化到 0-1（与 zhipu 通道及 CRAG 门控阈值保持同一量纲）
            primitive[i] = scores.get(i) / 10.0;
        }
        return applyScores(results, primitive);
    }

    private List<Double> batchScore(String query, List<MultiRetriever.RetrievalResult> results) {
        StringBuilder docs = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            docs.append("[").append(i + 1).append("] ").append(truncate(results.get(i).getContent(), 400)).append("\n");
        }
        String promptText = String.format(RERANK_PROMPT, query, docs, results.size());
        String output;
        try {
            output = modelRouter.call(promptText, null).trim();
        } catch (Exception e) {
            log.warn("LLM 重排调用失败，保留原检索顺序: {}", e.getMessage());
            return new ArrayList<>(java.util.Collections.nCopies(results.size(), 0.5));
        }

        String json = output.replaceAll("(?s).*?(\\[.*?\\]).*", "$1");
        String[] parts = json.replaceAll("[\\[\\]\\s]", "").split(",");
        if (parts.length < results.size()) {
            log.warn("LLM 评分数不足({}/{})，按中性分处理", parts.length, results.size());
            return new ArrayList<>(java.util.Collections.nCopies(results.size(), 0.5));
        }

        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            try {
                scores.add(Math.max(0, Math.min(10, Double.parseDouble(parts[i]))));
            } catch (NumberFormatException e) {
                scores.add(5.0);
            }
        }
        return scores;
    }

    /**
     * 将 0-1 得分写回并降序排序
     */
    private List<MultiRetriever.RetrievalResult> applyScores(List<MultiRetriever.RetrievalResult> results,
                                                             double[] scores) {
        List<MultiRetriever.RetrievalResult> scored = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            scored.add(results.get(i).toBuilder()
                    .score(scores[i])
                    .build());
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(MultiRetriever.RetrievalResult::getScore).reversed())
                .toList();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
