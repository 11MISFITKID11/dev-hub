# 缓存三防与消息队列改造设计说明

> 2026-10 改造：解决两个对标短板——缓存穿透/击穿/雪崩防护、异步任务消息队列化。
> 面向读者：维护者与评审者。每节先讲"为什么改"，再讲"怎么做的"。

## 一、缓存三防（RedisCacheService）

### 改造前的问题

`RedisCacheService` 只有裸 get/set/expire，调用方各自手写"先查缓存、未命中回源、写缓存"。
三个典型风险：

| 风险 | 场景 | 后果 |
|---|---|---|
| 穿透 | 查询不存在的 key（恶意/无效请求） | 每次都打到 Milvus / LLM，缓存形同虚设 |
| 击穿 | 热点 key 过期瞬间并发未命中 | 并发请求全部同时回源，LLM 调用被放大 N 倍（还有限流熔断连锁反应） |
| 雪崩 | 同批写入的 key 同时到期 | 回源洪峰集中打到后端 |

### 方案（getOrLoad 读穿 + jitter）

新增统一读穿入口，调用方只提供回源逻辑：

```java
Optional<T> getOrLoad(String key, Class<T> clazz, Supplier<T> loader, Duration ttl)
```

1. **防穿透**：`loader` 返回 null 时写入空值占位符（非 JSON 的哨兵串
   `__DEVHUB_NULL__`，60s TTL），占位存活期内相同 key 不再回源。真实数据写入后
   调用 `delete()` 即可立即纠正（delete 同时清占位符）。
2. **防击穿**：未命中时 `setIfAbsent(key:lock, 10s)` 抢互斥锁，仅持锁者回源；
   未抢到的请求自旋 3×50ms 等缓存（含占位符场景），超时降级直接回源——
   可用性优先，最坏情况退化为无缓存而不是报错。
3. **防雪崩**：回源写入的 TTL 经 `jitter()` 抖动（基准 ±20%，下限 1s），
   同批 key 的到期时间被打散。

双检：抢到锁后再查一次缓存，排队期间前一个持锁者可能已完成回源，避免重复回源。

### 落地位置

- `QueryRewriteService.rewrite()`：改写要调 LLM（贵且慢），是击穿放大的高危点。
  改造后并发同问只放一个 LLM 调用；改写失败返回 null → 空值占位 60s，
  同一失败问题不再反复冲击模型（配合限流器双保险）。
- `SemanticAnswerCache.store()`：精确层 key 与相似度层列表的 TTL 均加抖动。
- 其他现有调用方（get/set 裸用）不受影响，可逐步迁移到 getOrLoad。

## 二、文档入库消息化（Redis Stream）

### 改造前的问题

上传解析走 Spring `@Async` + 应用内事件（`AsyncIngestService`）：

- **丢任务**：队列在进程内存，应用重启/崩溃时未执行的上传永久丢失，
  前端却显示"解析中"（PROCESSING 外壳行无人更新）。
- **无重试**：ETL 失败（Milvus 抖动、embedding 超时）一次即终，只能重新上传。
- **无死信边界**：毒丸文件（解析必挂）会一直占线程池。

### 方案（消费者组 + 手动 ACK + PENDING 恢复 + 死信）

```
上传接口                    Redis                        消费者（应用内）
   │ 1.预插 PROCESSING 外壳行  │                              │
   │ 2.字节落临时文件           │                              │
   │ 3.XADD ──────────────▶ devhub:stream:doc-ingest          │
   │        （MAXLEN 1万 限长） │──── XREADGROUP ────────────▶ │ 4.解析→切片→向量化
   │                          │ ◀─── XACK（成功才确认）────── │    →外壳行置 completed
   │                          │    失败：不 ACK，留 PENDING    │
   │                          │         ▲                    │
   │                          │ 5.恢复任务每分钟 XPENDING/XCLAIM（空闲>2min 认领重试）
   │                          │ 6.投递≥3次 → 死信：清理外壳行+临时文件+ACK，日志留档
```

关键设计决策：

- **至少一次投递 + 幂等**：Stream 保证 at-least-once，重复投递由
  `processIngest` 幂等拦截——外壳行 `completed` 即跳过直接 ACK。
- **失败补偿与重试的配合**：ETL 失败会补偿删除外壳行（防幽灵行），
  重试前消费者从消息元数据（uploader/categoryId/deptId 都在消息里）
  重建外壳行，分类/上传人/部门不丢失。
- **临时文件生命周期**：处理成功或死信终态才删；PENDING 期间保留，
  重启后恢复任务仍能读到（同机部署，绝对路径存消息）。
- **消费者组从 0 建组**：应用重启后未消费的消息不丢。
- **恢复阈值 2 分钟**：大于单次 ETL 预期耗时，避免与正常消费中的消息重叠。

### 涉及类

| 类 | 职责 |
|---|---|
| `DocIngestStreamConfig` | Stream/组常量、建组、监听容器 |
| `AsyncIngestService` | submit（外壳行+XADD）、processIngest（幂等处理） |
| `DocIngestStreamConsumer` | onMessage（消费+ACK）、recoverPendingMessages（认领重试）、deadLetter（终态清理） |

### 与 @Async 的取舍

@Async 适合"丢了无所谓"的旁路操作（发通知、记日志）；凡是"用户在等结果"
的任务（文档解析状态可见、可重试）应该走持久化队列。当前规模单机 Redis Stream
够用且零新增中间件；未来多实例部署时消费者组天然支持横向扩容，需要
与 Kafka/RabbitMQ 对比时迁移成本也只在生产/消费两端。

## 三、顺带修复：WorkflowEngine 前进跳级

单测暴露：`nextForwardState` 取 sort **最大**的可流转目标，
TODO 同时定义 →DOING 和 →DONE 时"前进一步"会直接跳到 DONE。
已改为取**最近的**更大 sort（逐级前进），见 `WorkflowEngineTest.nextForwardState_picksHigherSort`。

## 四、验证

- 单测 20 个（`mvn test`）：缓存三防 7 个、Stream 消费链路 4 个、切片器 4 个、工作流引擎 5 个。
- 冒烟：`start-dev.bat` 启动后上传文档，观察日志
  `入库任务已入队` → `异步入库完成`；`docker exec agent-redis redis-cli XINFO GROUPS devhub:stream:doc-ingest`
  可见消费者组与 pending 计数。
