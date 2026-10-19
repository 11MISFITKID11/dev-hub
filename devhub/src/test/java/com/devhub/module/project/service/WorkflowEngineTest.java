package com.devhub.module.project.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.entity.WorkflowTransition;
import com.devhub.module.project.mapper.WorkflowStateMapper;
import com.devhub.module.project.mapper.WorkflowTransitionMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工作流引擎流转规则单元测试
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    @Mock
    private WorkflowStateMapper stateMapper;
    @Mock
    private WorkflowTransitionMapper transitionMapper;

    private WorkflowEngine engine;

    @BeforeAll
    static void initTableMeta() {
        // 纯单测环境无 SqlSessionFactory，手动初始化实体元数据供 LambdaQueryWrapper 解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowState.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTransition.class);
    }

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngine(stateMapper, transitionMapper);
    }

    private WorkflowState state(String name, int sort, int isDefault) {
        WorkflowState s = new WorkflowState();
        s.setId((long) sort);
        s.setProjectId(1L);
        s.setName(name);
        s.setSort(sort);
        s.setIsDefaultStart(isDefault);
        return s;
    }

    private WorkflowTransition transition(String from, String to) {
        WorkflowTransition t = new WorkflowTransition();
        t.setProjectId(1L);
        t.setFromState(from);
        t.setToState(to);
        return t;
    }

    @Test
    @DisplayName("流转校验：已定义路径放行（返回 null）")
    void validateTransition_allowedPath_returnsNull() {
        when(transitionMapper.selectCount(any())).thenReturn(1L);
        assertNull(engine.validateTransition(1L, "TODO", "DOING"));
    }

    @Test
    @DisplayName("流转校验：未定义路径拒绝并给出提示")
    void validateTransition_undefinedPath_returnsError() {
        when(transitionMapper.selectCount(any())).thenReturn(0L);
        String error = engine.validateTransition(1L, "TODO", "DONE");
        assertNotNull(error);
        assertTrue(error.contains("不允许"));
    }

    @Test
    @DisplayName("前进：取 sort 严格更大的目标状态")
    void nextForwardState_picksHigherSort() {
        when(transitionMapper.selectList(any()))
                .thenReturn(List.of(transition("TODO", "DOING"), transition("TODO", "DONE")));
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state("TODO", 1, 1), state("DOING", 2, 0), state("DONE", 3, 0)));

        assertEquals("DOING", engine.nextForwardState(1L, "TODO"));
    }

    @Test
    @DisplayName("后退：取 sort 更小的最近状态，跳过更大目标")
    void nextBackState_picksLargestLowerSort() {
        when(transitionMapper.selectList(any()))
                .thenReturn(List.of(transition("DOING", "TODO"), transition("DOING", "DONE")));
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state("TODO", 1, 1), state("DOING", 2, 0), state("DONE", 3, 0)));

        assertEquals("TODO", engine.nextBackState(1L, "DOING"));
    }

    @Test
    @DisplayName("初始状态：取 isDefaultStart 标记，无定义时兜底 TODO")
    void defaultStartState_fallsBackToTodo() {
        when(stateMapper.selectOne(any())).thenReturn(state("PENDING", 1, 1));
        assertEquals("PENDING", engine.defaultStartState(1L));

        when(stateMapper.selectOne(any())).thenReturn(null);
        assertEquals("TODO", engine.defaultStartState(1L));
    }
}
