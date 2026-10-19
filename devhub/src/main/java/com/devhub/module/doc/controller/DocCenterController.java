package com.devhub.module.doc.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.doc.entity.DocCategory;
import com.devhub.module.doc.mapper.DocCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 文档中心：分类管理 + 文档列表（解析状态/切片数/审核发布）
 * <p>
 * 上传与删除复用 AI 模块 /api/v1/documents 接口（级联清理向量）；
 * 本控制器补宿主侧的治理视图：分类、审核状态、归属分类调整。
 */
@RestController
@RequestMapping("/api/doc")
@RequiredArgsConstructor
public class DocCenterController {

    private final DocCategoryMapper categoryMapper;
    private final DocumentMapper documentMapper;
    private final com.devhub.module.ai.mapper.DocumentChunkMapper chunkMapper;
    private final com.devhub.module.ai.infrastructure.vectordb.MilvusService milvusService;
    private final com.devhub.module.ai.infrastructure.llm.ChatModelRegistry modelRegistry;
    private final com.devhub.common.NotificationService notify;

    // ---------- 分类 ----------
    @GetMapping("/categories")
    public Result<List<DocCategory>> categories() {
        return Result.success(categoryMapper.selectList(
                new LambdaQueryWrapper<DocCategory>().orderByAsc(DocCategory::getSort)));
    }

    @OperLog(module = "文档中心", action = "新建分类")
    @PreAuthorize("hasAuthority('ai:kb:manage')")
    @PostMapping("/categories")
    public Result<DocCategory> createCategory(@RequestBody DocCategory cat) {
        categoryMapper.insert(cat);
        return Result.success(cat);
    }

    @OperLog(module = "文档中心", action = "删除分类")
    @PreAuthorize("hasAuthority('ai:kb:manage')")
    @DeleteMapping("/categories/{id}")
    public Result<String> deleteCategory(@PathVariable Long id) {
        Long used = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getCategoryId, id));
        if (used != null && used > 0) {
            return Result.fail(400, "分类下仍有 " + used + " 份文档，先移出再删除");
        }
        categoryMapper.deleteById(id);
        return Result.success("ok");
    }

    // ---------- 文档治理视图 ----------
    @GetMapping("/documents")
    public Result<Page<Document>> documents(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String auditStatus) {
        LambdaQueryWrapper<Document> q = new LambdaQueryWrapper<Document>()
                .eq(categoryId != null, Document::getCategoryId, categoryId)
                .eq(auditStatus != null && !auditStatus.isBlank(), Document::getAuditStatus, auditStatus)
                .orderByDesc(Document::getCreateTime);
        return Result.success(documentMapper.selectPage(new Page<>(page, size), q));
    }

    /** 审核发布/驳回（治理位，检索可见性仍由 AI 侧 life_status 管理）；结果站内通知上传人（断点2补齐） */
    @OperLog(module = "文档中心", action = "审核文档")
    @PreAuthorize("hasAuthority('ai:kb:manage')")
    @PostMapping("/documents/{id}/audit")
    public Result<String> audit(@PathVariable String id, @RequestParam boolean pass) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) return Result.fail(404, "文档不存在");
        doc.setAuditStatus(pass ? "PUBLISHED" : "REJECTED");
        documentMapper.updateById(doc);
        notify.push(doc.getUploader(), "doc_audit",
                pass ? "文档已发布" : "文档已驳回",
                doc.getFileName(), "/#/doc");
        return Result.success(pass ? "已发布" : "已驳回");
    }

    /** 重建索引（借鉴 MaxKB 运维操作）：删旧向量→切片重嵌入→重写 Milvus */
    @OperLog(module = "文档中心", action = "重建索引")
    @PreAuthorize("hasAuthority('ai:kb:manage')")
    @PostMapping("/documents/{id}/reindex")
    public Result<String> reindex(@PathVariable String id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) return Result.fail(404, "文档不存在");
        var chunks = chunkMapper.selectList(new LambdaQueryWrapper<com.devhub.module.ai.model.entity.DocumentChunkEntity>()
                .eq(com.devhub.module.ai.model.entity.DocumentChunkEntity::getDocumentId, id)
                .eq(com.devhub.module.ai.model.entity.DocumentChunkEntity::getChunkType, 0)
                .eq(com.devhub.module.ai.model.entity.DocumentChunkEntity::getDeleted, 0));
        if (chunks.isEmpty()) return Result.fail(400, "该文档无切片（解析失败的请重新上传）");
        milvusService.deleteByDocumentId(id);
        java.util.List<String> ids = new java.util.ArrayList<>(), contents = new java.util.ArrayList<>();
        java.util.List<String> docIds = new java.util.ArrayList<>(), names = new java.util.ArrayList<>();
        for (var c : chunks) { ids.add(c.getId()); contents.add(c.getContent()); docIds.add(id); names.add(doc.getFileName()); }
        java.util.List<java.util.List<Float>> vecs = modelRegistry.embedAllToVectors(contents);
        milvusService.insertVectors(ids, docIds, names, vecs, contents);
        doc.setStatus("completed"); documentMapper.updateById(doc);
        return Result.success("已重建 " + chunks.size() + " 条向量");
    }

    @OperLog(module = "文档中心", action = "调整分类")
    @PreAuthorize("hasAuthority('ai:kb:manage')")
    @PutMapping("/documents/{id}/category")
    public Result<String> moveCategory(@PathVariable String id, @RequestParam Long categoryId) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) return Result.fail(404, "文档不存在");
        doc.setCategoryId(categoryId);
        documentMapper.updateById(doc);
        return Result.success("ok");
    }
}
