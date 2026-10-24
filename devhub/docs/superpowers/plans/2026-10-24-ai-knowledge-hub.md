# 企业智能知识中枢一期 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** AI 应用配置 + 开放问答 API + iframe 嵌入挂件，将 DevHub 转型为企业知识中枢（规格：`devhub/docs/superpowers/specs/2026-10-24-ai-knowledge-hub-design.md`）。

**架构：** 新增开放链路 `/api/open/**`（双凭据：apiKey 走 Filter 鉴权、appKey 走 Controller 校验），复用 AgentOrchestrator RAG 链路；检索域经 MultiRetriever 包内 ThreadLocal 覆盖，语义缓存域用 `app:{appKey}` 隔离；ai_usage 以 `app:{appKey}` 记账复用报表。

**技术栈：** Spring Boot 3.5 / MyBatis-Plus / Flyway / Redis / Vue3 + Element Plus。

**构建命令：** `cd D:\kaifa\javaproject\project-devhub\devhub && JAVA_HOME="D:\java21" "D:\springboot\Maven\apache-maven-3.9.11\bin\mvn.cmd" test`（下文简写 `mvn test`）

---

## 文件结构

| 文件 | 职责 |
|---|---|
| 创建 `src/main/resources/db/migration/V16__ai_app_open_api.sql` | ai_app/api_key 表 + 菜单重排 |
| 创建 `src/main/java/com/devhub/module/ai/model/entity/AiApp.java` | 应用实体 |
| 创建 `src/main/java/com/devhub/module/ai/model/entity/ApiKey.java` | 密钥实体 |
| 创建 `src/main/java/com/devhub/module/ai/mapper/AiAppMapper.java` `ApiKeyMapper.java` | Mapper |
| 创建 `src/main/java/com/devhub/module/ai/infrastructure/openapi/OpenApiAuthService.java` | key/app 校验（缓存）、限流、Referer 白名单 |
| 创建 `src/main/java/com/devhub/module/ai/infrastructure/openapi/ApiKeyAuthFilter.java` | `/api/open/v1/chat` 的 X-API-Key 鉴权 |
| 修改 `src/main/java/com/devhub/security/SecurityConfig.java` | permitAll 加 `/api/open/**` |
| 修改 `src/main/java/com/devhub/module/ai/service/rag/MultiRetriever.java` | 增加 ScopeOverrideHolder ThreadLocal |
| 修改 `src/main/java/com/devhub/module/ai/model/dto/ChatRequest.java` | 加 persona/cacheScope 字段 |
| 修改 `src/main/java/com/devhub/module/ai/service/agent/AgentOrchestrator.java` | persona 段、cacheScope、recordAiUsage 重载 |
| 创建 `src/main/java/com/devhub/module/ai/controller/openapi/OpenApiController.java` | 开放问答 + 挂件流式 |
| 创建 `src/main/java/com/devhub/module/ai/controller/AiAppController.java` | 应用/密钥管理（JWT） |
| 创建测试 `OpenApiAuthServiceTest.java` `OpenApiControllerTest.java` | 见任务 2/5 |
| 创建 `devhub-web/src/views/ai/Apps.vue` | 应用管理页（借鉴 Dify 分区卡片） |
| 创建 `devhub-web/src/views/ai/EmbedWidget.vue` | 挂件页（借鉴 MaxKB 极简聊天卡） |
| 修改 `devhub-web/src/router/index.js` | 两个新路由 |

计划自检注：规格第 7 节说"从 Chat.vue 抽 useChat composable 复用"，本计划改为**新建 `useSseChat` composable 仅挂件使用、Chat.vue 不动**——Chat.vue 与会话列表强耦合，重构无本期收益且有回归风险（YAGNI）。

---

### 任务 1：V16 迁移 + 实体 + Mapper

**文件：** 上表前 5 行。

- [ ] **步骤 1：编写 V16 迁移 SQL**

```sql
-- 文件：src/main/resources/db/migration/V16__ai_app_open_api.sql
CREATE TABLE ai_app (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_key VARCHAR(32) NOT NULL COMMENT '公开凭据，挂件用',
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  system_prompt TEXT COMMENT '应用人设',
  opening_greeting VARCHAR(255) COMMENT '挂件开场白',
  dept_id BIGINT NULL COMMENT '检索数据域，NULL=全公司',
  rate_limit_per_min INT NOT NULL DEFAULT 20,
  allowed_origins VARCHAR(512) NULL COMMENT '挂件 Referer 白名单，逗号分隔，空=不限',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_by VARCHAR(64),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_key (app_key),
  KEY idx_dept (dept_id)
);

CREATE TABLE api_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id BIGINT NOT NULL,
  key_hash CHAR(64) NOT NULL COMMENT 'SHA-256，明文仅创建时返回一次',
  key_prefix VARCHAR(8) NOT NULL,
  name VARCHAR(64),
  enabled TINYINT NOT NULL DEFAULT 1,
  expires_at DATETIME NULL,
  last_used_at DATETIME NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_key_hash (key_hash),
  KEY idx_app (app_id)
);

-- 导航重排：知识中枢(原智能问答)置顶，研发协作收组
UPDATE sys_menu SET name='知识中枢', sort=1 WHERE id=6;
UPDATE sys_menu SET sort=2 WHERE id=5;
UPDATE sys_menu SET sort=3 WHERE id=1;
UPDATE sys_menu SET sort=5 WHERE id=10;
UPDATE sys_menu SET sort=6 WHERE id=11;
INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (21, 6, 'AI 应用', '/ai/apps', 'ai:apps', 64, 'C'),
  (22, 0, '研发协作', NULL, NULL, 4, 'M');
UPDATE sys_menu SET parent_id=22, sort=1 WHERE id=2;
UPDATE sys_menu SET parent_id=22, sort=2 WHERE id=3;
UPDATE sys_menu SET parent_id=22, sort=3 WHERE id=4;
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu WHERE id IN (21,22);
INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT DISTINCT rm.role_id, 22 FROM sys_role_menu rm
   WHERE rm.menu_id IN (2,3,4) AND rm.role_id <> 1
     AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id=rm.role_id AND x.menu_id=22);
```

- [ ] **步骤 2：实体**（两张，模式照抄 PrjTask：@Data + @TableName + @TableId(AUTO)）

