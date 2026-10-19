package com.devhub.module.ai.service.knowledge;

import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.Document;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期知识自动清理：expire_at 到期的 active 文档自动 retired（历史文档过滤）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeExpiryScheduler {

    private final DocumentMapper documentMapper;

    /** 每小时执行 */
    @Scheduled(cron = "0 30 * * * ?")
    public void retireExpired() {
        List<Document> expired = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getLifeStatus, "active")
                        .lt(Document::getExpireAt, LocalDateTime.now()));
        for (Document doc : expired) {
            doc.setLifeStatus("retired");
            documentMapper.updateById(doc);
            log.info("文档已过期自动 retired: {} (expire_at={})", doc.getFileName(), doc.getExpireAt());
        }
        if (!expired.isEmpty()) {
            log.info("过期清理完成: {} 份文档 retired", expired.size());
        }
    }
}
