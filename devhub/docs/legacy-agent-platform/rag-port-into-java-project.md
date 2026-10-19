# RAG 问答「平移」进传统 Java 项目的方案书

> 需求：不是一个 AI 平台，也**不是**一套 OA。而是：手里有一个现有的传统 Java 后台项目（左侧菜单 + 多页管理端），把现在的 RAG 问答**搬进去，做成左侧菜单里的一个功能项**，点开就能用，作为该项目新增的一个模块。
>
> 本文回答两件事：**这个 Java 项目该定成什么方向**；**RAG 怎么平移进去、每一步做什么、怎么验收**。

---

## 0. 一页结论

| 项 | 结论 |
|---|---|
| 宿主项目方向 | **研发管理系统（DevHub）**：项目 / 迭代 / 任务 / 缺陷 / 文档中心 + 系统管理 |
| RAG 的位置 | 左侧菜单第 7 项 **「智能问答」**，下挂 3 个页面：对话问答 / 会话历史 / 知识运营 |
| 两者关系 | 宿主 80% 是传统 CRUD 业务；RAG 是一个菜单模块，**语料来自宿主自己的「文档中心」** |
| 工作量 | 宿主骨架 1.5 周（可用脚手架）+ RAG 后端平移 1 周 + 前端重写 1.5 周 + 收尾 0.5 周 ≈ **4.5 周** |
| 核心风险 | 不在 RAG 算法，在**三处接缝**：鉴权体系、SSE 穿网关、文档中心→知识库的异步链路 |

一句话：

> 一套传统研发管理系统，左侧菜单里多了一项「智能问答」，点开是一个嵌入式对话页；它的知识来自系统「文档中心」里已发布的规范文档，答不出会记缺口，管理员在「知识运营」里补。

---

## 1. 方向怎么定

### 1.1 三个候选宿主

| 候选 | 与现有语料的契合 | 业务厚度 | RAG 定位是否合理 | 结论 |
|---|---|---|---|---|
| **A. 研发管理系统**（项目/任务/缺陷/文档） | ★★★★★ 语料就是《阿里 Java 手册》《Arthas》《COLA》《SpringCloudAlibaba》，开发者查规范天经地义 | ★★★★ | 合理：文档中心是主业务，问答是延伸 | ✅ **推荐** |
| B. 企业知识管理系统 KMS | ★★★★★ | ★★ | ✗ 不合理：RAG 会变成系统主体，违背"一个小功能" | 否 |
| C. 电商/进销存后台 | ★ | ★★★★ | ✗ 牵强：商品问答没人用，纯为挂而挂 | 否 |
| D. 只有系统管理的通用后台 | ★★★ | ★ | △ 太薄，撑不起一个"项目" | 仅当已有现成项目时用 |

**选 A 的两条硬理由**

1. **语料天然**：你的 4 份 demo 文档全是研发规范，放在"文档中心"里毫无违和；换成电商后台，这 4 份文档就成了摆设。
2. **"小功能"成立**：文档中心负责存和管（CRUD、分类、权限、审核），问答只是"读"的一种方式。RAG 不在主链路上，但一用就离不开——这才是"新增功能"的正确形态。

> 如果**已经有**一个 Java 项目（毕设 / 课程设计 / 公司后台），不用新建宿主，直接跳到第 5 章「平移清单」，把 RAG 挂到它的菜单里即可。

### 1.2 推荐方向：研发管理系统 DevHub

| 角色 | 能做什么 |
|---|---|
| 开发/测试 | 看我的任务、提缺陷、传技术文档、**问 AI** |
| 项目经理 | 建项目/迭代、分派任务、看报表 |
| 管理员 | 用户/角色/菜单/字典、审核文档、**知识运营（缺口/反馈/草稿）** |

---

## 2. 系统蓝图

### 2.1 左侧菜单（★ 就是 RAG 落点）

```
工作台            /dashboard
项目管理          /project
  ├ 项目列表      /project/list
  ├ 迭代管理      /project/iteration
  └ 项目成员      /project/member
任务中心          /task
  ├ 我的任务      /task/mine
  ├ 任务看板      /task/board
  └ 全部任务      /task/list
缺陷跟踪          /defect
文档中心          /doc                      ← RAG 的语料来源
  ├ 文档分类      /doc/category
  ├ 文档列表      /doc/list                 （上传/解析状态/重建索引）
  └ 审核发布      /doc/audit
智能问答   ★      /ai                       ← 平移进来的模块
  ├ 对话问答      /ai/chat                  （主页面，默认打开这里）
  ├ 会话历史      /ai/history
  └ 知识运营      /ai/ops                   （仅管理员：缺口/反馈/草稿审核）
报表统计          /report
系统管理          /system
  ├ 用户管理 / 角色管理 / 菜单管理 / 部门管理
  ├ 字典管理 / 参数设置 / 操作日志
```

菜单第 7 项，不在第一位、不占主视觉——正好符合"系统的一个功能模块"。

### 2.2 页面清单（约 18 页）

宿主 15 页 + AI 3 页。AI 3 页里，**只有 `/ai/chat` 是重头**，另外两页是复用现有知识治理接口的简单列表页。

---

## 3. 技术栈