```java
// src/main/java/com/devhub/module/ai/model/entity/AiApp.java
package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_app")
public class AiApp {
    @TableId(type = IdType.AUTO)
    Long id;
    String appKey;
    String name;
    String description;
    String systemPrompt;
    String openingGreeting;
    Long deptId;
    Integer rateLimitPerMin;
    String allowedOrigins;
    Integer enabled;
    String createdBy;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
```

```java
// src/main/java/com/devhub/module/ai/model/entity/ApiKey.java
package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    Long id;
    Long appId;
    String keyHash;
    String keyPrefix;
    String name;
    Integer enabled;
    LocalDateTime expiresAt;
    LocalDateTime lastUsedAt;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
```

- [ ] **步骤 3：Mapper**（两个空接口 extends BaseMapper，放 `com.devhub.module.ai.mapper`）

```java
package com.devhub.module.ai.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ai.model.entity.AiApp;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface AiAppMapper extends BaseMapper<AiApp> {}

package com.devhub.module.ai.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ai.model.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {}
```

- [ ] **步骤 4：编译验证**：`mvn -q compile`，预期无 ERROR。
- [ ] **步骤 5：Commit** `git add -A && git commit -m "知识中枢一期：V16迁移(ai_app/api_key/菜单重排)+实体与Mapper"`

### 任务 2：OpenApiAuthService（TDD）

**文件：** 创建 `infrastructure/openapi/OpenApiAuthService.java`；测试 `src/test/java/com/devhub/module/ai/infrastructure/openapi/OpenApiAuthServiceTest.java`

- [ ] **步骤 1：编写失败测试**

```java
package com.devhub.module.ai.infrastructure.openapi;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.mapper.AiAppMapper;
import com.devhub.module.ai.mapper.ApiKeyMapper;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.entity.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenApiAuthServiceTest {

    @Mock AiAppMapper appMapper;
    @Mock ApiKeyMapper keyMapper;
    @Mock RedisCacheService cacheService;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private OpenApiAuthService service;

    @BeforeEach
    void setUp() { service = new OpenApiAuthService(appMapper, keyMapper, cacheService, redis); }

    private AiApp app() {
        AiApp a = new AiApp();
        a.setId(1L); a.setAppKey("app-abc123"); a.setEnabled(1);
        a.setRateLimitPerMin(2); a.setAllowedOrigins(null);
        return a;
    }

    @Test
    @DisplayName("apiKey 校验：命中缓存直接返回应用，零 DB")
    void authenticateKey_cacheHit() {
        when(cacheService.get(startsWith("open:key:"), eq(AiApp.class))).thenReturn(Optional.of(app()));
        Optional<AiApp> r = service.authenticateKey("dh-raw");
        assertTrue(r.isPresent());
        verifyNoInteractions(keyMapper, appMapper);
    }

    @Test
    @DisplayName("apiKey 校验：无缓存查库，key禁用返回空且不缓存应用")
    void authenticateKey_disabledKey() {
        when(cacheService.get(anyString(), eq(AiApp.class))).thenReturn(Optional.empty());
        ApiKey k = new ApiKey();
        k.setAppId(1L); k.setEnabled(0);
        when(keyMapper.selectOne(any())).thenReturn(k);
        assertTrue(service.authenticateKey("dh-raw").isEmpty());
    }

    @Test
    @DisplayName("apiKey 校验：过期返回空")
    void authenticateKey_expired() {
        when(cacheService.get(anyString(), eq(AiApp.class))).thenReturn(Optional.empty());
        ApiKey k = new ApiKey();
        k.setAppId(1L); k.setEnabled(1); k.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(keyMapper.selectOne(any())).thenReturn(k);
        assertTrue(service.authenticateKey("dh-raw").isEmpty());
    }

    @Test
    @DisplayName("apiKey 校验：合法且应用启用 → 返回应用并回填缓存")
    void authenticateKey_ok_backfillsCache() {
        when(cacheService.get(anyString(), eq(AiApp.class))).thenReturn(Optional.empty());
        ApiKey k = new ApiKey();
        k.setId(9L); k.setAppId(1L); k.setEnabled(1); k.setExpiresAt(LocalDateTime.now().plusDays(30));
        when(keyMapper.selectOne(any())).thenReturn(k);
        when(appMapper.selectById(1L)).thenReturn(app());
        Optional<AiApp> r = service.authenticateKey("dh-raw");
        assertTrue(r.isPresent());
        verify(cacheService).set(startsWith("open:key:"), eq(app()), any(Duration.class));
        verify(keyMapper).updateById(argThat(x -> x.getLastUsedAt() != null));
    }

    @Test
    @DisplayName("appKey 校验：应用禁用返回空")
    void authenticateApp_disabled() {
        when(cacheService.get(eq("open:app:app-abc123"), eq(AiApp.class))).thenReturn(Optional.empty());
        AiApp a = app(); a.setEnabled(0);
        when(appMapper.selectOne(any())).thenReturn(a);
        assertTrue(service.authenticateApp("app-abc123").isEmpty());
    }

    @Test
    @DisplayName("限流：分钟窗口内超过 app 上限返回 false")
    void allowRate_overLimit() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(startsWith("open:rl:"))).thenReturn(1L, 2L, 3L);
        AiApp a = app(); // limit 2
        assertTrue(service.allowRate(a));
        assertTrue(service.allowRate(a));
        assertFalse(service.allowRate(a));
    }

    @Test
    @DisplayName("Referer 白名单：配置为空放行；配置非空且不匹配则拒绝")
    void originAllowed() {
        AiApp a = app();
        assertTrue(service.originAllowed(a, "https://evil.com")); // null 白名单=不限
        a.setAllowedOrigins("https://oa.corp.com,https://portal.corp.com");
        assertTrue(service.originAllowed(a, "https://oa.corp.com/faq"));
        assertFalse(service.originAllowed(a, "https://evil.com"));
        assertFalse(service.originAllowed(a, null));
    }
}
```

- [ ] **步骤 2：运行验证失败**：`mvn test -Dtest=OpenApiAuthServiceTest`，预期编译失败（类不存在）。
- [ ] **步骤 3：实现**

