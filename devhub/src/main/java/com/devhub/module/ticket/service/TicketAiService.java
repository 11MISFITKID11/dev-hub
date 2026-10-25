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
    private final com.devhub.module.ai.service.PromptTemplateService promptTemplates;
    private final com.devhub.module.system.mapper.SysDictDataMapper dictMapper;

    private static final String DEFAULT_CATEGORY_LINE =
            "PRODUCT(产品咨询)、BUG(缺陷故障)、ACCOUNT(账号权限)、BILLING(计费相关)、USAGE(使用帮助)、OTHER(其他)";

    /** 工单分类字典（sys_dict_data type=ticket_category）：提示词选项与校验集的单一事实源 */
    private List<com.devhub.module.system.entity.SysDictData> categoryDict() {
        try {
            return dictMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                            com.devhub.module.system.entity.SysDictData>()
                    .eq(com.devhub.module.system.entity.SysDictData::getType, "ticket_category")
                    .orderByAsc(com.devhub.module.system.entity.SysDictData::getSort));
        } catch (Exception e) {
            log.warn("工单分类字典读取失败，回退内置分类: {}", e.getMessage());
            return List.of();
        }
    }

    /** 分类校验集：字典优先，空则内置（字典维护入口见 系统管理-字典） */
    public java.util.Set<String> categorySet() {
        List<com.devhub.module.system.entity.SysDictData> dict = categoryDict();
        if (dict.isEmpty()) return CATEGORIES;
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        dict.forEach(d -> set.add(d.getValue()));
        set.add("OTHER"); // 兜底分类恒可用
        return set;
    }

    /** AI 分类定级结果；LLM 失败/输出不合法时返回 null，由调用方降级 */
    public ClassifyResult classify(String title, String content) {
        List<com.devhub.module.system.entity.SysDictData> dict = categoryDict();
        String categoryLine = dict.isEmpty() ? DEFAULT_CATEGORY_LINE
                : dict.stream().map(d -> d.getValue() + "(" + d.getLabel() + ")")
                        .reduce((a, b) -> a + "、" + b).orElse(DEFAULT_CATEGORY_LINE);
        java.util.Set<String> allowed = categorySet();
        String prompt = promptTemplates.render(
                com.devhub.module.ai.service.PromptTemplateService.SCENE_TICKET_CLASSIFY,
                java.util.Map.of("categories", categoryLine,
                        "title", title == null ? "" : title,
                        "content", truncate(content, 800)));
        try {
            String out = modelRouter.call(prompt, null);
            String json = extractJson(out);
            if (json == null) return null;
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String category = node.path("category").asText("").toUpperCase();
            String priority = node.path("priority").asText("").toUpperCase();
            if (!allowed.contains(category) || !PRIORITIES.contains(priority)) return null;
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
        String prompt = promptTemplates.render(
                com.devhub.module.ai.service.PromptTemplateService.SCENE_TICKET_SUGGEST,
                java.util.Map.of("material", material,
                        "title", ticket.getTitle() == null ? "" : ticket.getTitle(),
                        "content", truncate(ticket.getContent(), 600)));
        return modelRouter.call(prompt, null).trim();
    }

    /** AI 质检：对已解决工单的坐席结单回复评分；失败返回 null（可手动重试） */
    public QcResult qc(SupportTicket ticket, String agentReplies) {
        String prompt = promptTemplates.render(
                com.devhub.module.ai.service.PromptTemplateService.SCENE_TICKET_QC,
                java.util.Map.of("content", truncate(ticket.getContent(), 800),
                        "agentReplies", truncate(agentReplies, 1200)));
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
