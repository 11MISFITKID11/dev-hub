# HelpHub 项目面试问答全集（基于真实实现，答案可直接背诵）

> 用法：每题答案都基于项目真实代码与数据（阈值/端口/版本号皆为实际值）。
> 背诵策略：先背第 1 部分的项目介绍，再按面试官深挖路径顺序过 2-13，第 14 部分的踩坑故事是区分度最高的部分。
> 配套学习材料见 `docs/java-springboot-learning-guide.md`（知识点讲解版）。

---

## 一、项目介绍（必背开场）

**Q1：用两分钟介绍你的项目。**

A：HelpHub 是一个 AI 智能客服与工单处理系统，Spring Boot 3.5 + Java 21 单体后端，Vue3 + Element Plus 前端，MySQL（Flyway 管理 34 个版本迁移）+ Redis + Milvus 向量库，LLM 通过 LangChain4j 接 OpenAI 兼容协议（DeepSeek 主模型 + 智谱 GLM 降级链）。核心两条业务线：①智能问答——意图路由后走 RAG 直答（混合检索+重排+引用强制+忠实度校验）或 ReAct Agent 工具循环（查订单/建工单，且接入了 MCP 外部工具服务器）；②工单闭环——AI 分类定级、SLA 超时自动升级（Redis ZSET 延迟队列）、坐席工作台处理、质检与满意度评价。拒答不编造：检索质量门控不过就转人工工单，形成"AI 答不了→人接管→知识库补全→AI 变强"的闭环。工程侧有完整的可观测性（Prometheus 指标+全链路 Trace+审计表）和 65 个测试（含真实 MySQL/Redis 的集成测试）。

**Q2：项目最大的技术亮点是什么？**

A：三个：①防幻觉三道防线——CRAG 门控拒答、逐句引用核查（不达标带反馈重生成、再不达标剥离无据句子）、RAGAS 式忠实度异步打分落库；②检索管线对标 Dify/QAnything——查询改写（指代消解/多变体/跨语言/HyDE）、向量+BM25+精确三通道混合召回、RRF 融合、cross-encoder 重排（失败降级 LLM 打分）、父子块展开；③MCP 客户端接入——外部 MCP server 的工具与本地工具在注册中心统一，并解决了同域工具冲突治理、白名单映射、语义缓存实体穿透三个真实工程问题。

**Q3：为什么做这个项目？/ 解决什么问题？**

A：企业客服场景的两个痛点：重复问题占比高（人工成本）、AI 直接回答会编造（信任成本）。所以设计目标从一开始就是"能答的交给 AI 并给出处，答不了的明确拒答转人工"，全部架构决策都围绕这个目标。

---

## 二、架构与选型

**Q4：画一下整体架构。**

A：分层：Vue3 SPA（hash 路由，vite 代理 /api）→ Spring Boot 单体（security 认证授权横切层 + common 切面/SSE 通知 + 四个业务域：ai 智能问答、ticket 工单、doc 知识入库、system/report 管理）→ 存储：MySQL 3307（业务+向量切片元数据）、Redis（缓存/延迟队列/Stream 队列/PubSub/限流）、Milvus 19530（1024 维向量）、外部 LLM API。文档入库走 Redis Stream 异步 ETL（解析→切分→向量化→写 Milvus）。

**Q5：为什么单体不是微服务？**

A：当前规模（单团队、QPS 低——AI 问答天然受 LLM 延迟约束）下微服务只增加运维成本不减复杂度。但模块化是按微服务边界设计的：module/ai、ticket、doc 高内聚、仅通过明确接口交互，未来拆分时每个 module 可独立成服务。这是"模块化单体"的演进式架构。

**Q6：为什么向量库选 Milvus 而不是 PGVector/Elasticsearch？**

A：三个理由：①专用向量引擎的索引能力（IVF/HNSW）和横向扩展强于插件方案；②业务库与向量库职责分离，向量重建/迁移不动业务库；③ Milvus 有独立部署运维生态（etcd+minio）。代价是多一套中间件——如果只是几万条文档的轻量场景 PGVector 更省，但我的目标是做面向生产的选型。Elasticsearch 不选是因为它的向量能力是检索引擎的附属，混合检索我们用"MySQL ngram 全文索引 + Milvus 向量"就覆盖了。

**Q7：为什么用 LangChain4j 而不是 Spring AI / 自己封装 HTTP？**

A：LangChain4j 提供 ToolSpecification（函数调用协议封装）、多 provider 抽象和 MCP 客户端，框架无关。Spring AI 1.x 更贴 Spring 生态但当时 MCP 与工具编排能力弱一档。完全自己封装 HTTP 的问题在于要处理各家 provider 的函数调用协议差异——这层不值得重造。但我没有用它的 AiServices 高层编排（声明式 Agent），因为我的 ReAct 循环需要自定义（思考步骤落 Trace、工具审计、最大迭代控制），只用它的原语层——这个取舍本身就是考点。

**Q8：为什么自研编排层而不用 LangGraph/Dify？**

A：评估过：LangGraph 的核心价值是图状态机+检查点+中断恢复，适合长时多分支工作流；我的场景是"意图路由 → 有限几种执行模式"的浅层编排，if-else 路由就够了。Dify 是低代码平台，引入后可控性和嵌入业务系统的能力受限。同时多智能体协作有公开实测表明更慢且无质量提升，我做了克制的单 Agent+编排路由设计。这个"评估后不做"的决策过程比盲目引入框架更能体现工程判断。