```java
// src/main/java/com/devhub/module/ai/infrastructure/openapi/OpenApiAuthService.java
package com.devhub.module.ai.infrastructure.openapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.mapper.AiAppMapper;
import com.devhub.module.ai.mapper.ApiKeyMapper;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.entity.ApiKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 开放 API 凭据校验 + 应用级限流 + 挂件 Referer 白名单。
 * 校验结果 Redis 缓存 5 分钟；应用/key 禁用时由管理接口调用 invalidate 主动失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiAuthService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final AiAppMapper appMapper;
    private final ApiKeyMapper keyMapper;
    private final RedisCacheService cacheService;
    private final StringRedisTemplate redis;

    /** apiKey（保密凭据）→ 所属应用；无效/禁用/过期/应用下线返回空 */
    public Optional<AiApp> authenticateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return Optional.empty();
        String hash = sha256(rawKey);
        String key = "open:key:" + hash;
        Optional<AiApp> cached = cacheService.get(key, AiApp.class);
        if (cached.isPresent()) return cached;
        try {
            ApiKey k = keyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                    .eq(ApiKey::getKeyHash, hash).last("LIMIT 1"));
            if (k == null || k.getEnabled() != 1) return Optional.empty();
            if (k.getExpiresAt() != null && k.getExpiresAt().isBefore(LocalDateTime.now())) return Optional.empty();
            AiApp app = appMapper.selectById(k.getAppId());
            if (app == null || app.getEnabled() != 1) return Optional.empty();
            cacheService.set(key, app, CACHE_TTL);
            try {
                ApiKey touch = new ApiKey();
                touch.setId(k.getId());
                touch.setLastUsedAt(LocalDateTime.now());
                keyMapper.updateById(touch);
            } catch (Exception ignored) { }
            return Optional.of(app);
        } catch (Exception e) {
            log.warn("apiKey 校验失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** appKey（公开凭据，挂件用）→ 应用 */
    public Optional<AiApp> authenticateApp(String appKey) {
        if (appKey == null || appKey.isBlank()) return Optional.empty();
        String key = "open:app:" + appKey;
        Optional<AiApp> cached = cacheService.get(key, AiApp.class);
        if (cached.isPresent()) return cached;
        try {
            AiApp app = appMapper.selectOne(new LambdaQueryWrapper<AiApp>()
                    .eq(AiApp::getAppKey, appKey).last("LIMIT 1"));
            if (app == null || app.getEnabled() != 1) return Optional.empty();
            cacheService.set(key, app, CACHE_TTL);
            return Optional.of(app);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 应用禁用/编辑后失效缓存（管理接口调用） */
    public void invalidateApp(String appKey) {
        cacheService.delete("open:app:" + appKey);
    }

    /** 应用级限流：每分钟固定窗口，Redis 故障时放行（限流不阻断服务） */
    public boolean allowRate(AiApp app) {
        int limit = app.getRateLimitPerMin() == null ? 20 : app.getRateLimitPerMin();
        try {
            String window = String.valueOf(System.currentTimeMillis() / 60_000);
            String key = "open:rl:" + app.getAppKey() + ":" + window;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, Duration.ofSeconds(70));
            return count == null || count <= limit;
        } catch (Exception e) {
            return true;
        }
    }

    /** Referer 是否在应用白名单内；白名单为空 = 不限来源 */
    public boolean originAllowed(AiApp app, String referer) {
        String allow = app.getAllowedOrigins();
        if (allow == null || allow.isBlank()) return true;
        if (referer == null || referer.isBlank()) return false;
        for (String o : allow.split(",")) {
            String prefix = o.trim();
            if (!prefix.isEmpty() && referer.startsWith(prefix)) return true;
        }
        return false;
    }

    public String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **步骤 4：运行验证通过**：`mvn test -Dtest=OpenApiAuthServiceTest`，预期 7 个 PASS。
- [ ] **步骤 5：Commit** `git commit -m "开放API凭据服务：apiKey/appKey校验+缓存、应用级限流、Referer白名单"`

### 任务 3：ApiKeyAuthFilter + SecurityConfig 放行

**文件：** 创建 `infrastructure/openapi/ApiKeyAuthFilter.java`；修改 `SecurityConfig.java:34-38`

- [ ] **步骤 1：实现 Filter**（模式照抄 ChatRateLimitFilter：OncePerRequestFilter + @Component，只拦 `/api/open/v1/chat`，通过则 `request.setAttribute("OPEN_API_APP", app)`）

```java
package com.devhub.module.ai.infrastructure.openapi;

