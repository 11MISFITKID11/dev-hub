package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ai.service.rag.Reranker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试器（对标 FastGPT 单点搜索测试 / RAGFlow 分段预览）：
 * 输入 query 展示完整检索过程——改写、各通道召回、RRF 融合、重排、门控判定与耗时分解。
 * 调知识库的必备工具：答不出/答错时先看这里定位是召回问题还是排序问题
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagDebugController {

    private final ChatModelRegistry modelRegistry;
    private final MultiRetriever multiRetriever;
    private final Reranker reranker;

    @Value("${agent.rag.top-k:5}")
    private int topK;

    @Value("${agent.rag.gate-top1-score:0.5}")
    private double gateTop1Score;

    @Value("${agent.rag.rerank.provider:llm}")
    private String rerankProvider;

    @GetMapping("/debug")
    public Result<Map<String, Object>> debug(@RequestParam String query,
                                             @RequestParam(required = false) String history) {
        Map<String, Object> out = new LinkedHashMap<>();

        List<Float> vector = null;
        long embedMs = 0;
        try {
            long t0 = System.currentTimeMillis();
            vector = modelRegistry.embedToVector(query);
            embedMs = System.currentTimeMillis() - t0;
        } catch (Exception e) {
            out.put("embedError", e.getMessage());
        }
        out.put("embedMs", embedMs);

        MultiRetriever.DebugTrace trace = multiRetriever.debugRetrieve(query, vector, history);
        out.put("query", query);
        out.put("scope", trace.scope());
        out.put("rewrite", trace.rewrite());
        out.put("channels", trace.channels());

        out.put("fused", trace.fused().stream().map(r -> Map.of(
                "fileName", r.getFileName() == null ? "" : r.getFileName(),
                "score", r.getScore())).toList());

        // 重排（真实 provider，含失败降级）与门控判定
        long t1 = System.currentTimeMillis();
        List<MultiRetriever.RetrievalResult> reranked = reranker.rerank(query, trace.fused());
        long rerankMs = System.currentTimeMillis() - t1;
        out.put("rerank", Map.of(
                "provider", rerankProvider,
                "ms", rerankMs,
                "results", reranked.stream().limit(topK).map(r -> Map.of(
                        "fileName", r.getFileName() == null ? "" : r.getFileName(),
                        "score", r.getScore())).toList()));

        if (!reranked.isEmpty()) {
            double top1 = reranked.get(0).getScore();
            boolean gated = top1 < gateTop1Score;
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("top1Score", top1);
            gate.put("threshold", gateTop1Score);
            gate.put("verdict", gated ? "弱召回，线上将拒答并记录缺口" : "通过，进入生成");
            out.put("gate", gate);
        } else {
            out.put("gate", Map.of("verdict", "无召回结果，线上将拒答"));
        }

        Map<String, Object> timings = new LinkedHashMap<>(trace.timings());
        timings.put("embedMs", embedMs);
        timings.put("rerankMs", rerankMs);
        out.put("timings", timings);
        return Result.success(out);
    }
}