| 层 | 选型 | 与现有项目的关系 |
|---|---|---|
| 后端 | Spring Boot 3.x + MyBatis-Plus + MySQL 8 + Redis | **完全一致，直接复用** |
| 认证 | Sa-Token（或 Spring Security + JWT） | 替换现有 `X-Admin-Token` |
| 向量库 | Milvus 2.5 | 不动 |
| 文档解析 | Apache Tika + 现有 ETL | 不动 |
| 前端 | **Vue 3 + Vite + Element Plus + Pinia + Vue Router** | 现有是单文件 vanilla SPA，需重写外壳 |
| 前端图表/编辑器 | ECharts（报表）、md-editor 或 markdown-it | 新增 |
| 部署 | Docker Compose（app + mysql + redis + milvus + etcd + minio + nginx） | 现有 6 容器 + nginx |

**加速建议**：宿主骨架直接用成熟脚手架（RuoYi-Vue3 / 若依微服务版裁剪 / 或 Spring Boot + Element Plus 模板），把时间留给业务与 RAG 集成。面试时说得清"哪些是我写的、为什么这样集成"比"全部手写但没时间做深"更值钱。

---

## 4. 宿主系统要做什么（RAG 之外的工作量）

### P0（必须）

| 模块 | 内容 |
|---|---|
| 登录鉴权 | 账密登录、token、登出、改密、登录日志 |
| RBAC | 用户、部门树、角色、菜单/按钮权限、数据权限（本人/本部门/全部） |
| 系统 | 字典、参数、文件上传、操作日志 |
| 项目 | 项目 CRUD、成员、迭代 |
| 任务 | 任务 CRUD、状态流转（待办/进行中/已完成）、指派人、优先级、截止日、看板拖拽（可简化） |
| 缺陷 | 缺陷 CRUD、状态、关联任务 |
| **文档中心** | 分类树、上传（PDF/Word/MD/TXT）、**解析状态与切片数**、发布审核、重建索引、删除级联清理向量 |
| 报表 | 任务完成率、缺陷趋势（ECharts 2 张图足够） |
| 工程 | 统一 `Result<T>`、全局异常、参数校验、分页、逻辑删除、审计字段自动填充、幂等、Flyway、Knife4j、Docker |

### P1（可裁剪）

消息通知、@提醒、附件预览、工时、导出 Excel、操作审计看板。

> 注意：**RAG 不在 P0 的主流程里**。任务、缺陷、文档中心都能独立工作，这才是"一个菜单功能"该有的依赖关系。

---

## 5. RAG 平移方案（核心章节）

### 5.1 总体思路：三层适配

```
现有独立系统                平移动作                    目标形态
─────────────────────────────────────────────────────────────────
独立 Spring Boot 进程   →  代码搬进 module-ai，注册为      同一个 Spring 容器
                          宿主的一个业务模块

X-Admin-Token 鉴权      →  改为登录态 + 菜单/按钮权限      Sa-Token + @SaCheckPermission

独立 6 张表             →  加审计字段与部门字段，迁移      共享同一 MySQL
                          kb_* / ai_* 前缀

Apple 风格单文件 SPA    →  写成 Vue 页面，挂到 layout       左侧菜单里的一页
                          的 <router-view>
```

**三不原则**（省一半工作量）：
- **不改**：`MultiRetriever` / `Reranker` / `QueryRewriteService` / `CitationEnforcer` / `FaithfulnessVerifier` / `PromptGuard` / `DocumentChunker` / `ETLPipeline` / `ModelRouter` / `CircuitBreaker` / 记忆 / 评测脚本 —— 一行不动，只换包名
- **少改**：Controller 层（换鉴权、换返回体）、Mapper（加字段）
- **必须新写**：Vue 页面、菜单权限、文档中心联动、统一响应适配

### 5.2 后端搬迁清单

| 现有文件/包 | 目标位置 | 处理 |
|---|---|---|
| `controller/ChatController` | `module/ai/controller/AiChatController` | 换鉴权、SSE 返回保持 `text/event-stream` |
| `controller/ConversationController` | 同上 | 会话按 `user_id` 过滤 |
| `controller/KnowledgeController` | `module/ai/controller/KnowledgeOpsController` | 加 `@SaCheckPermission("ai:ops")` |
| `controller/DocumentController` | **拆两半**：文档 CRUD 进 `module/doc`；重建索引/反馈进 `module/ai` | 见 5.5 |
| `service/agent/**` | `module/ai/service/agent/**` | 原样 |
| `service/rag/**` | `module/ai/service/rag/**` | 原样 |
| `service/knowledge/**` | `module/ai/service/knowledge/**` | 原样 |
| `service/memory`、`service/intent`、`service/tool` | `module/ai/service/**` | 原样 |
| `etl/**` | `module/doc/etl/**` | 归属文档中心（它服务于文档） |
| `infrastructure/llm/**`（路由/熔断/记账） | **提升到 `common/ai`** | 其他模块（如"任务智能摘要"）也能用 |
| `infrastructure/vectordb`、`cache`、`trace`、`security` | `module/ai/infrastructure/**` | `trace` 提升到 common |
| `mapper`、`model` | 跟随模块 | 加 `create_by/dept_id/update_time` 等 |
| `config/**` | 合并进宿主 `application.yml`，前缀统一 `agent.*` | 保留 key 名，减少改动 |
| `resources/static/index.html` | **删除** | 由 Vue 页面替代 |
| `tools/eval`、`tools/testing` | 保留在仓库根 | 作为回归测试，CI 跑 |

