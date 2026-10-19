# DevHub 功能深化迭代方案（对标 GitHub 高星 / 商业研发平台）

> 2026-10-18 调研。背景：当前 DevHub 的 CRUD 骨架已完整（项目/迭代/成员/任务状态机/看板/缺陷/文档中心/RAG/权限/日志），但每个模块停留在"能增删改查"层面。本文按"把每个模块从 CRUD 深化到工作流"的思路，给出三档迭代路线。
>
> 对标对象：**Plane**（30k★，开源 Jira 替代）、**Linear**（研发追踪标杆）、**禅道**（国产全覆盖）、**PingCode / 云效**（商业一站式）、**DORA 指标体系**（研发效能事实标准）。

---

## 一、为什么"浅"：对标后的差距地图

| 维度 | DevHub 现状 | Plane/Linear/PingCode 的做法 | 差距本质 |
|---|---|---|---|
| 任务 | 字段 CRUD + 三态 | **工作流引擎**：自定义状态机/字段/视图、循环周期(cycle)、WIP 限制、泳道 | 我们是"记录"，他们是"流程" |
| 缺陷 | 独立 CRUD | **缺陷↔任务↔需求联动**：关联/转换/合并、严重度矩阵、版本回归清点 | 缺陷是孤岛 |
| 迭代 | 一张表 | **迭代是核心工作容器**：燃尽图、速率(velocity)、周期自动开合 | 迭代只是个名字 |
| 报表 | 计数卡 | **研发效能**：周期时长(cycle time)、吞吐量、累积流图(CFD)、DORA 四指标 | 我们数数，他们度量流 |
| 协作 | 无 | **评论@通知流**：每个对象有时间线、操作动态、通知中心 | 单机工具感 |
| 权限 | 角色+菜单 | **对象级权限**：项目成员即权限边界、访客只读 | 部门级太粗 |
| AI | 问答模块 | **AI 遍布全流程**：任务摘要、缺陷去重、周报生成（云效/PingCode 已产品化） | AI 还是外挂 |

---

## 二、迭代方案（三档，按 ROI 排序）

### P0 深水区（每项 2-4 天，把"记录"变"流程"——价值密度最高）

**1. 对象时间线与协作评论**（借鉴 Linear/Plane 的 Activity 流，≈2 天）
- 新表 `prj_comment(id, obj_type, obj_id, content, mentions, creator, create_time)` + `prj_activity(id, obj_type, obj_id, action, actor, detail_json, create_time)`
- 任务/缺陷详情抽屉：评论列表 + @成员（解析 mentions）+ 操作历史（谁改了状态/指派/优先级，改动 diff）
- 改状态/指派时由 @OperLog 切面之外再落一条 activity（业务时间线，与审计日志分离）
- **为什么第一优先**：这是"工具感 → 产品感"的分水岭，且全部对象共用一套表

**2. 缺陷↔任务关联与质量门禁**（借鉴禅道/云效，≈2 天）
- `prj_defect.task_id` 已有 → 做成双向：任务详情显示关联缺陷，缺陷详情跳任务
- 缺陷解决 → 自动回写关联任务动态；迭代完成 → **缺陷清点**（该迭代新增/解决/遗留）
- 严重度×状态矩阵视图（哪个迭代漏测最多）

**3. 迭代周期化：燃尽图 + 速率**（借鉴 Plane Cycles / Jira Sprint，≈2.5 天）
- 迭代内任务按日快照 `prj_burndown(iteration_id, date, open_count)`（每日定时任务 + 手动触发）
- ECharts 燃尽图（理想线 vs 实际线）+ 近 5 迭代速率柱状图
- 迭代状态机（未开始/进行中/已关闭），关闭时未完成任务强制移回 backlog

**4. 流式效能报表（借鉴 Swarmia/LinearB，≈3 天）**
- 周期时长 cycle time：任务从创建→DONE 的耗时分布（P50/P90）——只需在任务状态变更 activity 里取时间戳计算
- 累积流图 CFD：按日统计 TODO/DOING/DONE 三列堆叠面积图——WIP 可视化的标准图
- 吞吐量（每周完成数）+ 看板列 WIP 限制（超限红点提示，借鉴 Atlassian 指南）
- **不做完整 DORA**（需接 CI/CD 数据源，暂无部署流水线），但 cycle time/吞吐/CFD 是其子集且零外部依赖

