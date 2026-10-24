# DevHub 企业智能知识中枢转型设计（一期）

> 日期：2026-10-24
> 状态：已获用户批准（方向 A / 单企业+部门隔离 / 核心三件一期）
> 对标项目：MaxKB（28k★）、RAGFlow、FastGPT、Dify（App 概念）

## 1. 背景与定位

现状问题：产品定位为"研发项目管理"，场景小众。转型为「企业 AI 知识中枢」：
知识库问答/文档中心/AI 应用为主业，研发协作（项目/任务/缺陷/迭代）降级为子场景。

- 前端导航重排：「问答」「文档中心」「AI 应用」为一级；「研发协作」收为二级分组。PM 代码不删。
- 后端新增开放链路：第三方系统 → `/api/open/**` → 复用 AgentOrchestrator 现有 RAG 全链路。
- 内部链路（JWT 鉴权）行为完全不变，所有新参数可选。

## 2. 关键决策

| 决策点 | 结论 |
|---|---|
| 租户模型 | 单企业部署，部门间隔离（复用 deptId 数据域 + RBAC，权限体系零改动） |
| 一期范围 | AI 应用配置 + 开放问答 API + iframe 嵌入挂件 |
| 二期 backlog | 反馈闭环强化（点赞点踩→KnowledgeGap 自动生产）、流式开放 API、key 级用量报表、智能客服人机接力 |
| 凭据体系 | 两级：appKey 公开（挂件）、apiKey 保密（服务端集成），对标 MaxKB 公开链接/API 分离 |

## 3. 数据模型（Flyway V16）

```sql
CREATE TABLE ai_app (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_key VARCHAR(32) NOT NULL COMMENT '公开凭据，挂件用',
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  system_prompt TEXT COMMENT '应用人设，插入提示词角色段',
  opening_greeting VARCHAR(255) COMMENT '挂件开场白',
  dept_id BIGINT NULL COMMENT '应用检索数据域，NULL=全公司',
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
  key_prefix VARCHAR(8) NOT NULL COMMENT '展示用前缀',
  name VARCHAR(64),
  enabled TINYINT NOT NULL DEFAULT 1,
  expires_at DATETIME NULL,
  last_used_at DATETIME NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_key_hash (key_hash),
  KEY idx_app (app_id)
);
```

apiKey 明文格式：`dh-` + 32 位随机 hex，共 35 字符。appKey 格式：`app-` + 12 位随机 hex。

## 4. 鉴权与限流

**ApiKeyAuthFilter**（OncePerRequestFilter，只拦 `/api/open/v1/chat`）：
- `X-API-Key` 头 → SHA-256 → 查 api_key → 校验 enabled/expires_at → 关联 ai_app 校验 enabled
- 通过后 app 信息放请求属性 `OPEN_API_APP`；失败返回 401 JSON
- 校验结果 Redis 缓存 5 分钟（key: `open:key:{hash}`），应用/key 禁用时主动失效
- widget 端点不走此 Filter（用 appKey），在 Controller 内校验

**应用级限流**（widget 与 open chat 共用）：Redis 固定窗口 `open:rl:{appKey}`，
按 app.rate_limit_per_min，超限返回 429。实现参考既有 ChatRateLimitFilter。

**SecurityConfig**：`/api/open/**` 加入 permitAll（认证由 ApiKeyAuthFilter/Controller 自行完成）；
前端 `/embed/:appKey` 为 Vue 路由，生产态由既有 `/` 与 index.html 放行规则覆盖，无新增。

## 5. API 契约

### 5.1 开放问答（apiKey 鉴权）
`POST /api/open/v1/chat`
- Header: `X-API-Key: dh-xxx`
- Body: `{"message": "...", "conversationId": "可选"}`
- 响应: `Result<ChatResponse>`（复用内部同步问答结构）
- RAG 强制开启（enableRag=true 固定），mode 固定 DIRECT

### 5.2 挂件流式（appKey 鉴权）
`POST /api/open/v1/widget/stream`
- Body: `{"appKey": "app-xxx", "message": "...", "conversationId": "可选"}`
- 响应: SSE（复用 __META__/分片/[DONE] 协议，与内部 /api/v1/chat/stream 一致）
- 校验：appKey 存在 + enabled + Referer 在 allowed_origins 白名单（配置非空时）
- 会话 ID 用 `embed:{appKey}:{浏览器生成uuid}` 前缀，与内部会话空间隔离