import com.devhub.module.ai.model.entity.AiApp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/** /api/open/v1/chat 的 X-API-Key 鉴权：通过后应用放请求属性 OPEN_API_APP */
@Order(2)
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final OpenApiAuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/open/v1/chat")) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AiApp> app = authService.authenticateKey(request.getHeader("X-API-Key"));
        if (app.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Invalid API key\"}");
            return;
        }
        request.setAttribute("OPEN_API_APP", app.get());
        chain.doFilter(request, response);
    }
}
```

- [ ] **步骤 2：SecurityConfig 放行**（`SecurityConfig.java` 第 37 行后加一行）

```java
.requestMatchers("/api/open/**").permitAll()
```

- [ ] **步骤 3：编译**：`mvn -q compile` 无 ERROR。
- [ ] **步骤 4：Commit** `git commit -m "开放API鉴权过滤器与Security放行"`

### 任务 4：问答链路注入改造（scope/persona/记账）

**文件：** 修改 `MultiRetriever.java`、`ChatRequest.java`、`AgentOrchestrator.java`；测试挂到任务 5 的 Controller 测试里覆盖

- [ ] **步骤 1：MultiRetriever 加 ThreadLocal 覆盖**（`currentScope()` 开头插入检查，`dataScope()` 不变）

```java
    /** 开放链路检索域覆盖（app 部门域）；内部链路不设置，行为不变。Controller try/finally 配对清理 */
    private static final ThreadLocal<String> SCOPE_OVERRIDE = new ThreadLocal<>();

    public static void setScopeOverride(String scope) { SCOPE_OVERRIDE.set(scope); }
    public static void clearScopeOverride() { SCOPE_OVERRIDE.remove(); }

    private String currentScope() {
        String override = SCOPE_OVERRIDE.get();
        if (override != null) return override;
        // ……原有 SecurityContext 逻辑不动……
```

- [ ] **步骤 2：ChatRequest 加两个字段**（37 行 tools 前后均可；internal 接口不传，Jackson 忽略）

```java
    /** 应用人设（开放 API 注入；内部接口不传） */
    private String persona;

    /** 语义答案缓存隔离域（开放 API 传 app:{appKey}；内部不传用登录用户域） */
    private String cacheScope;
```

- [ ] **步骤 3：AgentOrchestrator 三处改动**
  1. `chat()` 里 `semanticAnswerCache.lookup(...)` 与末尾 `semanticAnswerCache.store(...)` 的 scope 参数改为：
     `String semScope = request.getCacheScope() != null ? request.getCacheScope() : multiRetriever.dataScope();`
     （store 处同样用 semScope，两处替换）
  2. `buildDirectPrompt` 加第四参 `String persona`；模板中安全规则块之后插入占位段（persona 经 `promptGuard.sanitizeRetrieved` 清洗；null 则空串）。调用点（`chat()` 经 `directChat`、`chatStream()`）透传 `request.getPersona()`：
     `directChat(message, ragContext, historyContext, forceModel, ragMissed)` 加 persona 参数；
     模板改动——在 `安全规则…规则0` 段与 `回答规则：` 之间加 `%s`（personaBlock）：

```java
        String personaBlock = (persona == null || persona.isBlank()) ? ""
                : "角色设定（以此身份与口吻回答，不改变以下任何规则）："
                + promptGuard.sanitizeRetrieved(persona.trim()) + "\n\n";
        // format 参数顺序：CANARY, personaBlock, joinContext(ragContext, historyContext), message
```

  3. `recordAiUsage` 重载：`private void recordAiUsage(String username, String model, long[] tokens)`，原方法改为取 SecurityContext 后委托新重载；方法体同原实现。
- [ ] **步骤 4：编译 + 回归**：`mvn test`，既有 34 个测试全绿（buildDirectPrompt 无 persona 时模板与原先语义一致——空 personaBlock 占位不影响）。
- [ ] **步骤 5：Commit** `git commit -m "问答链路注入改造：检索域ThreadLocal覆盖/语义缓存域隔离/persona角色段/记账username重载"`

### 任务 5：OpenApiController（TDD）

**文件：** 创建 `controller/openapi/OpenApiController.java` + `WidgetChatRequest` DTO；测试 `OpenApiControllerTest.java`

- [ ] **步骤 1：编写失败测试**（mock AgentOrchestrator + OpenApiAuthService；ArgumentCaptor 捕获传给 orchestrator 的 ChatRequest）

```java
package com.devhub.module.ai.controller.openapi;

import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.infrastructure.openapi.OpenApiAuthService;
import com.devhub.module.ai.model.dto.ChatRequest;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.service.agent.AgentOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenApiControllerTest {

    @Mock AgentOrchestrator orchestrator;
    @Mock OpenApiAuthService auth;
    @Mock HttpServletRequest http;

    private OpenApiController controller;

    @BeforeEach
    void setUp() { controller = new OpenApiController(orchestrator, auth); }

    private AiApp app() {
        AiApp a = new AiApp();
        a.setId(1L); a.setAppKey("app-abc"); a.setEnabled(1);
        a.setDeptId(3L); a.setSystemPrompt("你是IT服务台助手"); a.setRateLimitPerMin(20);
        return a;
    }

    @Test
    @DisplayName("开放问答：persona/cacheScope/scope 注入正确，RAG 强制开")
    void chat_injectsAppContext() {
        AiApp app = app();
        when(http.getAttribute("OPEN_API_APP")).thenReturn(app);
        when(auth.allowRate(app)).thenReturn(true);
        ChatRequest req = new ChatRequest();
        req.setMessage("VPN 怎么连");

        controller.chat(req, http);

        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chat(cap.capture());
        ChatRequest sent = cap.getValue();
        assertEquals("你是IT服务台助手", sent.getPersona());
        assertEquals("app:app-abc", sent.getCacheScope());
        assertTrue(sent.isEnableRag());
        verify(auth).setScopeThen("dept:3", null); // 见下：scope 设置经 auth 代理以便测试
    }

    @Test
    @DisplayName("开放问答：限流超限抛 429 业务异常")
    void chat_rateLimited() {
        AiApp app = app();
        when(http.getAttribute("OPEN_API_APP")).thenReturn(app);
        when(auth.allowRate(app)).thenReturn(false);
        ChatRequest req = new ChatRequest();
        req.setMessage("hi");
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.chat(req, http));
        assertEquals(429, ex.getCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("挂件：应用禁用抛 403")
    void widget_disabledApp() {
        when(auth.authenticateApp("app-x")).thenReturn(Optional.empty());
        OpenApiController.WidgetChatRequest w = new OpenApiController.WidgetChatRequest();
        w.setAppKey("app-x"); w.setMessage("hi");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.widget(w, http));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("挂件：Referer 不在白名单抛 403")
    void widget_originBlocked() {
        AiApp app = app();
        when(auth.authenticateApp("app-abc")).thenReturn(Optional.of(app));
        when(http.getHeader("Referer")).thenReturn("https://evil.com");
        when(auth.originAllowed(app, "https://evil.com")).thenReturn(false);
        OpenApiController.WidgetChatRequest w = new OpenApiController.WidgetChatRequest();
        w.setAppKey("app-abc"); w.setMessage("hi");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.widget(w, http));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("挂件：通过后走 chatStream 且会话前缀 embed:")
    void widget_ok() {
        AiApp app = app();
        when(auth.authenticateApp("app-abc")).thenReturn(Optional.of(app));
        when(http.getHeader("Referer")).thenReturn(null);
        when(auth.originAllowed(app, null)).thenReturn(true);
        when(auth.allowRate(app)).thenReturn(true);
        when(orchestrator.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just("data"));
        OpenApiController.WidgetChatRequest w = new OpenApiController.WidgetChatRequest();
        w.setAppKey("app-abc"); w.setMessage("hi"); w.setConversationId("c1");
        controller.widget(w, http);
        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chatStream(cap.capture());
        assertTrue(cap.getValue().getConversationId().startsWith("embed:app-abc:c1"));
        assertEquals("你是IT服务台助手", cap.getValue().getPersona());
    }
}
```

注：测试里 `auth.setScopeThen(...)` 要求 OpenApiAuthService 提供一个**测试可 mock** 的代理方法（避免测试直接操作静态 ThreadLocal）：

```java
    /** 设置检索域覆盖并执行动作，finally 清理（Controller 唯一入口，便于测试） */
    public <T> T setScopeThen(String scope, java.util.function.Supplier<T> action) {
        com.devhub.module.ai.service.rag.MultiRetriever.setScopeOverride(scope);
        try {
            return action.get();
        } finally {
            com.devhub.module.ai.service.rag.MultiRetriever.clearScopeOverride();
        }
    }
```

- [ ] **步骤 2：运行验证失败**：`mvn test -Dtest=OpenApiControllerTest`，预期编译失败。
- [ ] **步骤 3：实现 Controller**

```java
package com.devhub.module.ai.controller.openapi;

import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.openapi.OpenApiAuthService;
import com.devhub.module.ai.model.dto.ChatRequest;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.enums.AgentMode;
import com.devhub.module.ai.service.agent.AgentOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 开放问答 API（规格 5.1/5.2）：
 * /chat 走 ApiKeyAuthFilter（X-API-Key）；/widget/stream 走 appKey + Referer 白名单 + 应用级限流。
 * 检索域=应用部门域，语义缓存域=app:{appKey}，记账 username=app:{appKey}。
 */
@RestController
@RequestMapping("/api/open/v1")
@RequiredArgsConstructor
public class OpenApiController {

    private final AgentOrchestrator agentOrchestrator;
    private final OpenApiAuthService auth;

    /** 服务端集成：同步问答（Filter 已完成鉴权） */
    @PostMapping("/chat")
    public Result<com.devhub.module.ai.model.dto.ChatResponse> chat(
            @Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        AiApp app = (AiApp) http.getAttribute("OPEN_API_APP");
        if (!auth.allowRate(app)) {
            throw new BusinessException(429, "应用调用量超过限额，请稍后再试");
        }
        applyAppContext(request, app);
        return auth.setScopeThen(retrievalScope(app), () ->
                Result.success(agentOrchestrator.chat(request)));
    }

    /** 挂件流式（SSE 协议与内部 /api/v1/chat/stream 一致） */
    @PostMapping(value = "/widget/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> widget(@RequestBody WidgetChatRequest body, HttpServletRequest http) {
        AiApp app = auth.authenticateApp(body.getAppKey())
                .orElseThrow(() -> new BusinessException(403, "服务暂时下线，请联系管理员"));
        if (!auth.originAllowed(app, http.getHeader("Referer"))) {
            throw new BusinessException(403, "来源不在白名单");
        }
        if (!auth.allowRate(app)) {
            throw new BusinessException(429, "提问太频繁，请稍后再试");
        }
        ChatRequest request = new ChatRequest();
        request.setMessage(body.getMessage());
        request.setConversationId("embed:" + app.getAppKey() + ":"
                + (body.getConversationId() == null ? "default" : body.getConversationId()));
        applyAppContext(request, app);
        return auth.setScopeThen(retrievalScope(app), () -> agentOrchestrator.chatStream(request));
    }

    private void applyAppContext(ChatRequest request, AiApp app) {
        request.setEnableRag(true);
        request.setMode(AgentMode.DIRECT);
        request.setPersona(app.getSystemPrompt());
        request.setCacheScope("app:" + app.getAppKey());
    }

    private String retrievalScope(AiApp app) {
        return app.getDeptId() == null ? "all" : "dept:" + app.getDeptId();
    }

    /** 挂件请求体 */
    @Data
    public static class WidgetChatRequest {
        private String appKey;
        private String message;
        private String conversationId;
    }
}
```

记账说明：`chat()` 内 orchestrator 已调 `recordAiUsage`（取 SecurityContext，匿名时为 null 会记失败）——在 orchestrator 的 recordAiUsage 委托处加：username 为空时读 `request.getCacheScope()`（即 app:{appKey}），实现"开放链路按应用记账"。chatStream 的 doOnComplete 记账点同样处理（两处各一行三元判断）。

- [ ] **步骤 4：运行验证通过**：`mvn test -Dtest=OpenApiControllerTest`，5 个 PASS。
- [ ] **步骤 5：Commit** `git commit -m "开放问答API与挂件流式端点：app上下文注入+双域隔离+应用记账"`

### 任务 6：AiAppController（应用/密钥管理，JWT）

**文件：** 创建 `controller/AiAppController.java`

- [ ] **步骤 1：实现**（要点：创建人生成 appKey/dept 默认绑创建人部门；key 明文 `dh-`+32hex 仅创建响应返回一次；编辑/禁用限创建人或 ADMIN；key/应用禁用后调 `auth.invalidateApp`；列表分页可按名称过滤）

```java
package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.openapi.OpenApiAuthService;
import com.devhub.module.ai.mapper.AiAppMapper;
import com.devhub.module.ai.mapper.ApiKeyMapper;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.entity.ApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** AI 应用与 API Key 管理（JWT 鉴权；编辑/禁用限创建人或 ADMIN） */
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class AiAppController {

    private final AiAppMapper appMapper;
    private final ApiKeyMapper keyMapper;
    private final OpenApiAuthService auth;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    @GetMapping
    public Result<Page<AiApp>> list(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "true") boolean activeOnly) {
        LambdaQueryWrapper<AiApp> q = new LambdaQueryWrapper<AiApp>()
                .like(keyword != null && !keyword.isBlank(), AiApp::getName, keyword)
                .eq(activeOnly, AiApp::getEnabled, 1)
                .orderByDesc(AiApp::getUpdateTime);
        return Result.success(appMapper.selectPage(new Page<>(page, size), q));
    }

    @OperLog(module = "AI应用", action = "创建应用")
    @PostMapping
    public Result<AiApp> create(@RequestBody AiApp app) {
        if (app.getName() == null || app.getName().isBlank()) {
            throw new BusinessException("应用名称不能为空");
        }
        String me = currentUsername();
        app.setId(null);
        app.setAppKey("app-" + hex(12));
        app.setEnabled(app.getEnabled() == null ? 1 : app.getEnabled());
        app.setCreatedBy(me);
        if (app.getDeptId() == null) {
            try {
                app.setDeptId(jdbc.queryForObject(
                        "SELECT dept_id FROM sys_user WHERE username = ? AND deleted = 0",
                        Long.class, me));
            } catch (Exception ignored) { }
        }
        appMapper.insert(app);
        return Result.success(app);
    }

    @OperLog(module = "AI应用", action = "更新应用")
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody AiApp patch) {
        AiApp app = requireOwned(id);
        patch.setId(id);
        patch.setAppKey(null);          // 凭据不可改
        patch.setCreatedBy(null);
        appMapper.updateById(patch);
        auth.invalidateApp(app.getAppKey());
        return Result.success("ok");
    }

    @OperLog(module = "AI应用", action = "禁用应用")
    @DeleteMapping("/{id}")
    public Result<String> disable(@PathVariable Long id) {
        AiApp app = requireOwned(id);
        AiApp patch = new AiApp();
        patch.setId(id);
        patch.setEnabled(0);
        appMapper.updateById(patch);
        auth.invalidateApp(app.getAppKey());
        return Result.success("ok");
    }

    @GetMapping("/{id}/keys")
    public Result<List<ApiKey>> keys(@PathVariable Long id) {
        requireOwned(id);
        return Result.success(keyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAppId, id).orderByDesc(ApiKey::getId)));
    }

    /** 创建 key：明文仅本次响应返回 */
    @OperLog(module = "AI应用", action = "创建API密钥")
    @PostMapping("/{id}/keys")
    public Result<Map<String, Object>> createKey(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, String> body) {
        requireOwned(id);
        String raw = "dh-" + hex(32);
        ApiKey k = new ApiKey();
        k.setAppId(id);
        k.setKeyHash(auth.sha256(raw));
        k.setKeyPrefix(raw.substring(0, 8));
        k.setName(body == null ? "default" : body.getOrDefault("name", "default"));
        k.setEnabled(1);
        keyMapper.insert(k);
        Map<String, Object> out = new HashMap<>();
        out.put("id", k.getId());
        out.put("keyPrefix", k.getKeyPrefix());
        out.put("name", k.getName());
        out.put("secret", raw);   // 唯一一次明文
        return Result.success(out);
    }

    @OperLog(module = "AI应用", action = "切换密钥状态")
    @PutMapping("/{id}/keys/{keyId}")
    public Result<String> toggleKey(@PathVariable Long id, @PathVariable Long keyId,
                                    @RequestParam boolean enabled) {
        requireOwned(id);
        ApiKey patch = new ApiKey();
        patch.setId(keyId);
        patch.setEnabled(enabled ? 1 : 0);
        keyMapper.updateById(patch);
        return Result.success("ok");
    }

    @OperLog(module = "AI应用", action = "删除密钥")
    @DeleteMapping("/{id}/keys/{keyId}")
    public Result<String> deleteKey(@PathVariable Long id, @PathVariable Long keyId) {
        requireOwned(id);
        keyMapper.deleteById(keyId);
        return Result.success("ok");
    }

    private AiApp requireOwned(Long id) {
        AiApp app = appMapper.selectById(id);
        if (app == null) throw new BusinessException(404, "应用不存在");
        String me = currentUsername();
        boolean admin = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin && !me.equals(app.getCreatedBy())) {
            throw new BusinessException(403, "仅应用创建人或管理员可操作");
        }
        return app;
    }

    private String hex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        return sb.toString();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
