package com.devhub.module.ai.controller;

import com.devhub.common.OperLog;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.vectordb.MilvusService;
import com.devhub.module.ai.mapper.DocumentChunkMapper;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.dto.DocumentUploadResponse;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.ai.model.entity.DocumentChunkEntity;
import com.devhub.module.ai.service.rag.MultiRetriever;
import com.devhub.module.ai.etl.ETLPipeline;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 * <p>
 * 提供文档上传、解析、向量化及文档列表/删除的入口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@PreAuthorize("hasAuthority('ai:kb:manage')")
@RequiredArgsConstructor
public class DocumentController {

    private final ETLPipeline etlPipeline;
    private final com.devhub.module.doc.service.AsyncIngestService asyncIngestService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final MilvusService milvusService;
    private final MultiRetriever multiRetriever;
    private final com.devhub.module.ai.mapper.KnowledgeFeedbackMapper feedbackMapper;

    /** 网页一键入库：同步抓取抽取正文，异步入库与文件上传共用同一条 Stream 管道 */
    @OperLog(module = "知识库", action = "网页入库")
    @PostMapping("/url")
    public Result<com.devhub.module.ai.model.dto.DocumentUploadResponse> ingestUrl(
            @RequestBody java.util.Map<String, String> body) {
        String url = body.get("url");
        if (url == null || !url.startsWith("http")) {
            return Result.fail(400, "url 必须以 http 开头");
        }
        Long categoryId = null;
        if (body.get("categoryId") != null && !body.get("categoryId").isBlank()) {
            categoryId = Long.parseLong(body.get("categoryId"));
        }
        return Result.success(asyncIngestService.submitUrl(url, categoryId));
    }

    /**
     * 分页文档列表
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "8") long size) {
        Page<Document> result = documentMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Document>().orderByDesc(Document::getCreateTime));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("pages", result.getPages());
        data.put("current", result.getCurrent());
        data.put("size", result.getSize());
        return Result.success(data);
    }

    /**
     * 删除文档：元数据逻辑删除 + 向量物理删除
     */
    @DeleteMapping("/{documentId}")
    public Result<Void> delete(@PathVariable String documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return Result.fail(404, "文档不存在: " + documentId);
        }

        try {
            milvusService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("向量清理失败（继续删除元数据）: {}", e.getMessage());
        }
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId));
        documentMapper.deleteById(documentId);
        multiRetriever.invalidateIndex();

        log.info("文档已删除: documentId={}, fileName={}", documentId, doc.getFileName());
        return Result.success(null);
    }

    /**
     * 知识反馈（错误知识停用的数据来源）：踩率超 30% 且反馈数>=5 自动停用
     */
    @PostMapping("/{documentId}/feedback")
    public Result<Map<String, Object>> feedback(@PathVariable String documentId,
                                                @RequestParam boolean useful) {
        feedbackMapper.insert(com.devhub.module.ai.model.entity.KnowledgeFeedback.builder()
                .documentId(documentId)
                .useful(useful ? 1 : 0)
                .build());

        Map<String, Object> result = new LinkedHashMap<>();
        List<com.devhub.module.ai.model.entity.KnowledgeFeedback> all = feedbackMapper.selectList(
                new LambdaQueryWrapper<com.devhub.module.ai.model.entity.KnowledgeFeedback>()
                        .eq(com.devhub.module.ai.model.entity.KnowledgeFeedback::getDocumentId, documentId));
        long total = all.size();
        long negative = all.stream().filter(f -> f.getUseful() == 0).count();
        result.put("total", total);
        result.put("negative", negative);

        if (total >= 5 && (double) negative / total > 0.3) {
            Document doc = documentMapper.selectById(documentId);
            if (doc != null && "active".equals(doc.getLifeStatus())) {
                doc.setLifeStatus("disabled");
                documentMapper.updateById(doc);
                result.put("autoDisabled", true);
                log.warn("文档 {} 踩率 {}% 已自动停用", doc.getFileName(), negative * 100 / total);
            }
        }
        return Result.success(result);
    }

    /**
     * 上传并处理文档
     * <p>
     * 支持 PDF、DOCX、TXT、MD 等格式。
     * 上传后自动执行：解析 → 切片 → 向量化 → 存入 Milvus → 元数据落库 → 生命周期治理
     */
    @PostMapping("/upload")
    public Result<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "categoryId", required = false) Long categoryId) {
        if (file.isEmpty()) {
            return Result.fail(400, "上传文件不能为空");
        }

        String fileName = file.getOriginalFilename();
        log.info("收到文档上传请求: fileName={}, size={}KB",
                fileName, file.getSize() / 1024);

        long startTime = System.currentTimeMillis();

        try {
            // 异步入库（方案 5.5）：外壳行 PROCESSING 立即可见，ETL 事件驱动后台执行
            DocumentUploadResponse response = asyncIngestService.submit(file, categoryId);
            response.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return Result.success(response);
        } catch (Exception e) {
            log.error("文档处理失败: fileName={}", fileName, e);
            return Result.fail("文档处理失败: " + e.getMessage());
        }
    }
}
