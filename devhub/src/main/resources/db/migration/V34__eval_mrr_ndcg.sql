-- 评测指标升级：检索排序质量 MRR / NDCG@k（对标 RAGAS 检索指标，补齐命中位置维度）
-- 命中率只看"是否在 Top-K"，MRR/NDCG 进一步看"排在第几"
ALTER TABLE eval_result
    ADD COLUMN mrr  DOUBLE NULL COMMENT 'MRR：首个命中文档排名倒数（未命中=0，未设期望=NULL）',
    ADD COLUMN ndcg DOUBLE NULL COMMENT 'NDCG@k：二值相关度折损累计增益（单期望文档，rank1=1.0）';

ALTER TABLE eval_run
    ADD COLUMN avg_mrr  DOUBLE NULL COMMENT '平均MRR（仅统计设置了期望文档的用例）',
    ADD COLUMN avg_ndcg DOUBLE NULL COMMENT '平均NDCG@k';
