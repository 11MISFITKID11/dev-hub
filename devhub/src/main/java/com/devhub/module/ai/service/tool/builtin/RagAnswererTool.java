package com.devhub.module.ai.service.tool.builtin;

import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ai.service.rag.Reranker;
import com.devhub.module.ai.service.tool.BaseTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（Agent-as-Tool：RAG 能力工具化，主 Agent 运行时自主委派）
 * <p>
 * 注册进工具中心后，ReAct 主 Agent 可按需调用本工具获取带出处的文档片段，
 * 形成 orchestrator-workers 委派拓扑——Agent 层由此成为 RAG 的编排层而非平行系统。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagAnswererTool extends BaseTool {

    private final MultiRetriever multiRetriever;
    private final Reranker reranker;
    private final ChatModelRegistry modelRegistry;

    private static final int TOP_N = 3;
    private static final int CONTENT_MAX = 600;

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        return "在企业知识库中检索已上传的文档，返回与问题最相关的原文片段（带文件名和相关度）。"
                + "回答任何关于公司文档、规约、手册、制度的问题之前，都应先调用此工具获取依据；"
                + "工具结果之外的内容不要编造。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "要检索的知识库问题，用完整、具体的自然语言表述"
                    }
                  },
                  "required": ["question"]
                }
                """;
    }

    @Override
    protected void validate(Map<String, Object> parameters) {
        if (!parameters.containsKey("question") || parameters.get("question").toString().isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
    }

    @Override
    protected String doExecute(Map<String, Object> parameters) {
        String question = parameters.get("question").toString();
        List<Float> queryVector = null;
        try {
            queryVector = modelRegistry.embedToVector(question);
        } catch (Exception e) {
            log.warn("检索问题向量化失败，降级为纯关键词检索: {}", e.getMessage());
        }

        List<MultiRetriever.RetrievalResult> results = multiRetriever.retrieve(question, queryVector);
        if (results.isEmpty()) {
            return "知识库中未找到与该问题相关的内容。请基于已有信息回答并说明知识库无此内容，不要编造。";
        }
        results = reranker.rerank(question, results);
        List<MultiRetriever.RetrievalResult> top = results.stream().limit(TOP_N).toList();

        StringBuilder sb = new StringBuilder("【知识库检索结果】命中 ").append(top.size()).append(" 条相关内容：\n");
        for (int i = 0; i < top.size(); i++) {
            MultiRetriever.RetrievalResult r = top.get(i);
            sb.append("\n[").append(i + 1).append("]《").append(r.getFileName() != null ? r.getFileName() : r.getDocumentId())
                    .append("》相关度 ").append(Math.round(r.getScore() * 100)).append("%：\n")
                    .append(truncate(r.getContent(), CONTENT_MAX)).append("\n");
        }
        sb.append("\n请基于以上内容作答，并注明来源文件名。");
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
