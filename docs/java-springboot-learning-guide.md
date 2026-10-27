# Java / Spring Boot 学习指导 —— 以 HelpHub 项目为教材

> 使用方法：本项目 8 个业务模块（ai/ticket/doc/report/system + 3 个旧模块）覆盖了 Java 后端面试的绝大多数考点。
> 每个知识点按「是什么 → 在项目哪里 → 面试怎么讲」三段式学习：**先读概念，再打开对应文件读真实代码，最后合上文档自己复述**。
> 学完标准：能对每个知识点回答"它在项目里解决什么问题、怎么实现的、有什么坑"。

## 学习路线总览（建议顺序）

| 阶段 | 主题 | 对应面试区 |
|---|---|---|
| 1 | Java 语言核心（21 特性） | Java 基础 |
| 2 | Spring IoC / Bean 生命周期 | Spring 原理 |
| 3 | 配置体系 | Spring Boot |
| 4 | Web 层（REST/SSE/异常/过滤器） | Web 开发 |
| 5 | 数据访问（MyBatis-Plus/Flyway/JDBC） | ORM/数据库 |
| 6 | Redis 六种用法 | 缓存/中间件 |
| 7 | 安全（JWT/RBAC/AOP 授权） | 安全 |
| 8 | AOP / 异步 / 定时 / 事件 | Spring 进阶 |
| 9 | 可观测性（Actuator/Micrometer/Trace） | 生产运维 |
| 10 | AI 工程层（LangChain4j/Agent/RAG/MCP） | 项目亮点 |
| 11 | 架构分层与设计模式 | 系统设计 |
| 12 | 测试体系 | 工程质量 |
| 13 | 构建部署与启动脚本 | 运维 |

---

## 阶段 1：Java 语言核心

