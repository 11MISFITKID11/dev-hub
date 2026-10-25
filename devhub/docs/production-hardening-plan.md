# DevHub 优化迭代方案（生产化加固）

> 2026-10-25 基于全项目深度评测产出。与 `feature-deepening-plan.md`（功能深化）互补：本文聚焦**从"内部可用"到"生产可上线"的加固项**，按 P0/P1/P2 三档排期，每项含改动点、涉及文件与验收标准。
>
> 评测结论回顾：核心 RAG 管线（两阶段检索 + CRAG 门控 + 引用/忠实度校验）、工单状态机（CAS 防并发 + SLA 双时钟 + 异步质检）、JWT 认证、前端 15 页面全真实对接均为生产级实现；短板集中在**安全默认值、可观测性、细粒度权限、Agent 工具层真实性、测试覆盖**五处。

---

## 一、短板地图

| 维度 | 现状 | 风险 | 档位 |
|---|---|---|---|
| 安全配置 | JWT 密钥/MySQL 账密有硬编码默认值 | 默认密钥泄露 = 全系统沦陷 | P0 |
| Prompt 防护 | `PromptGuard` 纯正则模式库 | 变体注入绕过率高 | P1 |
| 可观测性 | `TraceService` 内存 ConcurrentHashMap | 重启丢失、多实例失效、无法排障 | P1 |
| 权限粒度 | 前端菜单级 RBAC + 后端接口 403 | 无按钮/数据级权限，越权风险 | P1 |
| Agent 工具层 | `SearchTool` 模拟结果、订单查 mock 表 | 对外演示可信度、业务闭环断点 | P2 |
| 意图识别 | 纯规则 + 默认兜底 | 复杂问法误分流 | P2 |
| 测试 | 10 个单测文件，无集成/Controller 测试 | 回归靠手工 + loop_test.py | P1 |
| 数据导入 | seed 工单靠手工 SQL | 演示环境不可复现 | P2 |
| 仓库卫生 | gui-test-screenshots 混有 tmp 文件 | 仓库体积、观感 | P2 |

---

## 二、P0：安全加固（≈1 天，上线阻断项）

### 1. 消灭危险默认值
- `application.yml:4` JWT 密钥：去掉 `devhub-skeleton-secret...` 默认值，改为 `${DEVHUB_JWT_SECRET}` **无默认**——缺失时启动即失败（fail-fast），而非静默用弱密钥
- `application.yml:42-43` MySQL、`:29` Redis 同理：生产 profile（`application-prod.yml`）中全部无默认值；dev profile 保留 root/root 便于本地
- 新增启动自检：`@PostConstruct` 校验密钥长度 ≥ 32 字节且不等于历史默认值，否则抛异常拒绝启动
- 提供 `.env.example` 模板 + `start-dev.sh`/`start-dev.bat` 读取说明

**验收**：不设环境变量时 `mvn spring-boot:run -Dspring-boot.run.profiles=prod` 启动失败并打印明确错误；dev 环境行为不变。

### 2. 密钥轮换与 Token 失效
- JWT 增加 `kid` 头支持双密钥并行轮换（新旧密钥各验一次）
- 提供 `POST /api/v1/auth/rotate-secret`（仅 admin）与强制全员下线开关（Redis 记 token 版本号，`JwtTokenProvider` 校验时比对）

**验收**：轮换后旧 token 在宽限期（默认 10 分钟）内仍可用，宽限期后 401。

---

## 三、P1：结构升级（每项 2-4 天）

### 3. Trace 持久化 + 可观测性（≈3 天）
- `TraceService.java:29` 内存 Map → 落库：新表 `ai_trace(id, trace_id, span_id, parent_id, session_id, stage, model, input_tokens, output_tokens, latency_ms, status, error_msg, create_time)`，异步批量写（复用现有线程池模式，参考 `TicketService.java:224-231` 事务后异步写法）
- 保留 MDC 注入；日志 pattern 加 `%X{traceId}`
- Actuator 已引入：补 Prometheus 端点 + 三个核心指标（LLM 调用延迟直方图、RAG 检索命中率、工单 SLA 超时计数）
- Ops 页面（`devhub-web/src/views/ai/Ops.vue`）加 trace 查询：按 session/trace_id 展开调用树

**验收**：重启后历史 trace 可查；两个实例并行时 trace 不串不丢；Grafana 可见 LLM P95 延迟。

