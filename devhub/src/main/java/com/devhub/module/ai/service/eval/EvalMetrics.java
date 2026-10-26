package com.devhub.module.ai.service.eval;

import java.util.List;

/**
 * 检索排序质量指标（对标 RAGAS retrieval metrics）
 * <p>
 * 命中率只回答"期望文档是否进了 Top-K"；MRR/NDCG 进一步回答"排在第几"：
 * <ul>
 *   <li>MRR = 1 / 首个命中文档的排名（rank1=1.0，rank3≈0.333，未命中=0）</li>
 *   <li>NDCG@k（二值相关度、单期望文档）= 1 / log2(rank+1)（rank1=1.0，rank2≈0.631）；
 *       NDCG 对排名的惩罚比 MRR 平缓，两者一起看能区分"头部准"与"整体乱"</li>
 * </ul>
 * 用例未设置期望文档（expectDocName 为空）时不参与这两项指标（返回 null），
 * 与命中率"未设期望视为命中"的语义解耦。
 */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /**
     * 期望文档在结果列表中的排名（1-based）。
     *
     * @return 0=未命中；-1=未设期望（不参与指标）
     */
    public static int firstHitRank(String expectDocName, List<String> docNames) {
        if (expectDocName == null || expectDocName.isBlank()) {
            return -1;
        }
        String target = expectDocName.trim().toLowerCase();
        for (int i = 0; i < docNames.size(); i++) {
            String name = docNames.get(i);
            if (name != null && name.toLowerCase().contains(target)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** MRR；rank=-1（未设期望）返回 null */
    public static Double mrr(int rank) {
        if (rank < 0) return null;
        return rank == 0 ? 0.0 : 1.0 / rank;
    }

    /** NDCG@k（二值相关度）；rank=-1（未设期望）返回 null */
    public static Double ndcg(int rank) {
        if (rank < 0) return null;
        if (rank == 0) return 0.0;
        return 1.0 / (Math.log(rank + 1) / Math.log(2));
    }

    /** 四舍五入到 3 位小数（评测落库展示用） */
    public static Double round3(Double value) {
        if (value == null) return null;
        return Math.round(value * 1000) / 1000.0;
    }
}