| 知识点 | 项目落点 | 面试讲法要点 |
|---|---|---|
| record 不可变数据类 | `BaseTool.ToolResult`、`QueryRewriteService.Rewrites`、`MultiRetriever.RetrievalResult`（配合 `@Builder(toBuilder=true)`） | 替代只有字段的类；自动生成 equals/hashCode/toString；toBuilder 实现"改一个字段复制新对象"的不可变更新 |
| 密封/模式匹配 switch | `OrderQueryTool.statusCn()` 的 `switch (s) { case "PAID" -> ... }`；`AgentOrchestrator` 的 `switch (mode) { case REACT -> ... }` | switch 表达式有返回值、箭头语法无穿透；比 if-else 链可读且编译器可穷举检查 |
| 文本块 | `ReActAgent.REACT_SYSTEM_PROMPT`（""" 多行提示词） | 三引号保留换行缩进，写多行 SQL/提示词不用转义 |
| Stream API | 全项目集合处理；典型：`MultiRetriever.rrfFuse()` 的 `entrySet().stream().sorted(...).map(...).toList()` | 讲清惰性求值与终端操作；`.toList()`（16+）返回不可变列表 vs `Collectors.toList()` |
| Optional | `ToolRegistry.getTool()` 返回 `Optional<BaseTool>`，调用方 `map/orElseGet` | 防 null 语义显式化；别用于字段和参数 |
| 泛型 | `RedisCacheService.get(key, Class<T>)`；`@SuppressWarnings("unchecked")` 处理 Jackson 泛型擦除 | 泛型擦除的实际后果：Redis 反序列化拿回的是 LinkedHashMap，必须 `objectMapper.convertValue` 回强类型——`MultiRetriever.readResultCache()` 就是踩坑后的修复 |
| ConcurrentHashMap | `ToolRegistry.registry`、`McpToolService.clients/toolIndex`、`MultiRetriever.scopeCaches` | 讲 putIfAbsent/merge/computeIfAbsent 原子性；什么时候用 CHM 而不是 Hashtable/synchronized |
| ThreadLocal | `MultiRetriever.SCOPE_OVERRIDE`（数据域透传）、`UsageContext`（token 记账快照） | 线程级上下文；**必须配对 remove**；本项目的真实教训：SecurityContext 在 @Async/reactor 线程不传播，见阶段 7 |
| 异常体系 | 业务异常 `BusinessException` + 全局处理 `GlobalExceptionHandler` | 受检 vs 非受检；全局兜底让业务代码不写 try-catch |
| Builder 模式 | Lombok `@Builder`（`EvalRun.builder()...build()`） | 解决多参数构造的可读性；`@Builder(toBuilder=true)` 支持拷贝式修改 |
| 枚举 | `AgentMode`（DIRECT/REACT/PLANNER/REFLECTION）、`MessageRole` | 用枚举做状态机/路由标签，switch 穷举 |
| volatile | `ChatModelRegistry.primaryName` 等字段 | 可见性 vs 原子性；配合 synchronized rebuild 实现"配置热切换" |
| java.net.http.HttpClient | `Reranker.zhipuRerank()` 手写 HTTP 调智谱 API | JDK11+ 原生客户端，链式构建 Request/同步 send；什么时候不用 RestTemplate |

**练手任务**：打开 `ReActAgent.java`，找出其中 5 种以上 Java 语法特性并解释为什么用它。

---

## 阶段 2：Spring IoC 与 Bean 生命周期

| 知识点 | 项目落点 | 面试讲法要点 |
|---|---|---|
| 三层注解 | `@RestController`（controller/）、`@Service`（service/）、`@Component`（基础设施如 TraceService） | 语义分层，本质都是 Bean 注册 |
| 构造器注入 | 全项目统一 `@RequiredArgsConstructor` + `private final` 字段 | 为什么推荐构造器注入（不可变、依赖显式、单测好 new）——`ToolRouterMcpTest` 直接 `new ToolRouter(registry, mapper, mock)` 就是证明 |
| 循环依赖与 @Lazy | `IntentRecognizer` 构造器 `@Lazy ModelRouter`；`PromptGuard` 的 `@Lazy` | Spring 三级缓存解决 setter 注入环；构造器环会启动失败——@Lazy 打断：先注入代理，首次调用才取目标 |
| @PostConstruct / @PreDestroy | `ToolRegistry.init()` 自动注册全部工具；`McpToolService.init()` 连接 MCP、`shutdownClients()` 关连接 | Bean 初始化后/销毁前钩子；MCP 连接生命周期管理的完整范本 |
| @ConfigurationProperties | `LlmProperties`（agent.llm 前缀）、`McpProperties`（agent.mcp） | 类型安全配置 vs 散装 @Value；配套 `@EnableConfigurationProperties(X.class)`（挂在 ChatModelRegistry/McpToolService 上）；嵌套 List/Map 绑定 |
| @Value 与默认值 | `@Value("${agent.rag.top-k:5}")` | 占位符 + 默认值语法；环境变量覆盖（LLM_PRIMARY→${LLM_PRIMARY:glm-4-flash}） |
| @Profile | `DemoDataRunner` 的 `@Profile("demo")` | 按环境装配；demo profile 才灌演示数据 |
| Bean 作用域 | 默认全部单例；`ChatModelRegistry` 用"可变配置 + synchronized rebuild"替代多例 | 无状态单例为什么线程安全（无共享可变状态 or 并发容器） |
| 条件装配 | `ChatModelRegistry.rebuild()` 中"api-key 为空则跳过注册"是**代码级**条件装配（对比 @ConditionalOnProperty 讲原理） | 讲清 Spring Boot 自动配置的 @Conditional 家族，再讲项目为什么选运行时校验（密钥来自环境变量/DB，编译期不可知） |

**面试高频题**："构造器注入和字段注入的区别？" 用 `ToolRouter` 举例回答。

---

## 阶段 3：配置体系

- **application.yml 分层**：`agent.*` 是本项目全部自定义配置域（orchestrator/memory/rag/security/ratelimit/mcp/llm），读一遍这个文件等于读完系统的可调参数清单。
- **环境变量覆盖链**：`agent.mcp.enabled: ${AGENT_MCP_ENABLED:false}`——本地默认关，`AGENT_MCP_ENABLED=true` 开。密钥全部走 `${OPENAI_API_KEY:}` 引用 `D:\docker\middleware\.env`（**密钥永不入库**，这是面试加分点）。
- **配置优先级坑（真实案例）**：`LLM_PRIMARY` 环境变量只决定**初始**主模型，运行时主模型以 `ai_model_config` 表 `is_primary` 为准（`ModelConfigService` 启动时 DB 覆盖）——"环境变量 < 数据库动态配置"的运行时配置分层。
- **多环境**：application-prod.yml 无默认密钥 + `SecurityStartupCheck` 弱密钥拒启（fail-fast）。

---

## 阶段 4：Web 层

| 知识点 | 项目落点 | 要点 |
|---|---|---|
| RESTful 注解 | 全部 controller：`@RequestMapping("/api/v1/xxx")` + `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping`、`@PathVariable`、`@RequestParam(defaultValue=)`、`@RequestBody` | 资源化 URL 设计；分页 `page/size` 参数默认值 |
| 统一响应体 | `Result<T>`（code/message/data）+ 前端 axios 拦截器按 code 解包 | 为什么统一包装（前端一致性）；泛型保留类型 |
| 全局异常处理 | `GlobalExceptionHandler` @RestControllerAdvice | @ExceptionHandler 兜底 BusinessException 与未知 Exception；业务代码零 try-catch |
| SSE 流式输出 | `ChatController` 流式接口（Flux/text-event-stream）+ `NotifySseHub`（坐席通知） | SSE vs WebSocket（单向/低开销/自动重连）；流式 AI 回答的标准做法；心跳与 `[DONE]` 终止帧 |
| Servlet Filter | `ChatRateLimitFilter`（登录限流）、`JwtAuthenticationFilter`（OncePerRequestFilter） | 过滤器链顺序；OncePerRequestFilter 防异步分发重复执行（项目里 ASYNC dispatch permitAll 配套） |
| 异步请求分发 | SecurityConfig 中 `dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` | SSE/异步 servlet 的二次分发不再走认证（有 token 了） |
| 跨域与前端代理 | vite proxy `/api → 8080` | 开发期代理避免 CORS；联调报 ECONNREFUSED = 后端没起（本文档阶段 13 有完整排查） |

---

## 阶段 5：数据访问

| 知识点 | 项目落点 | 要点 |
|---|---|---|
| MyBatis-Plus BaseMapper | 全部 mapper 继承 BaseMapper，单表 CRUD 零 SQL | 对比 MyBatis：通用 CRUD + 条件构造器，仍可手写 SQL |
| LambdaQueryWrapper | `EvalService.run()` 的 `.eq(EvalCase::getEnabled, 1).orderByDesc(...)`；`MultiRetriever.loadValidDocumentIds()` 的 `.in().select().and(w->w.eq().or().isNull())` | 类型安全字段引用（重构改名编译器报错）；and/or 嵌套组 | 
| 分页插件 | `EvalController.cases()` 的 `Page<>(page,size)` | MyBatis-Plus 分页拦截器原理（自动改写 SQL 加 LIMIT + COUNT） |
| @TableLogic 软删除 | `Document` 实体 deleted 字段——**真实案例**：全局搜索删掉前，"手册"搜不到就是因为命中的是 deleted=1 的文档，MP 自动过滤 | 逻辑删除 vs 物理删除的取舍 |
| @TableName/驼峰映射 | `EvalResult.mrr ↔ eval_result.mrr`（map-underscore-to-camel-case） | 命名策略 |
| JdbcTemplate 共存 | `McpToolService.auditToolCall`、`MultiRetriever` 部门域查询、`SearchController`(已删) | 什么时候直接 JDBC：审计/轻量查询不值得建 mapper |
| Flyway 迁移 | `db/migration/V1..V34`，启动自动执行；V33 加 refused 列、V34 加 mrr/ndcg | 版本化 DDL、不可改历史文件只增新版本；测试里 DROP+CREATE 独立 schema（devhub_it） |
| 事务 @Transactional | `TicketService`、`ETLPipeline`、`TaskService` | 原子性边界设计；MP 默认连接池 HikariCP |
| 全文索引 | MySQL ngram 全文索引做 BM25 关键词通道（`DocumentChunkMapper.searchByKeyword`） | 混合检索的"关键词腿"不引 ES 的低成本方案 |

---

## 阶段 6：Redis 六种用法（面试重头戏）

全部集中在 `RedisCacheService` + 各使用方：

1. **缓存（对象/JSON）**：检索结果缓存（`rag:result:v{版本}:scope:hash`，30 分钟 TTL + **索引版本号失效**——文档变更 bump version 全部失效，解决"改数据后缓存脏"的经典问题）。
2. **读穿 + 防击穿**：`QueryRewriteService` 用 `getOrLoad`（并发同问只放一个 LLM 调用）+ 失败写短 TTL 空值占位防穿透。
3. **语义缓存（双层）**：`SemanticAnswerCache` 精确层（归一化问题 MD5）+ 相似度层（余弦 ≥0.95 复用历史答案）——GPTCache 架构；**实体守卫**（订单号不同的两问 sim 0.965 也不复用）是踩坑后的修复，面试讲这个坑非常加分。
4. **ZSET 延迟队列**：`SlaService` 用 ZSET 按"首响/解决截止时间"评分存工单，定时器 zrangebyscore 扫超时→升级优先级+重挂时钟。讲"为什么 ZSET 适合延迟任务"（天然按时间排序、O(logN) 插入）。
5. **Stream 消息队列**：文档入库 `ETLPipeline → Redis Stream → DocIngestStreamConsumer` 异步消费（解析→切分→向量化→写 Milvus），上传接口秒回、重试 3 次进死信日志。对比 List/PubSub 的消费确认优势。
6. **Pub/Sub**：`NotificationService` 发事件 → `NotifySseHub` 推坐席 SSE；多实例部署下也能广播到正确连接。
7. **限流**：`ChatRateLimitFilter` Redis 固定窗口（全局 120/s、单 IP 15/s）。

**通用要点**：TTL 加抖动（`cacheService.jitter`）防雪崩；key 前缀 `agent:` 统一命名空间（RedisConfig 配置）；数据域隔离进 key（scope=dept:N / all）。

---

## 阶段 7：安全（Spring Security + JWT + RBAC）

文件：`com/devhub/security/` 五件套 + `common/PermAspect`

| 知识点 | 落点 | 要点 |
|---|---|---|
| 过滤器链配置 | `SecurityConfig`（SecurityFilterChain lambda） | permitAll 白名单（登录/静态/actuator/SSE）、anyRequest().authenticated()、无状态会话 |
| JWT 签发/校验 | `JwtTokenProvider` | HS256 双密钥轮换：Redis 存 current/previous + 60s 缓存，rotate-secret 后旧 token 宽限 10 分钟；min-iat 支持全员强制下线 |
| 认证过滤器 | `JwtAuthenticationFilter extends OncePerRequestFilter` | 从 Header 解 token→查 Redis 密钥版本→写 SecurityContextHolder |
| UserDetailsService | `DbUserDetailsService` 查 sys_user | Spring Security 标准扩展点 |
| 密码存储 | BCrypt | 自带盐、慢哈希；为什么不存明文/MD5 |
| RBAC 权限模型 | sys_user/sys_role/sys_role_perm 三表；`AuthController./me` 返回 perms[] → 前端 `v-perm` 指令按权限渲染按钮 | 权限在**后端**（PermAspect 注解鉴权）+ 前端（体验）双层 |
| 方法级鉴权 AOP | `@RequirePerm` + `PermAspect` | 自定义注解+切点拦截，比 @PreAuthorize 更可控（查 DB 权限表） |
| 操作审计 | `@OperLog` + `OperLogAspect` | AOP 记录谁在何时干了什么 |
| **线程传播坑（必讲）** | 流式回答的 reactor 完成线程、@Async 忠实度校验线程里 `SecurityContextHolder.getContext().getAuthentication()` 为 null | 解法：请求线程先捕获 `streamOwner`/`usageSnapshot` 显式传参，异步侧用 `UsageContext.begin(snapshot)` 恢复——ThreadLocal 不跨线程是 Spring Security 异步场景第一坑 |

---

## 阶段 8：AOP / 异步 / 定时 / 事件

- **AOP 概念落地**：切面（OperLogAspect/PermAspect）、切点（注解）、通知类型（@Around）。自己写两个切面比背八股强十倍。
- **@Async**：`FaithfulnessVerifier.verifyAsync()` 异步忠实度打分不阻塞回答；注意点：同类内调用不生效（代理）、**ThreadLocal 记账丢失**（项目用快照入参解决）。
- **@Scheduled**：`KnowledgeExpiryScheduler`（知识过期下线）、`UrlHealthChecker`（引用链接健康）、SLA 时钟重建——单机调度够用，多实例要讲分布式锁/ShedLock 的演进。
- **线程池资源**：`LlmCallLimiter`（LLM 全局并发 20 + 排队超时 45s）——背压保护下游配额。

---

## 阶段 9：可观测性

- **Actuator**：/health /prometheus 端点与暴露策略（prometheus permitAll 供抓取）。
- **Micrometer 指标**：`devhub.llm.latency`（timer，tag=mode: sync/stream/tools）、`devhub.rag.gate`（counter，pass/refuse）、`devhub.sla.breach`（first/resolve）——**用业务语义命名指标**，面试讲"指标驱动调优"（如观察 gate refuse 率调阈值）。
- **自研 Trace**：TraceService 每请求 traceId + 阶段 span（改写/检索/重排/ReAct 迭代），Ops 页可视化；MDC 串联日志（日志里 `[traceId]`）。
- **审计表**：agent_action（AI 自主解决率/工具分布的数据源）、ai_guard_log（注入防护审计）、ai_usage（token 成本核算，含异步线程记账修复）。

---

## 阶段 10：AI 工程层（项目最大亮点）

按调用链读代码，每个环节都有对标开源的概念名：

1. **模型路由** `ChatModelRegistry`+`ModelRouter`：多 provider（OpenAI 兼容协议）、降级链（deepseek→glm4air→glm4plus）、熔断器（CircuitBreaker failure-threshold）、并发限流。面试词：*多模型容灾*。
2. **意图路由** `IntentRecognizer`：规则优先（零成本）→ LLM 兜底（白名单约束只准推荐 REACT，防注入面）+ Redis 缓存。*分层意图识别*。
3. **Agent 三范式**：`ReActAgent`（LangChain4j 原生 Function Calling 循环，max 10 轮）、`PlannerAgent`（Plan-and-Execute）、`ReflectionAgent`（评分自反思）。工具体系：`BaseTool` 模板方法（校验/计时/异常统一）+ `ToolRegistry`/`ToolRouter`。
4. **MCP 客户端** `McpToolService`：stdio/HTTP 双传输连接外部 server，远程工具 `mcp_<server>_<tool>` 并入本地工具列表；同域治理（replaces-local-tools）、白名单自动映射、isError→异常语义。*2026 工具互操作事实标准*。
5. **RAG 管线**（读 `MultiRetriever` 一个文件吃透检索）：查询改写（指代消解/变体/跨语言/HyDE）→ 多通道召回（向量+BM25+精确）→ RRF 融合 → cross-encoder 重排（API 失败降级 LLM 打分）→ 父子块展开 → CRAG 门控拒答。
6. **防幻觉三道防线**：门控（检索弱→拒答转工单）、引用强制（CitationEnforcer 逐句 [n] 核查→重生成→剥离）、忠实度校验（RAGAS 式 claims 分解异步打分落库）。
7. **Prompt 安全** `PromptGuard`：正则一道 + LLM 分类二道（fail-open）+ 金丝雀 + 审计。
8. **评测** `EvalService`+`EvalMetrics`：命中率/MRR/NDCG/门控/忠实度，retrieval 模式零 LLM 成本回归——*改提示词/换模型后跑一轮对比*。

---

## 阶段 11：架构分层与设计模式速查

```
devhub/
├── com.devhub.security/   认证授权（横切）
├── com.devhub.common/     AOP 切面、SSE 通知、跨模块工具
├── com.devhub.module.ai/      AI 能力域（agent/rag/llm/security/trace）
├── com.devhub.module.ticket/  工单域（业务闭环另一半）
├── com.devhub.module.doc/     知识库入库域（Stream 消费者）
├── com.devhub.module.system|report/  系统管理/报表
└── module.task|project|defect/  【旧 DevHub 残留，不作为学习材料】
```

| 模式 | 项目落点 |
|---|---|
| 模板方法 | `BaseTool.execute()`（统一入口/异常/耗时，子类只写 doExecute） |
| 策略 | `OrderDataProvider` 接口 + `MockOrderDataProvider`（换真实 ERP 零改工具层）；AgentMode 五模式分发 |
| 注册表 + 路由 | `ToolRegistry`（本地+MCP 统一）+ `ToolRouter`（前缀分流） |
| 外观 | `AgentOrchestrator`（一个 chat() 门面串起全部子系统） |
| 建造者 | ToolSpecification/EvalRun/Lombok @Builder |
| 降级链/熔断 | ModelRouter fallback + CircuitBreaker；Reranker zhipu→llm；PromptGuard LLM fail-open |
| 单例可变配置 | ChatModelRegistry volatile+synchronized rebuild（DB 热切换模型） |

**模块化讲法**："按业务域分包、ai 模块内部再分层（controller/service/infrastructure/etl），基础设施（llm/vectordb/trace）与业务隔离"。

---

## 阶段 12：测试体系

- **单测**：JUnit5 + Mockito。读 `EvalMetricsTest`（纯数学断言）、`McpToolServiceTest`（new 出来直测，不启容器——**可测性来自构造器注入**）、`ToolRouterMcpTest`（Mockito mock McpToolService 验证前缀分流）。
- **集成测试**：`TicketFlowIntegrationTest`——`@SpringBootTest` + 真实 MySQL（独立 schema devhub_it，静态块 DROP+CREATE 指定 utf8mb4_0900_ai_ci）+ Redis db15 + `@MockitoBean` 替身 Milvus/TicketAi；`@EnabledIfEnvironmentVariable(DEVHUB_IT=local)` 环境门控，平时 `mvn test` 不跑重测试。
- **MCP 集成冒烟**：`McpDemoServerIT` 真启动 node 子进程走完整 MCP 协议。
- 面试点：为什么不用 Testcontainers（本机 Docker Desktop 命名管道与 docker-java 不兼容的排查经历）、测试金字塔、mock 边界（mock 外部依赖不 mock 被测逻辑）。

---

## 阶段 13：构建部署与运维

- **Maven**：BOM 统一版本（langchain4j-bom）、spring-boot-maven-plugin repackage（fat jar 嵌套 jar 结构）、`mvn package -DskipTests`；**jar 被运行中进程锁住时先杀 8080 再打包**（Windows 特有）。
- **Docker Compose**：mysql(3307)/redis(6379)/milvus(19530)+etcd+minio 五容器；**compose up -d 返回 ≠ Milvus 就绪（gRPC 代理需 1-2 分钟）**——启动脚本必须 TCP 探测等待，这是 start-dev.bat 修过的真实 bug。
- **启动方式**：`start-dev.bat`（双击）/ `bash start-dev.sh`（Git Bash）/ 手动指令（中间件→构建→等 Milvus→带环境变量 java -jar→npm run dev）。
- **环境变量清单**：OPENAI_API_KEY（智谱，必带）、DEEPSEEK_API_KEY/LLM_PRIMARY（可选主模型切换）、AGENT_MCP_ENABLED（MCP 演示开关）、MYSQL_HOST/PORT/DB、REDIS_HOST、MILVUS_HOST——全部来自 `D:\docker\middleware\.env`（仓库外，防泄露）。
- **排查五步**（面试可讲故障排查方法论）：① curl health 看后端在不在 → ② tail build/devhub.log 看启动报错（Milvus 未就绪/主模型未注册/端口占用三类最常见）→ ③ netstat 查端口冲突 → ④ docker ps 看中间件 → ⑤ 前端 vite proxy 报 ECONNREFUSED 一定是后端问题不是前端问题。

---

## 附：30 道高频面试题速答清单（对照项目背）

1. 构造器注入 vs 字段注入？→ 阶段 2，ToolRouter 直 new 可测
2. @Lazy 解决什么？→ IntentRecognizer↔ModelRouter 构造器循环
3. 三级缓存？→ 讲原理 + 本项目用 @Lazy 主动打断更清晰
4. @ConfigurationProperties vs @Value？→ LlmProperties vs top-k
5. Bean 生命周期钩子？→ ToolRegistry.init / McpToolService shutdown
6. 全局异常怎么做的？→ GlobalExceptionHandler + BusinessException
7. REST 统一响应为什么？→ Result<T> + 前端拦截器解包
8. SSE 和 WebSocket 选型？→ AI 流式单向输出选 SSE
9. Filter vs Interceptor vs AOP？→ JwtFilter/ChatRateLimitFilter vs PermAspect/OperLog
10. MyBatis-Plus 优缺点？→ 零 SQL CRUD + Wrapper；复杂查询仍写 SQL/JdbcTemplate
11. 软删除怎么实现？→ @TableLogic + 全局搜索踩坑案例
12. Flyway 解决什么？→ V1-V34 版本化演进
13. 事务失效场景？→ 同类自调用/非 public；@Async 同理
14. Redis 为什么快 / 用在哪？→ 六种用法（缓存/读穿/语义/ZSET 队列/Stream/PubSub/限流）
15. 缓存三兄弟（穿透/击穿/雪崩）？→ 空值占位/getOrLoad 单飞/TTL 抖动
16. 缓存一致性？→ 索引版本号整体失效
17. 延迟任务怎么实现？→ ZSET + zrangebyscore（SLA）
18. 异步解耦怎么做？→ Redis Stream 入库 + 死信
19. JWT 无状态认证流程？→ 双密钥轮换 + Redis 版本
20. JWT 如何注销？→ Redis min-iat / rotate-secret
21. 密码怎么存？→ BCrypt
22. 接口鉴权怎么做？→ @RequirePerm 切面 + RBAC 三表
23. SecurityContext 异步丢失？→ streamOwner 显式传参（真实 bug）
24. 限流算法？→ 固定窗口（Redis INCR+EXPIRE），讲令牌桶对比
25. 熔断怎么做？→ CircuitBreaker 失败计数 + 半开
26. 观测三大支柱？→ Micrometer 指标/自研 Trace/结构化日志
27. ReAct 是什么？→ 思考-行动-观察循环，原生 Function Calling
28. RAG 为什么要混合检索+重排？→ 向量召回语义/关键词保专有名词/RRF 融合/精排
29. 如何防止大模型幻觉？→ 门控/引用强制/忠实度三道防线
30. MCP 是什么？→ 工具互操作协议；讲治理三坑（同域冲突/白名单/缓存实体）

---

## 建议学习节奏（4 周）

- **第 1 周**：阶段 1-3（Java + Spring 核心），每天读 2-3 个列出的文件
- **第 2 周**：阶段 4-6（Web + 数据 + Redis），动手改一个配置跑起来观察
- **第 3 周**：阶段 7-9（安全 + 进阶 + 可观测），把 30 题中对应部分自己答一遍
- **第 4 周**：阶段 10-11（AI 层 + 架构）+ 通读 AgentOrchestrator/MultiRetriever 两个核心文件，能画全链路时序图
- **检验**：合上文档，从"用户在页面问一个问题"开始，讲到"答案流式返回并落库"，每一步说出用到的知识点和文件名——讲得下来就出师了。