---

## 三、一次问答的完整链路（必背）

**Q9：用户发一个问题，后端发生了什么？**

A：①入口 ChatController 收到流式请求；②PromptGuard 输入扫描（正则拦截已知注入模式，可疑样本送小模型分类，Redis 缓存 24h，fail-open 不阻断主链路）；③语义答案缓存查询——精确层（归一化问题 MD5）命中直接回放，相似度层（历史问题向量余弦≥0.95）命中需过实体守卫（订单号等数字实体集合必须一致）；④意图识别——规则优先（含"我的订单/CS+数字"等事务信号进 Agent），未命中走 LLM 兜底（白名单约束只准推荐工具类）；⑤知识类走 RAG 直答：查询改写（一次 LLM 产出规范化+检索变体+英文翻译+类型判定 exact/normal/complex）→ 多通道召回（向量[+英文路]/MySQL ngram BM25/精确短语，exact 类跳向量省 embedding）→ RRF 融合取 15 候选 → 重排（智谱 cross-encoder，失败降级 glm-4-flash 批量打分）→ 父子块展开 → CRAG 门控（top1<0.5 拒答）→ top5 进生成 → 引用强制核查（实质句无 [n] 标注占比>40% 触发带反馈重生成，仍不达标剥离）→ 忠实度异步打分落库；⑥事务类走 ReAct Agent：原生 Function Calling 循环（最多 10 轮），工具=本地 6 个+MCP 远程（被替代的本地工具不推广），每次调用记 agent_action 审计表；⑦流式 SSE 返回，__META__ 帧带 conversationId/refused 标记（不落库）；⑧持久化会话与消息（拒答标记 refused 供历史会话恢复"转工单"入口）、token 用量记账（含异步线程快照回写）。

**Q10：拒答之后呢？转人工怎么实现？**

A：门控未过时 __META__ 帧带 refused=true，前端展示"转人工工单"入口；用户点击即调 create_ticket 工具建单（标题/内容预填用户问题），进入工单域：AI 分类定级（TicketAiService）→ SLA 时钟挂 Redis ZSET → 坐席工作台接单处理 → 完成后质检+满意度评价 → 低分/知识缺口进 KnowledgeGap 管理员补全知识库 → 检索索引版本 bump，下次同类问题 AI 能答。这是业务闭环。

---

## 四、Java 基础（结合项目）

**Q11：项目里哪些地方用了并发容器，为什么？**

A：ToolRegistry 的工具表、McpToolService 的客户端/工具索引用 ConcurrentHashMap——多请求线程并发读、管理操作并发写，putIfAbsent/merge 保证原子性；MultiRetriever 的数据域白名单缓存用 ConcurrentHashMap<scope, ScopeCache> + double-checked synchronized 刷新（5 秒 TTL）——读多写少场景的典型本地缓存模式。

**Q12：ThreadLocal 在项目里的使用和坑？**

A：两处使用：MultiRetriever.SCOPE_OVERRIDE（开放接口的数据域透传，Controller 配对 set/remove）、UsageContext（LLM token 记账快照）。坑是真实的：SecurityContext 基于 ThreadLocal，流式回答的 reactor 完成线程和 @Async 忠实度校验线程里取出来是 null——曾经导致 conversation.user_id 丢失和异步校验的 token 记账不入账。修复方式是请求线程先捕获（final String streamOwner / long[] usageSnapshot）显式传参，异步侧 UsageContext.begin(snapshot) 恢复快照再 diff 回写。这是 Spring Security 异步场景的第一坑。

**Q13：record 和 Lombok @Builder 怎么配合？什么时候用哪个？**

A：纯数据载体用 record（BaseTool.ToolResult、Rewrites）——不可变、自动 equals；需要"改一个字段生成副本"的用 @Builder(toBuilder=true)（RetrievalResult.r.toBuilder().score(x).build()，RRF 融合和重排改分时不影响原对象）。组合拳：@Data @Builder(toBuilder=true) @NoArgsConstructor @AllArgsConstructor 是实体/结果类的标配。

**Q14：全局异常处理怎么做的？**

A：@RestControllerAdvice 的 GlobalExceptionHandler 统一拦截 BusinessException（业务语义错误透传 message）和其他 Exception（兜底 500"服务内部错误"），统一包装 Result<T>。业务代码里不写 try-catch，但基础设施层（MCP 调用、缓存读写、忠实度校验）会 catch 降级不阻断主链路——区分"业务异常上抛"和"增强能力失败降级"两类策略。

---

## 五、Spring 原理

**Q15：讲讲你对本项目 IoC 的理解，举构造器注入的例子。**

A：全部 Service/Component 用 @RequiredArgsConstructor + private final 构造器注入。好处：依赖显式可读、字段不可变、可测试性——ToolRouterMcpTest 直接 new ToolRouter(registry, objectMapper, mockMcp) 不需要起 Spring 容器。字段注入(@Autowired)藏依赖、无法 final、单测要反射。

**Q16：遇到过循环依赖吗？怎么解决的？**

A：IntentRecognizer 依赖 ModelRouter（意图兜底要调 LLM），ModelRouter 又间接依赖意图结果相关组件，构造器注入成环启动失败。用 @Lazy 打断：IntentRecognizer 构造器参数标 @Lazy ModelRouter，Spring 注入代理，首次真正调用才解析目标 Bean。三级缓存只解决 setter/字段注入的环，构造器环必须主动打断——@Lazy 或重组依赖。

