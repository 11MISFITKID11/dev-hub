package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Query 改写服务（对标 Dify Query Rewrite / LangChain MultiQueryRetriever）
 * <p>
 * 一次 LLM 调用同时产出：规范化问题（纠错/去口语/指代消解）+ 检索变体 + 英文翻译。
 * 改写目标是提升检索命中率而非可读性。Redis 缓存 1 小时（getOrLoad 读穿：
 * 并发同问只放一个 LLM 调用，改写失败写短 TTL 空值占位防反复冲击模型），失败降级为原查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final ModelRouter modelRouter;
    private final RedisCacheService cacheService;
    private final com.devhub.module.ai.service.PromptTemplateService promptTemplates;

    private static final String CACHE_KEY_PREFIX = "rag:rewrite:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    // 改写提示词已迁移至 PromptTemplateService.SCENE_QUERY_REWRITE（可运营覆盖）

    /**
     * 改写结果：normalized 为规范化问题，variants 含检索变体，english 为英文
     */
    public record Rewrites(String normalized, List<String> variants, String english, String type, String hypothetical) {
        /** 全部去重后的检索查询（关键词通道用） */
        public List<String> allQueries() {
            List<String> all = new ArrayList<>();
            if (normalized != null && !normalized.isBlank()) all.add(normalized);
            if (variants != null) variants.forEach(v -> { if (v != null && !v.isBlank()) all.add(v); });
            if (english != null && !english.isBlank() && !all.contains(english)) all.add(english);
            return all;
        }

        /** 问题类型：exact=精确查找 / normal=一般问答 / complex=复杂分析 */
        public boolean isExact() { return "exact".equals(type); }
        public boolean isComplex() { return "complex".equals(type); }
    }

    /**
     * 改写查询（带缓存，失败降级为原查询）
     */
    public Rewrites rewrite(String query) {
        return rewrite(query, null);
    }

    /**
     * 带对话历史的改写（指代消解，LangChain history-aware retriever 借鉴）
     */
    public Rewrites rewrite(String query, String history) {
        if (query == null || query.isBlank()) {
            return new Rewrites(query, List.of(), null, "normal", null);
        }

        String cacheKey = CACHE_KEY_PREFIX + md5(query + "|" + (history == null ? "" : history));
        try {
            CachedRewrites parsed = cacheService.getOrLoad(cacheKey, CachedRewrites.class,
                    () -> callModel(query, history), CACHE_TTL).orElse(null);
            if (parsed == null) {
                return fallback(query);
            }
            Rewrites result = parsed.toRewrites();
            if (result.normalized() == null || result.normalized().isBlank()) {
                return fallback(query);
            }
            log.info("查询改写完成: {} -> normalized={}, variants={}, english={}",
                    query, result.normalized(), result.variants().size(),
                    result.english() != null ? "yes" : "no");
            return result;
        } catch (Exception e) {
            log.warn("查询改写失败，使用原查询: {}", e.getMessage());
            return fallback(query);
        }
    }

    /**
     * 单次 LLM 改写调用；失败或产出无效时返回 null，
     * 由 getOrLoad 写入短 TTL 空值占位，避免同一失败问题反复冲击模型
     */
    private CachedRewrites callModel(String query, String history) {
        try {
            String historyBlock = (history == null || history.isBlank()) ? ""
                    : "对话历史（供指代消解）：\n" + history + "\n";
            String output = modelRouter.call(promptTemplates.render(
                    com.devhub.module.ai.service.PromptTemplateService.SCENE_QUERY_REWRITE,
                    java.util.Map.of("question", query, "history", historyBlock)), null).trim();
            String json = output.replaceAll("(?s).*?(\\{.*}).*", "$1");
            CachedRewrites parsed = JsonHolder.MAPPER.readValue(json, CachedRewrites.class);
            return (parsed.normalized == null || parsed.normalized.isBlank()) ? null : parsed;
        } catch (Exception e) {
            log.warn("改写 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private Rewrites fallback(String query) {
        return new Rewrites(query, List.of(), null, "normal", null);
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /** 缓存载体（Jackson 需要无参构造+字段） */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CachedRewrites {
        private String normalized;
        private List<String> variants;
        private String english;
        private String type;
        private String hypothetical;

        Rewrites toRewrites() {
            return new Rewrites(normalized, variants != null ? variants : List.of(), english,
                    type != null ? type : "normal", hypothetical);
        }
    }

    private static class JsonHolder {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