```

- [ ] **步骤 2：编译**：`mvn -q compile` 无 ERROR。
- [ ] **步骤 3：Commit** `git commit -m "AI应用与APIKey管理接口：创建人/ADMIN权限、明文一次性返回、禁用即失效缓存"`

### 任务 7：前端应用管理页 + 路由

**文件：** 创建 `devhub-web/src/views/ai/Apps.vue`；修改 `router/index.js`

- [ ] **步骤 1：路由**（Layout children 里 `ai/ops` 之后加一行；无新增顶层路由）

```js
      { path: 'ai/apps', component: () => import('../views/ai/Apps.vue') },
```

- [ ] **步骤 2：Apps.vue**（Element Plus 卡片 + 分区表单弹窗 + 密钥抽屉 + iframe 代码复制；api 助手自动带 JWT）

```vue
<!-- devhub-web/src/views/ai/Apps.vue（借鉴 Dify 应用列表：卡片墙 + 创建弹窗 + 密钥管理抽屉） -->
<template>
  <div>
    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px">
      <h3 style="margin:0">AI 应用</h3>
      <el-button type="primary" @click="openCreate">＋ 创建应用</el-button>
    </div>

    <el-row :gutter="14">
      <el-col v-for="a in apps" :key="a.id" :span="8" style="margin-bottom:14px">
        <el-card shadow="hover" style="border-radius:14px">
          <div style="display:flex; justify-content:space-between">
            <b>{{ a.name }}</b>
            <el-tag :type="a.enabled === 1 ? 'success' : 'info'" size="small">
              {{ a.enabled === 1 ? '运行中' : '已下线' }}
            </el-tag>
          </div>
          <div style="color:var(--el-text-color-secondary); font-size:12.5px; margin:8px 0; min-height:34px">
            {{ a.description || '暂无描述' }}
          </div>
          <div style="font-size:12px; color:var(--el-text-color-secondary)">appKey: {{ a.appKey }}</div>
          <div style="display:flex; gap:8px; margin-top:10px">
            <el-button size="small" @click="openEdit(a)">配置</el-button>
            <el-button size="small" @click="openKeys(a)">密钥</el-button>
            <el-button size="small" @click="openEmbed(a)">嵌入</el-button>
            <el-button size="small" type="danger" plain @click="disable(a)">下线</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 创建/编辑弹窗：分区表单 -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑应用' : '创建应用'" width="560px">
      <el-form label-width="96px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
        <el-divider content-position="left">人设与开场白</el-divider>
        <el-form-item label="角色人设">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="3"
                    placeholder="例：你是公司 IT 服务台助手，回答简洁、给出操作步骤" />
        </el-form-item>
        <el-form-item label="开场白">
          <el-input v-model="form.openingGreeting" placeholder="挂件打开时显示的第一句话" />
        </el-form-item>
        <el-divider content-position="left">访问配置</el-divider>
        <el-form-item label="部门数据域">
          <el-select v-model="form.deptId" clearable placeholder="全公司（默认）" style="width:100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="每分钟限额"><el-input-number v-model="form.rateLimitPerMin" :min="1" :max="600" /></el-form-item>
        <el-form-item label="Referer白名单">
          <el-input v-model="form.allowedOrigins" placeholder="https://oa.corp.com,https://portal.corp.com（空=不限）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 密钥抽屉 -->
    <el-drawer v-model="keyDlg" :title="'API 密钥 — ' + (cur && cur.name)" size="44%">
      <el-button type="primary" size="small" style="margin-bottom:12px" @click="createKey">＋ 新建密钥</el-button>
      <el-alert v-if="freshSecret" type="warning" :closable="false" style="margin-bottom:12px"
                title="密钥仅此一次显示，请立即复制保存" />
      <el-input v-if="freshSecret" :model-value="freshSecret" readonly style="margin-bottom:12px">
        <template #append><el-button @click="copy(freshSecret)">复制</el-button></template>
      </el-input>
      <el-table :data="keys" size="small">
        <el-table-column prop="keyPrefix" label="前缀" width="110" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="lastUsedAt" label="最近使用" width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" link @click="toggleKey(row)">{{ row.enabled === 1 ? '禁用' : '启用' }}</el-button>
            <el-button size="small" link type="danger" @click="deleteKey(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 嵌入代码 -->
    <el-dialog v-model="embedDlg" title="嵌入到你的系统" width="560px">
      <p style="font-size:13px; color:var(--el-text-color-secondary)">在任意内部系统的 HTML 里粘贴：</p>
      <el-input :model-value="embedCode" type="textarea" :rows="3" readonly />
      <template #footer><el-button type="primary" @click="copy(embedCode)">复制代码</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
