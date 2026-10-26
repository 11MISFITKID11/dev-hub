package com.devhub.module.ai.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索排序指标数学正确性（MRR / NDCG@k）
 */
class EvalMetricsTest {

    @Test
    void firstHitRank_大小写不敏感_包含匹配() {
        assertEquals(1, EvalMetrics.firstHitRank("开发手册",
                List.of("阿里巴巴Java开发手册.pdf", "入职指引.docx")));
        assertEquals(2, EvalMetrics.firstHitRank("manual",
                List.of("目录.md", "Onboarding-Manual.pdf")));
        assertEquals(0, EvalMetrics.firstHitRank("不存在", List.of("a.pdf", "b.docx")));
        // 未设期望：-1，不参与指标
        assertEquals(-1, EvalMetrics.firstHitRank("  ", List.of("a.pdf")));
        assertEquals(-1, EvalMetrics.firstHitRank(null, List.of("a.pdf")));
    }

    @Test
    void mrr_倒数排名() {
        assertEquals(1.0, EvalMetrics.mrr(1));
        assertEquals(0.5, EvalMetrics.mrr(2));
        assertEquals(1.0 / 3, EvalMetrics.mrr(3), 1e-9);
        assertEquals(0.0, EvalMetrics.mrr(0));   // 未命中
        assertNull(EvalMetrics.mrr(-1));          // 未设期望
    }

    @Test
    void ndcg_折损比MRR平缓() {
        assertEquals(1.0, EvalMetrics.ndcg(1));
        assertEquals(1.0 / (Math.log(3) / Math.log(2)), EvalMetrics.ndcg(2), 1e-9);
        assertEquals(0.0, EvalMetrics.ndcg(0));
        assertNull(EvalMetrics.ndcg(-1));
        // rank2 处 NDCG(0.631) > MRR(0.5)：NDCG 惩罚更平缓
        assertTrue(EvalMetrics.ndcg(2) > EvalMetrics.mrr(2));
        // 单调递减
        assertTrue(EvalMetrics.ndcg(2) > EvalMetrics.ndcg(3) && EvalMetrics.ndcg(3) > EvalMetrics.ndcg(4));
    }

    @Test
    void round3_保留三位小数() {
        assertEquals(0.333, EvalMetrics.round3(1.0 / 3));
        assertEquals(0.5, EvalMetrics.round3(0.5));
        assertNull(EvalMetrics.round3(null));
    }
}
