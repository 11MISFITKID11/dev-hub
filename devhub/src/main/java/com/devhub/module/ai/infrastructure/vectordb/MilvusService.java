package com.devhub.module.ai.infrastructure.vectordb;

import com.devhub.module.ai.config.MilvusConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量数据库服务
 * <p>
 * 封装向量的插入、搜索、删除等操作。
 * 集合 schema：doc_id(chunk主键)、document_id(所属文档)、file_name(来源文件名)、embedding(向量)、content(原文)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusClient;
    private final MilvusConfig milvusConfig;

    @PostConstruct
    public void init() {
        ensureCollectionLoaded();
    }

    /**
     * 插入向量数据（带文档归属信息）
     *
     * @param ids         chunk ID 列表
     * @param documentIds 所属文档 ID 列表
     * @param fileNames   来源文件名列表
     * @param vectors     向量数据列表
     * @param contents    原始文本列表
     * @return 插入数量
     */
    public long insertVectors(List<String> ids, List<String> documentIds, List<String> fileNames,
                              List<List<Float>> vectors, List<String> contents) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("doc_id", ids));
        fields.add(new InsertParam.Field("document_id", documentIds));
        fields.add(new InsertParam.Field("file_name", fileNames));
        fields.add(new InsertParam.Field("embedding", vectors));
        fields.add(new InsertParam.Field("content", contents));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.insert(insertParam);

        if (response.getStatus() != R.Status.Success.getCode()) {
            log.error("Milvus 向量插入失败: {}", response.getMessage());
            throw new RuntimeException("向量插入失败: " + response.getMessage());
        }

        long count = response.getData().getInsertCnt();
        log.info("成功插入 {} 条向量到集合 {}", count, milvusConfig.getCollectionName());
        return count;
    }

    /**
     * 按 chunk ID 删除向量（如长期记忆等无文档归属的数据）
     */
    public long insertVectors(List<String> ids, List<List<Float>> vectors, List<String> contents) {
        return insertVectors(ids, Collections.nCopies(ids.size(), ""),
                Collections.nCopies(ids.size(), ""), vectors, contents);
    }

    /**
     * 按文档 ID 删除该文档的所有向量
     *
     * @return 删除的数量
     */
    public long deleteByDocumentId(String documentId) {
        R<MutationResult> response = milvusClient.delete(
                DeleteParam.newBuilder()
                        .withCollectionName(milvusConfig.getCollectionName())
                        .withExpr("document_id == \"" + documentId + "\"")
                        .build()
        );
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("按文档删除向量失败: " + response.getMessage());
        }
        long count = response.getData().getDeleteCnt();
        log.info("文档 {} 的向量已删除 {} 条", documentId, count);
        return count;
    }

    /**
     * 向量相似度搜索
     *
     * @param queryVector 查询向量
     * @param topK        返回结果数量
     * @return 搜索结果
     */
    public SearchResults searchSimilar(List<Float> queryVector, int topK) {
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("doc_id", "document_id", "file_name", "content"))
                .withTopK(topK)
                .withVectors(Collections.singletonList(queryVector))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\": 16}")
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);

        if (response.getStatus() != R.Status.Success.getCode()) {
            log.error("Milvus 向量搜索失败: {}", response.getMessage());
            throw new RuntimeException("向量搜索失败: " + response.getMessage());
        }

        log.debug("向量搜索完成，返回 {} 条结果", topK);
        return response.getData();
    }

    /**
     * 检索最相似 chunk 的文本内容（冲突校验等轻量场景用）
     */
    public List<String> searchSimilarTexts(List<Float> queryVector, int topK) {
        try {
            io.milvus.response.SearchResultsWrapper wrapper =
                    new io.milvus.response.SearchResultsWrapper(searchSimilar(queryVector, topK).getResults());
            List<String> texts = new ArrayList<>();
            wrapper.getRowRecords(0).forEach(row -> {
                Object content = row.getFieldValues().get("content");
                if (content != null) {
                    texts.add(content.toString());
                }
            });
            return texts;
        } catch (Exception e) {
            log.warn("相似文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 确保集合存在且 schema 与配置一致（不存在则创建，维度不一致则重建）
     */
    private void ensureCollectionLoaded() {
        try {
            String collectionName = milvusConfig.getCollectionName();

            R<Boolean> hasCollection = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );

            if (Boolean.TRUE.equals(hasCollection.getData())) {
                Integer existingDim = describeEmbeddingDimension(collectionName);
                if (existingDim != null && existingDim != milvusConfig.getDimension()) {
                    log.warn("集合 {} 的向量维度({})与配置({})不一致，删除重建（原向量数据将丢失）",
                            collectionName, existingDim, milvusConfig.getDimension());
                    milvusClient.dropCollection(DropCollectionParam.newBuilder()
                            .withCollectionName(collectionName).build());
                } else {
                    milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName).build());
                    log.info("Milvus 集合 {} 已加载到内存", collectionName);
                    return;
                }
            } else {
                log.info("Milvus 集合 {} 不存在，自动创建", collectionName);
            }

            createCollection(collectionName);
        } catch (Exception e) {
            log.warn("Milvus 初始化检查失败（服务可能未启动）: {}", e.getMessage());
        }
    }

    /**
     * 查询集合中 embedding 字段的实际维度
     */
    private Integer describeEmbeddingDimension(String collectionName) {
        try {
            R<DescribeCollectionResponse> resp = milvusClient.describeCollection(
                    DescribeCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );
            if (resp.getStatus() != R.Status.Success.getCode()) {
                return null;
            }
            for (io.milvus.grpc.FieldSchema field : resp.getData().getSchema().getFieldsList()) {
                if ("embedding".equals(field.getName()) && field.getDataType() == DataType.FloatVector) {
                    return field.getTypeParamsList().stream()
                            .filter(kv -> "dim".equals(kv.getKey()))
                            .map(kv -> Integer.parseInt(kv.getValue()))
                            .findFirst().orElse(null);
                }
            }
        } catch (Exception e) {
            log.warn("读取集合维度失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 创建集合并建立向量索引
     */
    private void createCollection(String collectionName) {
        FieldType docIdField = FieldType.newBuilder()
                .withName("doc_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(128)
                .withPrimaryKey(true)
                .build();
        FieldType documentIdField = FieldType.newBuilder()
                .withName("document_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(128)
                .build();
        FieldType fileNameField = FieldType.newBuilder()
                .withName("file_name")
                .withDataType(DataType.VarChar)
                .withMaxLength(512)
                .build();
        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(milvusConfig.getDimension())
                .build();
        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();

        R<RpcStatus> createResp = milvusClient.createCollection(
                CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withShardsNum(1)
                        .addFieldType(docIdField)
                        .addFieldType(documentIdField)
                        .addFieldType(fileNameField)
                        .addFieldType(embeddingField)
                        .addFieldType(contentField)
                        .build()
        );
        checkStatus(createResp, "创建集合");

        R<RpcStatus> indexResp = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("embedding")
                        .withIndexType(IndexType.valueOf(milvusConfig.getIndexType()))
                        .withMetricType(MetricType.valueOf(milvusConfig.getMetricType()))
                        .withExtraParam("{\"nlist\":" + milvusConfig.getNlist() + "}")
                        .build()
        );
        checkStatus(indexResp, "创建索引");

        milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        log.info("Milvus 集合 {} 创建完成（dim={}, index={}）并已加载",
                collectionName, milvusConfig.getDimension(), milvusConfig.getIndexType());
    }

    private void checkStatus(R<RpcStatus> response, String action) {
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException(action + "失败: " + response.getMessage());
        }
    }
}
