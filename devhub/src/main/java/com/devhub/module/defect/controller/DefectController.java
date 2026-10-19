package com.devhub.module.defect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.Result;
import com.devhub.module.defect.entity.PrjDefect;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缺陷跟踪：CRUD + 状态（OPEN→FIXED→CLOSED）
 */
@RestController
@RequestMapping("/api/defects")
@RequiredArgsConstructor
public class DefectController {

    private final PrjDefectMapper defectMapper;
    private final com.devhub.module.project.controller.CollabController collab;
    private final com.devhub.module.task.mapper.PrjTaskMapper taskMapper;
    private final NotificationService notify;
    private final com.devhub.module.ai.etl.ETLPipeline etlPipeline;
    private final com.devhub.module.defect.service.DefectVectorService vectorService;
    private static final Set<String> VALID = Set.of("OPEN", "FIXED", "CLOSED");

    @GetMapping
    public Result<Page<PrjDefect>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "10") long size,
                                        @RequestParam(required = false) Long projectId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String severity) {
        LambdaQueryWrapper<PrjDefect> q = new LambdaQueryWrapper<PrjDefect>()
                .eq(projectId != null, PrjDefect::getProjectId, projectId)
                .eq(status != null && !status.isBlank(), PrjDefect::getStatus, status)
                .eq(severity != null && !severity.isBlank(), PrjDefect::getSeverity, severity)
                .orderByDesc(PrjDefect::getUpdateTime);
        return Result.success(defectMapper.selectPage(new Page<>(page, size), q));
    }

    @OperLog(module = "缺陷跟踪", action = "提缺陷")
    @PostMapping
    public Result<PrjDefect> create(@RequestBody PrjDefect defect) {
        if (defect.getStatus() == null || !VALID.contains(defect.getStatus())) defect.setStatus("OPEN");
        defect.setReporterUsername(currentUsername());
        defectMapper.insert(defect);
        collab.record("DEFECT", defect.getId(), "create", null, null, defect.getTitle(), defect.getReporterUsername());
        vectorService.upsert(defect); // P2-8：缺陷向量化，供语义相似检索
        return Result.success(defect);
    }

    @OperLog(module = "缺陷跟踪", action = "更新缺陷")
    @ProjectScoped(ProjectScoped.ObjType.DEFECT)
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody PrjDefect defect) {
        if (defect.getStatus() != null && !VALID.contains(defect.getStatus())) {
            return Result.fail(400, "非法缺陷状态");
        }
        defect.setId(id);
        PrjDefect before = defectMapper.selectById(id);
        if (before == null) return Result.fail(404, "缺陷不存在");
        if (defect.getStatus() != null && !defect.getStatus().equals(before.getStatus())) {
            collab.record("DEFECT", id, "status", "status", before.getStatus(), defect.getStatus(), null);
            // P0-2②：缺陷解决/关闭回写关联任务动态（跨对象联动）
            if (before.getTaskId() != null && Set.of("FIXED", "CLOSED").contains(defect.getStatus())) {
                collab.record("TASK", before.getTaskId(), "field", "defect",
                        before.getStatus() + ":" + before.getTitle(),
                        defect.getStatus() + ":" + before.getTitle(), null);
            }
            if ("CLOSED".equals(defect.getStatus()) && !"CLOSED".equals(before.getStatus())) {
                notify.push(before.getReporterUsername(), "defect", "你的缺陷已关闭",
                        before.getTitle(), "/#/defect");
                sinkDefectKnowledge(defect.getId());
            }
        }
        if (defect.getAssigneeUsername() != null && !defect.getAssigneeUsername().equals(before.getAssigneeUsername())) {
            notify.push(defect.getAssigneeUsername(), "defect", "缺陷转给你修复",
                    before.getTitle(), "/#/defect");
        }
        defectMapper.updateById(defect);
        // 更新后语义可能变化，同步刷新向量（合并旧值，因为 PUT 是部分更新）
        vectorService.upsert(defectMapper.selectById(id));
        return Result.success("ok");
    }

    @OperLog(module = "缺陷跟踪", action = "删除缺陷")
    @ProjectScoped(ProjectScoped.ObjType.DEFECT)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        defectMapper.deleteById(id);
        return Result.success("ok");
    }

    /** P2-9 缺陷关闭 → 知识沉淀：现象+根因写入 ETL，供后续 AI 问答命中 */
    private void sinkDefectKnowledge(Long defectId) {
        try {
            PrjDefect d = defectMapper.selectById(defectId);
            if (d == null) return;
            String nl = System.lineSeparator();
            String text = "缺陷知识沉淀 #" + d.getId() + nl + nl
                    + "【现象】" + d.getTitle() + nl
                    + "【描述】" + (d.getDescription() == null ? "-" : d.getDescription()) + nl
                    + "【严重级】" + d.getSeverity() + nl
                    + "【当前状态】" + d.getStatus() + nl;
            etlPipeline.processText("缺陷知识-" + d.getTitle(), text,
                    "ai_generated", "draft", null, (long) text.length());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DefectController.class)
                    .warn("缺陷知识沉淀失败（不影响关闭）: {}", e.getMessage());
        }
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ---------- P0-2 缺陷↔任务双向关联 ----------
    /** 任务关联的缺陷列表（任务详情抽屉用） */
    @GetMapping("/by-task/{taskId}")
    public Result<List<PrjDefect>> byTask(@PathVariable Long taskId) {
        return Result.success(defectMapper.selectList(new LambdaQueryWrapper<PrjDefect>()
                .eq(PrjDefect::getTaskId, taskId).orderByDesc(PrjDefect::getId)));
    }

    /** 迭代缺陷清点：新增/解决/遗留（迭代关闭时质量门禁） */
    @GetMapping("/iteration/{iterationId}/summary")
    public Result<Map<String, Object>> iterationSummary(@PathVariable Long iterationId) {
        var taskIds = taskMapper.selectList(new LambdaQueryWrapper<com.devhub.module.task.entity.PrjTask>()
                .eq(com.devhub.module.task.entity.PrjTask::getIterationId, iterationId)
                .select(com.devhub.module.task.entity.PrjTask::getId))
                .stream().map(com.devhub.module.task.entity.PrjTask::getId).toList();
        if (taskIds.isEmpty()) return Result.success(Map.of("total", 0, "open", 0, "fixed", 0, "closed", 0));
        List<PrjDefect> all = defectMapper.selectList(new LambdaQueryWrapper<PrjDefect>()
                .in(PrjDefect::getTaskId, taskIds));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("total", all.size());
        m.put("open", all.stream().filter(d -> "OPEN".equals(d.getStatus())).count());
        m.put("fixed", all.stream().filter(d -> "FIXED".equals(d.getStatus())).count());
        m.put("closed", all.stream().filter(d -> "CLOSED".equals(d.getStatus())).count());
        m.put("bySeverity", all.stream().collect(java.util.stream.Collectors.groupingBy(
                d -> d.getSeverity() == null ? "NORMAL" : d.getSeverity(), java.util.stream.Collectors.counting())));
        return Result.success(m);
    }
}