**Q17：Bean 生命周期在项目里的实际应用？**

A：@PostConstruct 做资源初始化：ToolRegistry.init() 扫描容器里所有 BaseTool 自动注册；McpToolService.init() 按配置建立 MCP 连接并索引远程工具（失败只降级记日志不阻断启动）。@PreDestroy 做清理：McpToolService.shutdownClients() 逐个 close 客户端杀掉 stdio 子进程。单 server 连不上不影响应用是"增强能力非关键路径"的降级设计。

**Q18：@ConfigurationProperties 的实现原理和优势？**

A：Spring Boot 启动时把 application.yml 里 agent.llm 前缀的树按字段名绑定到 LlmProperties（支持嵌套 Map<String,ModelSpec>、List、Duration 等），需要 @EnableConfigurationProperties 注册（项目挂在 ChatModelRegistry 上）。相比散装 @Value：类型安全、一次绑定整棵配置树、可校验、IDE 可导航。项目里所有可调参数（阈值/超时/降级链）都收敛在这类 Properties 里，运维不用改代码调优。

**Q19：AOP 在项目里的三个应用和原理？**

A：①权限：@RequirePerm + PermAspect 环绕通知查 sys_role_perm 校验；②审计：@OperLog + OperLogAspect 记录操作日志；③（对比）Spring Security 的 @PreAuthorize 是表达式硬编码，自研切面的优势是权限来自 DB 可运营。原理：运行时动态代理（接口 JDK 代理/无接口 CGLIB 子类），切面方法被代理链拦截。同类内自调用不走代理是失效场景——项目避免了这种写法。

---

## 六、数据访问（MyBatis-Plus / MySQL / Flyway）

**Q20：为什么 MyBatis-Plus 而不是 JPA？**

A：国内生态、SQL 可控性：单表 CRUD 用 BaseMapper 零 SQL（selectPage/insert/updateById），复杂查询手写 SQL（BM25 全文检索的 XML/注解 SQL、部门域过滤的动态条件）。JPA 的领域模型驱动在多表关联复杂的报表场景不如 SQL 直白。

**Q21：LambdaQueryWrapper 比字符串字段名好在哪？**

A：`wrapper.eq(EvalCase::getEnabled, 1)` 方法引用编译期检查字段存在，重命名重构不破；字符串 "enabled" 运行时才炸。嵌套组合：`.and(w -> w.eq(Document::getDeptId, id).or().isNull(Document::getDeptId))` 表达"本部门+公共文档"。

**Q22：软删除怎么实现的？踩过什么坑？**

A：@TableLogic 的 deleted 字段，MP 自动在所有查询追加 deleted=0。坑：曾有全局搜索搜不到《手册》文档，排查发现 DB 直查命中 2 条但都是 deleted=1——不是 bug 是 MP 正确过滤了。教训是排查"查不到"要区分应用层过滤（软删/权限/生命周期状态）和数据真不存在。知识库还有业务级生命周期（active/draft/retired/superseded），检索白名单只放行 active/draft 且 draft 结果降权 30%。

**Q23：分页插件原理？**

A：MyBatisPlusInterceptor 注册 PaginationInnerInterceptor，拦截 Executor.query，自动改写 SQL 追加 LIMIT 和生成 COUNT 查询，Page<EvalCase> 泛型承载 total/records。项目里评测用例管理、工单列表都用它。

**Q24：Flyway 的作用和纪律？**

A：db/migration 下 V1-V34 按版本号启动时顺序执行（V33 加 message.refused 列、V34 加评测 mrr/ndcg 列），schema 与代码同仓库演进。纪律：已合入的迁移文件永不修改，只新增版本——否则校验和变了启动失败。测试环境用独立 schema devhub_it，静态块 DROP+CREATE 且显式指定 utf8mb4_0900_ai_ci——不指定会继承驱动的 unicode_ci 排序规则，V25 的 JOIN 出现"Illegal mix of collations"，这是真实踩过的坑。

**Q25：MySQL 全文索引怎么支撑检索？**

A：document_chunk 表建 ngram 全文索引做关键词通道（BM25 相关度排序），ngram 对中文友好（二元分词），配合 MATCH...AGAINST 在 Mapper 里手写。它承担混合检索的"专有名词/精确术语"腿，向量通道负责语义。没有引入 ES：数据量（万级切片）MySQL 全文索引足够，省一套中间件。

**Q26：索引怎么设计的？**

A：主键查询为主（conversation/message/document_chunk 都是 ID 等值）；高频过滤字段建索引：ticket 的 status+priority（列表按状态/优先级筛选排序）、message 的 conversation_id（会话消息按会话查）、document 的 life_status+dept_id（检索白名单）。原则：区分度高的等值列在前，覆盖列表页的排序键。EXPLAIN 验证避免全表扫描。

---

## 七、Redis（必考重头）

**Q27：项目里 Redis 的几种用法分别是什么？**

A：七种：①对象缓存（检索结果 30min TTL + 索引版本号整体失效）；②读穿缓存+防击穿（改写结果 getOrLoad 并发单飞，失败写短 TTL 空值防穿透）；③双层语义缓存（精确 MD5 层 + 余弦≥0.95 相似层，200 条容量 24h TTL 带抖动）；④ZSET 延迟队列（SLA 首响/解决双时钟，定时 zrangebyscore 扫超时升级优先级重挂时钟）；⑤Stream 队列（文档入库异步 ETL，重试 3 次进死信）；⑥PubSub（工单事件→SSE 推坐席通知，多实例可广播）；⑦固定窗口限流（全局 120/s、单 IP 15/s）+ JWT 双密钥版本存储。

