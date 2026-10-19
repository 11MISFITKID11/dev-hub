package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
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
 * 知识缺口列表 / 手动触发生成草稿 / 草稿审核采纳
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@PreAuthorize("hasAuthority('ai:ops')")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeGapService gapService;
    private final KnowledgeProductionService productionService;

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
