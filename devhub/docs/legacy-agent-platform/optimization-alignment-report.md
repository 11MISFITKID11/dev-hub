# 优化方案对齐报告：借鉴 vs 自研

> 2026-10-15 · 结论：架构模式普遍借鉴 GitHub 高星项目，但实现均为手写；本轮对 3 个"重复造轮子"点按业界成熟方案重构并实测。

## 一、逐项审计

| # | 组件 | 性质 | 对标项目 | 状态 |
|---|------|------|----------|------|
| 1 | 混合检索 + RRF 融合 | 借鉴实现 | Dify Hybrid Search、LangChain EnsembleRetriever（RRF k=60 标准参数） | 已对齐，无需改 |
| 2 | 查询改写（归一化/变体/英文/分类） | 借鉴实现 | LangChain MultiQueryRetriever、Dify 问题改写 | 已对齐 |
| 3 | 三层缓存之改写缓存/检索缓存 | 自研（常规模式） | 各 RAG 平台标配 | 保留 |
| 4 | **语义答案缓存** | 自研简化版 | **GPTCache（zilliztech，8k+★）**：exact-match 层 + similarity 层双层数据流水线 | ✅ 本轮补上精确层 |
| 5 | **重排序** | 自研（LLM-as-reranker） | **QAnything（网易有道）两阶段检索**：召回后必须 cross-encoder 精排；Dify 的 rerank provider 抽象（Cohere/Jina/BGE 可插拔） | ✅ 本轮接入智谱官方 Rerank API + provider 抽象 |
| 6 | **结构化切片** | 自研（参考 LlamaIndex NodeParser 思路） | **RAGFlow Laws chunker**（infiniflow，30k+★）：中文法律/手册类文档按 章→节→条 层级切分，**chunk 携带层级路径** | ✅ 本轮补上 L1/L2 标题路径继承 |
| 7 | LLM 切分精修（D1） | 自研 | 最接近：LlamaIndex SemanticSplitter（embedding 斜率切分，另一流派）；RAGFlow 的模板切分不覆盖此场景 | 保留自研（已有实测收益：截断率下降） |
| 8 | 检索路由（exact/normal/complex） | 自研（搭改写便车） | **semantic-router（aurora-gen-ai，3k+★）**：embedding 余弦分类，零 LLM 成本 | 等价保留：我们复用改写调用的分类输出，同样零额外成本，且多拿到改写文本 |
| 9 | 知识生命周期（版本链/生效日期/URL探活/冲突检测/过期） | 自研 | 无直接开源对标（RAGFlow/Dify 均无生命周期治理）；思想最接近企业 CMS 的内容治理 | 保留（差异化能力） |
| 10 | 知识缺口检测 + 自生产闭环 | 自研 | 最接近：Self-RAG（反思触发检索）思想；Draft-验证-人工审核是通用数据飞轮模式 | 保留（差异化能力） |
| 11 | 原生 Function Calling ReAct 循环 | 手写（语义等同框架内置） | **LangChain4j AiServices**：框架自带工具循环 | 有意保留手写：AiServices 不暴露中间工具调用步骤，前端"思考过程"面板需要逐步记录 |
| 12 | Embedding 分批（64/批） | 自研（本轮修复） | LangChain4j EmbeddingStoreIngestor 内部同款分批策略 | 已对齐 |

## 二、本轮重构明细

### 1. Reranker：接入智谱官方 Rerank API（对标 QAnything/Dify）
- `Reranker.java` 重构为 provider 抽象：`agent.rag.rerank.provider = llm | zhipu`
  - `zhipu`：智谱 cross-encoder 重排（`POST /api/paas/v4/rerank`），复用现有 API Key，精度高、按量计费
  - `llm`：glm-4-flash 单次批量打分，免费，**默认值**（成本最优）
  - zhipu 失败自动降级 llm，保证链路可用
- 踩坑记录：智谱 rerank **必须显式传 `top_n`**，缺省时返回空 `results`（与官方文档"默认全部"不符），已写死 `top_n=候选数`
- 观察结论：智谱 rerank 对短文本打分饱和（0.999+ 聚集），rank-1 排序准确但区分度弱；对检索质量要求高时再开启
- 开关方式：`.env` 中 `AGENT_RAG_RERANK_PROVIDER=zhipu` 后 `docker compose up -d app`

### 2. SemanticAnswerCache：补 GPTCache 式精确匹配层
- `lookup` 先查 MD5(归一化问题) 的 Redis 精确层 → 命中直接返回（零向量计算）；未命中再走余弦相似度层
- `store` 双写两层，24h TTL 一致
- 实测：重复提问从"余弦扫描 200 条"变为"单 key 直查"，日志输出 `语义缓存精确层命中`

### 3. DocumentChunker：补 RAGFlow Laws 式层级上下文继承
- 两级标题识别：L1（`#`/`##`、第X章/篇/部分、中文顿号序号短行）+ L2（`###`/`####`、第X节、`(一)`/`1.` 短行、加粗行）
- 标题启发式（同 RAGFlow）：行短（≤30字）且无句末标点 → 标题；否则视为列表项不继承
- 每个 chunk 前置 `【L1 · L2】` 路径，脱离原文仍可定位条款出处
- 实测：Java 手册 186/204、Arthas 手册 202/249 切片携带标题路径；引用来源显示 `【二、异常日志 · 7.【强制】…】`

## 三、为什么保留的自研部分不再改

- **D1 LLM 切分精修**：RAGFlow/LangChain 均无"指令式拆并 + 长度守恒校验"等价物，且已有实测收益，改用 embedding 斜率切分需重训调参，性价比低
- **检索路由**：semantic-router 的价值在"无 LLM 调用前提下分类"；我们的改写调用本来就发生，分类是顺带输出，架构上已是最优成本
- **ReAct 手写循环**：与 LangChain4j `DefaultAiServices` 内部循环语义一致，额外换来了 thinkingSteps 透出；迁 AiServices 会丢失前端思考过程面板
- **生命周期 + 知识自生产**：对标项目缺失，属于本项目的差异化设计（V3 B/C 期已实测）

## 参考

- GPTCache: https://github.com/zilliztech/gptcache
- RAGFlow: https://github.com/infiniflow/ragflow （Laws 切片器 `rag/app/laws.py`）
- QAnything: https://github.com/netease-youdao/QAnything （两阶段检索架构文档）
- semantic-router: https://github.com/aurora-gen-ai/semantic-router
- 智谱 Rerank API: https://docs.bigmodel.cn/api-reference/模型-api/文本重排序
- LangChain4j: https://github.com/langchain4j/langchain4j