**Q28：缓存穿透/击穿/雪崩分别怎么应对？**

A：穿透（查不存在的数据打到 DB）：改写 LLM 调用失败写短 TTL 空值占位，同问不再反复冲击；检索结果缓存的损坏条目（Jackson 泛型擦除导致反序列化异常）捕获后清 key 按未命中处理。击穿（热 key 过期瞬间并发打库）：getOrLoad 读穿——并发同 key 只放一个回源线程。雪崩（大量 key 同时过期）：TTL 加随机抖动（jitter）。

**Q29：缓存一致性怎么保证？**

A：场景是"知识库文档变更后旧检索结果必须失效"。方案：索引版本号——文档上传/删除/审核后 bump Redis 里 rag:index:version，所有结果缓存 key 含 v{版本}，旧版本 key 自然过期淘汰（惰性失效，不需要逐 key 删除），同时主动清内存数据域白名单。比"更新 DB 后删缓存"简单可靠：不追求实时强一致（TTL 30min），版本号保证"下一次变更后全量换 key"。

**Q30：语义缓存是什么？踩过什么坑？**

A：对标 GPTCache 的双层结构：第一层归一化问题（去空白/压空格/小写）MD5 精确命中；第二层遍历历史问题向量算余弦，≥0.95 视为等价问题直接复用答案（含溯源）。坑（真实事故）：用户问"订单 SO-2024-1004 发货了吗"，与缓存的"订单 SO-2024-1001 发货了吗"余弦 0.965 命中，回放了 1001 的旧答案——订单号只差一位，向量几乎不变但语义完全不同。修复：实体守卫——提取双方问题里的数字实体（正则 [A-Za-z]*-?\d+），集合不一致就绕过缓存走真实链路；双方都无数字实体（纯常识问答）才允许复用。这是"向量相似≠语义等价"的教科书案例。

**Q31：为什么用 ZSET 做延迟任务？对比其他方案。**

A：SLA 超时检测需要"到点触发"：member=工单，score=截止时间戳，定时任务 zrangebyscore(0, now) 拿到全部超时单，升级优先级后把新截止时间 zadd 重挂（解决时钟）。对比：JDK DelayQueue 单机内存重启丢；RabbitMQ 死信队列要引中间件且延迟不好动态改；Redis 时间轮过重。当前单机规模 ZSET 最合适，且天然支持按时间范围扫描和原子更新。多实例演进：扫队列加 Redis 分布式锁或 Redisson。

**Q32：Redis Stream 和 Kafka/RabbitMQ 怎么选？**

A：文档入库是单应用、低吞吐、要求"上传秒回+失败重试+死信"的场景，Stream 足够且零新增中间件：ConsumerGroup 语义、pending 列表重试（重试 3 次后记死信日志人工重传）。Kafka 的价值在多消费者/高吞吐/回放，当前用不上；引入反而增加运维面。这是"按场景选中间件，不按简历选"的例子。

---

## 八、安全

**Q33：JWT 认证完整流程？**

A：登录 AuthController 校验 BCrypt 密码 → JwtTokenProvider 用当前密钥（HS256）签发 token（sub=用户名 uid/roles，iat/exp 24h）→ 前端存 localStorage 每次放 Authorization: Bearer → JwtAuthenticationFilter（OncePerRequestFilter）在过滤器链解析 token、查 Redis 校验密钥版本（防旧密钥 token）、构建 Authentication 写 SecurityContextHolder → 后续 @RequirePerm 切面查 DB 权限表细粒度鉴权。

**Q34：为什么做双密钥轮换？怎么实现？**

A：单密钥泄露/离职场景需要"换钥匙但用户不掉线"。实现：Redis 存 current/previous 两个密钥，验签先试 current 再试 previous；rotate-secret 接口轮换后旧 token 有 10 分钟宽限期，60s 本地缓存减少 Redis 查询；min-iat 机制记录全员最早签发时间，logout-all 可让所有人重新登录（token 的 iat 早于该值即失效）。密钥校验启动时 fail-fast：占位符/弱密钥/长度<32 直接拒启（SecurityStartupCheck）。

**Q35：密码为什么用 BCrypt？**

A：自带随机盐（同密码每次哈希不同，防彩虹表）、慢哈希（cost 因子暴力破解成本高）、密码学社区广泛验证。MD5/SHA 已不安全且无盐。BCryptPasswordEncoder 一行接入 Spring Security。

**Q36：RBAC 模型怎么设计的？前端怎么配合？**

A：sys_user/sys_role/sys_role_perm 三表（用户-角色-权限），登录后 /me 返回 perms[] 权限码列表。后端 @RequirePerm("ticket:handle") 切面鉴权是真正的安全边界；前端 v-perm 自定义指令按权限隐藏按钮是体验层（不是安全——永远不信任前端）。部门数据隔离再加一层：检索白名单按 dept_id（本部门+公共），AI 层不自行查权限统一走白名单。

**Q37：提示注入（Prompt Injection）怎么防？**