defineOptions({ name: 'AppsPage' })
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const apps = ref([]), depts = ref([])
const dlg = ref(false), keyDlg = ref(false), embedDlg = ref(false)
const form = ref({}), cur = ref(null), keys = ref([]), freshSecret = ref('')
const embedCode = ref('')

async function load() { apps.value = (await api.get('/apps', { params: { size: 50 } })).records || [] }
onMounted(async () => {
  load()
  try { depts.value = await api.get('/system/depts') } catch (e) { /* 无权限时忽略 */ }
})

function openCreate() {
  form.value = { rateLimitPerMin: 20 }
  dlg.value = true
}
function openEdit(a) { form.value = { ...a }; dlg.value = true }
async function save() {
  if (form.value.id) await api.put(`/apps/${form.value.id}`, form.value)
  else await api.post('/apps', form.value)
  ElMessage.success('已保存'); dlg.value = false; load()
}
async function disable(a) {
  await ElMessageBox.confirm(`下线后挂件与 API 立即不可用，确认下线「${a.name}」？`, '确认')
  await api.delete(`/apps/${a.id}`); load()
}
async function openKeys(a) {
  cur.value = a; freshSecret.value = ''
  keys.value = await api.get(`/apps/${a.id}/keys`)
  keyDlg.value = true
}
async function createKey() {
  const r = await api.post(`/apps/${cur.value.id}/keys`, { name: 'key-' + (keys.value.length + 1) })
  freshSecret.value = r.secret
  keys.value = await api.get(`/apps/${cur.value.id}/keys`)
}
async function toggleKey(row) {
  await api.put(`/apps/${cur.value.id}/keys/${row.id}`, null, { params: { enabled: row.enabled !== 1 } })
  keys.value = await api.get(`/apps/${cur.value.id}/keys`)
}
async function deleteKey(row) {
  await api.delete(`/apps/${cur.value.id}/keys/${row.id}`)
  keys.value = await api.get(`/apps/${cur.value.id}/keys`)
}
function openEmbed(a) {
  const base = location.origin
  embedCode.value = `<iframe src="${base}/#/embed/${a.appKey}" width="420" height="640" style="border:0;border-radius:14px" title="AI 助手"></iframe>`
  embedDlg.value = true
}
async function copy(text) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}
</script>
```

- [ ] **步骤 3：验证**：`npm run build`（devhub-web 目录）无错误。
- [ ] **步骤 4：Commit** `git commit -m "前端AI应用管理页：卡片列表/分区配置/密钥一次性明文/嵌入代码复制"`

### 任务 8：挂件页（公开路由）

**文件：** 创建 `devhub-web/src/views/ai/EmbedWidget.vue`；修改 `router/index.js`；新建 `devhub-web/src/composables/useSseChat.js`

- [ ] **步骤 1：路由**（顶层，login 之前加）

```js
  { path: '/embed/:appKey', component: () => import('../views/ai/EmbedWidget.vue'), meta: { public: true } },
