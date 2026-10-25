package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.devhub.module.ai.mapper.MessageMapper;
import com.devhub.module.ai.model.dto.ChatResponse;
import com.devhub.module.ai.model.entity.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 忠实度校验器（方案三，算法对标 RAGAS faithfulness / TruLens groundedness）
 * <p>
 * 把答案拆成独立事实声明，逐条判断是否被召回资料支撑，
 * 忠实度 = 被支撑声明数 / 总声明数。低分答案标记出来供人工复核与监控。
 * 使用免费模型（glm-4-flash），同步模式供评测调用，异步模式供生产链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaithfulnessVerifier {

    private final ModelRouter modelRouter;
    private final MessageMapper messageMapper;
    private final com.devhub.module.ai.service.PromptTemplateService promptTemplates;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.rag.faithfulness-model:glm-4-flash}")
    private String verifierModel;

    // 校验提示词已迁移至 PromptTemplateService.SCENE_FAITHFULNESS

    /**
     * 同步校验，返回忠实度 [0,1]；解析失败返回 -1（未知，不计入统计）
     */
    public double verify(String question, String answer, List<ChatResponse.SourceDocument> sources) {
        if (answer == null || answer.isBlank() || sources == null || sources.isEmpty()) {
            return -1;
        }
        try {
            String context = buildContext(sources);
            String prompt = promptTemplates.render(com.devhub.module.ai.service.PromptTemplateService.SCENE_FAITHFULNESS,
                    java.util.Map.of("context", context, "answer", answer));
            String output = modelRouter.call(prompt, verifierModel).trim();

            String json = extractJson(output);
            JsonNode claims = objectMapper.readTree(json).path("claims");
            if (!claims.isArray() || claims.isEmpty()) {
                return -1;
            }
            int supported = 0;
            int total = 0;
            for (JsonNode claim : claims) {
                if (!claim.has("text") || claim.path("text").asText().isBlank()) {
                    continue;
                }
                total++;
                if (claim.path("supported").asBoolean(false)) {
                    supported++;
                }
            }
            double score = total == 0 ? -1 : (double) supported / total;
            log.info("忠实度校验: claims={}, supported={}, faithfulness={}", total, supported,
                    score < 0 ? "N/A" : String.format("%.2f", score));
            return score;
        } catch (Exception e) {
            log.warn("忠实度校验失败（不阻塞主流程）: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 异步校验并落库（生产链路：不增加响应延迟）。
     * 记账修复：继承请求线程的用量快照，校验新增的 token 数回写 message 表——
     * 此前 ThreadLocal 在异步线程直接丢失，校验成本从不入账。
     */
    @Async
    public void verifyAsync(String question, String answer, List<ChatResponse.SourceDocument> sources,
                            String assistantMessageId, long[] usageSnapshot) {
        com.devhub.module.ai.infrastructure.llm.UsageContext.begin(usageSnapshot);
        double score = verify(question, answer, sources);
        long[] taken = com.devhub.module.ai.infrastructure.llm.UsageContext.take();
        long deltaTokens = (taken[0] + taken[1]) - (usageSnapshot[0] + usageSnapshot[1]);
        recordScore(assistantMessageId, score);
        recordAsyncTokens(assistantMessageId, deltaTokens);
        if (score >= 0 && score < 0.6) {
            log.warn("低忠实度答案({})，建议人工复核: answer={}", String.format("%.2f", score),
                    answer.substring(0, Math.min(80, answer.length())));
        }
    }

    /** 异步校验消耗的 token 累加到助手消息 */
    private void recordAsyncTokens(String messageId, long deltaTokens) {
        if (messageId == null || deltaTokens <= 0) {
            return;
        }
        try {
            Message msg = messageMapper.selectById(messageId);
            if (msg != null) {
                msg.setTokenCount((msg.getTokenCount() == null ? 0 : msg.getTokenCount()) + (int) deltaTokens);
                messageMapper.updateById(msg);
            }
        } catch (Exception e) {
            log.warn("异步记账回写失败: {}", e.getMessage());
        }
    }

    /**
     * 忠实度分数回写到助手消息（供检索/监控查询）
     */
    public void recordScore(String messageId, double score) {
        if (messageId == null || score < 0) {
            return;
        }
        try {
            Message msg = new Message();
            msg.setId(messageId);
            msg.setFaithScore(score);
            messageMapper.updateById(msg);
        } catch (Exception e) {
            log.warn("忠实度落库失败: {}", e.getMessage());
        }
    }

    private String buildContext(List<ChatResponse.SourceDocument> sources) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("[资料").append(i + 1).append("]\n").append(sources.get(i).getContent());
        }
        return sb.toString();
    }

    /** 从模型输出中容错提取 JSON 对象 */
    private String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return output.substring(start, end + 1);
        }
        throw new IllegalStateException("输出中未找到 JSON");
    }
}