预计：**77 个 Java 文件里约 60 个是纯搬（只改 package 与 import），10 个改鉴权/返回，7 个重写/拆分。**

### 5.3 鉴权与权限

```java
// 旧
@PostMapping("/chat")  // 靠 X-Admin-Token 拦
// 新
@SaCheckPermission("ai:chat:send")
@PostMapping("/ai/chat/stream")
public SseEmitter chat(@RequestBody ChatRequest req) {
    Long userId = StpUtil.getLoginIdAsLong();
    ...
}
```

| 资源 | 权限标识 | 谁能用 |
|---|---|---|
| 智能问答菜单 | `ai:chat` | 全部登录用户 |
| 发送提问 | `ai:chat:send` | 全部登录用户 |
| 会话历史 | `ai:history` | 全部（只能看自己的） |
| 导出对话 | `ai:chat:export` | 管理员 |
| 知识运营菜单 | `ai:ops` | 管理员/知识管理员 |
| 重建索引、删除文档向量 | `ai:kb:manage` | 管理员 |

**数据域**（这一步最容易漏，也最容易出事）：文档按 `dept_id` 可见，检索时必须带过滤：

```java
// 在检索入口统一注入，不给 AI 层"自己查权限"的机会
Set<Long> visibleDeptIds = dataScopeService.visibleDeptIds(userId);
SearchContext ctx = SearchContext.builder().deptIds(visibleDeptIds).build();
```

Milvus `filter` 与 MySQL 通道同时加 `dept_id in (...)`，最后再按 `status = EFFECTIVE` 过滤。**验收用例：A 部门用户检索 B 部门文档必须 0 命中。**

### 5.4 数据层迁移

保留 6 张表结构，改名 + 加列：

| 现有 | 新 | 变更 |
|---|---|---|
| `conversation` | `ai_conversation` | `+ user_id, dept_id, title, create_time, deleted` |
| `message` | `ai_message` | `+ dept_id, citations_json` |
| `document` | `kb_document` | `+ category_id, dept_id, status(DRAFT/EFFECTIVE/FAILED), version, chunk_count, uploader_id, audit_by` |
| `document_chunk` | `kb_chunk` | `+ dept_id`（冗余，过滤用） |
| `knowledge_gap` | `kb_gap` | `+ handled_by, ticket_id(空), status` |
| `knowledge_feedback` | `kb_feedback` | 不变 |

新增一张 `ai_usage`（按 `dept_id + user_id + stat_date` 统计 token），供报表用。

**Milvus 集合不重建**，只把 `dept_id` 加进标量字段并回填（现有 4 份文档，重建也就几分钟，直接重建更省事）。

### 5.5 与「文档中心」打通（这是"平移"变"融合"的关键）

不做这一步，AI 就是个外挂；做了这一步，AI 才是系统的一部分。

```
用户上传 PDF
   → sys_file 落盘       → kb_document(status=PENDING)
   → 发布 DocumentPublishedEvent（Spring Event，@TransactionalEventListener AFTER_COMMIT）
   → 异步 ETL：Tika 解析 → 结构感知切片 → Embedding(64/批) → Milvus + kb_chunk 双写
   → status: PROCESSING → EFFECTIVE（chunk_count 回写）
   → 失败 → FAILED + error_msg，列表页可点"重试"
   → 删除文档 → 事务后级联删 Milvus 分区 + kb_chunk + 缓存失效
```

前端文档列表新增 3 列：**解析状态 / 切片数 / 操作（重建索引）**。这一列状态就是演示时最好的"系统感"。

同时反向：AI 拒答产生的缺口，在「知识运营」里可一键生成 FAQ 草稿 → 审核 → 直接落成一篇 `kb_document`（来源标记 AI），第二天就能被检索到。**闭环很短，但很打动人。**

### 5.6 前端嵌入：三个方案怎么选

| 方案 | 工期 | 体验 | 风险 | 结论 |
|---|---|---|---|---|
| **A. iframe 套现有 index.html** | 1–2 天 | 割裂（滚动条、主题、弹窗层级） | token 要透传、SSE 在 iframe 内正常 | 只做过渡，不建议交付 |
| **B. Vue 组件重写** | 5–8 天 | 与系统一致 | 需重写 UI，但逻辑可复用 | ✅ **推荐** |
| C. qiankun 微前端 | 8–10 天 | 一致 | 过度设计，配置复杂 | 不做 |

选 B，但**别从零写**：现有 `index.html` 里最有价值的是逻辑，不是样式。抽出三个纯 JS 工具直接复用：

- SSE 流式解析（`fetch + ReadableStream`，`__META__` 首包 + `[DONE]` 结束）
- Markdown 渲染 + 代码高亮 + 复制按钮
- `[n]` 引用上标 → 悬浮预览 → 点击跳原文

组件划分：