A：PromptGuard 两道防线：①正则一道拦已知注入模式（角色扮演/忽略指令/泄露系统提示等），高置信直接 block；②软信号（可疑关键词）样本送小模型分类，Redis 缓存 24h（md5 key），LLM 不可用时 fail-open 放行（增强能力不阻断业务）；③金丝雀：系统提示埋随机 canary，模型输出含 canary 即判定系统提示泄露；全部进 ai_guard_log 审计（命中来源 regex/llm/canary）。系统提示层再加规则：用户消息/工具返回里的指令一律视为待处理数据——MCP 工具返回天然是不可信外部内容，这层规则直接复用。曾发现流式路径没接输入扫描，补上了——安全要覆盖全部入口。

**Q38：SecurityContext 为什么会丢？怎么发现和修复的？**

A：SecurityContextHolder 默认 ThreadLocal 策略。流式回答用 reactor，生成完成回调在 ForkJoinPool 线程执行，那里 SecurityContext 是空的——表现为刷新页面后会话 user_id 变 null。修复：请求线程先 final String streamOwner = currentOwner() 捕获，异步侧用 ownerOverride 参数落库。同类问题还有 @Async 忠实度校验的 token 记账——UsageContext 快照传参恢复。本质：凡跨线程就别依赖 ThreadLocal 上下文，显式传参。

---

## 九、性能与可用性

**Q39：问答延迟多少？怎么优化的？**

A：基线 10-20 秒，根因是串行 LLM 链：改写→意图→检索→重排→生成每个环节都是一次 LLM 往返。已做的优化：①改写缓存前移——热门问题先按"原问题+历史"直查结果缓存，命中跳过改写那次 LLM 调用（省 1-3 秒）；②语义答案缓存整条链路短路（命中零 LLM 成本）；③意图规则优先命中就不调 LLM；④检索通道裁剪——exact 类问题跳过向量通道省 embedding 调用；⑤重排默认 LLM 批量打分（免费单次调用）而非逐条。进一步方向：改写/意图并行化、非流式改全流式（首字时间大幅提前）。

**Q40：LLM 服务的可用性怎么保障？**

A：四层：①多模型降级链——primary(DeepSeek) 失败依次 glm-4-air→glm-4-plus→glm-4-flash，全部 OpenAI 兼容协议；②熔断器——连续 5 次失败打开，30 秒后半开试探，3 次成功关闭；③并发保护——全局 20 并发上限+45 秒排队超时，超限返回"稍后再试"而不是打爆供应商配额（背压）；④各增强环节独立降级——重排失败保序、忠实度失败跳过、缓存失败走真实链路、MCP 单 server 失败不影响启动。原则：核心链路（回答）尽力可用，增强链路可丢。

**Q41：SLA 超时升级的完整机制？**

A：工单创建时按优先级算首响/解决两个截止时间，ZADD 进 Redis ZSET（member=工单号，score=截止时间戳）。定时任务 zrangebyscore 扫超时：首响超时升级优先级并重挂解决时钟（一次性不重复挂）；解决超时再次升级并告警通知（PubSub→SSE 推坐席）。超时计数进 devhub.sla.breach 指标（tag=first/resolve），报表页可看超时率。应用重启后从 DB 重建队列（进行中工单重新入队），不丢任务。

---

## 十、AI 工程深挖（项目核心区分度）

**Q42：RAG 为什么要混合检索？RRF 怎么融合？**

A：向量检索抓语义但专有名词/编号弱（"SO-2024-1001"的 embedding 和别的订单号几乎一样）；BM25 抓字面但同义表述弱。三通道：向量（Milvus 余弦，复杂问题加英文翻译路跨语言召回）、关键词（MySQL ngram 全文索引）、精确短语（LIKE，条款编号强通道）。融合用 RRF：score = Σ 1/(60+rank)，k=60 是论文标准值（Dify/LangChain 同款）；多通道都命中的 chunk 分数叠加，归一化到 0-1 后取前 15 进重排。不直接比原始分因为向量分和 BM25 分量纲不可比。

**Q43：两阶段检索（召回+重排）为什么不一步到位？**

A：粗召回通道各取 25 条保证召回率；cross-encoder 重排是 query 和文档联合编码打分，精度高但贵，只对融合后的 15 候选做。架构对标 QAnything：重排只负责排序质量，截断 top-k 由编排层决定。重排 provider 可配置：智谱官方 rerank API（cross-encoder）失败自动降级 glm-4-flash 单次批量打分（免费，0-10 分归一化 0-1 与官方通道同量纲——阈值联动）。然后父子块展开：子块命中折叠到父块取大上下文进生成，放在重排之后因为父块内容宽泛直接重排会被打低分误触门控（实测教训）。

**Q44：HyDE 是什么？为什么实现了却默认关？**

A：Hypothetical Document Embeddings——让 LLM 先生成假设性答案，用答案的向量去检索（答案与文档同分布比问题更近）。改写服务已产出 hypothetical 字段，仅 complex 类问题启用（控 embedding 成本）。默认关是因为 A/B 实测：flash 模型生成的假设文档向量对复杂题召回不稳，kwRecall 从 0.926 降到 0.83——功能留在代码里由配置开关控制，等更强模型/更好的生成提示再启用。"实测数据驱动开关"比"概念新就上"重要。同类决策：200 字子块二级检索实测损伤 BM25 区分度，回滚为单层。

**Q45：防幻觉三道防线具体怎么实现？**

