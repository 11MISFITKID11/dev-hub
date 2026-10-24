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
     * 基于规则的快速意图判断
     */
    private IntentResult ruleBasedRecognize(String input) {
        String lower = input.toLowerCase().trim();

        if (lower.matches("^(你好|hi|hello|嗨|早上好|晚上好|hey).*")) {
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
     * 兜底：规则未命中按知识问答处理（零成本；原 LLM 兜底因收益不抵 RTT 已移除）
     */
    private IntentResult llmBasedRecognize(String input) {
        log.info("规则未命中，按默认意图处理（KNOWLEDGE_QA）: input={}", truncate(input, 60));
        return IntentResult.builder()
                .intentType("KNOWLEDGE_QA")
                .agentMode(AgentMode.REACT)
                .confidence(0.5)
                .needsRag(true)
                .suggestedTools(List.of())
                .build();
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
