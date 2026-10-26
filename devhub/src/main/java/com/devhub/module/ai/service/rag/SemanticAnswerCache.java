package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 语义答案缓存（设计对标 GPTCache 的双层结构）
 * <p>
 * 第一层：精确匹配 —— 归一化问题哈希直接命中，零向量计算；
 * 第二层：相似度匹配 —— 新问题向量与历史问题向量余弦相似度超过阈值时复用历史答案。
 * 容量上限 + 24h TTL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticAnswerCache {

    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String LIST_KEY = "rag:semantic-answers";
    private static final String EXACT_KEY_PREFIX = "rag:semantic-exact:";
    private static final int MAX_ENTRIES = 200;
    private static final double SIMILARITY_THRESHOLD = 0.95;
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * 缓存条目：问题向量 + 完整答案（含溯源）
     */
    public record CachedAnswer(String scope, List<Float> queryVector, String question,
                               String answer, String sourcesJson, String model) {}

    /**
     * 查找语义等价的历史答案：先精确层，再相似度层
     */
    public CachedAnswer lookup(List<Float> queryVector, String question, String scope) {
        // 第一层：精确匹配（GPTCache exact-match layer）
        CachedAnswer exact = cacheService.get(exactKey(scope, question), CachedAnswer.class).orElse(null);
        if (exact != null) {
            log.info("语义缓存精确层命中: question={}", question);
            return exact;
        }

        // 第二层：相似度匹配
        try {
            List<String> entries = cacheService.listRange(LIST_KEY, 0, -1);
            if (entries == null) return null;

            CachedAnswer best = null;
            double bestSim = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                CachedAnswer entry = objectMapper.readValue(entries.get(i), CachedAnswer.class);
                if (!java.util.Objects.equals(entry.scope(), scope)) { continue; }  // 数据域隔离
                double sim = cosine(queryVector, entry.queryVector());
                if (sim > bestSim) {
                    bestSim = sim;
                    best = entry;
                }
            }
            if (best != null && bestSim >= SIMILARITY_THRESHOLD) {
                // 实体守卫：订单号等数字实体不同的两问，向量相似度会虚高（实测仅单号之差 sim=0.965），
                // 不能视为等价问题——实体集合不一致时绕过缓存走真实链路
                if (!sameEntities(question, best.question())) {
                    log.info("语义缓存相似({})但实体不同，绕过: now={}, cached={}",
                            String.format("%.3f", bestSim), question, best.question());
                    return null;
                }
                log.info("语义答案缓存命中: question={}, sim={}, 原问题={}",
                        question, String.format("%.3f", bestSim), best.question());
                return best;
            }
        } catch (Exception e) {
            log.warn("语义缓存查询失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 存入答案（双写：精确层 hash + 相似度层 list，超容裁剪头部）
     */
    public void store(List<Float> queryVector, String question, String answer,
                      String sourcesJson, String model, String scope) {
        try {
            CachedAnswer entry = new CachedAnswer(scope, queryVector, question, answer, sourcesJson, model);
            String json = objectMapper.writeValueAsString(entry);
            cacheService.listRightPush(LIST_KEY, json);
            cacheService.set(exactKey(scope, question), entry, cacheService.jitter(TTL));
            long size = cacheService.listRange(LIST_KEY, 0, -1) == null
                    ? 0 : cacheService.listRange(LIST_KEY, 0, -1).size();
            if (size > MAX_ENTRIES) {
                cacheService.listTrim(LIST_KEY, size - MAX_ENTRIES, -1);
            }
            cacheService.expire(LIST_KEY, cacheService.jitter(TTL));
        } catch (Exception e) {
            log.warn("语义缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 精确层 key：归一化问题（去首尾空白/压连续空白/转小写）的 MD5
     */
    private String exactKey(String scope, String question) {
        String normalized = question == null ? "" : question.trim().replaceAll("\\s+", " ").toLowerCase();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return EXACT_KEY_PREFIX + (scope == null ? "all" : scope) + ":" + sb;
        } catch (java.security.NoSuchAlgorithmException e) {
            return EXACT_KEY_PREFIX + normalized.hashCode();
        }
    }

    /** 数字实体（订单号/手机号/金额等含数字片段），用于语义缓存的等价性守卫 */
    private static final java.util.regex.Pattern ENTITY =
            java.util.regex.Pattern.compile("[A-Za-z]*-?\\d+(?:-\\d+)*");

    /**
     * 两问题的数字实体集合是否一致。双方均无实体（纯常识问答）视为一致，可走缓存；
     * 任一方含实体且集合不同（如 SO-2024-1001 vs SO-2024-1004）视为不同问题。
     */
    static boolean sameEntities(String q1, String q2) {
        java.util.Set<String> e1 = entities(q1), e2 = entities(q2);
        return e1.isEmpty() && e2.isEmpty() || e1.equals(e2);
    }

    private static java.util.Set<String> entities(String q) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (q == null) return set;
        java.util.regex.Matcher m = ENTITY.matcher(q);
        while (m.find()) set.add(m.group());
        return set;
    }

    private double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size()) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