A：①CRAG 门控（检索侧）：重排 top1 分数 < 0.5 视为弱召回，宁可拒答不给弱资料——拒答回复转工单入口，这是"答不了"的诚实表达；②引用强制（生成侧）：生成提示词要求每个事实句标 [n] 来源编号，CitationEnforcer 逐句核查——实质陈述句（≥15 字、非标题/引导语）无标注占比 > 40% 触发带反馈重生成一次（把未引用句子列给模型修正），仍不达标就剥离无据句子（内容确实被资料支撑的漏标句保留，用词元重叠率≥0.3 判断，避免误伤）；③忠实度校验（离线侧）：RAGAS 式 claims 分解——小模型把答案拆成独立事实声明逐条判断是否被召回资料支撑，得分=支撑数/总数，异步打分（不增加响应延迟）落库 message.faith_score，<0.6 告警人工复核，token 消耗快照回写记账。

**Q46：Agent 三种模式分别适用什么场景？怎么选型路由？**

A：ReAct（推理-行动-观察循环，原生 Function Calling，最多 10 轮）适合工具组合不确定的查询——查订单可能要查物流、可能要建工单；Plan-and-Execute（先 LLM 生成步骤计划再逐步执行最后汇总）适合可预先分解的多步任务；Self-Reflection（生成-评分-改进循环，≥8 分停，最多 3 轮）适合准确性优先的长答案。路由：IntentRecognizer 规则优先（含订单/工单等事务信号→REACT），LLM 兜底分类但白名单约束只准推荐 REACT（防分类面过宽被注入）。知识类不进 Agent 直接 RAG 直答——快、带引用、省成本。

**Q47：MCP 是什么？你的接入架构？**

A：Model Context Protocol，AI 工具调用的开放协议（类比 USB-C）：server 暴露工具（名称/描述/JSON Schema 参数），client 通过 stdio 或 Streamable HTTP 连接。我的架构：McpToolService 管理 multiple server 连接（stdio 本地子进程/HTTP 远程），listTools() 拿到 LangChain4j ToolSpecification（库已完成 JSON Schema 转换），重命名 mcp_<server>_<tool> 后并入 ToolRegistry 的本地工具列表——模型统一 Function Calling，ToolRouter 按前缀分流到远程执行，结果包装成统一 ToolResult（审计/耗时语义与本地一致，agent_action 表照记）。管理端点 /api/v1/mcp/status（连接与工具清单）/refresh（改配置免重启重连）。默认关（AGENT_MCP_ENABLED=false），生产可换官方 filesystem/mysql/jira server 零代码改动。

**Q48：MCP 接入踩了哪些坑？（区分度最高）**

A：三个真实坑，全部修复并有测试：①同域工具冲突——本地 query_order（mock 数据）和 MCP 订单工具同时可见时，模型优先本地且"未找到"后不换工具重试，MCP 永远不被触达。修复：agent.mcp.replaces-local-tools 配置——被替代的本地工具不再向模型推广（显式调用仍可执行，隐藏推广不禁用执行）。②编排层白名单不感知 MCP——agentic 分支工具白名单 AGENT_TOOLS=[query_order, create_ticket] 没有 MCP 工具，叠加治理隐藏后模型手里只剩建工单工具，于是它直接建了个"查订单"工单。修复：listToolSpecificationsForWhitelist——白名单里被替代的本地工具自动映射其 MCP 同名工具，调用方零改动。③就是 Q30 的语义缓存实体穿透。另外还有工具描述工程：描述里写"（演示数据源）"模型会拒绝用它查"真实"订单——MCP 工具描述应写强制性适用条件。这三个坑说明：工具生态接入的核心难题不是协议而是治理。

**Q49：评测体系怎么建的？MRR/NDCG 是什么？**

A：EvalService 两种模式：retrieval（走到重排为止，零 LLM 成本，改检索参数后快速回归）和 full（完整问答链路，额外得忠实度）。指标：命中率（期望文档是否进 Top5，full 模式还校验期望关键词在答案里）、门控通过率、平均 top1 分数、MRR、NDCG、忠实度。MRR=1/首个命中文档排名（衡量"第一个相关结果排多前"）；NDCG@k（二值相关度单期望文档）=1/log2(rank+1)，惩罚比 MRR 平缓——两者一起区分"头部准"与"整体乱"。未设期望文档的用例不参与排名指标（null 不计入平均）。实测基线：命中率 100%、MRR=1、NDCG=1、门控 80%。改提示词/换模型/调阈值后跑一轮对比上一轮，这就是检索质量的 CI 回归。

**Q50：可观测性做了什么？**

A：三类：①Micrometer/Prometheus 指标——devhub.llm.latency 计时器（tag: sync/stream/tools 模式）、devhub.rag.gate 计数（pass/refuse 拒答率）、devhub.sla.breach（first/resolve 超时数），/actuator/prometheus 暴露；②自研 Trace——每请求 traceId 贯穿日志（MDC），阶段 span（改写/各检索通道/融合/重排/门控/ReAct 每轮迭代）落库，运维页可视化每阶段耗时，还提供 debugRetrieve 调试端点返回各通道明细；③审计表——agent_action（AI 自主解决率/工具分布的数据源）、ai_guard_log（注入审计）、ai_usage（token 成本按用户/模型核算）。指标驱动调优的例子：观察 gate refuse 率调阈值 0.5。

**Q51：多轮对话的上下文怎么管理？**