```
views/ai/
├── AiChatPage.vue            页面壳（三栏：会话列表 / 对话区 / 引用抽屉）
├── components/
│   ├── SessionList.vue       会话历史（新建/重命名/删除）
│   ├── MessageList.vue       消息流（滚动锚定、自动到底）
│   ├── MessageItem.vue       单条消息（用户/AI 分样式）
│   ├── ThinkingPanel.vue     思考过程折叠（ReAct 步骤逐步透出）
│   ├── CitationBadge.vue     [n] 上标 + 悬浮卡片 + 跳转
│   ├── MarkdownView.vue      渲染 + 代码块
│   └── Composer.vue          输入区（Enter 发送、Shift+Enter 换行、停止生成、重新生成）
├── composables/
│   ├── useSseChat.js         SSE 请求与事件分发、中断控制
│   └── useCitation.js        引用解析与定位
├── KnowledgeOpsPage.vue      知识运营（缺口/反馈/草稿审核，表格页）
└── HistoryPage.vue           会话历史（表格页）
```

**样式对齐**：删掉 Apple 风格那套 CSS 变量，改用 Element Plus 的 `--el-*`；暗色模式用 `html.dark` 走 EP 官方方案，不自己维护。

### 5.7 SSE 穿网关（必踩，提前处理）

| 坑 | 处理 |
|---|---|
| Nginx 默认缓冲 → 前端"卡 20 秒然后一次性吐出" | `proxy_buffering off; proxy_cache off; proxy_http_version 1.1;` |
| 网关/网关超时（全链路 ~24s） | `proxy_read_timeout 300s; proxy_send_timeout 300s;` |
| `EventSource` 不能带 header、只能 GET | 用 `fetch` + `ReadableStream` 自己解析（项目里已是这个思路） |
| Spring 返回被统一 `Result<T>` 包装 | SSE 接口**不要**包 `Result`，直接 `SseEmitter` / `Flux<ServerSentEvent>` |
| 断线重连 | 前端指数退避重连 + 后端按 `messageId` 续写 |
| 中文乱码 | `produces = "text/event-stream;charset=UTF-8"` |

### 5.8 Token 记账接到人

现有 `/usage/summary` 是按模型统计。平移后改成按**部门 + 用户 + 日期**统计，挂到报表菜单里出一张"AI 用量"表。注意现有实现里 `UsageContext` 是 ThreadLocal，`@Async` 忠实度校验会丢——项目已修过，搬迁后**必须回归这一条**（异步线程池要装饰 `TaskDecorator` 传递上下文）。

---

## 6. 接口清单

| 接口 | 方法 | 权限 | 说明 |
|---|---|---|---|
| `/api/ai/chat/stream` | POST | `ai:chat:send` | SSE 对话（不包 Result） |
| `/api/ai/conversations` | GET | `ai:history` | 我的会话列表（分页） |
| `/api/ai/conversations/{id}/messages` | GET | `ai:history` | 会话消息 |
| `/api/ai/conversations/{id}` | DELETE | `ai:history` | 删除会话（软删） |
| `/api/ai/messages/{id}/feedback` | POST | `ai:chat:send` | 👍/👎 |
| `/api/ai/usage` | GET | `report:view` | 用量统计 |
| `/api/doc/upload` | POST | `doc:add` | 上传触发 ETL |
| `/api/doc/{id}/reindex` | POST | `ai:kb:manage` | 重建索引 |
| `/api/doc/{id}` | DELETE | `doc:remove` | 级联清理向量 |
| `/api/ai/gaps` | GET | `ai:ops` | 知识缺口 |
| `/api/ai/gaps/{id}/draft` | POST | `ai:ops` | 生成 FAQ 草稿 |
| `/api/ai/drafts/{id}/review` | POST | `ai:ops` | 审核入库 |

所有非 SSE 接口统一返回 `Result<T>{code, msg, data}`，错误码沿用宿主的枚举，AI 特有错误追加一段（如 `AI_UNAVAILABLE`、`AI_QUOTA_EXCEEDED`）。

---

## 7. 数据库表（增量）

```sql
-- 宿主（传统后台标配）
sys_user / sys_dept / sys_role / sys_menu / sys_user_role / sys_role_menu
sys_dict_type / sys_dict_data / sys_config / sys_file
sys_oper_log / sys_login_log

-- 业务
prj_project(id, name, code, owner_id, dept_id, status, start_date, end_date)
prj_iteration(id, project_id, name, start_date, end_date, status)
prj_member(id, project_id, user_id, role)
prj_task(id, project_id, iteration_id, title, content, status, priority,
         assignee_id, creator_id, dept_id, due_date, ...)
prj_defect(id, project_id, task_id, title, severity, status, reporter_id, assignee_id, ...)
doc_category(id, parent_id, name, sort)

-- 迁移 + AI
kb_document / kb_chunk / kb_gap / kb_feedback
ai_conversation / ai_message / ai_usage
```

---

## 8. 十个必踩的坑（提前写进 checklist）

1. **SSE 被网关缓冲** → 只看到一次性输出
2. **SSE 接口被包了 `Result`** → 前端解析全乱
3. **`EventSource` 带不了 token** → 改用 fetch stream
4. **`@Async` 丢 `UsageContext`** → 异步校验的 token 记 0
5. **删文档没删向量** → 幽灵切片继续被检索出来（现有 ETL 有补偿，搬迁要保留）
6. **文档没加部门过滤** → 越权泄露，这是唯一"会出事"的坑
7. **Milvus 启动慢于 app** → compose 加 healthcheck + `depends_on: condition: service_healthy`
8. **上传大文件失败** → `spring.servlet.multipart.max-file-size=100MB` + nginx `client_max_body_size`
9. **菜单权限改了不生效** → 权限缓存在 Redis，改完要清
10. **`utf8mb4` + ngram 索引漏建** → BM25 通道直接失效，召回腰斩