```

- [ ] **步骤 2：useSseChat.js**（SSE 消费循环抽自 Chat.vue 逻辑，挂件与后续复用）

```js
// devhub-web/src/composables/useSseChat.js
export function useSseChat() {
  let ctrl = null
  /** 流式对话；onMeta(meta)、onDelta(text) 回调；返回 Promise<完整文本> */
  async function stream(url, body, { onMeta, onDelta }) {
    ctrl = new AbortController()
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: ctrl.signal
    })
    const reader = res.body.getReader(), dec = new TextDecoder()
    let buf = '', raw = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      const events = buf.split('\n\n'); buf = events.pop()
      for (const evt of events) {
        const line = evt.split('\n').find(l => l.startsWith('data:'))
        if (!line) continue
        const data = line.slice(5)
        if (data === '[DONE]') continue
        if (data.startsWith('__META__')) { onMeta && onMeta(JSON.parse(data.slice(8))) }
        else { raw += data; onDelta && onDelta(raw) }
      }
    }
    return raw
  }
  function abort() { ctrl && ctrl.abort() }
  return { stream, abort }
}
```

- [ ] **步骤 3：EmbedWidget.vue**（MaxKB 式极简聊天卡：头部应用名+AI 标识、开场白、气泡、输入框；匿名、appKey 从路由取；localStorage 存 conversationId 续聊）

```vue
<template>
  <div style="max-width:460px; margin:24px auto; display:flex; flex-direction:column; height:calc(100vh - 48px); background:#fff; border-radius:16px; box-shadow:0 8px 32px rgba(0,0,0,.12); overflow:hidden">
    <div style="padding:14px 18px; border-bottom:1px solid #eee; display:flex; justify-content:space-between; align-items:center">
      <b>{{ appName || 'AI 助手' }}</b>
      <span style="font-size:11px; color:#999">Powered by DevHub</span>
    </div>
    <div ref="listEl" style="flex:1; overflow-y:auto; padding:16px; background:#f7f8fa">
      <div v-if="greeting" style="margin-bottom:14px">
        <div class="wb">{{ greeting }}</div>
      </div>
      <div v-for="(m, i) in msgs" :key="i" :style="{ display:'flex', justifyContent: m.role === 'USER' ? 'flex-end' : 'flex-start', marginBottom:'12px' }">
        <div class="wb" :class="{ me: m.role === 'USER' }" style="white-space:pre-wrap">{{ m.content || '…' }}</div>
      </div>
    </div>
    <div style="display:flex; gap:8px; padding:12px; border-top:1px solid #eee">
      <el-input v-model="input" :disabled="busy" placeholder="输入问题，Enter 发送" @keydown.enter.exact.prevent="send" />
      <el-button type="primary" :disabled="!input.trim() || busy" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'EmbedWidget' })
