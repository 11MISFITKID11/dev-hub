# 面试准备完全手册：企业级知识库问答平台

> 目标：把"跟 AI 做出来的项目"变成"我完全理解的项目"。核心资产是——**每个技术决策都有「为什么这么做 + 数据证明 + 踩过的坑」三件套**，这是面试中最稀缺的东西。
>
> 使用方式：每天 2 小时 = 1 小时看资源补理论 + 1 小时回项目代码对照。四周后所有"资源 → 代码 → 题库"对上号。

---

## 一、简历写法

### 简历条目（可直接用）

> **企业级知识库问答平台（个人全栈项目）**
> 技术栈：Java 17 / Spring Boot 3.5 / LangChain4j / Milvus / Redis / MySQL / Docker
>
> - **两阶段混合检索**（三通道召回 → RRF 融合 → 重排精筛），自建 52 题黄金评测集驱动优化，关键词召回率 0.85 → 0.97
> - **防幻觉三防线**（CRAG 检索门控 / 逐句引用校验 / RAGAS 式忠实度核验），知识库外问题拒答正确率 100%，答案忠实度 0.93
> - **Prompt 注入防护三层栏**（输入检测 / 检索内容中和 / canary 泄露检测），恶意文档注入实测零生效
> - **知识生命周期治理**（版本链 / 冲突检测 / URL 探活 / 踩率自动停用）与**自生产闭环**（缺口检测 → 草稿 → 校验 → 人工审核）

### 三个杀手锏故事（滚瓜烂熟）

每个故事都真实发生过，讲述模板 = 背景 → 现象 → 排查 → 修复 → 数据。

**故事 1：评测驱动抓隐藏 Bug**
"我建了 52 题评测集后第一轮跑分就发现召回率异常，逐例排查发现关键词检索通道没有按文档生命周期过滤——同一份手册三个版本的切片在互相挤占 top-k 名额。修复后 7 个弱召回用例全部恢复，零退化。这件事让我确信：没有度量就没有优化。"

**故事 2：防幻觉防线的分层逻辑**
"一个知识库外的 Python GIL 问题逃过了我的 CRAG 检索门控（LLM 重排打分有波动，给了它 0.5 以上），模型自由发挥作答了。但忠实度校验器把答案拆成声明逐条比对资料，直接判 0 分识破。这让我把忠实度校验从『只告警』升级成『低分自动改判拒答』的最终兜底。"

**故事 3：真实工程坑三连**
"中文文件名上传后乱码——Windows 下 curl 以 GBK 编码发 multipart 文件名，服务端按 UTF-8 解析失败，改为手工构建 UTF-8 请求体解决；大文档入库报错——智谱 embedding API 单次最多 64 条输入，给批量向量化加了分批；接智谱官方 Rerank API 时返回空结果——排查发现必须显式传 top_n，与官方文档描述不符，已在代码注释记录。"

---

## 二、四周学习方案（按被拷打概率排序）

### 第 1 周：RAG / LLM 核心（项目灵魂，100% 被问）

| 考点 | 项目对应物 | 需掌握程度 |
|---|---|---|
| RAG 全链路 | 整条检索管道 | 白板画出：改写 → 路由 → 三通道召回 → RRF 融合 → 重排 → 门控 → 生成 → 校验 |
| Embedding / 向量检索 | Milvus 1024 维 | 余弦 vs 内积、ANN 索引（IVF/HNSW）为什么不用全量比对 |
| BM25 原理 | MySQL ngram 通道 | TF-IDF → BM25 演进、与向量检索为什么互补（精确词 vs 语义） |
| RRF 融合 | k=60 | 公式 `score = Σ 1/(k + rank)`，k 压制单通道头部垄断，60 是论文标准值 |
| Rerank | LLM / 智谱双模式 | bi-encoder（向量）vs cross-encoder（重排）原理区别 |
| 查询改写 / 路由 | QueryRewriteService | 多查询变体、跨语言召回、exact 类跳过 embedding 省成本 |
| 防幻觉 | 三防线 | RAGAS faithfulness 算法（拆声明→逐条比对）、CRAG 思想、引用标注 |
| 切片策略 | DocumentChunker | 结构感知切片、标题路径继承（chunk 携带【章·节】上下文） |

**学习资源：**

