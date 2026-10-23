package com.devhub.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjActivity;
import com.devhub.module.project.entity.PrjComment;
import com.devhub.module.project.mapper.PrjActivityMapper;
import com.devhub.module.project.mapper.PrjCommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对象协作：评论 + @提及 + 操作时间线（Linear 式 Activity 流，方案 P0-1）
 */
@RestController
@RequestMapping("/api/collab")
@RequiredArgsConstructor
public class CollabController {

    private final PrjCommentMapper commentMapper;
    private final PrjActivityMapper activityMapper;
    private final com.devhub.module.project.service.ActivityRecorder activityRecorder;
    private final com.devhub.common.NotificationService notify;
    private final com.devhub.common.ProjectScopeAspect scopeGuard;
    private final com.devhub.module.task.mapper.PrjTaskMapper taskMapper;
    private final com.devhub.module.defect.mapper.PrjDefectMapper defectMapper;

    private static final Set<String> VALID_TYPES = Set.of("TASK", "DEFECT");

    /** 对象全部动态：评论与字段变更合并按时间排序 */
    @GetMapping("/{objType}/{objId}/timeline")
    public Result<List<Map<String, Object>>> timeline(@PathVariable String objType, @PathVariable Long objId) {
        if (!VALID_TYPES.contains(objType)) return Result.fail(400, "非法对象类型");
        List<PrjComment> comments = commentMapper.selectList(new LambdaQueryWrapper<PrjComment>()
                .eq(PrjComment::getObjType, objType).eq(PrjComment::getObjId, objId)
                .orderByAsc(PrjComment::getId));
        List<PrjActivity> activities = activityMapper.selectList(new LambdaQueryWrapper<PrjActivity>()
                .eq(PrjActivity::getObjType, objType).eq(PrjActivity::getObjId, objId)
                .orderByAsc(PrjActivity::getId));

        List<Map<String, Object>> merged = new java.util.ArrayList<>();
        comments.forEach(c -> merged.add(Map.of(
                "kind", "comment", "id", c.getId(), "content", c.getContent(),
                "actor", c.getCreator(), "time", String.valueOf(c.getCreateTime()))));
        activities.forEach(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("kind", "activity"); m.put("id", a.getId());
            m.put("action", a.getAction()); m.put("actor", a.getActor());
            m.put("field", a.getFieldName() == null ? "" : a.getFieldName());
            m.put("oldValue", a.getOldValue() == null ? "" : a.getOldValue());
            m.put("newValue", a.getNewValue() == null ? "" : a.getNewValue());
            m.put("time", String.valueOf(a.getCreateTime()));
            merged.add(m);
        });
        merged.sort((x, y) -> String.valueOf(x.get("time")).compareTo(String.valueOf(y.get("time"))));
        return Result.success(merged);
    }

    @OperLog(module = "协作", action = "发表评论")
    @PostMapping("/{objType}/{objId}/comments")
    public Result<PrjComment> comment(@PathVariable String objType, @PathVariable Long objId,
                                      @RequestBody Map<String, String> body) {
        if (!VALID_TYPES.contains(objType)) return Result.fail(400, "非法对象类型");
        String content = body.getOrDefault("content", "").trim();
        if (content.isEmpty()) return Result.fail(400, "评论内容不能为空");
        // 对象级权限：objType 为运行时字符串无法挂注解，此处手工校验项目成员身份（方案 P1-6）
        Long projectId = resolveProjectId(objType, objId);
        if (projectId != null) {
            String err = scopeGuard.checkMemberWrite(currentUser(), projectId, false);
            if (err != null) return Result.fail(403, err);
        }
        PrjComment c = new PrjComment();
        c.setObjType(objType); c.setObjId(objId); c.setContent(content);
        c.setCreator(currentUser());
        commentMapper.insert(c);

        PrjActivity act = new PrjActivity();
        act.setObjType(objType); act.setObjId(objId); act.setAction("comment");
        act.setActor(currentUser());
        activityMapper.insert(act);
        // @提及 → 通知被提及人
        for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("@([a-zA-Z0-9_\\u4e00-\\u9fa5]+)").matcher(content); m.find(); ) {
            String mentioned = m.group(1);
            if (!mentioned.equals(currentUser())) {
                notify.push(mentioned, "mention", currentUser() + " 在"
                        + ("TASK".equals(objType) ? "任务" : "缺陷") + "评论中提及了你",
                        content.substring(0, Math.min(60, content.length())), "/#/task");
            }
        }
        return Result.success(c);
    }

    /** 供任务/缺陷控制器记录字段变更动态（实现委托 ActivityRecorder，保留签名兼容既有调用方） */
    public void record(String objType, Long objId, String action,
                       String field, Object oldV, Object newV, String actor) {
        activityRecorder.record(objType, objId, action, field, oldV, newV, actor);
    }

    private Long resolveProjectId(String objType, Long objId) {
        if ("TASK".equals(objType)) {
            var t = taskMapper.selectById(objId);
            return t == null ? null : t.getProjectId();
        }
        var d = defectMapper.selectById(objId);
        return d == null ? null : d.getProjectId();
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "system";
    }
}
