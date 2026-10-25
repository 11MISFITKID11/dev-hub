package com.devhub.module.ai.service.tool.builtin;

import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ai.service.tool.BaseTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 站内知识搜索工具（W4-7 真实化：模拟结果 → 本项目 RAG 检索）
 * <p>复用 MultiRetriever 三通道混合检索（向量+关键词+精确），结果带文档来源。
 * 客服场景知识以站内知识库为准（无外网搜索需求），工具描述已同步收紧，避免模型误用于时事类问题。
 */
@Slf4j
@Component
public class SearchTool extends BaseTool {

    private final MultiRetriever retriever;
    private final ChatModelRegistry modelRegistry;

    public SearchTool(MultiRetriever retriever, ChatModelRegistry modelRegistry) {
        this.retriever = retriever;
        this.modelRegistry = modelRegistry;
    }

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "搜索企业知识库（产品文档/政策条款/部署指南等站内知识）。当需要查找公司内部的资料、"
                + "政策细节、设备文档时使用；不适用于互联网时事或站内没有的外部信息。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "搜索关键词（用知识库中可能出现的表述）" }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    protected void validate(Map<String, Object> parameters) {
        if (!parameters.containsKey("query") || parameters.get("query").toString().isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
    }

    @Override
    protected String doExecute(Map<String, Object> parameters) {
        String query = parameters.get("query").toString().strip();
        List<Float> vector = null;
        try {
            vector = modelRegistry.embedToVector(query);
        } catch (Exception e) {
            log.warn("搜索工具向量化失败，走关键词通道: {}", e.getMessage());
        }
        List<MultiRetriever.RetrievalResult> results = retriever.retrieve(query, vector, null);
        if (results == null || results.isEmpty()) {
            return "知识库中未搜到相关内容：" + query;
        }
        StringBuilder sb = new StringBuilder("知识库搜索结果（" + results.size() + " 条）：");
        int rank = 0;
        for (MultiRetriever.RetrievalResult r : results) {
            if (++rank > 5) break;
            sb.append("\n[").append(rank).append("] 来源《").append(r.getFileName()).append("》：")
                    .append(abbreviate(r.getContent(), 200));
        }
        return sb.toString();
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