A：短期记忆 Redis 存最近 20 轮，构建 LLM 上下文时按 system→历史→检索结果组装；检索改写带对话历史做指代消解（"它怎么退款"→补全指代对象），历史感知检索对标 LangChain history-aware retriever。长期记忆设计了摘要归档（超阈值 LLM 生成会话摘要）但目前只到摘要生成、向量化入检索是留的扩展点——面试诚实说这是 roadmap 不是已完成项，并讲方案（摘要 embed 进 Milvus 独立 collection，检索时与知识库结果 RRF 融合）。

---

## 十一、系统设计与演进

**Q52：如果流量涨 100 倍怎么办？**

A：分层演进：①应用层——模块化单体直接多实例+Nginx 负载均衡，无状态（会话在 DB/Redis）天然水平扩展；需要处理的点：Redis 限流已经是全局的、SLA 扫描和定时任务要加分布式锁或拆到独立调度实例；②LLM 成本——语义缓存命中率提升（容量扩容）、非流式全流式化；③检索——Milvus 本身支持集群，MySQL 读库+切片元数据走从库；④队列——Stream 换 Kafka（多消费者/回放需求出现时）；⑤真正的微服务拆分按 module 边界：ai 域独立（LLM 网关化）→ 工单域独立。

**Q53：项目有什么不足？会怎么改进？（必问，诚实答）**

A：①长期记忆闭环未完成（方案已有）；②PlannerAgent 还是文本协议+正则解析（应升级 function calling 输出结构化计划+失败重规划）；③编排无检查点（Agent 中途失败不能续跑，可参考 LangGraph checkpoint 思想在 Redis 落状态）；④门控拒答后没有 Web 搜索兜底通道；⑤多实例下定时任务和 SSE 通知的集群化（PubSub 已支持广播，调度需分布式锁）；⑥前端无 SSR/CDN 优化。每个不足都附改进方案——展示的是演进意识而不是缺陷。

---

## 十二、测试与工程质量

**Q54：测试策略是什么？**

A：65 个测试三层：①纯单测（约 60 个）——数学逻辑直测（EvalMetricsTest 的 MRR/NDCG 断言）、可测性靠构造器注入（new McpToolService(props) 不起容器）、Mockito mock 边界依赖（ToolRouterMcpTest mock McpToolService 验证前缀分流）；②环境门控集成测试——@EnabledIfEnvironmentVariable(DEVHUB_IT=local)，平时 mvn test 不跑重测试，TicketFlowIntegrationTest 起真实 Spring 上下文+真实 MySQL（独立 devhub_it schema）+Redis db15，@MockitoBean 替身 Milvus/TicketAiService，覆盖工单全生命周期 12 断言（一个 Python E2E 脚本的 Java 化）；③MCP 集成冒烟——真启动 node 子进程走完整协议（initialize/tools/list/tools/call/isError）。

**Q55：为什么没用 Testcontainers？**

A：试过：本机 Docker Desktop 新版命名管道（dockerDesktopLinuxEngine）与 docker-java 的探测不兼容，"Could not find a valid Docker environment"。权衡后改为本地真实中间件+环境变量门控：测试语义等价（真实 MySQL/Redis），代价是依赖本机环境——CI 里可以用 GitHub Actions 的 service 容器或支持的环境再启 Testcontainers。教训是技术选型要验证环境兼容性而不是想当然。

**Q56：mock 的边界原则？**

A：mock 外部依赖（Milvus 向量库、AI 服务、MCP 服务），不 mock 被测逻辑和数据流。集成测试里 Milvus mock 因为它重且慢（向量检索正确性由检索调试端点手工验证），MySQL 用真的因为被测的就是 SQL 与事务。原则：测的是行为边界，不是实现细节。

---

## 十三、运维与前端（简要）

**Q57：启动流程和踩过的坑？**

A：一键脚本 start-dev.bat/sh 五步：中间件 compose up → 清 8080 占用（杀残留 java）→ mvn package → TCP 探测 19530 等 Milvus 就绪 → 启后端+前端。最大的坑就是时序：compose up -d 返回只代表容器启动，Milvus gRPC 代理要 1-2 分钟才就绪，java 抢跑会启动失败——表现为前端正常但 /api 全部 ECONNREFUSED（后端窗口有报错但用户只看前端窗口）。修复：启动前 TCP 探测等待+启动后健康轮询 90 秒+失败明确指引。其他坑：Windows 下运行中的进程锁 jar（打包前先杀 8080）；密钥从仓库外 .env 读，环境变量空值会覆盖 Spring 默认值导致主模型解析失败（脚本里做了空值透传过滤）。

**Q58：前端做了哪些生产化？**

A：Vue3+Vite8+Element Plus：①构建优化——manualChunks 分包+drop console，vendor 从 1071kB 降到 194kB（-82%）；highlight.js 按需注册 7 种语言；②全局错误处理——app.config.errorHandler+unhandledrejection 兜底上报；③路由体验——afterEach 动态标题、chunk 加载失败（发版后旧 hash 404）自动刷新一次自愈；④权限——v-perm 指令按权限渲染；⑤SSE 流式渲染+打字机效果、拒答态"转工单"入口。苹果风格 UI 分层演进（精修→去 AI 味→生产对齐→Apple HIG）是四轮迭代的产物。

---

## 十四、踩坑故事集（STAR 格式，区分度最高，选 2-3 个深讲）

