package com.devhub.module.ai.service.eval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.mapper.EvalCaseMapper;
import com.devhub.module.ai.mapper.EvalResultMapper;
import com.devhub.module.ai.mapper.EvalRunMapper;
import com.devhub.module.ai.model.entity.EvalCase;
import com.devhub.module.ai.model.entity.EvalResult;
import com.devhub.module.ai.model.entity.EvalRun;
import com.devhub.module.ai.service.agent.AgentOrchestrator;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ai.service.rag.Reranker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 评测服务单元测试：命中判定语义 + retrieval 模式聚合落库
 */
@ExtendWith(MockitoExtension.class)
class EvalServiceTest {

    @Mock EvalCaseMapper caseMapper;
    @Mock EvalRunMapper runMapper;
    @Mock EvalResultMapper resultMapper;
    @Mock ChatModelRegistry modelRegistry;
    @Mock MultiRetriever multiRetriever;
    @Mock Reranker reranker;
    @Mock AgentOrchestrator orchestrator;

    private EvalService service;

    @BeforeAll
    static void initTableMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EvalCase.class);
    }

    @BeforeEach
    void setUp() {
        service = new EvalService(caseMapper, runMapper, resultMapper,
                modelRegistry, multiRetriever, reranker, orchestrator);
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "gateThreshold", 0.5);
    }

    private EvalCase c(long id, String q, String expectDoc) {
        EvalCase c = new EvalCase();
        c.setId(id); c.setQuestion(q); c.setExpectDocName(expectDoc); c.setEnabled(1);
        return c;
    }

    private MultiRetriever.RetrievalResult r(String fileName, double score) {
        return MultiRetriever.RetrievalResult.builder()
                .fileName(fileName).score(score).content("x").build();
    }

    @Test
    @DisplayName("命中判定：未设期望=true；包含匹配（大小写不敏感）；不包含=false")
    void matchHit_semantics() {
        assertTrue(service.matchHit(c(1L, "q", null), List.of("任何.pdf")));
        assertTrue(service.matchHit(c(1L, "q", "开发手册"), List.of("阿里巴巴Java开发手册.pdf", "其他.md")));
        assertTrue(service.matchHit(c(1L, "q", "cola"), List.of("COLA架构.md")));
        assertFalse(service.matchHit(c(1L, "q", "开发手册"), List.of("COLA架构.md")));
    }

    @Test
    @DisplayName("retrieval 跑批：命中/门控/top1 聚合，run 与逐条 result 落库")
    void run_retrieval_aggregatesAndPersists() {
        when(caseMapper.selectList(any())).thenReturn(List.of(
                c(1L, "问A", "手册"), c(2L, "问B", "不存在")));
        when(modelRegistry.embedToVector(anyString())).thenReturn(List.of(0.1f));
        MultiRetriever.DebugTrace trace = new MultiRetriever.DebugTrace(
                java.util.Map.of(), List.of(), List.of(), java.util.Map.of(), "all");
        when(multiRetriever.debugRetrieve(anyString(), any(), any())).thenReturn(trace);
        // 问A 重排命中期望文档且过门控；问B 文档不符且 top1 低分
        when(reranker.rerank(eq("问A"), any())).thenReturn(List.of(
                r("阿里巴巴Java开发手册.pdf", 0.9), r("其他.md", 0.3)));
        when(reranker.rerank(eq("问B"), any())).thenReturn(List.of(r("无关.md", 0.2)));

        EvalRun run = service.run(false);

        assertEquals(2, run.getTotal());
        assertEquals(1, run.getHitCount());
        assertEquals(1, run.getGatePass());
        assertEquals(0.55, run.getAvgTop1());   // (0.9 + 0.2) / 2 全用例平均
        assertEquals("retrieval", run.getMode());

        ArgumentCaptor<EvalResult> cap = ArgumentCaptor.forClass(EvalResult.class);
        verify(resultMapper, times(2)).insert(cap.capture());
        List<EvalResult> saved = cap.getAllValues();
        assertEquals(1, saved.get(0).getHit());
        assertEquals(0, saved.get(1).getHit());
        assertEquals(0, saved.get(1).getGatePass());   // top1 0.2 < 0.5 → 拒答
        verify(runMapper).insert(any(EvalRun.class));
    }
}
