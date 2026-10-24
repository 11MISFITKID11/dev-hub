package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.devhub.module.ai.infrastructure.vectordb.MilvusService;
import com.devhub.module.ai.mapper.DocumentChunkMapper;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.milvus.grpc.SearchResults;
import io.milvus.response.SearchResultsWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 多路检索引擎
 * <p>
 * 支持多种检索策略并行执行，合并结果后统一排序。
 * <ul>
 *   <li>向量语义检索：通过 Milvus 进行余弦相似度搜索</li>
 *   <li>关键词检索：基于 BM25 或简单关键词匹配</li>
 *   <li>缓存层：高频查询结果缓存，避免重复检索</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRetriever {

    private final MilvusService milvusService;
    private final RedisCacheService cacheService;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;
    private final ModelRouter modelRouter;
    private final ChatModelRegistry modelRegistry;
    private final QueryRewriteService queryRewriteService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Value("${agent.rag.top-k:5}")
    private int topK;

    /** 每通道粗召回深度（对标 QAnything 两阶段检索：先深召回，后重排精筛） */
    @Value("${agent.rag.recall-depth:25}")
    private int recallDepth;

    /** RRF 融合后送入重排的候选池大小 */
    @Value("${agent.rag.candidate-n:15}")
    private int candidateN;

    /** HyDE 开关（默认关闭：实测 flash 生成的假设文档向量对 complex 题召回不稳，kwRecall -0.08） */
    @Value("${agent.rag.hyde-enabled:false}")
    private boolean hydeEnabled;

    @Value("${agent.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    /** RRF 融合常数（标准值 60，来自 RRF 论文，LangChain/Dify 同款） */
    private static final int RRF_K = 60;

    private static final String CACHE_PREFIX = "rag:retrieve:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String INDEX_VERSION_KEY = "rag:index:version";
    private static final Duration RESULT_CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 执行多路检索
     *
     * @param query       用户查询
     * @param queryVector 查询向量（如果为 null 会尝试生成）
     * @return 检索结果列表
     */
    /**
     * 混合检索（对标 Dify/FastGPT 多路召回）：
     * Query 改写（规范化+变体+英文）→ 向量通道（规范+英文）+ 关键词通道（全部查询）→ RRF 融合。
     * 结果缓存 30 分钟，文档变更时版本号失效。
     *
     * @param query       用户查询
     * @param queryVector 查询向量（null 则只走关键词通道）
     * @return 融合排序后的结果
     */
    @SuppressWarnings("unchecked")
    public List<RetrievalResult> retrieve(String query, List<Float> queryVector) {
        return retrieve(query, queryVector, null);
    }

    /**
     * 执行多路检索（可带对话历史用于指代消解改写）
     */
    @SuppressWarnings("unchecked")
    public List<RetrievalResult> retrieve(String query, List<Float> queryVector, String history) {
        log.info("开始混合检索: query={}, topK={}", truncate(query, 80), topK);
        long start = System.currentTimeMillis();

        QueryRewriteService.Rewrites rewrites = queryRewriteService.rewrite(query, history);
        log.info("查询类型: {} (改写: {})", rewrites.type(), truncate(rewrites.normalized(), 60));

        // 检索结果缓存
        String cacheKey = null;
        try {
            String version = cacheService.get(INDEX_VERSION_KEY, String.class).orElse("0");
            cacheKey = "rag:result:v" + version + ":" + currentScope() + ":" + md5(rewrites.normalized());
            List<RetrievalResult> cached = readResultCache(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                log.info("检索结果缓存命中，跳过检索");
                return cached;
            }
        } catch (Exception e) {
            log.warn("检索缓存读取失败: {}", e.getMessage());
        }

        List<List<RetrievalResult>> channels = new ArrayList<>();

        // ===== 检索路由（按问题类型裁剪通道，控制成本）=====
        if (rewrites.isExact()) {
            // exact：精确编号/文件名查找 → 关键词 + 精确匹配通道（省 embedding 调用）
            log.info("路由决策[exact]: 关键词 + 精确匹配通道（跳过向量）");
            rewrites.allQueries().forEach(q -> channels.add(keywordRetrieval(q)));
            channels.add(exactMatch(rewrites.normalized()));
        } else {
            // normal/complex：向量通道（复用调用方已算好的向量）
            // HyDE（LangChain HypotheticalDocumentEmbedder 借鉴）：complex 类用"假设答案"的向量检索——
            // 答案与文档的语义空间比问题更近；仅 complex 启用控制 embedding 成本
            List<Float> effectiveVector = queryVector;
            if (hydeEnabled && rewrites.isComplex() && rewrites.hypothetical() != null && !rewrites.hypothetical().isBlank()) {
                try {
                    effectiveVector = modelRegistry.embedToVector(rewrites.hypothetical());
                    log.info("HyDE 启用: 假设文档向量替代问题向量");
                } catch (Exception e) {
                    log.warn("HyDE 向量化失败，回退问题向量: {}", e.getMessage());
                }
            }
            channels.add((effectiveVector != null && !effectiveVector.isEmpty())
                    ? vectorRetrieval(effectiveVector) : Collections.emptyList());

            // complex 额外走英文翻译路（跨语言召回）
            if (rewrites.isComplex() && rewrites.english() != null && !rewrites.english().isBlank()) {
                try {
                    channels.add(vectorRetrieval(modelRegistry.embedToVector(rewrites.english())));
                } catch (Exception e) {
                    log.warn("英文查询向量化失败: {}", e.getMessage());
                }
            }

            // 关键词通道：所有改写查询
            rewrites.allQueries().forEach(q -> channels.add(keywordRetrieval(q)));

            // 第三层：精确短语匹配（对 normalized 查询做 LIKE，exact 类问题的强通道）
            if (rewrites.normalized() != null && !rewrites.normalized().isBlank()) {
                channels.add(exactMatch(rewrites.normalized()));
            }
        }

        int activeChannels = (int) channels.stream().filter(ch -> !ch.isEmpty()).count();
        log.info("检索通道: {} 个查询 / {} 个非空通道", rewrites.allQueries().size(), activeChannels);

        // 两阶段顺序：子块融合/重排（精准匹配与打分）→ 父块展开（大上下文生成）。
        // 展开放在重排之后——父块内容宽泛，直接重排会被打低分误触门控（实测教训）
        List<RetrievalResult> filtered = rrfFuse(channels).stream()
                .limit(candidateN)
                .toList();

        log.info("混合检索完成: 耗时 {}ms, 候选 {} 条（每通道召回 {}，重排后由编排层截 top{}）",
                System.currentTimeMillis() - start, filtered.size(), recallDepth, topK);

        if (cacheKey != null && !filtered.isEmpty()) {
            cacheService.set(cacheKey, filtered, RESULT_CACHE_TTL);
        }
        return filtered;
    }

    /** 缓存条目转换用（泛型擦除后需逐条 convertValue 回强类型） */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 读取检索结果缓存：Jackson 泛型擦除使 List 元素反序列化为 LinkedHashMap，
     * 直接返回会在下游转型 RetrievalResult 时抛 ClassCastException（曾导致缓存命中必降级）。
     * 逐条转回强类型；转换失败视为脏缓存，清除后按未命中处理（最坏退化为重新检索）
     */
    List<RetrievalResult> readResultCache(String cacheKey) {
        Optional<List> cached = cacheService.get(cacheKey, List.class);
        if (cached.isEmpty() || cached.get().isEmpty()) {
            return null;
        }
        try {
            List<RetrievalResult> results = new ArrayList<>();
            for (Object element : cached.get()) {
                results.add(objectMapper.convertValue(element, RetrievalResult.class));
            }
            return results;
        } catch (Exception e) {
            log.warn("检索缓存内容损坏，按未命中处理并清除: {}", e.getMessage());
            cacheService.delete(cacheKey);
            return null;
        }
    }

    /**
     * 文档变更时调用：bump 版本号使全部检索缓存失效
     */
    public void invalidateIndex() {
        invalidateWhitelist();
        try {
            String current = cacheService.get(INDEX_VERSION_KEY, String.class).orElse("0");
            cacheService.set(INDEX_VERSION_KEY, String.valueOf(Long.parseLong(current) + 1), Duration.ofDays(30));
            log.info("检索索引版本已升级（缓存全部失效）");
        } catch (Exception e) {
            cacheService.set(INDEX_VERSION_KEY, String.valueOf(System.currentTimeMillis()), Duration.ofDays(30));
        }
    }

    private static String md5(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * RRF（Reciprocal Rank Fusion）：score = Σ 1/(k + rank)，多路都命中的 chunk 得分叠加
     */
    private List<RetrievalResult> rrfFuse(List<List<RetrievalResult>> channels) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievalResult> byId = new HashMap<>();

        channels.forEach(ch -> accumulateRrf(ch, rrfScores, byId));

        double maxPossible = channels.size() / (double) (RRF_K + 1);
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> {
                    RetrievalResult r = byId.get(e.getKey());
                    double score = Math.min(1.0, e.getValue() / maxPossible);
                    // AI 生成的 draft 知识降权 30%（管理员采纳后转正恢复全权重）
                    if ("draft".equals(r.getDocStatus())) {
                        score *= 0.7;
                    }
                    return r.toBuilder()
                            .score(score)
                            .source("hybrid")
                            .build();
                })
                .toList();
    }

    private void accumulateRrf(List<RetrievalResult> ranked, Map<String, Double> scores,
                               Map<String, RetrievalResult> byId) {
        for (int i = 0; i < ranked.size(); i++) {
            RetrievalResult r = ranked.get(i);
            scores.merge(r.getChunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
            byId.putIfAbsent(r.getChunkId(), r);
        }
    }

    /**
     * Parent-Child 展开（small-to-big）：子块命中 → 替换为父块（大上下文）进重排与生成。
     * 同一父块的多个子块命中折叠为一条（取最高 RRF 分）；旧版单层切片（无 parent_id）原样保留。
     */
    public List<RetrievalResult> expandToParents(List<RetrievalResult> fused) {
        if (fused.isEmpty()) {
            return fused;
        }
        try {
            // 批量查询子块的 parent 归属
            List<String> childIds = fused.stream()
                    .map(RetrievalResult::getChunkId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            Map<String, String> parentOf = new HashMap<>();
            for (var chunk : documentChunkMapper.selectList(
                    new LambdaQueryWrapper<com.devhub.module.ai.model.entity.DocumentChunkEntity>()
                            .in(com.devhub.module.ai.model.entity.DocumentChunkEntity::getId, childIds)
                            .select(com.devhub.module.ai.model.entity.DocumentChunkEntity::getId,
                                    com.devhub.module.ai.model.entity.DocumentChunkEntity::getParentId))) {
                parentOf.put(chunk.getId(), chunk.getParentId());
            }
            List<String> parentIds = parentOf.values().stream()
                    .filter(pid -> pid != null && !pid.isBlank()).distinct().toList();
            if (parentIds.isEmpty()) {
                return fused; // 全是旧版单层切片
            }
            Map<String, com.devhub.module.ai.model.entity.DocumentChunkEntity> parents = new HashMap<>();
            for (var p : documentChunkMapper.selectList(
                    new LambdaQueryWrapper<com.devhub.module.ai.model.entity.DocumentChunkEntity>()
                            .in(com.devhub.module.ai.model.entity.DocumentChunkEntity::getId, parentIds))) {
                parents.put(p.getId(), p);
            }

            // 折叠：父块取最高融合分；无父块的命中保留原子块
            Map<String, RetrievalResult> collapsed = new LinkedHashMap<>();
            for (RetrievalResult r : fused) {
                String pid = parentOf.get(r.getChunkId());
                String key = pid != null ? pid : r.getChunkId();
                var parent = pid != null ? parents.get(pid) : null;
                RetrievalResult expanded = parent != null
                        ? r.toBuilder().chunkId(parent.getId()).content(parent.getContent()).build()
                        : r;
                collapsed.merge(key, expanded, (a, b) -> a.getScore() >= b.getScore() ? a : b);
            }
            log.info("Parent-Child 展开: {} 子块命中 -> {} 父块", fused.size(), collapsed.size());
            return new ArrayList<>(collapsed.values());
        } catch (Exception e) {
            log.warn("父块展开失败，返回子块结果: {}", e.getMessage());
            return fused;
        }
    }

    /**
     * 向量语义检索（取 recallDepth 粗召回候选，供融合使用；按文档生命周期状态过滤）
     */
    private List<RetrievalResult> vectorRetrieval(List<Float> queryVector) {
        try {
            SearchResults searchResults = milvusService.searchSimilar(queryVector, recallDepth);
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());

            // 有效文档白名单（active/draft；retired/superseded/disabled/conflict 的内容不返回）
            java.util.Set<String> validDocIds = loadValidDocumentIds();

            List<RetrievalResult> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
                Map<String, Object> fieldValues = wrapper.getRowRecords(0).get(i).getFieldValues();
                String docId = fieldValues.getOrDefault("document_id", "").toString();
                if (!validDocIds.contains(docId)) {
                    continue;
                }
                results.add(RetrievalResult.builder()
                        .chunkId(fieldValues.getOrDefault("doc_id", "").toString())
                        .documentId(docId)
                        .fileName(fieldValues.getOrDefault("file_name", "").toString())
                        .content(fieldValues.getOrDefault("content", "").toString())
                        .score((double) idScore.getScore())
                        .docStatus(statusOf(docId))
                        .source("vector")
                        .build());
            }
            return results;
        } catch (Exception e) {
            log.error("向量检索失败", e);
            return Collections.emptyList();
        }
    }

    /** 白名单缓存（按数据域 scope 隔离）：时间戳失效 + invalidateIndex 主动失效 */
    private static final long WHITELIST_TTL_MS = 5_000;
    private record ScopeCache(java.util.Set<String> ids, java.util.Map<String, String> status, long loadedAt) {}
    private final java.util.Map<String, ScopeCache> scopeCaches = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 数据域（方案 5.3）：ADMIN/无安全上下文（后台任务、评测脚本）= 全部文档；
     * 普通用户 = 本部门 + 公共（dept_id IS NULL）。检索三通道统一经此白名单过滤，
     * AI 层不自行查权限。
     */
    /** 公开给编排层：语义缓存同样按数据域隔离 */
    public String dataScope() { return currentScope(); }

    /** 开放链路检索域覆盖（app 部门域）；内部链路不设置，行为不变。开放 Controller 经 setScopeThen 配对设置/清理 */
    private static final ThreadLocal<String> SCOPE_OVERRIDE = new ThreadLocal<>();

    public static void setScopeOverride(String scope) { SCOPE_OVERRIDE.set(scope); }

    public static void clearScopeOverride() { SCOPE_OVERRIDE.remove(); }

    private String currentScope() {
        String override = SCOPE_OVERRIDE.get();
        if (override != null) return override;
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
                return "all";
            }
            boolean admin = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            if (admin) return "all";
            Long dept = jdbcTemplate.queryForObject(
                    "SELECT dept_id FROM sys_user WHERE username = ? AND deleted = 0",
                    Long.class, auth.getName());
            return "dept:" + (dept == null ? -1 : dept);
        } catch (Exception e) {
            return "all";
        }
    }

    private java.util.Set<String> loadValidDocumentIds() {
        String scope = currentScope();
        long now = System.currentTimeMillis();
        ScopeCache cached = scopeCaches.get(scope);
        if (cached != null && now - cached.loadedAt() < WHITELIST_TTL_MS) {
            return cached.ids();
        }
        synchronized (this) {
            cached = scopeCaches.get(scope);
            if (cached != null && System.currentTimeMillis() - cached.loadedAt() < WHITELIST_TTL_MS) {
                return cached.ids();
            }
            LambdaQueryWrapper<com.devhub.module.ai.model.entity.Document> q =
                    new LambdaQueryWrapper<com.devhub.module.ai.model.entity.Document>()
                            .in(com.devhub.module.ai.model.entity.Document::getLifeStatus, "active", "draft")
                            .select(com.devhub.module.ai.model.entity.Document::getId,
                                    com.devhub.module.ai.model.entity.Document::getLifeStatus,
                                    com.devhub.module.ai.model.entity.Document::getDeptId);
            if (!"all".equals(scope)) {
                long deptId = Long.parseLong(scope.substring(5));
                // 本部门 + 公共；dept_id 为 NULL 的旧文档视作公共
                q.and(w -> w.eq(com.devhub.module.ai.model.entity.Document::getDeptId, deptId)
                        .or().isNull(com.devhub.module.ai.model.entity.Document::getDeptId));
            }
            var docs = documentMapper.selectList(q);
            java.util.Map<String, String> fresh = new java.util.concurrent.ConcurrentHashMap<>();
            docs.forEach(d -> fresh.put(d.getId(), d.getLifeStatus()));
            ScopeCache entry = new ScopeCache(fresh.keySet(), fresh, System.currentTimeMillis());
            scopeCaches.put(scope, entry);
            return entry.ids();
        }
    }

    /** 文档变更时主动失效全部 scope 白名单（upload/delete/review 经 invalidateIndex 触发） */
    private void invalidateWhitelist() {
        scopeCaches.clear();
    }

    private String statusOf(String docId) {
        for (ScopeCache sc : scopeCaches.values()) {
            String st = sc.status().get(docId);
            if (st != null) return st;
        }
        return "active";
    }

    /**
     * 关键词检索：MySQL ngram 全文索引（BM25 相关度排序），作为混合检索的关键词通道。
     * 按有效文档白名单过滤——旧版本（superseded/retired）切片不参与检索，
     * 否则同文档多版本会挤占 top-k 名额（向量通道已在 Milvus 侧过滤）。
     */
    private List<RetrievalResult> keywordRetrieval(String query) {
        try {
            List<com.devhub.module.ai.model.entity.DocumentChunkEntity> hits =
                    documentChunkMapper.searchByKeyword(query, recallDepth);
            java.util.Set<String> validDocIds = loadValidDocumentIds();
            List<RetrievalResult> results = new ArrayList<>();
            for (com.devhub.module.ai.model.entity.DocumentChunkEntity hit : hits) {
                if (!validDocIds.contains(hit.getDocumentId())) {
                    continue;
                }
                results.add(RetrievalResult.builder()
                        .chunkId(hit.getId())
                        .documentId(hit.getDocumentId())
                        .fileName(hit.getFileName())
                        .content(hit.getContent())
                        .score(hit.getScore() != null ? hit.getScore() : 0.0)
                        .docStatus(statusOf(hit.getDocumentId()))
                        .source("keyword")
                        .build());
            }
            return results;
        } catch (Exception e) {
            log.warn("关键词检索失败（通道降级）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 合并去重：相同 chunk 取最高分
     */
    private List<RetrievalResult> mergeAndDeduplicate(List<RetrievalResult> results) {
        Map<String, RetrievalResult> deduped = new LinkedHashMap<>();

        for (RetrievalResult result : results) {
            String key = result.getChunkId() != null && !result.getChunkId().isBlank()
                    ? result.getChunkId() : result.getDocumentId() + "#" + result.getContent().hashCode();
            if (deduped.containsKey(key)) {
                RetrievalResult existing = deduped.get(key);
                if (result.getScore() > existing.getScore()) {
                    deduped.put(key, result);
                }
            } else {
                deduped.put(key, result);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private Optional<List<RetrievalResult>> checkCache(String query) {
        // 简化实现：通过查询文本 hash 做缓存 key
        return Optional.empty();
    }

    private void cacheResults(String query, List<RetrievalResult> results) {
        // 生产环境应实现缓存写入
    }

    /**
     * 第三层：精确短语匹配（LIKE，命中得 1.0 分，适合具体条款编号/文件号等）
     */
    private List<RetrievalResult> exactMatch(String phrase) {
        try {
            List<com.devhub.module.ai.model.entity.DocumentChunkEntity> hits =
                    documentChunkMapper.searchExact(phrase, recallDepth);
            java.util.Set<String> validDocIds = loadValidDocumentIds();
            return hits.stream()
                    .filter(hit -> validDocIds.contains(hit.getDocumentId()))
                    .map(hit -> RetrievalResult.builder()
                    .chunkId(hit.getId())
                    .documentId(hit.getDocumentId())
                    .fileName(hit.getFileName())
                    .content(hit.getContent())
                    .score(hit.getScore() != null ? hit.getScore() : 1.0)
                    .docStatus(statusOf(hit.getDocumentId()))
                    .source("exact")
                    .build()).toList();
        } catch (Exception e) {
            log.warn("精确匹配通道失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    @Data
    @Builder(toBuilder = true)
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RetrievalResult {
        /** chunk 主键，用于去重 */
        private String chunkId;
        private String documentId;
        /** 来源文件名，用于引用溯源 */
        private String fileName;
        private String content;
        private double score;
        /** 来源标识：vector / keyword / hybrid */
        private String source;
        /** 所属文档生命周期状态（draft 结果降权用） */
        private String docStatus;
    }
}