---

## 9. 分期计划（4.5 周，1 人）

| 期 | 内容 | 工期 | DoD |
|---|---|---|---|
| **一期：宿主骨架** | 脚手架 + 登录/RBAC/菜单 + 用户部门角色 + 字典参数 + 日志 + 项目/任务/缺陷基础 CRUD | 1.5 周 | 三种角色登录后菜单不同；任务能建能改能看板 |
| **二期：文档中心 + RAG 后端** | 文档分类/上传/解析状态/审核；`module-ai` 搬迁；鉴权与数据域改造；表迁移；SSE 接口跑通（用 Postman 验） | 1 周 | `curl` 能拿到流式回答；换 token 后 401；越权检索 0 命中 |
| **三期：RAG 前端** | Vue 三个页面；SSE 组件；Markdown + 引用 + 思考面板；会话历史 | 1.5 周 | **点左侧菜单 → 能连续对话 → 引用可点 → 停止/重发正常** |
| **四期：收尾** | 知识运营（缺口/草稿/审核）、用量报表、ECharts 报表、Docker 全栈、52 题回归、README | 0.5 周 | 一键起容器；评测指标不低于现有 |

**顺序不能反**：先把宿主骨架立起来（菜单、权限、布局都对了），RAG 才有地方挂。直接先搬 AI，会卡在"页面挂哪、token 怎么传"上。

---

## 10. 验收清单（12 条，逐条打勾）

**功能**
- [ ] 左侧菜单能看到「智能问答」，点击进入对话页
- [ ] 首问出字是**逐字流式**，不是憋 20 秒一次性出来
- [ ] 答案中的 `[n]` 可点击，能定位到原文切片
- [ ] 思考过程可折叠展开，ReAct 步骤逐步透出
- [ ] 会话可新建/切换/删除，刷新后历史还在
- [ ] 停止生成有效，重新生成有效
- [ ] 管理员能在「知识运营」看到缺口，一键生成草稿并审核入库

**权限与安全**
- [ ] 未登录访问 AI 接口返回 401
- [ ] 普通用户看不到「知识运营」菜单
- [ ] A 部门用户检索 B 部门文档 → 0 命中，走拒答

**联动与工程**
- [ ] 文档中心上传 PDF → 列表显示"解析中 → 已生效 + 切片数"，随后即可被问答命中
- [ ] 删除文档后，再问相关内容不再命中（无幽灵切片）
- [ ] 关掉 LLM Key：AI 页提示"服务不可用"，**其余功能全部正常**
- [ ] `docker compose up -d` 一键起全栈，`/actuator/health` 全绿；52 题评测指标不低于现状

---

## 11. 不做什么

- ❌ 微服务拆分、注册中心、网关（单体足以，为拆而拆是减分项）
- ❌ iframe 当最终交付（只作过渡）
- ❌ 重写 RAG 核心（检索/重排/引用/治理一行不动）
- ❌ 自研权限框架、自研富文本（用 Sa-Token + Element Plus）
- ❌ 微前端、SSR、多租户
- ❌ 给除"文档中心"以外的模块也接 AI（先做一个入口，别铺开）

---

## 12. 一句话陈述（简历/答辩可用）

> 在自研的研发管理系统（Spring Boot 3 + MyBatis-Plus + Vue3 + Element Plus，含 RBAC、部门数据权限、项目/任务/缺陷、文档中心）中，将独立的 RAG 问答系统**模块化平移**为左侧菜单的一项功能：后端以 `module-ai` 接入统一登录与菜单权限，检索链路按部门数据域过滤，SSE 直连前端；文档中心发布文档后经事件驱动进入 ETL 管道（Tika 解析 → 结构感知切片 → 向量化 → 双写），解析状态与切片数回显在列表页；问答侧完整保留两阶段混合检索、`[n]` 条款级引用、CRAG 拒答与忠实度校验，并支持大模型不可用时业务零影响。

---

## 附：立刻能动手的第一步

1. 定脚手架（RuoYi-Vue3 裁剪 / 或 Spring Boot + Element Plus 模板），跑通登录 + 菜单
2. 建 `module-ai` 空模块，pom 依赖加进去，先搬一个 `MultiRetriever` 验证能编译运行
3. 建 `V2__ai_module.sql`：6 张表改名加列 + `ai_usage`
4. 用 Postman 打通 `/api/ai/chat/stream`（先不管前端）
5. 再动手写 `AiChatPage.vue`

先让接口在新宿主里跑通，再谈页面。这样每一步都是可验证的，不会卡在"整体跑不起来"。


---

## 13. 平移执行状态（2026-10-18 更新）

**宿主骨架 + RAG 平移首期已落地**（鉴权按指定选型 Spring Security + JWT，未用 Sa-Token）：

