package com.devhub.module.ai.etl;

import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.infrastructure.vectordb.MilvusService;
import com.devhub.module.ai.mapper.DocumentChunkMapper;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.dto.DocumentUploadResponse;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.ai.model.entity.DocumentChunkEntity;
import com.devhub.module.ai.service.rag.MultiRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ETL 数据管道
 * <p>
 * 文档处理完整流程：
 * <ol>
 *   <li>Extract（提取）：使用 DocumentParser 从文件中提取文本</li>
 *   <li>Transform（转换）：使用 DocumentChunker 切分文本为 Chunk</li>
 *   <li>Load（加载）：向量化后存入 Milvus</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ETLPipeline {

    private final DocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final TableTextFixer tableTextFixer;
    private final LlmChunkRefiner llmChunkRefiner;
    private final MilvusService milvusService;
    private final ChatModelRegistry modelRegistry;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final MultiRetriever multiRetriever;
    private final com.devhub.module.ai.service.knowledge.KnowledgeLifecycleService lifecycleService;

    /**
     * 执行完整的 ETL 流程
     *
     * @param file 上传的文件
     * @return 处理结果
     */
    public DocumentUploadResponse process(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("ETL 管道启动: fileName={}", fileName);

        // E: 提取文本
        String text = documentParser.parse(file);
        if (text.isBlank()) {
            throw new com.devhub.module.ai.common.BusinessException("文档内容为空，无法处理");
        }

        return processText(fileName, text, "upload", "active", null, file.getSize());
    }

    /**
     * 文本直接入库（AI 生成的知识草稿走这里，与上传共用同一条管道）
     *
     * @return 处理结果
     */
    public DocumentUploadResponse processText(String fileName, String text,
                                              String sourceType, String lifeStatus,
                                              String sourceConversationId, Long fileSize) {
        return processText(null, fileName, text, sourceType, lifeStatus, sourceConversationId, fileSize);
    }

    /** 异步入口重载：documentId 由调用方预置（外壳行已存在，此处更新而非再插入） */
    public DocumentUploadResponse processText(String presetDocumentId, String fileName, String text,
                                              String sourceType, String lifeStatus,
                                              String sourceConversationId, Long fileSize) {
        String documentId = presetDocumentId != null ? presetDocumentId
                : UUID.randomUUID().toString().replace("-", "");
        try {
            return doProcessText(documentId, fileName, text, sourceType, lifeStatus, sourceConversationId, fileSize,
                    presetDocumentId != null);
        } catch (Exception e) {
            // 幽灵行补偿：管道任一步失败（Milvus/embedding/生命周期）后清理已落的元数据，
            // 避免留下有 chunk_count 却无切片的半成品记录（历史上出现三次的已知模式）。
            // 注：跨 MySQL+Milvus 无分布式事务，采用补偿而非 @Transactional
            log.error("入库管道失败，执行补偿清理: documentId={}", documentId, e);
            try {
                milvusService.deleteByDocumentId(documentId);
                documentMapper.deleteById(documentId);
            } catch (Exception cleanupEx) {
                log.error("补偿清理失败（需人工检查幽灵行）: documentId={}", documentId, cleanupEx);
            }
            throw e;
        }
    }

    private DocumentUploadResponse doProcessText(String documentId, String fileName, String text,
                                                 String sourceType, String lifeStatus,
                                                 String sourceConversationId, Long fileSize,
                                                 boolean isPresetId) {
        log.info("知识入库: documentId={}, fileName={}, source={}", documentId, fileName, sourceType);

        // 表格启发式修复：PDF 平铺表格还原为 Markdown 表（恢复行列结构后再切片）
        text = tableTextFixer.fix(text);

        // T: 切分为 Chunk（结构感知）→ LLM 精修（仅可疑片段，成本可控）
        List<DocumentChunker.Chunk> chunks = documentChunker.chunk(text, documentId);
        if (chunks.isEmpty()) {
            throw new RuntimeException("知识切片结果为空");
        }
        chunks = llmChunkRefiner.refine(chunks, documentId);

        // Parent-Child 二级拆分：父块进 MySQL（供展开），子块进 Milvus（供检索）
        List<DocumentChunker.Chunk> children = documentChunker.splitToChildren(chunks);

        // 文档元数据落库（先落库，生命周期治理需要更新它）
        if (isPresetId) {
            // 外壳行（PROCESSING）已由异步入口预插：更新为完成态字段
            Document shell = new Document();
            shell.setId(documentId);
            shell.setChunkCount(chunks.size());
            shell.setStatus("completed");
            documentMapper.updateById(shell);
        } else {
        documentMapper.insert(Document.builder()
                .id(documentId)
                .fileName(fileName)
                .fileType(extractFileType(fileName))
                .fileSize(fileSize != null ? fileSize : (long) text.length())
                .chunkCount(chunks.size())
                .status("completed")
                .lifeStatus(lifeStatus)
                .sourceType(sourceType)
                .sourceConversationId(sourceConversationId)
                .build());
        }

        // L: 向量化并存入 Milvus（仅子块——小块匹配精准），返回向量供冲突校验
        List<List<Float>> vectors = loadToVectorDB(documentId, fileName, children, lifeStatus);

        // 切片内容同步落 MySQL：父块（chunk_type=1，供子块命中后展开）+ 子块（chunk_type=0，关键词通道检索对象）
        try {
            for (DocumentChunker.Chunk parent : chunks) {
                documentChunkMapper.insert(DocumentChunkEntity.builder()
                        .id(parent.getId())
                        .documentId(documentId)
                        .fileName(fileName)
                        .content(parent.getContent())
                        .chunkIndex(parent.getIndex())
                        .docStatus(lifeStatus)
                        .chunkType(1)
                        .build());
            }
            for (DocumentChunker.Chunk child : children) {
                documentChunkMapper.insert(DocumentChunkEntity.builder()
                        .id(child.getId())
                        .documentId(documentId)
                        .fileName(fileName)
                        .content(child.getContent())
                        .chunkIndex(child.getIndex())
                        .docStatus(lifeStatus)
                        .parentId(child.getParentId())
                        .chunkType(0)
                        .build());
            }
        } catch (Exception e) {
            log.warn("切片落库失败（关键词检索通道不可用，不影响向量检索）: {}", e.getMessage());
        }

        // 知识生命周期治理（仅上传文档：版本链/生效日期/URL提取/冲突校验）
        String lifeNotes = "";
        if ("upload".equals(sourceType) || "web".equals(sourceType)) {
            try {
                lifeNotes = lifecycleService.onDocumentIngested(documentId, fileName, text, vectors);
            } catch (Exception e) {
                log.warn("知识生命周期治理失败（不影响入库）: {}", e.getMessage());
            }
        }

        // 检索缓存失效（知识库已变更）
        multiRetriever.invalidateIndex();

        log.info("知识入库完成: documentId={}, chunks={}", documentId, chunks.size());

        return DocumentUploadResponse.builder()
                .documentId(documentId)
                .fileName(fileName)
                .chunkCount(chunks.size())
                .status("completed")
                .notes(lifeNotes)
                .build();
    }

    /**
     * 将 Chunk 向量化并存入 Milvus（真实 Embedding，向量携带文档归属信息用于溯源）
     *
     * @return 各 chunk 的向量（供冲突校验复用）
     */
    private List<List<Float>> loadToVectorDB(String documentId, String fileName,
                                             List<DocumentChunker.Chunk> chunks, String lifeStatus) {
        List<String> ids = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (DocumentChunker.Chunk chunk : chunks) {
            ids.add(chunk.getId());
            documentIds.add(documentId);
            fileNames.add(fileName);
            contents.add(chunk.getContent());
        }

        List<List<Float>> vectors = embedContents(contents);

        try {
            milvusService.insertVectors(ids, documentIds, fileNames, vectors, contents);
            log.info("成功写入 {} 条向量到 Milvus（文档: {}）", chunks.size(), fileName);
        } catch (Exception e) {
            log.error("向量写入 Milvus 失败", e);
            throw new RuntimeException("向量存储失败: " + e.getMessage(), e);
        }

        return vectors;
    }

    /**
     * 提取文件扩展名（不含点，小写）
     */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 调用向量模型批量生成文本向量
     */
    private List<List<Float>> embedContents(List<String> contents) {
        try {
            return modelRegistry.embedAllToVectors(contents);
        } catch (Exception e) {
            log.error("文本向量化失败: {}", e.getMessage());
            throw new RuntimeException("文本向量化失败: " + e.getMessage(), e);
        }
    }
}
