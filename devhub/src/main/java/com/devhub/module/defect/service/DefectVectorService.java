package com.devhub.module.defect.service;

import com.devhub.module.ai.config.MilvusConfig;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.defect.entity.PrjDefect;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 缺陷向量服务（方案 P2-8）：历史缺陷向量化入独立集合 defect_vectors，
 * 相似缺陷走语义检索而非关键词匹配。与知识库集合（agent_documents）物理隔离，互不污染。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefectVectorService {

    public static final String COLLECTION = "defect_vectors";

    private final MilvusServiceClient milvusClient;
    private final MilvusConfig milvusConfig;
    private final ChatModelRegistry modelRegistry;

    private volatile boolean backfilled = false;

    @PostConstruct
    public void ensure() {
        try {
            R<Boolean> has = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            if (!Boolean.TRUE.equals(has.getData())) {
                create();
            } else {
                milvusClient.loadCollection(
                        LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            }
            log.info("缺陷向量集合 {} 就绪", COLLECTION);
        } catch (Exception e) {
            log.warn("缺陷向量集合初始化失败（相似缺陷将回退关键词检索）: {}", e.getMessage());
        }
    }

    private void create() {
        io.milvus.param.collection.FieldType id = io.milvus.param.collection.FieldType.newBuilder()
                .withName("doc_id").withDataType(DataType.VarChar).withMaxLength(64)
                .withPrimaryKey(true).build();
        io.milvus.param.collection.FieldType embedding = io.milvus.param.collection.FieldType.newBuilder()
                .withName("embedding").withDataType(DataType.FloatVector)
                .withDimension(milvusConfig.getDimension()).build();
        R<RpcStatus> c = milvusClient.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).withShardsNum(1)
                .addFieldType(id).addFieldType(embedding).build());
        if (c.getStatus() != R.Status.Success.getCode()) throw new RuntimeException("创建缺陷集合失败: " + c.getMessage());
        R<RpcStatus> i = milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION).withFieldName("embedding")
                .withIndexType(IndexType.valueOf(milvusConfig.getIndexType()))
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":" + milvusConfig.getNlist() + "}").build());
        if (i.getStatus() != R.Status.Success.getCode()) throw new RuntimeException("创建缺陷索引失败: " + i.getMessage());
        milvusClient.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
    }

    private static String docIdOf(Long defectId) { return "defect-" + defectId; }

    public String textOf(PrjDefect d) {
        return "缺陷：" + d.getTitle() + "\n严重级：" + d.getSeverity()
                + "\n描述：" + (d.getDescription() == null ? "" : d.getDescription());
    }

    /** 写入/刷新单个缺陷向量（缺陷创建、更新、首次检索前调用） */
    public void upsert(PrjDefect d) {
        try {
            List<Float> vec = modelRegistry.embedToVector(textOf(d));
            milvusClient.delete(DeleteParam.newBuilder().withCollectionName(COLLECTION)
                    .withExpr("doc_id == \"" + docIdOf(d.getId()) + "\"").build());
            milvusClient.insert(InsertParam.newBuilder().withCollectionName(COLLECTION)
                    .withFields(List.of(new InsertParam.Field("doc_id", List.of(docIdOf(d.getId()))),
                            new InsertParam.Field("embedding", List.of(vec)))).build());
        } catch (Exception e) {
            log.warn("缺陷向量写入失败 #{}: {}", d.getId(), e.getMessage());
        }
    }

    /** 启动后首次检索时回填全部历史缺陷（量级小，一次性成本） */
    public void backfill(List<PrjDefect> all) {
        if (backfilled || all.isEmpty()) { backfilled = true; return; }
        try {
            for (PrjDefect d : all) upsert(d);
            log.info("历史缺陷向量回填完成：{} 条", all.size());
        } catch (Exception e) {
            log.warn("缺陷向量回填失败: {}", e.getMessage());
        } finally {
            backfilled = true;
        }
    }

    /**
     * 语义相似检索：返回 [缺陷ID, 相似度] 列表（不含自身）。
     * @return null 表示向量通道不可用（调用方回退关键词检索）
     */
    public List<Object[]> search(PrjDefect target, List<PrjDefect> all, int topK) {
        try {
            backfill(all);
            List<Float> q = modelRegistry.embedToVector(textOf(target));
            R<io.milvus.grpc.SearchResults> resp = milvusClient.search(SearchParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of("doc_id"))
                    .withTopK(topK + 1)
                    .withVectors(Collections.singletonList(q))
                    .withVectorFieldName("embedding")
                    .withParams("{\"nprobe\": 16}")
                    .build());
            if (resp.getStatus() != R.Status.Success.getCode()) return null;
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<Object[]> hits = new ArrayList<>();
            int n = wrapper.getRowRecords(0).size();
            for (int i = 0; i < n; i++) {
                String docId = String.valueOf(wrapper.getRowRecords(0).get(i).getFieldValues().getOrDefault("doc_id", ""));
                try {
                    long defectId = Long.parseLong(docId.replace("defect-", ""));
                    if (defectId != target.getId()) {
                        hits.add(new Object[]{defectId, wrapper.getIDScore(0).get(i).getScore()});
                    }
                } catch (NumberFormatException ignore) { }
            }
            return hits.size() > topK ? hits.subList(0, topK) : hits;
        } catch (Exception e) {
            log.warn("缺陷向量检索失败（回退关键词）: {}", e.getMessage());
            return null;
        }
    }
}
