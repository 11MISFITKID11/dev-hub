package com.devhub.module.ticket.service;

import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ticket.entity.SupportTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 工单侧 AI 能力（对标智能客服项目的三个 AI 环节，全部复用现有 LLM 路由与 RAG 检索）：
 * <ul>
 *   <li>分类定级：建单时 LLM 结构化输出 category/priority，失败降级 OTHER/MEDIUM（不阻断建单）</li>
 *   <li>回复建议：坐席处理时复用 MultiRetriever 多路检索生成草稿（检索链与问答同源，质量可比）</li>
 *   <li>服务质检：结单后对坐席回复评分（0-100），低分进质量看板</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAiService {

    public static final Set<String> CATEGORIES = Set.of("PRODUCT", "BUG", "ACCOUNT", "BILLING", "USAGE", "OTHER");
    public static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final ModelRouter modelRouter;
    private final ChatModelRegistry modelRegistry;
    private final MultiRetriever multiRetriever;

    /** AI 分类定级结果；LLM 失败/输出不合法时返回 null，由调用方降级 */
    public ClassifyResult classify(String title, String content) {
        String prompt = """
                你是企业知识库客服系统的工单分类助手。请对工单进行分类和优先级判断。
                分类只能从以下选一：PRODUCT(产品咨询)、BUG(缺陷故障)、ACCOUNT(账号权限)、BILLING(计费相关)、USAGE(使用帮助)、OTHER(其他)
                优先级只能从以下选一：HIGH(阻断使用/严重影响)、MEDIUM(有影响可绕过)、LOW(咨询/建议)
                只输出 JSON，不要输出任何其他文字，格式：{"category":"PRODUCT","priority":"MEDIUM","reason":"一句话理由"}

                工单标题：%s
                工单内容：%s
                """.formatted(title, truncate(content, 800));
        try {
            String out = modelRouter.call(prompt, null);
            String json = extractJson(out);
            if (json == null) return null;
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String category = node.path("category").asText("").toUpperCase();
            String priority = node.path("priority").asText("").toUpperCase();
            if (!CATEGORIES.contains(category) || !PRIORITIES.contains(priority)) return null;
            return new ClassifyResult(category, priority, node.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("工单 AI 分类失败，降级默认分类: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AI 回复建议：复用问答侧多路检索（向量+关键词+RRF+重排）拿知识库资料，为坐席起草回复。
     * 与"AI 直接回答客户"不同——草稿始终经人工确认后发出，人机协同而非替代。
     */
    public String suggestReply(SupportTicket ticket) {
        List<MultiRetriever.RetrievalResult> results;
        try {
            List<Float> vector = modelRegistry.embedToVector(ticket.getContent());
            results = multiRetriever.retrieve(ticket.getContent(), vector, null);
        } catch (Exception e) {
            log.warn("工单检索失败，无资料生成建议: {}", e.getMessage());
            results = List.of();
        }
        StringBuilder kb = new StringBuilder();
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            MultiRetriever.RetrievalResult r = results.get(i);
            kb.append("【资料").append(i + 1).append("·").append(r.getFileName()).append("】\n")
              .append(truncate(r.getContent(), 300)).append("\n\n");
        }
        String material = kb.isEmpty() ? "（知识库未检索到相关资料）" : kb.toString();
        String prompt = """
                你是客服坐席的回复助手。请根据知识库资料为下面的工单起草一条给客户的回复。
                要求：礼貌专业、可直接发送、先给结论再给操作步骤、引用资料中的关键参数或命令；
                不超过 300 字；若资料不足以解决工单问题，明确说明还需要客户补充什么信息，不要编造。

                %s
                工单标题：%s
                工单内容：%s
                """.formatted(material, ticket.getTitle(), truncate(ticket.getContent(), 600));
        return modelRouter.call(prompt, null).trim();
    }

    /** AI 质检：对已解决工单的坐席结单回复评分；失败返回 null（可手动重试） */
    public QcResult qc(SupportTicket ticket, String agentReplies) {
        String prompt = """
                你是客服团队的质检员。请对已解决工单中坐席的处理质量评分。
                评分维度：完整性（是否正面解决工单问题）、礼貌性、专业性。总分 0-100，从严评分。
                只输出 JSON，不要输出任何其他文字，格式：{"score":85,"problems":"无问题 或 具体问题描述"}

                工单内容：%s
                坐席回复全文：%s
                """.formatted(truncate(ticket.getContent(), 800), truncate(agentReplies, 1200));
        try {
            String out = modelRouter.call(prompt, null);
            String json = extractJson(out);
            if (json == null) return null;
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            int score = node.path("score").asInt(-1);
            if (score < 0 || score > 100) return null;
            return new QcResult(score, node.path("problems").asText(""));
        } catch (Exception e) {
            log.warn("工单 AI 质检失败 ticketId={}: {}", ticket.getId(), e.getMessage());
            return null;
        }
    }

    /** 宽松提取首个 JSON 对象（容忍模型输出 markdown 代码块或前后缀文字） */
    private String extractJson(String text) {
        if (text == null) return null;
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : null;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    public record ClassifyResult(String category, String priority, String reason) {}
    public record QcResult(int score, String problems) {}
}
