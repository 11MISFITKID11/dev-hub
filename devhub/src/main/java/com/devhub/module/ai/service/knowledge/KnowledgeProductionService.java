package com.devhub.module.ai.service.knowledge;

import com.devhub.module.ai.etl.ETLPipeline;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.devhub.module.ai.infrastructure.vectordb.MilvusService;
import com.devhub.module.ai.mapper.KnowledgeGapMapper;
import com.devhub.module.ai.mapper.MessageMapper;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.KnowledgeGap;
import com.devhub.module.ai.model.entity.Message;
import com.devhub.module.ai.model.enums.MessageRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 自动知识生产线（自我生长闭环的核心）
 * <p>
 * 缺口 → 从真实对话中生成知识草稿 → 校验 Agent 审核（证据支撑 + 查重）→
 * draft 入库（检索降权）→ 管理员采纳后转正。对标 Letta 自生长记忆 / Voyager 技能库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProductionService {

    private final KnowledgeGapMapper gapMapper;
    private final MessageMapper messageMapper;
    private final DocumentMapper documentMapper;
    private final ModelRouter modelRouter;
    private final ChatModelRegistry modelRegistry;
    private final MilvusService milvusService;
    private final ETLPipeline etlPipeline;

    /**
     * 异步生成草稿（高频缺口自动触发 / 管理员手动触发）
     */
    @Async
    public void generateDraftAsync(Long gapId) {
        try {
            generateDraft(gapId);
        } catch (Exception e) {
            log.error("草稿生成失败: gapId={}, err={}", gapId, e.getMessage());
            KnowledgeGap gap = gapMapper.selectById(gapId);
            if (gap != null) {
                gap.setStatus("open");
                gapMapper.updateById(gap);
            }
        }
    }

    /**
     * 同步生成草稿并入库，返回结果说明
     */
    public String generateDraft(Long gapId) {
        KnowledgeGap gap = gapMapper.selectById(gapId);
        if (gap == null) {
            return "缺口不存在";
        }
        gap.setStatus("generating");
        gapMapper.updateById(gap);

        // 1. 从历史对话搜索该问题的成功问答作为证据来源
        String context = buildConversationContext(gap.getConversationId(), gap.getQuestion());

        // 2. 生成草稿（基于对话证据，禁止编造）
        String draft = generateKnowledgeText(gap.getQuestion(), context);
        if (draft == null) {
            gap.setStatus("open");
            gapMapper.updateById(gap);
            return "对话中缺乏足够可靠信息，未生成草稿（缺口保留，建议人工补充）";
        }

        // 3. 校验 Agent：证据支撑 + 与现库查重
        String validation = validate(draft, context);
        if (!"supported".equals(validation)) {
            gap.setStatus("open");
            gapMapper.updateById(gap);
            return "校验未通过（" + validation + "），草稿已丢弃";
        }

        // 4. 入库为 draft（检索降权可见，待管理员采纳）
        etlPipeline.processText(
                "AI知识：" + truncate(gap.getQuestion(), 40),
                draft, "ai_generated", "draft", gap.getConversationId(), null);

        gap.setStatus("resolved");
        gapMapper.updateById(gap);
        log.info("知识草稿已入库: gap={}, 草稿长度={}", gapId, draft.length());
        return "草稿已生成并入库（draft 状态），请在知识库中审核采纳";
    }

    /**
     * 管理员审核：draft → active（采纳）/ disabled（拒绝）
     */
    public String review(String documentId, boolean approve) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return "文档不存在";
        }
        if (!"draft".equals(doc.getLifeStatus()) && !"conflict".equals(doc.getLifeStatus())) {
            return "仅草稿/冲突状态可审核";
        }
        doc.setLifeStatus(approve ? "active" : "disabled");
        documentMapper.updateById(doc);
        log.info("知识审核: {} -> {}", doc.getFileName(), doc.getLifeStatus());
        return approve ? "已采纳为正式知识" : "已拒绝并停用";
    }

    /* ---------- 内部流程 ---------- */

    /**
     * 构建草稿素材：优先跨会话搜索该问题的历史成功问答（真实可靠信息的来源），
     * 拒答会话本身通常无有效信息
     */
    private String buildConversationContext(String conversationId, String question) {
        StringBuilder sb = new StringBuilder();

        // 1. 跨会话：相似 USER 消息的历史问答（问题前8字模糊匹配）
        String prefix = question != null && question.length() > 8
                ? question.substring(0, 8) : question;
        try {
            List<Message> userMsgs = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getRole, MessageRole.USER)
                            .likeRight(Message::getContent, prefix)
                            .orderByDesc(Message::getCreateTime)
                            .last("LIMIT 3"));
            for (Message um : userMsgs) {
                List<Message> qa = messageMapper.selectList(
                        new LambdaQueryWrapper<Message>()
                                .eq(Message::getConversationId, um.getConversationId())
                                .orderByAsc(Message::getCreateTime));
                for (Message m : qa) {
                    if (MessageRole.ASSISTANT.equals(m.getRole()) && m.getContent() != null && m.getContent().length() > 30) {
                        sb.append("用户：").append(truncate(um.getContent(), 200))
                          .append("\n助手：").append(truncate(m.getContent(), 600)).append("\n\n");
                        break;
                    }
                }
                if (sb.length() > 1500) break;
            }
        } catch (Exception e) {
            log.warn("历史问答搜索失败: {}", e.getMessage());
        }

        // 2. 拒答会话自身上下文（辅助）
        if (conversationId != null && sb.length() == 0) {
            sb.append(buildSingleConversation(conversationId));
        }
        return sb.toString();
    }

    private String buildSingleConversation(String conversationId) {
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getCreateTime)
                        .last("LIMIT 20"));
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(MessageRole.USER.equals(m.getRole()) ? "用户" : "助手")
              .append("：").append(truncate(m.getContent(), 500)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从对话证据生成知识文本（无可靠信息时返回 null——宁可无草稿不可有幻觉）
     */
    private String generateKnowledgeText(String question, String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        String output = modelRouter.call(String.format("""
                你是企业知识库管理员。根据以下真实对话记录，提炼可直接入库的知识条目。

                规则：
                1. 只使用对话中出现过的信息，禁止编造任何内容
                2. 如果对话中没有关于该问题的可靠答案或信息，只输出一行：INSUFFICIENT
                3. 输出为结构化的知识文档（适用于企业制度/流程问答），简洁准确

                待补知识的问题：%s

                对话记录：
                %s
                """, question, context), null).trim();

        if (output.contains("INSUFFICIENT") || output.length() < 30) {
            return null;
        }
        return output;
    }

    /**
     * 校验 Agent：证据支撑判定 + 与现有知识查重
     *
     * @return supported / 证据不足 / 与现有知识重复
     */
    private String validate(String draft, String context) {
        try {
            // 查重：与现库高相似内容
            List<Float> vector = modelRegistry.embedToVector(truncate(draft, 2000));
            List<String> similar = milvusService.searchSimilarTexts(vector, 2);
            String dupPrompt = "";
            if (!similar.isEmpty()) {
                dupPrompt = "\n\n【现有相似知识】:\n" + String.join("\n---\n", similar.stream().limit(1).toList());
            }

            String verdict = modelRouter.call(String.format("""
                    你是知识入库审核员，逐项检查这份草稿：
                    1. 证据支撑：草稿的每条事实是否都能在【对话证据】中找到来源？（不能凭空推断）
                    2. 查重：草稿是否与【现有相似知识】内容重复？

                    【知识草稿】：%s

                    【对话证据】：%s%s

                    只输出一个结论：
                    - supported（两项均通过）
                    - unsupported（存在无证据支撑的内容）
                    - duplicate（与现有知识重复）
                    """, truncate(draft, 1000), truncate(context, 1500), dupPrompt), null).trim().toLowerCase();

            if (verdict.contains("duplicate")) {
                return "与现有知识重复";
            }
            if (verdict.contains("supported") && !verdict.contains("unsupported")) {
                return "supported";
            }
            return "证据不足";
        } catch (Exception e) {
            log.warn("草稿校验异常（从严处理）: {}", e.getMessage());
            return "校验异常";
        }
    }

    private String truncate(String text) {
        return truncate(text, 60);
    }

    private String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