import { ref, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useSseChat } from '../../composables/useSseChat'

const route = useRoute()
const appKey = route.params.appKey
const appName = ref(''), greeting = ref('')
const msgs = ref([]), input = ref(''), busy = ref(false)
const listEl = ref()
const { stream } = useSseChat()
const convId = () => localStorage.getItem('dh_embed_' + appKey) || ''
const setConv = id => localStorage.setItem('dh_embed_' + appKey, id)

onMounted(async () => {
  try {
    const meta = await fetch(`/api/open/v1/widget/meta/${appKey}`).then(r => r.json())
    if (meta && meta.data) { appName.value = meta.data.name; greeting.value = meta.data.greeting || '' }
  } catch (e) { /* 拿不到就静默 */ }
})

function scrollBottom() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }

async function send() {
  const q = input.value.trim()
  if (!q || busy.value) return
  input.value = ''
  msgs.value.push({ role: 'USER', content: q })
  const ai = { role: 'ASSISTANT', content: '' }
  msgs.value.push(ai)
  busy.value = true
  scrollBottom()
  try {
    const raw = await stream('/api/open/v1/widget/stream',
      { appKey, message: q, conversationId: convId() },
      { onMeta: m => { if (m.conversationId) setConv(m.conversationId.split(':').pop()) },
        onDelta: t => { ai.content = t; scrollBottom() } })
    ai.content = raw || '（未返回内容）'
  } catch (e) {
    ai.content = '服务暂时不可用，请稍后再试'
  } finally {
    busy.value = false
    scrollBottom()
  }
}
</script>

<style scoped>
.wb { background:#fff; border-radius:12px; padding:10px 14px; max-width:82%; font-size:13.5px; line-height:1.7; box-shadow:0 1px 2px rgba(0,0,0,.06) }
.wb.me { background:var(--el-color-primary); color:#fff }
</style>
```

- [ ] **步骤 4：OpenApiController 补挂件元数据端点**（挂件头部信息，匿名可用）

```java
    /** 挂件元信息：应用名 + 开场白（应用禁用返回空 name，前端静默） */
    @GetMapping("/widget/meta/{appKey}")
    public Result<Map<String, String>> widgetMeta(@PathVariable String appKey) {
        return auth.authenticateApp(appKey).map(app -> {
            Map<String, String> m = new HashMap<>();
            m.put("name", app.getName());
            m.put("greeting", app.getOpeningGreeting() == null ? "" : app.getOpeningGreeting());
            return Result.success(m);
        }).orElseGet(() -> Result.success(Map.of("name", "", "greeting", "")));
    }
```

（`Result.success(Map.of(...))` 泛型推断问题则显式 `Result.<Map<String,String>>success`；import java.util.HashMap/Map 已在 Controller 引入范围。）

- [ ] **步骤 5：构建验证**：`npm run build` 无错误；后端 `mvn -q compile` 无错误。
- [ ] **步骤 6：Commit** `git commit -m "挂件页与元数据端点：匿名SSE对话/开场白/会话续聊，useSseChat composable"`

### 任务 9：全量测试 + 冒烟 + 收尾

- [ ] **步骤 1：全量测试**：`mvn test`——预期 34+7+5=46 个全绿。
- [ ] **步骤 2：打包**：`mvn -q package -DskipTests`。
- [ ] **步骤 3：起环境**（Docker Desktop 需在运行）：`docker compose -f D:\docker\middleware\docker-compose.yml up -d`，然后按既有方式后台起 jar（参考 `build/` 下此前冒烟命令：MYSQL_PORT=3307 等 + .env 里的 key）。
- [ ] **步骤 4：冒烟断言**（全部通过才算完成）：
  1. 登录 admin → `POST /api/apps` 建应用（含 system_prompt、opening_greeting）→ 返回 appKey
  2. `POST /api/apps/{id}/keys` → 记下明文 secret
  3. `curl -X POST /api/open/v1/chat -H "X-API-Key: {secret}" -d '{"message":"..."}'` → 200 且回答带 persona 风格；错 key → 401
  4. 浏览器开 `http://localhost:5173/#/embed/{appKey}` → 开场白显示、能对话、刷新后会话续聊
  5. `docker exec agent-mysql mysql -uroot -proot devhub -e "SELECT username, COUNT(*) FROM ai_usage WHERE username LIKE 'app:%' GROUP BY username"` → 有 app: 记账行
  6. 登录后导航：知识中枢（问答/历史/运营/AI应用）在前、研发协作分组含项目/任务/缺陷
- [ ] **步骤 5：Commit** `git commit -m "知识中枢一期冒烟通过：开放API/挂件/应用记账/导航重排全链路验证"`

---

## 计划自检记录

1. **规格覆盖度**：规格 §3→任务1；§4→任务2/3；§5.1/5.2→任务5（+任务8步骤4元数据端点，规格未列但挂件头部必需）；§5.3→任务6；§6→任务4；§7→任务7/8（useChat 偏差已注明）；§8→任务2/3/5 异常路径；§9→任务2/5/9；§10→任务4 双域/任务2 白名单。无遗漏。
2. **占位符扫描**：无 TODO/待定；所有代码步骤含完整代码。
3. **类型一致性**：`OpenApiAuthService.setScopeThen` 在任务5测试与实现一致；`WidgetChatRequest` 字段 appKey/message/conversationId 与前端一致；ChatRequest 新字段 persona/cacheScope 全文一致。
