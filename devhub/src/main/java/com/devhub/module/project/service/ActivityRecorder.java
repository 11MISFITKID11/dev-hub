package com.devhub.module.project.service;

import com.devhub.module.project.entity.PrjActivity;
import com.devhub.module.project.mapper.PrjActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 对象动态记录器：从 CollabController 抽出的共享组件，
 * 供任务/缺陷/项目等多处记录字段变更（此前 Controller 之间互相调用）
 */
@Component
@RequiredArgsConstructor
public class ActivityRecorder {

    private final PrjActivityMapper activityMapper;

    /** 记录对象动态（含状态流转时间戳，效能报表取数源）；actor 为空取当前登录用户 */
    public void record(String objType, Long objId, String action,
                       String field, Object oldV, Object newV, String actor) {
        PrjActivity a = new PrjActivity();
        a.setObjType(objType); a.setObjId(objId); a.setAction(action);
        a.setFieldName(field);
        a.setOldValue(oldV == null ? null : String.valueOf(oldV));
        a.setNewValue(newV == null ? null : String.valueOf(newV));
        a.setActor(actor == null ? currentUsername() : actor);
        activityMapper.insert(a);
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "system";
    }
}