| 项 | 状态 | 落点 |
|---|---|---|
| 宿主骨架（DevHub） | ✅ | `D:\kaifa\javaproject\devhub`（Spring Boot 3.5 单体，com.devhub.* ） |
| Spring Security + JWT | ✅ | `com.devhub.security`：JwtTokenProvider(HS256)/JwtAuthenticationFilter/DbUserDetailsService/SecurityConfig/AuthController，无状态 + 方法级 @PreAuthorize |
| RBAC 种子 | ✅ | Flyway V1：sys_user/role/menu/user_role/role_menu；admin(ADMIN)/dev01(USER)；菜单树=方案 2.1 的 8+3；V2 补 ai:kb:manage 权限点 |
| RAG 代码平移 | ✅ | 74 个 Java 文件 `com.agent.platform→com.devhub.module.ai`（脚本化包名重写，检索/重排/防幻觉/注入防护一行未动） |
| 旧鉴权移除 | ✅ | X-Admin-Token 拦截器不迁移；401（未登录）/403（无权限）语义由 Security 接管 |
| 会话数据域 | ✅ | conversation 表 +user_id；列表按当前登录人过滤；编排器写入归属（实测 admin 1 条 / dev01 0 条） |
| 部署 | ✅ | compose 新增 devhub-app（8081，独立 devhub 库，复用 MySQL/Redis/Milvus 容器） |
| 前端 | ✅ | 登录遮罩（账密→JWT→localStorage）/api+SSE 统一带 Bearer/骨架菜单（8 一级+AI 子项）/用户徽标+退出 |
| 知识库迁移 | ✅ | 手册+Arthas 重传 devhub 库，问答命中带出处 |

**四项验收实测**：未登录 401 ✓；admin 登录换 JWT、业务接口 200 ✓；dev01 访问知识运营 403（GlobalExceptionHandler 补 AccessDeniedException 映射）✓；会话跨用户隔离 ✓。

**与方案的差异（骨架阶段刻意简化）**：
1. AI 表沿用原名（document/conversation/…）未改 kb_*/ai_* 前缀——独立 devhub 库内无冲突，正式版再改名
2. 口令 {noop} 明文种子（DelegatingPasswordEncoder 兼容），正式版换 {bcrypt}
3. 保留单文件前端而非 Vue3——登录/菜单/JWT 已接，Vue 重写（方案 5.6 方案 B）为下一步
4. 文档中心联动（DocumentPublishedEvent 事件驱动 ETL）、部门数据域、ai_usage 报表为下一步


## 14. 宿主业务完成状态（2026-10-18 第二批）

**方案 P0 宿主业务全部落地**（借鉴 RuoYi 模式：@Log 注解+AOP 操作日志、字典缓存、部门树）：

