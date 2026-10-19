package com.devhub.module.ai.service.knowledge;

import com.devhub.module.ai.mapper.KnowledgeGapMapper;
import com.devhub.module.ai.model.entity.KnowledgeGap;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.List;

/**
 * 知识缺口服务（自动知识生产的触发源）
 * <p>
 * 拒答时记录缺口；同一问题重复出现自动累加次数；
 * 高频缺口（>=3 次）自动触发草稿生成——知识库随真实使用持续生长。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGapService {

    private final KnowledgeGapMapper gapMapper;
    private final KnowledgeProductionService productionService;

    private static final int AUTO_GENERATE_THRESHOLD = 3;

    /**
     * 记录知识缺口（异步，不影响对话主流程）
     */
    @Async
    public void recordGap(String question, String conversationId) {
        try {
            String hash = md5(normalize(question));
            KnowledgeGap existing = gapMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeGap>().eq(KnowledgeGap::getQuestionHash, hash));

            if (existing != null) {
                existing.setHitCount(existing.getHitCount() + 1);
                existing.setConversationId(conversationId != null ? conversationId : existing.getConversationId());
                gapMapper.updateById(existing);
                log.info("知识缺口重复出现: {} (第 {} 次)", truncate(question), existing.getHitCount());

                // 高频缺口自动触发草稿生成
                if ("open".equals(existing.getStatus())
                        && existing.getHitCount() >= AUTO_GENERATE_THRESHOLD) {
                    productionService.generateDraftAsync(existing.getId());
                }
            } else {
                gapMapper.insert(KnowledgeGap.builder()
                        .questionHash(hash)
                        .question(question)
                        .conversationId(conversationId)
                        .status("open")
                        .hitCount(1)
                        .build());
                log.info("新知识缺口已记录: {}", truncate(question));
            }
        } catch (Exception e) {
            log.warn("知识缺口记录失败: {}", e.getMessage());
        }
    }

    public List<KnowledgeGap> listGaps(String status) {
        return gapMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGap>()
                        .eq(status != null && !status.isBlank(), KnowledgeGap::getStatus, status)
                        .orderByDesc(KnowledgeGap::getHitCount)
                        .last("LIMIT 50"));
    }

    private String normalize(String question) {
        return question == null ? "" : question.trim().replaceAll("\\s+", "");
    }

    private String truncate(String text) {
        return text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
