package com.devhub.module.ai.service.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.mapper.EvalCaseMapper;
import com.devhub.module.ai.mapper.EvalResultMapper;
import com.devhub.module.ai.mapper.EvalRunMapper;
import com.devhub.module.ai.model.dto.ChatRequest;
import com.devhub.module.ai.model.dto.ChatResponse;
import com.devhub.module.ai.model.entity.EvalCase;
import com.devhub.module.ai.model.entity.EvalResult;
import com.devhub.module.ai.model.entity.EvalRun;
import com.devhub.module.ai.model.enums.AgentMode;
import com.devhub.module.ai.service.agent.AgentOrchestrator;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ai.service.rag.Reranker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测跑批服务（对标 RAGAS 的检索质量回归，复用自身真实链路）：
 * <ul>
 *   <li>retrieval 模式：走到重排为止（零 LLM 成本）——命中率/门控/top1 分</li>
 *   <li>full 模式：走 orchestrator.chat 完整链路（evalMode 跳语义缓存）——额外得到忠实度</li>
 * </ul>
 * 改提示词/换模型/调检索参数后跑一轮，对比上一轮指标即回归验证
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalService {

    private final EvalCaseMapper caseMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final ChatModelRegistry modelRegistry;
    private final MultiRetriever multiRetriever;
    private final Reranker reranker;
    private final AgentOrchestrator orchestrator;

    @Value("${agent.rag.top-k:5}")
    private int topK;

    @Value("${agent.rag.gate-top1-score:0.5}")
    private double gateThreshold;

    /** 跑一轮；返回 run 记录（明细已落库） */
    public EvalRun run(boolean full) {
        long start = System.currentTimeMillis();
        List<EvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getEnabled, 1));
        if (cases.isEmpty()) {
            throw new com.devhub.module.ai.common.BusinessException("评测集为空，请先添加用例");
        }

        int hitCount = 0, gatePass = 0, faithCount = 0;
        double top1Sum = 0, faithSum = 0, mrrSum = 0, ndcgSum = 0;
        int rankCount = 0;
        List<EvalResult> results = new ArrayList<>();

        for (EvalCase c : cases) {
            EvalResult r = full ? runFull(c) : runRetrieval(c);
            r.setCaseId(c.getId());
            r.setQuestion(c.getQuestion());
            results.add(r);
            if (r.getHit() != null && r.getHit() == 1) hitCount++;
            if (r.getGatePass() != null && r.getGatePass() == 1) gatePass++;
            if (r.getTop1Score() != null) top1Sum += r.getTop1Score();
            if (r.getFaithScore() != null) { faithSum += r.getFaithScore(); faithCount++; }
            if (r.getMrr() != null) { mrrSum += r.getMrr(); ndcgSum += r.getNdcg(); rankCount++; }
        }

        EvalRun run = EvalRun.builder()
                .mode(full ? "full" : "retrieval")
                .total(cases.size())
                .hitCount(hitCount)
                .gatePass(gatePass)
                .avgTop1(results.isEmpty() ? 0 : Math.round(top1Sum / results.size() * 1000) / 1000.0)
                .avgMrr(rankCount == 0 ? null : Math.round(mrrSum / rankCount * 1000) / 1000.0)
                .avgNdcg(rankCount == 0 ? null : Math.round(ndcgSum / rankCount * 1000) / 1000.0)
                .avgFaith(faithCount == 0 ? null : Math.round(faithSum / faithCount * 100) / 100.0)
                .costMs(System.currentTimeMillis() - start)
                .createdBy(currentUser())
                .build();
        runMapper.insert(run);
        for (EvalResult r : results) {
            r.setRunId(run.getId());
            resultMapper.insert(r);
        }
        log.info("评测跑批完成: mode={}, total={}, hit={}, gate={}, cost={}ms",
                run.getMode(), run.getTotal(), hitCount, gatePass, run.getCostMs());
        return run;
    }

    /** 检索模式：embed → debugRetrieve（真实通道+融合）→ 重排，不打 LLM */
    private EvalResult runRetrieval(EvalCase c) {
        EvalResult r = new EvalResult();
        try {
            List<Float> vector = modelRegistry.embedToVector(c.getQuestion());
            MultiRetriever.DebugTrace trace = multiRetriever.debugRetrieve(c.getQuestion(), vector, null);
            List<MultiRetriever.RetrievalResult> reranked = reranker.rerank(c.getQuestion(), trace.fused());
            fillFromRetrieval(r, c, reranked);
        } catch (Exception e) {
            log.warn("评测用例执行失败: question={}, cause={}", c.getQuestion(), e.getMessage());
            r.setHit(0);
            r.setGatePass(0);
            r.setTopDocs("执行失败: " + e.getMessage());
        }
        return r;
    }

    /** 完整模式：真实问答链路（含引用/忠实度），evalMode 防语义缓存短路 */
    private EvalResult runFull(EvalCase c) {
        EvalResult r = new EvalResult();
        try {
            ChatRequest req = new ChatRequest();
            req.setMessage(c.getQuestion());
            req.setEnableRag(true);
            req.setMode(AgentMode.DIRECT);
            req.setVerifyFaithfulness(true);
            req.setEvalMode(true);
            req.setConversationId("eval-bench");
            ChatResponse resp = orchestrator.chat(req);
            List<ChatResponse.SourceDocument> sources = resp.getSources() == null
                    ? List.of() : resp.getSources();
            r.setHit(matchHit(c, sources.stream()
                    .map(s -> s.getFileName() == null ? "" : s.getFileName()).toList()) ? 1 : 0);
            r.setGatePass(sources.isEmpty() ? 0 : 1);
            r.setTopDocs(joinDocs(sources.stream()
                    .map(s -> s.getFileName() == null ? "" : s.getFileName()).toList()));
            List<String> sourceNames = sources.stream()
                    .map(s -> s.getFileName() == null ? "" : s.getFileName()).toList();
            int rank = EvalMetrics.firstHitRank(c.getExpectDocName(), sourceNames);
            r.setMrr(EvalMetrics.round3(EvalMetrics.mrr(rank)));
            r.setNdcg(EvalMetrics.round3(EvalMetrics.ndcg(rank)));
            r.setTop1Score(sources.isEmpty() ? null
                    : Math.round(sources.get(0).getScore() * 1000) / 1000.0);
            r.setFaithScore(resp.getFaithfulness());
            if (resp.getReply() != null) {
                r.setAnswerExcerpt(resp.getReply().length() > 400
                        ? resp.getReply().substring(0, 400) : resp.getReply());
            }
            // 关键词校验：期望关键词全部出现在答案中才计命中
            if (c.getExpectKeywords() != null && !c.getExpectKeywords().isBlank()
                    && r.getHit() == 1 && resp.getReply() != null) {
                for (String kw : c.getExpectKeywords().split("[,，]")) {
                    if (!kw.isBlank() && !resp.getReply().contains(kw.trim())) {
                        r.setHit(0);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("评测用例(full)执行失败: question={}, cause={}", c.getQuestion(), e.getMessage());
            r.setHit(0);
            r.setGatePass(0);
            r.setTopDocs("执行失败: " + e.getMessage());
        }
        return r;
    }

    private void fillFromRetrieval(EvalResult r, EvalCase c,
                                   List<MultiRetriever.RetrievalResult> reranked) {
        List<String> names = reranked.stream().limit(topK)
                .map(x -> x.getFileName() == null ? "" : x.getFileName()).toList();
        r.setHit(matchHit(c, names) ? 1 : 0);
        r.setGatePass(reranked.isEmpty() ? 0 : (reranked.get(0).getScore() >= gateThreshold ? 1 : 0));
        r.setTop1Score(reranked.isEmpty() ? null
                : Math.round(reranked.get(0).getScore() * 1000) / 1000.0);
        int rank = EvalMetrics.firstHitRank(c.getExpectDocName(), names);
        r.setMrr(EvalMetrics.round3(EvalMetrics.mrr(rank)));
        r.setNdcg(EvalMetrics.round3(EvalMetrics.ndcg(rank)));
        r.setTopDocs(joinDocs(names));
    }

    /** 命中判定：期望文档名非空时，任一 Top-K 文件名包含它（大小写不敏感） */
    boolean matchHit(EvalCase c, List<String> docNames) {
        String expect = c.getExpectDocName();
        if (expect == null || expect.isBlank()) return true;   // 未设期望视为命中（只看门控）
        String target = expect.trim().toLowerCase();
        return docNames.stream().anyMatch(n -> n != null && n.toLowerCase().contains(target));
    }

    private String joinDocs(List<String> names) {
        String joined = String.join("；", names);
        return joined.length() > 900 ? joined.substring(0, 900) : joined;
    }

    private String currentUser() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