**故事 1：语义缓存实体穿透（推荐主打）**
S：语义缓存上线后，用户问不同订单号的问题会收到别的订单的答案。T：定位并修复，且不能放弃语义缓存的收益。A：日志发现"订单 SO-2024-1004"与缓存里"订单 SO-2024-1001"余弦 0.965 ≥ 阈值 0.95 被判等价；分析出向量对"只差一位数字"的文本几乎无区分度；设计实体守卫——正则提取双方数字实体（订单号/手机号/金额），集合不一致即绕过缓存，双方均无实体才可复用；补 4 个单测（单号不同/同单号措辞不同/双方无实体/一方有实体）。R：缓存命中率保持，错答案归零；沉淀认知"向量相似≠语义等价，实体敏感场景必须加守卫"。

**故事 2：AGENT_TOOLS 白名单不感知 MCP**
S：MCP 接入后演示时模型永远不调远程工具，甚至直接建了个"查订单"的工单。T：让 MCP 工具在白名单体系下自然可用。A：三层排查——先怀疑模型指令遵循（glm-4-flash 确实弱，换 DeepSeek 主模型改善但仍不调）；加日志看模型实际收到的工具列表，发现编排层 AGENT_TOOLS 白名单只有本地两工具，叠加治理隐藏后被替代的本地工具后模型手里只剩 create_ticket；修复 listToolSpecificationsForWhitelist：白名单内被替代工具自动映射 MCP 同名工具，编排层零改动。R：自然问法即可触发 MCP 调用（5ms 返回真实数据）；沉淀"治理配置必须全链路一致"的检查意识。

**故事 3：SecurityContext 跨线程丢失**
S：流式对话刷新页面后会话 user_id 变 null，AI 用量记账在异步线程偶发丢失。T：修复并沉淀模式。A：定位到 reactor 完成回调和 @Async 线程里 SecurityContextHolder（ThreadLocal）取不到认证信息；修复：请求线程捕获快照（streamOwner/usageSnapshot）显式传参，异步侧 UsageContext.begin() 恢复后 diff 回写。R：数据不再丢；形成准则"凡跨线程不依赖 ThreadLocal，显式传参或 TaskDecorator"。

**故事 4：Milvus 启动时序**
S：双击启动脚本后前端正常但所有 API 连接拒绝，用户以为项目坏了。T：根治。A：从后端窗口日志找到"Milvus Proxy is not ready yet"——compose up -d 返回≠就绪（gRPC 代理初始化 1-2 分钟），java 启动即连失败退出；给三个启动脚本统一加"TCP 探测 19530 等就绪+启动后健康轮询+8080 清理"，bat 的 timeout 换 ping（stdin 重定向场景 timeout 直接报错）。R：双击启动从"看运气"变成确定性成功；沉淀"依赖就绪探测应该是启动脚本标配"。

**故事 5：__META__ 协议帧污染数据**
S：流式接口用 __META__ 帧向前端推会话元信息，早期实现把帧追加进了回复缓冲区并随消息落库，历史会话里混入 42 行协议文本。T：清理数据+根治。A：两个流式分支都加 .filter(text -> !text.startsWith("__META__"))，已污染数据用十六进制定位（Git Bash 中文条件静默失配的 GBK 坑）批量清洗；后续拒答标记改为独立字段 refused 持久化（V33 迁移），协议与数据彻底分离。R：新数据零污染，历史会话可正确恢复"转工单"入口。

**故事 6：软删除引发的"查不到"疑案**
S：全局搜索搜"手册"无结果，DB 直查却有 2 条命中。T：判定真伪。A：先怀疑中文编码（用十六进制字面量排除 GBK 转码因素），再对比 API 链路与 SQL 直查的差异——MyBatis-Plus @TableLogic 自动追加 deleted=0，命中的 2 条是软删除文档，框架行为正确。顺带发现该全局搜索是旧 DevHub 残留（搜任务/缺陷/需求死表），整功能下线。R：排除疑案+清理技术债；沉淀"查不到先列全过滤条件（软删/权限/生命周期），再怀疑数据"。

---

## 十五、反问环节可用的素材

- 团队的 AI 能力是自建还是接平台？检索评测有没有回归体系？
- 我在项目里做过"多模型降级+熔断+并发背压"的 LLM 网关层，贵司 LLM 调用的稳定性方案是什么样？
- 知识库运营（生命周期/缺口检测）和客服业务是怎么协同的？

## 十六、数字速记卡（面试前 10 分钟过一遍）

| 项 | 值 |
|---|---|
| 技术栈 | Spring Boot 3.5 / Java 21 / MyBatis-Plus 3.5.7 / LangChain4j 1.13 / Milvus SDK 2.5.3 / Vue3+Vite8+Element Plus |
| 迁移版本 / 测试数 | Flyway V1-V34 / 65 tests（63 单测+2 环境门控集成） |
| 检索参数 | 召回 25/通道 → RRF(k=60) → 候选 15 → 重排 → top5；门控 0.5；引用下限 0.6；draft 降权 0.7 |
| 语义缓存 | 余弦 0.95 + 实体守卫；容量 200；TTL 24h；结果缓存 30min+版本号失效 |
| 模型链 | DeepSeek 主 → glm-4-air → glm-4-plus → glm-4-flash；熔断 5 次/30s；并发 20/排队 45s |
| ReAct | 原生 Function Calling；最多 10 轮；工具=本地 6+MCP 远程 |
| 评测基线 | 命中率 100% / MRR 1 / NDCG 1 / 门控 80% / 忠实度异步 |
| 端口 | 前端 5173 / 后端 8080 / MySQL 3307 / Redis 6379 / Milvus 19530 |
| 前端优化 | vendor 1071→194kB（-82%） |
