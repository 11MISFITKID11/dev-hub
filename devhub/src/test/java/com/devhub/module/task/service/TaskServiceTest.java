package com.devhub.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.project.entity.PrjTaskDep;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.mapper.PrjTaskDepMapper;
import com.devhub.module.project.service.ActivityRecorder;
import com.devhub.module.project.service.AutomationService;
import com.devhub.module.project.service.RequirementStatusService;
import com.devhub.module.project.service.WorkflowEngine;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 任务服务单元测试：事务性写路径的业务规则 + 并发冲突（乐观锁/CAS）
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private PrjTaskMapper taskMapper;
    @Mock
    private PrjTaskDepMapper depMapper;
    @Mock
    private WorkflowEngine workflowEngine;
    @Mock
    private AutomationService automation;
    @Mock
    private NotificationService notify;
    @Mock
    private ActivityRecorder activityRecorder;
    @Mock
    private RequirementStatusService requirementStatusService;

    private TaskService service;

    @BeforeAll
    static void initTableMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PrjTask.class);
        TableInfoHelper.initTableInfo(assistant, PrjTaskDep.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskService(taskMapper, depMapper, workflowEngine, automation,
                notify, activityRecorder, requirementStatusService);
    }

    private PrjTask task(long id, String status, Integer version) {
        PrjTask t = new PrjTask();
        t.setId(id);
        t.setProjectId(1L);
        t.setTitle("任务" + id);
        t.setStatus(status);
        t.setVersion(version);
        return t;
    }

    private WorkflowState state(String name, int sort, Integer wipLimit, String category) {
        WorkflowState s = new WorkflowState();
        s.setProjectId(1L);
        s.setName(name);
        s.setSort(sort);
        s.setWipLimit(wipLimit);
        s.setCategory(category);
        return s;
    }

    @Test
    @DisplayName("advance 并发冲突：CAS 更新 0 行 → 409")
    void advance_casConflict_throws409() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "TODO", 3));
        when(workflowEngine.nextForwardState(1L, "TODO")).thenReturn("DOING");
        when(workflowEngine.statesOf(1L)).thenReturn(List.of(
                state("TODO", 1, null, null), state("DOING", 2, null, null)));
        when(taskMapper.update(any(PrjTask.class), any(Wrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.advance(1L, true));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人变更"));
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("advance 成功：CAS 命中 → 记动态 + 需求联动")
    void advance_success_recordsAndSyncs() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "TODO", 3));
        when(workflowEngine.nextForwardState(1L, "TODO")).thenReturn("DOING");
        when(workflowEngine.statesOf(1L)).thenReturn(List.of(
                state("TODO", 1, null, null), state("DOING", 2, null, null)));
        when(taskMapper.update(any(PrjTask.class), any(Wrapper.class))).thenReturn(1);
        // 首次读主任务，CAS 成功后重载 fresh（新版本实体）
        when(taskMapper.selectById(1L)).thenReturn(task(1, "TODO", 3), task(1, "DOING", 4));
        when(automation.onStatusChanged(any(), eq("DOING"))).thenReturn(false);

        String next = service.advance(1L, true);

        assertEquals("DOING", next);
        verify(activityRecorder).record(eq("TASK"), eq(1L), eq("status"), eq("status"),
                eq("TODO"), eq("DOING"), isNull());
        verify(requirementStatusService).syncStoryStatus(null);
    }

    @Test
    @DisplayName("advance WIP 拦截：目标状态超上限 → 400")
    void advance_wipLimitBlocked() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "TODO", 1));
        when(workflowEngine.nextForwardState(1L, "TODO")).thenReturn("DOING");
        when(workflowEngine.statesOf(1L)).thenReturn(List.of(
                state("TODO", 1, null, null), state("DOING", 2, 1, null)));
        when(taskMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.advance(1L, true));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("WIP"));
    }

    @Test
    @DisplayName("advance 依赖拦截：前置任务未到终态 → 400")
    void advance_depBlocked() {
        PrjTask main = task(1, "DOING", 2);
        when(taskMapper.selectById(1L)).thenReturn(main);
        when(workflowEngine.nextForwardState(1L, "DOING")).thenReturn("DONE");
        when(workflowEngine.statesOf(1L)).thenReturn(List.of(
                state("DOING", 2, null, null), state("DONE", 3, null, "DONE")));
        PrjTaskDep dep = new PrjTaskDep();
        dep.setTaskId(1L);
        dep.setDependsOn(9L);
        when(depMapper.selectList(any())).thenReturn(List.of(dep));
        when(taskMapper.selectById(9L)).thenReturn(task(9, "DOING", 0));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.advance(1L, true));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("前置任务未完成"));
    }

    @Test
    @DisplayName("update 版本冲突：乐观锁更新 0 行 → 409")
    void update_versionConflict_throws409() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "TODO", 5));
        when(taskMapper.updateById(any(PrjTask.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(1L, task(1, null, null)));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("update 任务不存在 → 404")
    void update_notFound_throws404() {
        when(taskMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(1L, new PrjTask()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("create 非法状态 → 400")
    void create_invalidStatus_throws400() {
        when(workflowEngine.validateState(1L, "FLYING")).thenReturn("非法状态「FLYING」");
        PrjTask t = task(0, "FLYING", null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(t, "admin"));
        assertEquals(400, ex.getCode());
        verify(taskMapper, never()).insert(any(PrjTask.class));
    }
}