### P1 中水区（每项 3-5 天，结构升级）

**5. 自定义工作流引擎**（借鉴 Plane/Jira，≈5 天）
- 新表 `prj_workflow_state(id, project_id, name, category(TODO/DOING/DONE), wip_limit, sort)`
- 任务状态从硬编码 TODO/DOING/DONE → 项目级可配置状态集 + 允许的流转规则表
- 现有状态机校验逻辑改为查规则表；看板列动态渲染
- 这是技术上最有含金量的一项（面试可讲元数据驱动设计），但先做 P0 价值更高

**6. 对象级权限与项目空间**（借鉴 Linear 团队边界，≈3 天）
- 权限判定从"部门数据域"细化到"项目成员表"：非成员对项目下任务/缺陷只读
- 项目成员角色（OWNER/DEV/QA）驱动：仅 OWNER 可删项目/管成员，QA 可转缺陷
- 现有 prj_member 表已在，缺的是拦截层（AOP 切 `@ProjectScoped` 注解统一校验）

**7. 通知中心**（≈3 天）
- `sys_notification(id, user, type, title, content, ref_url, read, create_time)`
- 评论@、任务指派、缺陷转给我、迭代关闭、文档审核结果 → 五类事件写通知
- 顶栏铃铛 + 未读数轮询（WebSocket 可后补）

### P2 浅水区（AI 融合，把 RAG 能力反哺全系统）

**8. 任务智能摘要与缺陷去重**（≈2 天）
- 已平移的 `infrastructure/llm`（多模型路由）本就是宿主公共能力：任务列表一键"生成本周进展"、提缺陷时调用向量检索提示"历史相似缺陷 Top3"（复用 Milvus + document_chunk 通道，把历史缺陷也切片入库）
- **这是"RAG 平移进传统项目"叙事的终极证明：AI 基建反哺业务**

**9. 缺陷知识库自动沉淀**（≈2 天）
- 缺陷 CLOSED 时自动把"现象+根因+解法"（评论区约定模板）写入知识库 ETL → 下次相似缺陷直接命中
- 知识生产闭环从"问答拒答"扩展到"交付自动沉淀"

**10. 周报生成器**（≈1 天）
- 按@维度聚合：本周完成/进行中/新增缺陷/AI 问答高频问题 → LLM 生成 Markdown 周报

---

## 三、排期建议（4 周）

| 周 | 内容 | 交付物 |
|---|---|---|
| 1 | P0-1 时间线评论 + P0-2 缺陷关联 | 任务/缺陷详情有协作与历史 |
| 2 | P0-3 迭代周期化 + P0-4 效能报表 | 燃尽图/CFD/周期时长上线 |
| 3 | P1-6 对象级权限 + P1-7 通知中心 | 权限细化 + 五类通知 |
| 4 | P2-8/9/10 AI 反哺 | 缺陷去重/沉淀/周报 |

P1-5（工作流引擎）建议单独立项——它值得一周专注做，混在排期里容易烂尾。

## 四、明确不做

- ❌ 完整 DORA（无 CI/CD 数据源，接了也是假数）
- ❌ 甘特图/项目集（PingCode 级功能，单项目阶段无意义）
- ❌ Git 集成/代码评审（云效领域，越界）
- ❌ 多租户 SaaS 化

## 五、一句话

**前 18 个菜单解决"有没有"，这一轮解决"深不深"：每个对象从一条记录变成一条有历史、有协作、有度量的流。** P0 四项做完，DevHub 就从"演示系统"跨入"小团队真敢用"的区间。

---

**参考**：[Plane](https://github.com/makeplane/plane)（30k★）· [Linear](https://linear.app) · [禅道](https://www.zentao.net) · [PingCode](https://pingcode.com/why-pingcode) · [云效](https://www.aliyun.com/product/yunxiao) · [DORA 指标](https://dora.dev/guides/dora-metrics) · [Atlassian WIP 指南](https://www.atlassian.com/agile/kanban/wip-limits) · [Swarmia×Linear](https://linear.app/integrations/swarmia) · [NocoBase 开源 PM 盘点](https://www.nocobase.com/cn/blog/github-open-source-project-management-tools)