### 4. 细粒度权限（≈3 天）
- 后端：新增 `@RequirePerm("ticket:transfer")` 注解 + AOP 拦截，权限点挂到现有角色-菜单表（新增 `sys_role_perm`），沿用 `ProjectScopeAspect` 的切面模式
- 工单/会话接口先覆盖（转派、结单、质检、删除属主校验已有，收编进统一权限点）
- 前端：`stores/user.js` 登录返回增加 `perms[]`，封装 `v-perm` 指令控制按钮显隐（后端 403 仍是最终防线）

**验收**：坐席角色调用质检接口返回 403；前端对应按钮隐藏；admin 不受影响。

### 5. 集成测试层（≈2 天）
- 引入 Testcontainers（MySQL + Redis；Milvus 用现有 mock/内存实现隔离）
- 优先覆盖三条黄金链路：
  1. 登录 → 会话 SSE 流式问答 → 拒答 → 转工单（把 `loop_test.py` 的 12 断言 Java 化，纳入 CI）
  2. 工单状态机全流转 + SLA 暂停/超时（已有单测，升级为带 DB 的集成版）
  3. 知识库上传 → ETL → 检索命中
- Maven profile `it` 隔离，`mvn verify -Pit` 触发

**验收**：CI 上 `mvn verify -Pit` 全绿；loop_test.py 保留作冒烟但不再是唯一回归手段。

### 6. PromptGuard 升级（≈2 天）
- `PromptGuard.java:31-51` 正则层保留为第一道（快、零成本）
- 第二道：可疑样本（正则命中边缘/高熵输入）送小模型分类（glm-4-flash，走现有 `ModelRouter` 降级链），判定结果缓存
- 拦截事件落 `ai_guard_log` 表，Ops 页面可审核误杀/漏杀并回流正则库

**验收**：对常见变体注入集（大小写混淆、编码绕过、角色扮演）拦截率较纯正则提升，且正常问题误杀率 < 1%。

---

## 四、P2：打磨项（每项 0.5-2 天）

### 7. Agent 工具层接真实数据（≈2 天）
- `SearchTool.java:59`：模拟结果 → 调本项目 RAG 检索（内部复用 `MultiRetriever`），实现"站内知识搜索"真实化
- `OrderQueryTool`：mock_order 表 → 抽象 `OrderDataProvider` 接口，默认实现仍读 mock 表（演示用），预留真实 API 实现位；工具描述中标注数据源

### 8. 意图识别混合化（≈1.5 天）
- `IntentRecognizer.java:43-101` 规则命中即返回（快路径保留）
- 规则未命中/低置信 → LLM 分类兜底（glm-4-flash + 结构化输出），结果缓存进 Redis（同问 24h 不重复调）
- 分类样本落库，定期回流规则库

### 9. seed 数据工程化（≈1 天）
- `scripts/seed_tickets.py` 产出的手工 SQL → Flyway repeatable migration（`R__demo_data.sql`）或独立 `demo` profile 的 CommandLineRunner，一键重置演示环境
- 3 个客户演示账号纳入同一机制

### 10. 仓库卫生（≈0.5 天）
- 清理 `gui-test-screenshots/` 下 tmp_call 临时文件；截图目录移出仓库或入 `.gitignore` + LFS
- `devhub/backend-dev.log`、`devhub/build`、`target/` 确认全部在 `.gitignore` 内

---

## 五、排期建议（单人节奏）

| 周 | 内容 | 里程碑 |
|---|---|---|
| W1 | P0（1、2）+ P2-10 卫生 | **可安全部署**：无默认密钥、token 可轮换 |
| W2 | P1-3 Trace + P1-4 权限 | **可运维**：调用链可查、越权可控 |
| W3 | P1-5 集成测试 + P1-6 PromptGuard | **可回归**：CI 全绿、防注入达标 |
| W4 | P2-7/8/9 | **可演示**：工具真实化、一键 seed |

依赖关系：4（权限）依赖现有角色表无阻塞；5（集成测试）建议在 3（Trace 落库）之后做，便于断言 trace 记录；其余可并行。

---

## 六、明确不做（本期边界）

- 完整 DORA 指标 / CI-CD 数据源接入（无部署流水线，见 feature-deepening-plan）
- 分布式追踪全家桶（SkyWalking/OTel Collector）：先落库 + MDC，多实例规模上来再换
- 前端按钮权限的全量覆盖：先工单/会话两个高危模块，其余模块随迭代补齐