- 📺 [从0到1搭建自己的RAG（B站，通俗入门）](https://www.bilibili.com/video/BV1GTL3zGEiG)
- 📺 [Agentic RAG 大模型+智能体全流程实战（B站）](https://www.bilibili.com/video/BV1ZiEEzLE3X)
- 📺 [企业级RAG全套视频教程 15天（B站）](https://www.bilibili.com/video/BV1S7B5BDEiP)
- 🎓 [NVIDIA 官方中文课：RAG 智能体入门（免费+证书）](https://learn.nvidia.com/courses/course-detail?course_id=course-v1:DLI+S-FX-15+V1-ZH)
- 📖 [学习 RAG 看这篇就够了（知乎图文）](https://zhuanlan.zhihu.com/p/673392898)
- 📄 论文（面试提一嘴即加分）：
  - RAG Survey — arXiv:2005.11401
  - CRAG（Corrective RAG）— arXiv:2401.15884
  - ReAct — arXiv:2210.03629
  - Lost in the Middle（长上下文中间遗忘，解释为什么重排排序重要）
  - RRF 论文：Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods（2009）
- 🏗 对标仓库（说得出思路即可，本项目多处借鉴它们）：
  - [QAnything（网易有道）](https://github.com/netease-youdao/QAnything) — 两阶段检索架构
  - [RAGFlow（infiniflow）](https://github.com/infiniflow/ragflow) — Laws 切片器、中文结构化解析
  - [Dify](https://github.com/langgenius/dify) — rerank provider 抽象、混合检索
  - [GPTCache（zilliztech）](https://github.com/zilliztech/gptcache) — 语义缓存双层结构
  - [NeMo Guardrails（NVIDIA）](https://github.com/NVIDIA-NeMo-Guardrails) — 三层 rail 防护

### 第 2 周：LangChain4j + LLM API（Java 面试官最爱切入）

| 考点 | 项目对应物 |
|---|---|
| Chat API 结构 | ModelRouter.call / stream（messages/temperature/max_tokens） |
| Function Calling 协议 | ToolRegistry（JSON Schema → ToolSpecification）+ ReActAgent 手写循环 |
| 流式 SSE | chatStream：`__META__` 首事件 → 分片 → `[DONE]` |
| 多模型降级熔断 | ChatModelRegistry 注册 4 模型 + 降级链 + 三态熔断 |
| Token 记账 | UsageContext（ThreadLocal 聚合）+ usage summary 接口 |

**标准答案预置**："为什么不用框架自带的 AiServices 做工具循环？"
→ "AiServices 不暴露中间工具调用步骤，我的前端思考过程面板需要逐步透出每次工具调用的 thought/action/observation，所以手写了语义等同的循环，顺便能自己控制最大迭代和安全兜底。"

**学习资源：**

- 📖 [LangChain4j 中文文档](https://langchain4j.cn) — 概念过一遍
- 📖 [LangChain4j 官方文档](https://docs.langchain4j.info)
- 🎓 [pig-mesh/langchain4j-course（GitHub 免费 Java AI 课程）](https://github.com/pig-mesh/langchain4j-course)
- 📖 [LangChain4j 保姆级教程（知乎）](https://zhuanlan.zhihu.com/p/1926960821461839994)
- 📖 [LangChain4j 全攻略实战（掘金）](https://juejin.cn/post/7519780005393186843)
- 📖 OpenAI Function Calling 官方文档（协议事实标准，智谱兼容它）

### 第 3 周：Java 基础 + 存储三件套（从项目切八股的主战场）

| 八股考点 | 项目里的真实用例（背诵时挂上去） |
|---|---|
| ThreadLocal | UsageContext token 记账。**主动说局限**：@Async / 流式线程切换会丢上下文——ThreadLocal 经典陷阱 |
| 线程池七参数 | 知识库里就有手册原文：禁止 Executors（无界队列 OOM 风险），用 ThreadPoolExecutor 显式参数 |
| ConcurrentHashMap | 文档状态白名单 docStatusCache、PromptGuard 模式列表 |
| AtomicReference + CAS | 三态熔断器状态转换 CLOSED→OPEN→HALF_OPEN |
| @Async 原理 | 忠实度异步校验、缺口记录——AOP 代理机制、同类自调用失效 |
| AOP / 拦截器 | AuthInterceptor 校验 X-Admin-Token（写操作鉴权） |
| Redis 缓存穿透/击穿/雪崩 | 三级缓存体系；防"拒答案被缓存"= 防污染（ragMissed 不入缓存） |
| Redis 数据结构 | 语义缓存 list + trim 模拟容量上限、TTL、SCAN 模式清理 |
| MySQL 全文索引 | ngram 分词原理、BM25 相关度排序、MATCH...AGAINST 语法 |
| MySQL 索引 / B+树 | document_chunk 按 document_id 查询的索引设计 |
| 为什么不用 ES | 数据量（几百切片）+ 运维成本取舍；量级上来迁 ES 或 Milvus sparse，检索通道已抽象 |

**学习资源：**

- 📖 [小林 coding](https://www.xiaolincoding.com) — MySQL / Redis 图解（面试圣经）
- 📖 JavaGuide — javaguide.cn（八股总纲，查漏补缺用）
- 📺 B 站搜索："黑马 JUC 并发编程"、"黑马 Redis 实战"、"尚硅谷 SpringBoot"
- 📖 [Milvus 官方中文文档](https://milvus.io/zh) — 索引类型 / 度量方式 / 标量过滤
- 📖 [Redis 官方文档](https://redis.io/docs/latest/)

### 第 4 周：工程化 + 模拟拷打

- **Docker**：卷挂载热更新 jar（改代码 → mvn package → docker restart 即发布）、compose 依赖编排、.env 环境变量注入配置。📺 B 站搜 "尚硅谷 Docker"
- **评测方法论**：Recall@K / MRR / 有据率 / faithfulness 的定义与计算——`tools/eval/eval_runner.py` 就是实现，能手写伪代码
- **SSE vs WebSocket**：为什么选 SSE——单向推送足够、纯 HTTP 兼容、自动重连；WebSocket 双向但过重
- **Reactor 背压**：Flux 流式的基础概念（响应式流规范四接口）
- **Tika**：统一文档解析入口、Metadata 修正类型识别（DOCX 被 ZIP 误判的坑）
- **最后 3 天**：自问自答第三部分题库，每题录音回放检查表达

---

## 三、高频拷打题库（15 题 + 答题骨架）

**Q1 为什么弃 Spring AI 选 LangChain4j？**
多模型只需 OpenAI 兼容协议一个 provider（Spring AI 当时 completions-path 对智谱不兼容）；原生 ToolSpecification 支持 Function Calling；1.13 版本 API 稳定。替换成本：只动 infrastructure/llm 一层，上层消费者零感知。

**Q2 为什么 BM25 放 MySQL 不上 Elasticsearch？**
数据量小（几百切片）时 ngram 全文索引够用，省一个重型组件的运维成本；检索通道已抽象（vector/keyword/exact 三通道），量级上来可以无痛迁 ES 或 Milvus 原生 sparse。架构取舍题要答出"什么时候该换"。

**Q3 RRF 公式和 k=60？**
score = Σ 1/(k + rank)，对每通道排名取倒数求和。k 压制单通道第一名的优势，让"多通道都命中"的文档胜出。60 是 RRF 论文标准值，Dify/LangChain 同款。两路都命中的 chunk 得分叠加，天然实现了"语义+关键词双确认"。

**Q4 重排为什么用 LLM 打分不用 cross-encoder？**
没有本地 GPU 跑 cross-encoder（如 bge-reranker）；LLM 批量打分一次调用、用免费模型零边际成本；已抽象 provider 接口，一行配置切智谱官方 Rerank API（真 cross-encoder）。能讲出原理差异更好：bi-encoder 分别编码求相似度（快但粗），cross-encoder 联合编码 query+doc（准但慢、无法预计算）。

**Q5 语义缓存阈值 0.95 怎么定的？**
太低会误命中（相似问题不同答案——比如"上海公积金比例"和"北京公积金比例"），太高形同虚设。0.95 实测同义改写能命中、相近问题不误伤。配套两个防污染设计：拒答案不入缓存、检索弱命中（ragMissed）不入缓存。

**Q6 防幻觉为什么三道防线而不是一道？**
纵深防御：门控拦弱召回（覆盖大部分场景、成本零）；引用约束拦生成越界（逐句 [n] 标注+无据剥离）；忠实度兜底拦漏网（真实案例：GIL 问题逃过门控，被声明级校验判 0 分）。每层拦截成本递增、覆盖递减。

**Q7 怎么证明优化有效？**
52 题黄金评测集：每题带期望文档+期望关键词，关键词经 SQL 大小写敏感校验确保真实存在；跑分输出 kwRecall@5/MRR/拒答正确率/忠实度/延迟；每次改动前后对比（两阶段架构使召回率 0.851→0.968 有报告存档）。金句：**没有度量就没有优化**。

**Q8 Milvus 1024 维哪来的？**
智谱 embedding-3 支持自定义维度（256-2048），1024 是精度/存储/检索速度的折衷。踩过坑：换 embedding 模型时新旧维度不一致（1536→1024），Milvus 集合自动检测并重建。

**Q9 embedding 64 条分批是什么问题？**
智谱 API 单次请求 input 数组上限 64，大文档（200+ 切片）批量入库直接报错。修复：embedAllToVectors 按 64 分批调用。体现的是读报错信息定位 API 边界的能力。

**Q10 知识版本链怎么设计？**
document 表 version + superseded_by 字段；同名文件上传自动把旧版标 superseded 并挂链；检索白名单只放行 active/draft。曾因此抓出真 bug：关键词通道没接白名单，旧版切片污染 top-k——评测集第一轮就暴露了。

**Q11 多轮对话上下文怎么传？**
buildHistoryContext 取最近 20 轮拼进提示词（Redis 短期记忆）。主动说局限：没有指代消解（"它怎么用"指代上一轮实体），升级方向是改写时做共指解析。

**Q12 Prompt 注入怎么防？**
三层栏（借鉴 NeMo Guardrails）：输入栏窄模式正则拦高置信攻击；检索栏把文档里的指令样文本**在重排之前**中和成数据标记（关键认知：重排器也是 LLM，注入文本会污染它的评分）；输出栏 canary 哨兵检测系统提示词泄露。外加指令层级声明（用户消息和资料中的指令都是数据）。

**Q13 Token 记账怎么实现？有什么坑？**
ThreadLocal 的 begin/add/take 聚合全链路用量。**主动说坑**：@Async 方法、流式切换线程时上下文会丢——ThreadLocal 的经典局限，解法是 InheritableThreadLocal 或显式传参/装饰线程池（我评估后选择了容忍误差+按模型粗粒度统计）。

**Q14 并发量上来怎么办？**
应用无状态（会话在 Redis、数据在 MySQL/Milvus），直接横向扩容 + 负载均衡；缓存体系（改写/检索结果/语义答案三层）本身是削峰设计；Milvus 支持分布式部署，MySQL 读写分离。瓶颈分析要分层说：先压测找瓶颈再谈优化。

**Q15 和 Dify/FastGPT 比你的价值？**
商业平台通用但浅治理——它们不管理知识的过期、冲突、版本。我做了生命周期治理 + 自生产闭环 + 可度量的评测体系，是"可信"维度的垂直深挖。定位差异不是重复造轮子。

---

## 四、资源总表

| 类型 | 资源 | 用途 |
|---|---|---|
| 视频 | [从0到1搭建RAG](https://www.bilibili.com/video/BV1GTL3zGEiG) | 第 1 周 RAG 入门 |
| 视频 | [Agentic RAG 全流程实战](https://www.bilibili.com/video/BV1ZiEEzLE3X) | 第 1 周 RAG 进阶 |
| 视频 | [企业级RAG全套 15天](https://www.bilibili.com/video/BV1S7B5BDEiP) | 第 1 周 查漏补缺 |
| 课程 | [NVIDIA RAG 中文课](https://learn.nvidia.com/courses/course-detail?course_id=course-v1:DLI+S-FX-15+V1-ZH) | 第 1 周 免费+证书 |
| 图文 | [RAG 看这篇就够了（知乎）](https://zhuanlan.zhihu.com/p/673392898) | 第 1 周快速通读 |
| 文档 | [LangChain4j 中文文档](https://langchain4j.cn) / [官方](https://docs.langchain4j.info) | 第 2 周主教材 |
| 课程 | [pig-mesh/langchain4j-course](https://github.com/pig-mesh/langchain4j-course) | 第 2 周 Java AI 实操 |
| 图文 | [LangChain4j 保姆级教程（知乎）](https://zhuanlan.zhihu.com/p/1926960821461839994) / [全攻略（掘金）](https://juejin.cn/post/7519780005393186843) | 第 2 周补充 |
| 图文 | [小林 coding](https://www.xiaolincoding.com) | 第 3 周 MySQL/Redis 八股 |
| 文档 | [Milvus 中文文档](https://milvus.io/zh) | 第 3 周向量库原理 |
| 视频 | B 站搜："黑马 JUC" "黑马 Redis" "尚硅谷 Docker" | 第 3-4 周八股补课 |
| 论文 | arXiv: 2005.11401 / 2401.15884 / 2210.03629 | RAG/CRAG/ReAct 原始出处 |
| 仓库 | QAnything / RAGFlow / Dify / GPTCache / NeMo Guardrails | 对标与借鉴来源 |

## 五、面试当天检查清单

- [ ] 能 3 分钟讲完项目架构（拿 README 的架构图对着白板画）
- [ ] 三个杀手锏故事每个 1 分钟版本 + 3 分钟展开版
- [ ] 15 题题库全部过了至少两遍，每题先给结论再给展开
- [ ] 主动暴露的坑准备好了（ThreadLocal 异步丢上下文、多轮无指代消解）——主动说比被问出来好
- [ ] 反问环节问题准备好（如：贵司知识库场景的评测是怎么做的？）
