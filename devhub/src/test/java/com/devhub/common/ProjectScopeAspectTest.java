package com.devhub.common;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import com.devhub.module.project.entity.PrjMember;
import com.devhub.module.project.mapper.PrjIterationMapper;
import com.devhub.module.project.mapper.PrjMemberMapper;
import com.devhub.module.project.mapper.PrjRequirementMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 权限切面缓存单元测试：命中免查库、非成员占位、未命中回填
 */
@ExtendWith(MockitoExtension.class)
class ProjectScopeAspectTest {

    private static final String NOT_MEMBER = "__NOT_MEMBER__";

    @Mock
    private PrjTaskMapper taskMapper;
    @Mock
    private PrjDefectMapper defectMapper;
    @Mock
    private PrjMemberMapper memberMapper;
    @Mock
    private PrjIterationMapper iterationMapper;
    @Mock
    private PrjRequirementMapper requirementMapper;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private RedisCacheService cacheService;

    private ProjectScopeAspect aspect;

    @BeforeAll
    static void initTableMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PrjTask.class);
        TableInfoHelper.initTableInfo(assistant, PrjMember.class);
    }

    @BeforeEach
    void setUp() {
        aspect = new ProjectScopeAspect(taskMapper, defectMapper, memberMapper,
                iterationMapper, requirementMapper, jdbc, cacheService);
    }

    @Test
    @DisplayName("管理员身份缓存命中：零 DB 查询直接放行")
    void adminCacheHit_noDbCall() {
        when(cacheService.get("perm:isadmin:boss", Boolean.class)).thenReturn(Optional.of(true));

        assertNull(aspect.checkMemberWrite("boss", 1L, true));
        verifyNoInteractions(jdbc, memberMapper);
    }

    @Test
    @DisplayName("成员角色缓存命中 OWNER：ownerOnly 校验通过")
    void memberRoleCacheHit_ownerPasses() {
        when(cacheService.get("perm:isadmin:dev1", Boolean.class)).thenReturn(Optional.of(false));
        when(cacheService.get("perm:member:1:dev1", String.class)).thenReturn(Optional.of("OWNER"));

        assertNull(aspect.checkMemberWrite("dev1", 1L, true));
        verifyNoInteractions(jdbc, memberMapper);
    }

    @Test
    @DisplayName("非成员占位缓存：直接拒绝，不查库")
    void notMemberPlaceholder_rejected() {
        when(cacheService.get("perm:isadmin:dev2", Boolean.class)).thenReturn(Optional.of(false));
        when(cacheService.get("perm:member:1:dev2", String.class)).thenReturn(Optional.of(NOT_MEMBER));

        String err = aspect.checkMemberWrite("dev2", 1L, false);
        assertNotNull(err);
        assertTrue(err.contains("非项目成员"));
        verifyNoInteractions(memberMapper);
    }

    @Test
    @DisplayName("缓存未命中：精确查库 + 回填缓存（取代原全量拉成员内存过滤）")
    void cacheMiss_queriesSingleMemberAndBackfills() {
        when(cacheService.get(anyString(), eq(String.class))).thenReturn(Optional.empty());
        when(cacheService.get("perm:isadmin:dev3", Boolean.class)).thenReturn(Optional.of(false));
        PrjMember m = new PrjMember();
        m.setProjectId(1L);
        m.setUsername("dev3");
        m.setRoleKey("DEV");
        when(memberMapper.selectOne(any())).thenReturn(m);

        String err = aspect.checkMemberWrite("dev3", 1L, true);

        assertNotNull(err);
        assertTrue(err.contains("仅项目负责人"));
        verify(memberMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(cacheService).set(eq("perm:member:1:dev3"), eq("DEV"), any(Duration.class));
    }
}
