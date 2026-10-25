package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.DocumentChunkMapper;
import com.devhub.module.ai.model.entity.DocumentChunkEntity;
import com.devhub.module.ai.service.knowledge.KnowledgeGapService;
import com.devhub.module.ai.service.knowledge.KnowledgeProductionService;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识生产管理控制器
 * <p>
 * 知识缺口列表 / 手动触发生成草稿 / 草稿审核采纳 / 知识片段检索视图
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@PreAuthorize("hasAuthority('ai:ops')")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeGapService gapService;
    private final KnowledgeProductionService productionService;
    private final DocumentChunkMapper chunkMapper;

    /**
     * 知识片段分页（知识管理页）：按关键词（内容/文档名）与文档过滤
     */
    @GetMapping("/chunks")
    public Result<Page<DocumentChunkEntity>> chunks(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String documentId) {
        LambdaQueryWrapper<DocumentChunkEntity> w = new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(notBlank(documentId), DocumentChunkEntity::getDocumentId, documentId)
                .and(notBlank(keyword), q -> q
                        .like(DocumentChunkEntity::getContent, keyword)
                        .or().like(DocumentChunkEntity::getFileName, keyword))
                .orderByDesc(DocumentChunkEntity::getCreateTime);
        return Result.success(chunkMapper.selectPage(new Page<>(page, size), w));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 知识缺口列表（status 为空返回全部）
     */
    @GetMapping("/gaps")
    public Result<List<com.devhub.module.ai.model.entity.KnowledgeGap>> gaps(
            @RequestParam(required = false) String status) {
        return Result.success(gapService.listGaps(status));
    }

    /**
     * 手动触发某缺口的知识草稿生成（同步返回结果说明）
     */
    @PostMapping("/gaps/{gapId}/generate")
    public Result<String> generate(@PathVariable Long gapId) {
        return Result.success(productionService.generateDraft(gapId));
    }

    /**
     * 草稿/冲突知识审核
     */
    @PostMapping("/{documentId}/review")
    public Result<String> review(@PathVariable String documentId, @RequestParam boolean approve) {
        return Result.success(productionService.review(documentId, approve));
    }
}