| 模块 | 内容 | 关键接口 |
|---|---|---|
| 项目管理 | CRUD+成员（自动 OWNER）+迭代 | /api/projects (+/members /iterations) |
| 任务中心 | CRUD+状态机(TODO→DOING→DONE 可回退)+我的任务+看板分组 | /api/tasks (/{id}/advance) |
| 缺陷跟踪 | CRUD+严重级+状态(OPEN→FIXED→CLOSED) | /api/defects |
| 文档中心 | 分类 CRUD+治理列表(解析状态/切片数/审核)+审核发布+分类调整 | /api/doc/**，上传复用 /api/v1/documents/upload?categoryId |
| 工作台/报表 | 计数卡(项目/完成率/缺陷/AI概览)+7日缺陷趋势 | /api/report/dashboard |
| 系统管理 | 部门树/字典/操作日志/登录日志/用户管理(建/删/重置密码)/改密 | /api/system/** |
| 工程化 | @OperLog AOP 全写接口埋日志；登录埋登录日志；MyBatis-Plus 分页 | com.devhub.common.OperLogAspect |

**全链路烟雾实测**：建项目(1)→加成员→建迭代→建任务×3→状态机推进两次(TODO→DONE)→提缺陷→工作台(完成率33.3%/AI文档2)→操作日志9条(AOP记录推进状态/提缺陷)→登录日志→文档中心上传→待审核→发布(PUBLISHED)→dev01 越权日志 403/任务 200/myTodo=2→RAG 回归正常。

**前端**：单文件升级为完整多页应用——哈希路由（菜单点击切页/AI 页保留）、通用 CRUD 表格引擎（过滤器/分页/编辑弹窗/行操作，一套配置驱动四张业务表）、工作台（统计卡+CSS 柱状趋势图）、文档中心（分类筛选/审核流/上传带分类）、系统管理页（用户表/部门树/字典/双日志/改密）。

**过程坑（3 个）**：①迁移 Document 实体含 file_path/user_id 列而 V1 建表遗漏→V4/V5 补列；②document 主键是 32 位 hex 字符串，文档中心接口误用 Long→改 String；③Windows 下 jar 单文件挂载在 mvn 重建后失效→compose force-recreate 容器重挂载。

**剩余（方案 P1）**：Vue3 重写外壳(5.6-B)、文档中心事件驱动异步 ETL(5.5)、部门数据域(5.3)、bcrypt 口令、知识运营页独立化。


## 15. P1 安全与异步落地（2026-10-18 第三批）

| 项 | 状态 | 说明 |
|---|---|---|
| 异步 ETL（5.5） | ✅ | AsyncIngestService：上传秒回（102ms）→ PROCESSING 外壳行 → 事件驱动后台 Tika/切片/向量化 → completed+chunk_count 回写；前端列表轮询看「解析中→已生效」 |
| 部门数据域（5.3） | ✅ | 白名单按 scope 缓存：ADMIN=全部 / USER=本部门+公共(NULL)；三通道统一过滤，AI 层不自查权限 |
| **缓存跨域泄漏（新发现并修复）** | ✅ | 语义答案缓存与检索结果缓存原为全局共享——B 部门用户可命中 A 部门用户的缓存答案（实测复现）。修复：两缓存 key/条目均带 scope |
| bcrypt 口令 | ✅ | V6 种子 {noop}→{bcrypt}，DelegatingPasswordEncoder 兼容 |

**数据域验收实测**：admin 命中部门1专属词（引用出处）/ dev01 查同词 sources 无部门1文档且答案零泄露（拒答）/ dev01 查公共文档正常——三向通过。

**坑（2）**：①中文 LIKE 经 docker cp 进 mysql 会被编码破坏（V7 改 ASCII 模式匹配）；②LLM 重排对一句话合成文档排序不稳（已知特性，非权限问题——DB 层 BM25 命中已验证）。


## 16. AI 子页面补齐（2026-10-18 第四批）

方案 2.1 菜单树中「智能问答」的三个子页面全部就位（单文件前端内实现，Vue3 重写仍留作 P2）：

| 页面 | 路由 | 内容 | 权限 |
|---|---|---|---|
| 对话问答 | /ai/chat | 既有聊天页（默认） | 全部 |
| **会话历史** | /ai/history | 我的会话表格（轮次/时间）→ 查看消息详情（含模型与忠实度分数）→ 删除 | 全部（仅本人数据） |
| **知识运营** | /ai/ops | 知识缺口列表（一键生成 AI 草稿）→ 待审核文档（AI 草稿/冲突，采纳/拒绝）→ 已停用文档 | ai:ops（普通用户 403） |

实测：admin 见 2 个开放缺口、dev01 见 3 条自己的会话、dev01 访问运营接口 403——三向通过。

**至此方案 P0+P1 全部落地**。剩余 P2：Vue3 外壳重写（需 Node 构建环境）、用量报表（ai_usage 按人统计）、任务看板拖拽页。


## 17. P2 完成状态（2026-10-18 第五批）

| 项 | 状态 | 说明 |
|---|---|---|
| AI 用量报表（5.8） | ✅ | 每次问答 token 按（日,用户,模型）幂等累加进 ai_usage（V8 唯一键 + ON DUPLICATE KEY）；GET /api/report/ai-usage；前端 /report 页（表格+占比条） |
| 任务看板拖拽页 | ✅ | /task 列表页切换"看板视图"：三列（待办/进行/完成）HTML5 拖拽换状态，drop 调 PUT /api/tasks/{id}（后端状态机校验兜底） |
| Vue3 外壳重写 | ⏸ 未做 | 本机 Node v24 可用；按方案 5.6-B 估期 5-8 天，需整段独立排期。当前单文件前端功能完整（登录/8 菜单/CRUD 引擎/看板/报表/文档中心/知识运营），作为正式交付可用 |

**至此方案 P0+P1+P2（除 Vue3 重写）全部完成。** 坑：mvn -q 静默失败时 grep -c 不输出计数——构建校验必须显式看 BUILD SUCCESS（本轮 StatsController 缺 RequestParam import 静默漏过一次，靠"jar 内 class 字节检查"定位）。


## 18. Vue3 前后端分离落地（2026-10-18 第六批，方案 5.6-B）

| 项 | 落点 |
|---|---|
| 工程 | `D:\kaifa\javaproject\devhub-web`（Vite + Vue3 + Element Plus + Pinia + Vue Router，Node 24，npmmirror 源） |
| 布局 | 登录页 + Layout：el-menu 层级菜单（按 sys_menu 树渲染）+ 可折叠侧栏 + 暗色主题（EP 官方 css-vars）+ 用户下拉 |
| 业务页 | 工作台（统计卡+趋势）/ 项目（CRUD+迭代弹窗+成员弹窗）/ 任务（列表+看板拖拽+状态机按钮）/ 缺陷 / 文档中心（上传→轮询解析→审核→重建索引→分类）/ 报表（AI 用量）/ 系统管理（用户/角色权限勾选/部门树/字典/操作日志/改密） |
| AI 页 | 三栏聊天（会话列表/流式对话/来源标签+原文抽屉）· marked+DOMPurify+highlight.js · fetch+ReadableStream SSE 带中止 · 引用来源标签点击开抽屉 / 会话历史（含忠实度）/ 知识运营（缺口→草稿→审核） |
| 部署 | 多阶段 Dockerfile（node build → nginx:alpine），nginx 按 5.7 配置：`/api/v1/chat/stream` 单独 location 禁缓冲+300s 超时；compose 服务 devhub-web @ **8082** |

**实测**：8082 登录→dashboard 200；SSE 流式分片逐条到达（45 个 data 事件，首包 `__META__`）——nginx 未缓冲。单文件版（8081）保留并存。


## 19. P0 功能深化落地（2026-10-18 第七批，feature-deepening-plan P0 全部完成）

| 项 | 交付 |
|---|---|
| 时间线与评论（P0-1） | prj_comment/prj_activity 表（V9）；CollabController（timeline 合并排序/评论含@提及）；任务/缺陷创建、状态流转、指派、优先级变更全量记录字段 diff；前端任务/缺陷详情抽屉（时间线/关联缺陷/评论三 Tab） |
| 缺陷关联（P0-2） | /defects/by-task/{taskId} 双向列表；/defects/iteration/{id}/summary 清点（新增/解决/遗留/按严重度）；迭代弹窗内联展示 |
| 迭代周期化（P0-3） | prj_burndown 快照表 + 每日 23:30 定时任务；/report/iterations/{id}/burndown（快照缺失时现场统计兜底）+ 近 5 迭代速率；前端燃尽柱状图 |
| 效能报表（P0-4） | /report/flow：cycle time P50/P90（分钟精度）、周吞吐量、14 天累积流图（CFD 三色堆叠）、当前 WIP；前端 /metrics 页 |

**全链路实测**：建项目→迭代→任务→评论→两次推进→时间线 5 条（create/comment×2/status×2）✓ 关联缺陷 1 条 ✓ 迭代清点 total=1/SERIOUS=1 ✓ 燃尽图（1 任务已完成）✓ 效能（done=1/CFD 14 点/WIP=0）✓。坑：秒级完成任务 cycle time 算出负小时 → 改分钟精度 + max(0,…)。


## 20. P1/P2 功能深化落地（2026-10-18 第八批，feature-deepening-plan 全部完成）

| 项 | 交付 |
|---|---|
| 对象级权限（P1-6） | @ProjectScoped 注解 + ProjectScopeAspect：ADMIN 全通；OWNER/DEV/QA 成员按角色；非成员 403"非项目成员"。任务/缺陷的更新/删除接口已接入 |
| 通知中心（P1-7） | sys_notification 表（V10）+ NotificationService + /api/notifications（列表/未读数/已读）；**四类事件**：任务指派、缺陷转派、缺陷关闭（通知报告人）、评论 @提及（正则解析） |
| 相似缺陷检索（P2-8） | /ai-assist/defects/{id}/similar：ngram 全文相似（V11 补 ft_title 索引），返回 Top3；实测"crash when saving dialog file"→Top1 命中同类缺陷 |
| 缺陷知识沉淀（P2-9） | 缺陷 CLOSED 时自动将现象/描述/严重级写入 ETL（draft 状态，降权待审）——知识生产闭环从"拒答补知识"扩展到"交付自动沉淀" |
| 周报生成器（P2-10） | /ai-assist/weekly-report：聚合本周完成任务/进行中/提交缺陷/AI 问答记录 → LLM 生成 Markdown 周报（含本周完成/下周计划/风险段落） |

**验收实测**：非成员 outsider 改任务 403 ✓ / dev01 非该项目成员同样 403 ✓ / 指派通知 dev01 未读=1 ✓ / @提及通知 outsider 收到 mention ✓ / 相似缺陷 Top3 命中 ✓ / 缺陷关闭自动沉淀 draft 文档 ✓ / 周报 Markdown 生成 ✓。

**坑（3）**：①heredoc 写含中文转义的 Java 字符串必断——改 Edit 工具或字符串拼接；②相似缺陷首跑 500——prj_defect 无 ngram 索引（V11 补）；③测试脚本中文 JSON 的 GBK 坑在验收脚本里复发三次——最终全部改 python urllib 内置 json 编码。

**至此 feature-deepening-plan（P0+P1+P2 除工作流引擎外）全部落地。** 工作流引擎（P1-5）单独排期。


## 21. 自定义工作流引擎落地（2026-10-18 第九批，P1-5——深化方案最后一项完成）

**元数据驱动设计**（V12）：`prj_workflow_state`（项目级状态：name/category 分类/wip_limit/is_default_start）+ `prj_workflow_transition`（允许流转规则）；project_id=0 为默认模板（TODO→DOING→DONE 双向），存量项目 V12 自动套模板、新建项目 `initDefault()` 自动初始化。

**WorkflowEngine 服务**：statesOf/transitionsOf/defaultStartState/validateState/validateTransition/nextForwardState/nextBackState（sort 相对大小+并列取后定义者）。**TaskController 硬编码三态完全移除**：create 走 defaultStartState、update/PUT 走 validateState+validateTransition+WIP 拦截、advance 走 nextForward/Back+目标 WIP。

**WorkflowController**：状态 CRUD（category 白名单/唯一默认初始状态）+ 规则 CRUD + `GET /api/projects/{id}/workflow`（状态+规则全量）。

**验收实测**：①新项目自动 3 状态 4 规则 ✓ ②自定义 UAT 状态（category=DOING, wip=1）+ DOING→UAT→DONE 规则 ✓ ③流转链 **DOING→UAT→DONE** 走自定义路径 ✓ ④非法状态 BOGUS 400（提示允许列表）✓ ⑤TODO 直改 DONE 无规则 400 ✓ ⑥**WIP=1 拦截**：第 1 个进 UAT 过、第 2 个 400"WIP 限制" ✓ ⑦存量项目 V12 种子兼容 ✓

**坑（2）**：①python 字符串切片替换把整个文件头切掉（s.find 切片法必须配合完整重写）——文件重建；②sort 并列（UAT 与 DONE 都 sort=3）时 forward 选错——tie-break 取后定义者。

**至此 feature-deepening-plan P0+P1+P2 全部完成（含工作流引擎）。**

