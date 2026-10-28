package com.devhub.module.ai.service.intent;

import com.devhub.module.ai.model.enums.AgentMode;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别服务
 * <p>
 * 分析用户输入的意图，决定使用哪种 Agent 模式和工具集合。
 * 规则快速判断 + 默认兜底（原 LLM 兜底已移除：前端固定传 DIRECT 模式，
 * LLM 识别结果几乎不被消费，却让每次同步问答白付 1~3 秒 RTT）
 */
@Slf4j
@Service
public class IntentRecognizer {

    @org.springframework.context.annotation.Lazy
    private final com.devhub.module.ai.infrastructure.llm.ModelRouter modelRouter;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public IntentRecognizer(@org.springframework.context.annotation.Lazy
                            com.devhub.module.ai.infrastructure.llm.ModelRouter modelRouter,
                            org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.modelRouter = modelRouter;
        this.redis = redis;
    }

    /** 可推荐工具目录（与 ToolRegistry 注册名一致） */
    private static final String TOOL_CATALOG =
            "calculator(计算),search(站内知识搜索),database_query(SQL查询),query_order(订单物流),create_ticket(建工单),rag_answerer(知识问答)";

    /**
     * 识别用户意图
     *
     * @param userInput 用户输入
     * @return 意图识别结果
     */
    public IntentResult recognize(String userInput) {
        log.info("开始意图识别: input={}", truncate(userInput, 100));

        IntentResult ruleResult = ruleBasedRecognize(userInput);
        if (ruleResult != null) {
            log.info("规则命中意图: type={}, confidence={}", ruleResult.getIntentType(), ruleResult.getConfidence());
            return ruleResult;
        }

        return llmBasedRecognize(userInput);
    }

    /**
     * 仅规则识别（零 LLM 成本）：流式路径的闲聊短路判定用。
     * 返回 null 表示规则未命中（调用方不应由此走 LLM 兜底）
     */
    public IntentResult recognizeByRule(String userInput) {
        return ruleBasedRecognize(userInput);
    }

    /**
     * 基于规则的快速意图判断
     */
    private IntentResult ruleBasedRecognize(String input) {
        String lower = input.toLowerCase().trim();

        // 问候与身份类（自我认知）：闲聊短路路径，模型以中性身份自述（不隶属任何特定公司）
        if (lower.matches("^(你好|hi|hello|嗨|早上好|晚上好|hey|你是谁|你是什么|自我介绍|介绍一下你|你叫什么|你们是哪家公司).*")) {
            return IntentResult.builder()
                    .intentType("SIMPLE_CHAT")
                    .agentMode(AgentMode.DIRECT)
                    .confidence(0.95)
                    .needsRag(false)
                    .suggestedTools(List.of())
                    .build();
        }

        if (lower.contains("计算") || lower.matches(".*\\d+[+\\-*/]\\d+.*")) {
            return IntentResult.builder()
                    .intentType("TOOL_USE")
                    .agentMode(AgentMode.REACT)
                    .confidence(0.9)
                    .needsRag(false)
                    .suggestedTools(List.of("calculator"))
                    .build();
        }

        if (lower.contains("搜索") || lower.contains("查找") || lower.contains("最新")) {
            return IntentResult.builder()
                    .intentType("TOOL_USE")
                    .agentMode(AgentMode.REACT)
                    .confidence(0.85)
                    .needsRag(false)
                    .suggestedTools(List.of("search"))
                    .build();
        }

        if (lower.contains("查询数据") || lower.contains("sql") || lower.contains("数据库")) {
            return IntentResult.builder()
                    .intentType("TOOL_USE")
                    .agentMode(AgentMode.REACT)
                    .confidence(0.85)
                    .needsRag(false)
                    .suggestedTools(List.of("database_query"))
                    .build();
        }

        return null;
    }

    /**
     * 兜底（仅 REACT 场景消费；DIRECT 快路径不经过此处）：
     * 规则未命中 → 小模型推荐工具集（Redis 24h 缓存），失败/超时回退默认 KNOWLEDGE_QA 零工具。
     */
    private IntentResult llmBasedRecognize(String input) {
        String key = "intent:llm:" + Math.abs((input == null ? "" : input).hashCode());
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                List<String> tools = parseTools(cached);
                return knowledageResult(tools);
            }
        } catch (Exception ignored) { }
        try {
            String answer = modelRouter.call("""
                    你是客服系统的意图分类器。根据客户消息从工具目录中推荐 0-2 个最可能用到的工具。
                    工具目录：%s
                    只输出 JSON：{"tools": ["工具名"], "type": "TOOL_USE|KNOWLEDGE_QA"}

                    客户消息：%s
                    """.formatted(TOOL_CATALOG, input == null ? "" : input), null);
            List<String> tools = parseTools(answer);
            try {
                redis.opsForValue().set(key, String.join(",", tools), java.time.Duration.ofHours(24));
            } catch (Exception ignored) { }
            log.info("LLM 意图兜底: tools={}", tools);
            return knowledageResult(tools);
        } catch (Exception e) {
            log.warn("LLM 意图兜底失败，回退默认 KNOWLEDGE_QA: {}", e.getMessage());
            return knowledageResult(List.of());
        }
    }

    private IntentResult knowledageResult(List<String> tools) {
        return IntentResult.builder()
                .intentType(tools.isEmpty() ? "KNOWLEDGE_QA" : "TOOL_USE")
                .agentMode(AgentMode.REACT)
                .confidence(0.5)
                .needsRag(true)
                .suggestedTools(tools)
                .build();
    }

    /** 从 LLM 输出解析工具名列表（容错：截取 JSON 数组段；输出不合法返回空） */
    private List<String> parseTools(String answer) {
        try {
            if (answer == null) return List.of();
            int s = answer.indexOf('[');
            int e = answer.indexOf(']');
            if (s < 0 || e <= s) return List.of();
            String inner = answer.substring(s + 1, e).replace("\"", "");
            return java.util.Arrays.stream(inner.split(","))
                    .map(String::trim).filter(t -> !t.isEmpty() && TOOL_CATALOG.contains(t + "("))
                    .limit(2).toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 保留映射工具方法供规则层扩展使用 */
    private AgentMode mapToAgentMode(String intentType) {
        return switch (intentType) {
            case "SIMPLE_CHAT" -> AgentMode.DIRECT;
            case "COMPLEX_TASK" -> AgentMode.PLANNER;
            default -> AgentMode.REACT;
        };
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    @Data
    @Builder
    public static class IntentResult {
        private String intentType;
        private AgentMode agentMode;
        private double confidence;
        private boolean needsRag;
        private List<String> suggestedTools;
    }
}