### 5.3 应用管理（JWT 鉴权，内部）
- `GET/POST/PUT/DELETE /api/apps`（分页列表/创建/更新/禁用，删除=禁用+隐藏）
- `GET /api/apps/{id}/keys`、`POST /api/apps/{id}/keys`（创建返回明文一次）、
  `PUT /api/apps/{id}/keys/{keyId}`（禁用/启用）、`DELETE .../keys/{keyId}`
- 权限：登录用户可创建应用（自动绑定创建人部门）；编辑/禁用限创建人或 ADMIN

## 6. 对现有问答链路的最小改动

两个隔离域分开处理（关键设计）：
- **检索白名单域**（哪些文档可被检索）：沿用现有格式 `"all"` 或 `"dept:{id}"`，
  由 app.dept_id 决定（NULL→"all"）。机制：`MultiRetriever` 增加包内 ThreadLocal
  `ScopeOverrideHolder`（set/get/clear），`currentScope()` 首先检查它；开放链路
  Controller 在 try/finally 中 set 与 clear。内部链路不 set，行为不变。
- **语义答案缓存域**（防跨应用串答案）：开放链路固定用 `"app:{appKey}"`
  （AgentOrchestrator 里 SemanticAnswerCache.lookup/store 的 scope 参数），
  不同应用即使同部门也不共用答案——persona 不同答案就不同。检索结果缓存
  （MultiRetriever 内部 rag:result key）已含 currentScope() 输出，自动跟随。

1. `ChatRequest` 新增可选字段 `persona`（String，应用人设）。
2. `buildDirectPrompt`：persona 非空时作为「角色设定」段插入安全规则之后、参考资料之前；
   persona 内容过 `promptGuard.sanitizeRetrieved` 同款清洗（防应用配置本身带注入）；
   不开放覆盖完整模板（保住引用规范与 canary）。
3. `recordAiUsage` 重载为可显式传 username；开放链路传 `app:{appKey}`，
   现有 UsageController 报表自动覆盖应用维度。

## 7. 前端设计（借鉴 GitHub 优秀项目）

- **AI 应用管理页 `/apps`**（布局借鉴 Dify 应用列表+配置分区）：
  卡片列表（名称/描述/状态/调用量入口）→ 详情页分区表单：基本信息、人设与开场白、
  访问配置（部门域/限流/白名单）、API Key 管理（创建弹窗显示明文一次，列表只见前缀+禁用按钮）、
  嵌入代码一键复制（iframe 片段）。
- **挂件页 `/embed/:appKey`**（UI 借鉴 MaxKB 聊天挂件的极简风）：
  全高聊天卡：头部应用名+「AI 由 DevHub 提供」标识、开场白气泡、消息列表、输入框；
  SSE 消费逻辑从 Chat.vue 抽 `useChat` composable 复用；匿名路由（不走 JWT）。
- **导航重排**：一级=问答/文档中心/AI 应用；二级分组「研发协作」=项目/需求/任务/缺陷。

## 8. 错误处理

| 场景 | 码 | 说明 |
|---|---|---|
| apiKey 无效/禁用/过期 | 401 | `Invalid API key` |
| 应用禁用 | 403 | 挂件端转友好文案「服务暂时下线」 |
| Referer 不在白名单 | 403 | widget 专属 |
| 应用级限流超限 | 429 | 挂件端文案「提问太频繁，稍后再试」 |
| 参数校验失败 | 400 | 复用 GlobalExceptionHandler |

统一走 Result 结构；开放端点异常同样被 GlobalExceptionHandler 捕获。

## 9. 测试

- ApiKeyAuthService：hash 命中/缓存命中/禁用失效/过期
- 应用限流：窗口内超限 429
- OpenApiController（mock orchestrator）：401/403/429/200 四路径
- buildDirectPrompt：含 persona 段落、无 persona 与原模板逐字一致（回归）
- scopeOverride：检索与语义缓存收到 override 而非用户域
- 冒烟全链路：建应用→发key→curl 开放 API 问答→浏览器 /embed 对话→报表见 app:xxx 用量

## 10. 风险与对策

- 挂件匿名滥用：限流 + Referer 白名单 + enabled 开关即时下线
- persona 被用于注入：清洗后插入 + 模板不可覆盖 + 输出栏 canary 检测兜底
- 应用间答案串库：语义缓存/检索缓存 scope 均带 app 域（scopeOverride 含 appKey 维度）
