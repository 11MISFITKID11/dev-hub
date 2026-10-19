package com.devhub.module.ai.service.knowledge;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.Document;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * URL 探活定时任务（无效知识自动清理）
 * <p>
 * 每天凌晨检查 active 文档中的外部链接，连续 2 次失联的文档自动 retired。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlHealthChecker {

    private final DocumentMapper documentMapper;
    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String FAIL_COUNT_PREFIX = "url:fail:";

    /** 每天凌晨 3 点执行 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void checkAll() {
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getLifeStatus, "active")
                        .isNotNull(Document::getUrls));

        int retired = 0;
        for (Document doc : docs) {
            if (checkDocument(doc)) {
                retired++;
            }
        }
        log.info("URL 探活完成: 检查 {} 份文档，{} 份因失联被标记 retired", docs.size(), retired);
    }

    /**
     * @return 文档是否被标记 retired
     */
    private boolean checkDocument(Document doc) {
        try {
            List<String> urls = objectMapper.readValue(doc.getUrls(), List.class);
            List<String> valid = urls.stream().filter(this::isAlive).toList();
            if (valid.size() == urls.size() && !urls.isEmpty()) {
                cacheService.delete(FAIL_COUNT_PREFIX + doc.getId());
                return false;
            }
            // 存在失联链接：连续 2 次 → retired
            String key = FAIL_COUNT_PREFIX + doc.getId();
            String count = cacheService.get(key, String.class).orElse("0");
            int fails = Integer.parseInt(count) + 1;
            cacheService.set(key, String.valueOf(fails), Duration.ofDays(30));
            if (fails >= 2) {
                doc.setLifeStatus("retired");
                documentMapper.updateById(doc);
                log.warn("文档 {} 因链接连续 {} 次失联被标记 retired", doc.getFileName(), fails);
                return true;
            }
            log.warn("文档 {} 存在失联链接（第 {} 次）: {}", doc.getFileName(), fails,
                    urls.size() - valid.size());
        } catch (Exception e) {
            log.warn("URL 探活异常: doc={}, err={}", doc.getFileName(), e.getMessage());
        }
        return false;
    }

    private boolean isAlive(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 400;
        } catch (Exception e) {
            // 有些站点拒绝 HEAD，退回 GET 重试一次
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() < 400;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
