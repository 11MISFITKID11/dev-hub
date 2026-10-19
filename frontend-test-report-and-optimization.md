# DevHub 前端测试报告与优化方案

> 测试日期：2026-10-18 · 测试人：前端测试工程师（ZCode）
> 测试对象：`devhub-web`（Vue 3.5 + Element Plus 2.14 + Vite 8 + Pinia）× `devhub`（Spring Boot 3.5，端口 8080）
> 测试方式：API 黑盒（curl 经 Vite 代理 5173→8080）+ 浏览器 GUI 黑盒（真实点击/输入/截图）

---

## 一、前后端连接评测结论：✅ 连接正常

| 验证项 | 结果 |
|---|---|
| Vite 代理 `/api` → `localhost:8080` | ✅ 21 个核心端点全部 HTTP 200 且返回真实数据 |
| 登录/退出（JWT 签发、Bearer 头、localStorage 持久化） | ✅ |
| 未带 token 访问受保护端点 | ✅ HTTP 401 + `{"code":401,"message":"未登录或令牌已失效"}`，前端自动清 token 跳登录页 |
| 错误密码 | ✅ 返回业务码 401 提示（HTTP 200 信封，前端拦截器正常弹错） |
| SSE 流式对话 `/api/v1/chat/stream` | ✅ `__META__` 事件携带 conversationId 与 RAG 引用来源，流式渲染正常 |
| LLM（glm-4-flash）+ Milvus 检索链路 | ✅ 回答带《阿里巴巴Java开发手册》引用出处 |
| 角色菜单（admin 12 项 / dev01 9 项） | ✅ 按登录返回 menus 动态渲染 |
| 暗色模式 / 路由守卫 / 会话残留恢复 | ✅ |

## 二、GUI 黑盒测试发现的缺陷

### 🔴 P0-1 表格插槽名写错 `#d="s"` → 38 处列渲染空白（8 个页面全中招）
- **现象**：项目/任务/缺陷/文档/系统管理/AI 用量报表各表格中，凡用 `<template #d="s">` 的列（负责人、状态、优先级、起止、审核、**操作列**等）全部空白；只有带 `prop` 的列正常。
- **影响**：**编辑/删除/推进/详情/发布/驳回等全部操作按钮不可见**，表格页的 CRUD 从 UI 层面被阻断——属于致命缺陷。
- **根因**：Vue 3 中 `#d` 是名为 `d` 的具名插槽；`el-table-column` 只渲染 `default` 插槽。
- **证据**：项目管理页 DOM 快照——`row "智能问答平台 AI-KB"` 仅前两个 cell 有值，其余 4 个 cell 为空；截图见 `gui-test-screenshots/`。
- **涉及文件**：Project.vue(9) Task.vue(5) Defect.vue(5) DocCenter.vue(4) ai/Ops.vue(6) ai/History.vue(3) Report.vue(3) System.vue(3)

### 🟠 P1-2 任务看板切换不触发数据加载，永远显示 0
- **现象**：点击「看板视图」后三列均显示 `待办（0）/进行中（0）/已完成（0）`；后端 `/api/tasks/board` 实际有数据。
- **根因**：`Task.vue` 切换按钮仅 `boardV = !boardV`，未调用 `load()`；`board` 数据仅在筛选变化等触发 `load()` 时才拉取。

### 🟠 P1-3 登录失败弹出两条重复错误提示
- **现象**：错误密码登录后同时出现「用户名或密码错误」与「登录失败：用户名或密码错误」两条。
- **根因**：axios 响应拦截器已 `ElMessage.error`，`Login.vue` 的 catch 又拼接一条。

### 🟡 P2-4 登录页暗色首帧闪烁
- 暗色 `html.dark` 仅在 `Layout.vue` 挂载时应用，登录页/首帧无暗色。

### 🟡 P2-5 杂项
- `vite.config.js` 注释写「后端（8081）」实际代理 8080（过期注释误导联调）；
- 前端无 ESLint / 单元测试；`Project.vue` 的 `watch` 在 `import` 之后声明（风格问题）。

### ✅ 通过项
登录/退出链路、AI 对话流式+引用出处、看板卡片样式、新建任务弹窗表单、工作台统计卡、暗色切换、字典加载、分页、知识运营页数据。

## 三、设计对标（GitHub 优秀项目）

| 借鉴对象 | 采纳点 |
|---|---|
| [vue-pure-admin](https://github.com/pure-admin/vue-pure-admin) | `src/styles` 分层（令牌 → 组件覆盖 → 暗色适配）；暗色用 1px 边框替代阴影 |
| [vue-vben-admin 5.x](https://github.com/vbenjs/vue-vben-admin) | Design Token（`--radius`、间距 4px 基数、`--background/--card` 语义底色）；登录页居中卡片范式 |
| [vue-element-admin](https://github.com/PanJiaChen/vue-element-admin) | 表格页四段式：筛选行 → 工具栏 → 表格 → 右对齐分页；状态列 statusMap 彩色 tag |
| [Ant Design ProTable](https://procomponents.ant.design/components/table) | 分页含 `total, sizes`；主操作按钮在工具栏左侧 |
| [Atlassian Kanban](https://www.atlassian.com/agile/kanban/boards) / ServiceNow Horizon | 看板列头 = 状态色点 + 标题 + 计数；卡片带优先级与截止信息 |

## 四、优化方案（执行清单）

### A. 缺陷修复（必做）
| # | 内容 | 文件 |
|---|---|---|
| A1 | `#d="s"` → `#default="s"` 全量替换（38 处 / 8 文件） | 上述涉及文件 |
| A2 | 看板切换时调用 `load()` 拉取看板数据 | Task.vue |
| A3 | 登录失败只保留拦截器一条提示 | Login.vue |
| A4 | 应用启动即应用暗色主题（消除首帧闪烁） | main.js |
| A5 | 修正 vite 代理注释；Project.vue `watch`/import 顺序 | vite.config.js、Project.vue |

### B. 设计优化（对标上述项目，保持轻量渐进）
| # | 内容 |
|---|---|
| B1 | 新增 `src/styles/index.css` 设计令牌：品牌主色覆盖 EP 变量、统一圆角/间距、`--dh-page-bg` 页面底色（亮暗自适应）、卡片阴影亮色化/暗色描边、通用 `.table-toolbar`/`.pager` 类 |
| B2 | 分页统一升级：`layout="total, sizes, prev, pager, next"` + `background`（ProTable 规范） |
| B3 | 看板列头加状态色点（Atlassian 规范），卡片补优先级 tag 与截止日 |
| B4 | 登录页现代化：品牌渐变背景 + 居中卡片 + 大输入框 + 全宽主按钮 + 暗色适配（vben 范式） |

### C. 回归验证
`vite build` 构建通过 + 浏览器回归：表格列恢复显示、操作按钮可用、看板有数据、登录错误单条提示、暗色无闪烁。

---

## 五、执行记录与回归结果

### 执行记录（2026-10-18）

| 项 | 变更 | 文件 |
|---|---|---|
| A1 | `#d="s"` → `#default="s"` ×38 处 | Project/Task/Defect/DocCenter/Report/System/ai-History/ai-Ops 共 8 个 .vue |
| A2 | 新增 `toggleBoard()`：切换看板时调用 `load()` | Task.vue |
| A3 | 登录 catch 不再重复弹错，移除多余 ElMessage 导入 | Login.vue |
| A4 | main.js 启动即按 `dh_theme` 应用 `html.dark` | main.js |
| A5 | 代理注释 8081→8080；`watch` 提升到 import 中、移除文件尾 import | vite.config.js、Project.vue |
| B1 | 新增设计令牌与全局样式（EP 主色覆盖为品牌蓝 #0071e3 全套、圆角/间距/页面底色令牌、暗色描边策略、`.table-toolbar`/`.pager`/看板列头类、滚动条细化） | src/styles/index.css（新）、main.js 引入 |
| B2 | 4 个列表页分页升级：`total, sizes, prev, pager, next` + `background` + `v-model:page-size` 响应式（load 参数同步改造） | Project/Task/Defect/DocCenter |
| B3 | 看板列头状态色点 + 计数；卡片补优先级 tag 与截止日 | Task.vue |
| B4 | 登录页改版：品牌区（logo+名称+slogan）、欢迎文案、径向渐变背景、页脚、暗色变量适配 | Login.vue |

### 回归验证（全部通过）

| 验证点 | 修复前 | 修复后 |
|---|---|---|
| `vite build` | — | ✅ 606ms 构建通过（chunk 体积告警为存量问题） |
| 项目管理表格 | 负责人/状态/起止/操作 4 列空白 | ✅ `row "P166 系统管理员 进行中 ~ 编辑 迭代 成员 删除"` 全列渲染 |
| 缺陷跟踪表格 | 严重级/状态/修复人/操作空白 | ✅ `row "crash on save dialog 12 一般 待修复 - 详情 编辑 删除"` |
| AI 用量报表 | 日期/合计空白 | ✅ `10-18 ... 22129` 渲染（占比列为渐变条无文本，正常） |
| 任务看板 | 切换后三列恒为 0 | ✅ 待办（7）/进行中（3）/已完成（3），卡片含标题+优先级+项目·指派人 |
| 登录错误提示 | 2 条重复 alert | ✅ 仅 1 条「用户名或密码错误」 |
| 登录页暗色首帧 | class=''（闪白） | ✅ class='dark' 即刻生效 |
| 重新登录 admin | — | ✅ 跳转 /#/ai/chat，系统恢复可用 |

### 遗留建议（未在本次执行，按需排期）
1. **构建体积**：`index` chunk 867KB、`Chat` 1001KB —— highlight.js/marked 建议按需引入或 manualChunks 拆分（vite build 已给出 rolldown codeSplitting 提示）。
2. **质量保障**：前端无 ESLint/Prettier/Vitest；建议补充 `eslint-plugin-vue` + 核心流程（登录、API 拦截器）单测——本次 38 处同拼写错误正是缺乏 lint 的产物。
3. **交互打磨**：表格可加 `border stripe`、操作列 `fixed="right"`；DocCenter 上传轮询可换 SSE；暗色下图表配色需人工过一遍。
4. **后端小项**：登录失败建议返回 HTTP 401 而非 200+code（当前前端已兼容，不影响功能）；CORS 未配置（当前走 Vite 代理/nginx 同源部署，不影响，若未来直连需补）。

---

## 六、第二轮升级：Apple 风格毛玻璃设计改版（2026-10-18）

应需求「借鉴苹果公司设计，圆角 + 毛玻璃，高端大气」，以 macOS Big Sur / iOS 材质语言为蓝本整体改版。

### 设计体系（src/styles/index.css 重写）

| 要素 | 实现 |
|---|---|
| 字体 | 苹果系统字体栈：`-apple-system → SF Pro → PingFang SC`，开启字体平滑 |
| 极光壁纸 | `body::before` 固定层，四组大尺度径向渐变（Apple Blue/Purple/Cyan/Pink 低饱和晕染），亮暗双套 |
| 毛玻璃三档材质 | `glass`（内容卡片 62% 白 + blur24 + saturate180%）/ `glass-2`（侧栏顶栏 72% 白 + blur32）/ `glass-3`（弹层 82% 白 + blur36），暗色同名变量自动翻转为深空黑系 |
| 圆角体系 | 卡片 18px、浮层 22px、控件 9px（--el-border-radius-base）、消息胶囊 999px |
| 发丝描边 | 亮色 rgba(255,255,255,.55) 高光边 + rgba(0,0,0,.07) 分隔线；暗色 rgba(255,255,255,.10) |
| 分层阴影 | 卡片双层柔影 / 浮层深影，暗色加深 |

### 组件落地

- **侧栏**：Chrome Material 玻璃 + macOS 风格菜单胶囊（选中项变品牌蓝圆角胶囊，悬停半透明蓝）
- **顶栏**：玻璃条 + 发丝下边线；头像加品牌色投影
- **表格**：透明化挂在玻璃卡片上，表头去底色、悬停行品牌蓝 6% 着色
- **弹窗/抽屉/下拉/消息**：全部 glass-3 浮层材质，22px/16px 圆角
- **看板**：玻璃列（16px 圆角）+ 玻璃卡片（13px、悬停上浮 1px 投影加深）
- **登录页**：macOS 登录屏式鲜艳极光（带 26s 缓慢漂移动画）+ 32px blur 玻璃卡 + 品牌蓝全宽按钮
- **对话页**：用户气泡品牌蓝渐变+投影，AI 回答玻璃气泡，会话列表玻璃面板+胶囊选中态；**顺带修复点击历史会话不加载消息的缺陷**（原 `@click="cur = c"` 未调 openConv）
- **工作台**：统计数字品牌蓝渐变文字（-webkit-background-clip）

### 验证结果

- `vite build` ✅ 605s 级通过（605ms）
- GUI 实测截图 6 张（亮/暗双色）：工作台、暗色工作台、看板、对话页、项目表格、极光登录页 —— 存于 `gui-test-screenshots/apple_*.png`
- 功能回归：表格数据完整、看板加载、登录/退出（admin 已恢复登录态）
- 兼容性：`backdrop-filter` 已加 `-webkit-` 前缀；Chromium/Edge/Safari 均支持，无需降级

---

## 七、第三轮执行：模板层结构优化（P0→P1→P2，2026-10-18）

按第二轮评估结论执行。**新增 6 个基础设施文件，改造 11 个视图，`vite build` 通过（600ms）。**

### P0 结构性改造

| 项 | 内容 | 文件 |
|---|---|---|
| useCrudList composable | 统一 page/size/total/rows/loading/load/reset + debounce 工具 | `src/composables/useCrudList.js`（新，38 行） |
| options store | 用户/项目下拉选项会话级缓存，选择器与表格名称映射共享一份数据（原每页重复请求 ×4） | `src/stores/options.js`（新） |
| 3 个基础组件 | `DictTag`（字典+统一语义色映射）、`UserSelect`、`ProjectSelect` | `src/components/`（新） |
| 页面接入 | Project/Task/Defect/DocCenter 全部改用 composable + 组件，删除各自复制的 load/mapper/色彩映射 | 4 个视图 |
| 表单校验+防重 | 5 个弹窗补 `:rules`（必填/最小长度）+ validate + 确定 `:loading` | Project/Task/Defect/DocCenter/System |
| 404 路由 | 玻璃卡 404 页 + catch-all | `views/NotFound.vue`（新）、router |
| 路由权限过滤 | 守卫按登录 menus 的叶子 path 过滤（menus 缺失时不拦截防锁死）；`/metrics` 隐藏页白名单 | `stores/user.js`（allowedPaths/firstPath）、router |

### P1 交互细节
- Project 关键字搜索防抖 300ms（原每击键一次请求）
- Layout `<keep-alive :include>` 缓存 11 个页面（列表筛选/页码切页保留）
- 4 个列表页操作列 `fixed="right"`，标题列 `show-overflow-tooltip`
- 表格空态 `el-empty` + 「新建第一个 XX」引导按钮
- 顺带修复：任务详情评论时间线原引用 `c.creator/c.createTime`（实际字段为 actor/time，原显示 undefined）

### P2 图表
- Metrics CFD 原 `p.done*8px` 写死比例（数据>19 即溢出 150px 容器）→ 按 14 天最大总量归一化；周吞吐柱图同步归一化

### 回归验证（全部通过）

| 验证点 | 结果 |
|---|---|
| 列表页数据渲染（composable + options store + DictTag） | ✅ 项目/任务表格全列正常，优先级显示字典中文标签（中） |
| 空表单提交被拦截 | ✅ 「请输入标题」「请选择项目」红字，弹窗不关、不发请求（截图 p0_form_validation.png） |
| 404 | ✅ `/#/not-exist-page` 显示玻璃卡 404 + 返回首页 |
| dev01 越权直达 `/#/system` | ✅ 被守卫重定向到 /ai/chat，页面不渲染（修复前可直接打开仅靠后端 403） |
| 隐藏页白名单 | ✅ dev01 访问 /metrics 正常放行 |
| keep-alive | ✅ 任务页输入筛选「复验」→ 切工作台 → 返回，筛选保留 |
| 看板切换加载 | ✅ 待办（7）/进行中（3）/已完成（3） |
| admin 恢复登录 | ✅ 系统恢复可用状态 |

> 回归备注：测试中途 IAB 浏览器宿主出现原生输入管线/截帧表面退化（点击偶发不触发、截图超时或返回过期缓存），换新标签页后部分恢复；期间用 DOM 级派发事件完成交互验证（element.click 对按钮/菜单项均可靠触发，Vue 应用健康），判定为宿主环境问题、非应用缺陷。上述全部结论基于 DOM 快照与 DOM 级交互双重证据。

### 工程量对比
- 新增基础设施 6 文件共 ~130 行；Project.vue 155→168 行但业务重复逻辑（load/mapper/映射表 ×4 份）收敛为共享实现；后续新增列表页只需 ~60 行。

---

## 八、第四轮执行：功能深化方案缺口补齐（8 项全量落地，2026-10-19）

对照第七节完成度矩阵的缺口清单，按 ROI 顺序执行完毕。**后端 7 个文件改动 + 新增 1 个服务类，前端 6 个文件改动；`mvn package` 与 `vite build` 均通过，全部经 API + GUI 双重验证。**

### 执行明细

| # | 缺口 | 实现 | 验证结果 |
|---|---|---|---|
| 1 | P1-7 通知前端 | 新组件 `NotificationBell.vue`：顶栏铃铛 + 未读角标（60s 轮询）+ 玻璃面板（类型色点/全部已读/点击已读并跳转 refUrl）；Layout 集成 | ✅ GUI：角标 2，面板含「迭代已关闭：Sprint-T」等实时通知 |
| 2 | P2-8/10 AI 入口 | Defect 详情抽屉加「相似缺陷」tab（懒加载）；Report 页加「AI 周报」卡片（marked 渲染） | ✅ GUI：相似缺陷 3 条带分值；周报生成真实数据 Markdown（本周 14 缺陷 + 问答记录） |
| 3 | P1-5 前端对接 | Project 行内「工作流」按钮 → 配置弹窗（状态集/WIP/初始态 + 流转规则增删）；Task 看板**列随工作流动态渲染**（后端 board 同步改为动态分组）；列头显示 WIP 上限 | ✅ GUI：给 P166 加 REVIEW 状态后看板变 4 列，DOING 列显示 0/5 |
| 4 | P0-3② 迭代关闭 | `POST /projects/iterations/{id}/close`（OWNER-only @ProjectScoped）：置关闭态 → 非终态任务移回 backlog（LambdaUpdateWrapper 显式 set null）+ 记动态 → 通知全员（iteration 类型）；前端迭代弹窗加状态列与关闭按钮 | ✅ API：关闭 Sprint-T 返回「2 个未完成任务移回 backlog」；task2 归属清空、时间线有 iteration→backlog、通知送达 |
| 5 | P0-2② 缺陷回写 | 缺陷 FIXED/CLOSED 且关联任务时，向任务时间线写 `defect` 字段动态 | ✅ API：缺陷4→FIXED 后 task6 时间线出现「OPEN:关联缺陷1 → FIXED:关联缺陷1」 |
| 6 | P0-3③ 理想线 | burndown 返回 `ideal[]`（首日总量按迭代起止日期线性递减，缺日期时用快照跨度兜底）；前端燃尽图改 **SVG 双折线**（蓝色实际线+圆点、灰色虚线理想线） | ✅ API：12 天迭代理想线 8 点；GUI：双线+图例正常 |
| 7 | P1-6 权限补齐 | `@ProjectScoped` 挂载：advance（TASK）、删项目/加成员/移成员（PROJECT, ownerOnly）；评论接口手工校验（切面新增 `checkMemberWrite` 公共方法 + ITERATION 类型） | ✅ API：dev01 评论非成员项目任务 403、推进 403、删项目 403（仅 OWNER） |
| 8 | P2-8 向量检索 | 新服务 `DefectVectorService`：独立 Milvus 集合 `defect_vectors`（与知识库物理隔离）；缺陷创建/更新自动向量化 + 首次检索回填历史；similar 语义优先、ngram 回退 | ✅ API：中文标题「关联缺陷1」语义命中「SSE偶发断流 0.80 / 验证任务崩溃复现 0.73」——无共同关键词，证明为真语义通道（修复了 SDK IDScore 读取方式导致的通道失效） |

### 关键修复插曲
初版向量检索的 `getIDScore` RowRecord 读取方式在该 Milvus SDK 版本抛 "This record is empty"（向量通道从未生效，命中结果实为 ngram 回退）。对照本库 `MultiRetriever` 的成熟解析模式（`getRowRecords` 字段值 + `getIDScore(i).getScore()` 索引对齐）修正后，中文语义检索真正打通。

### 完成度矩阵（刷新后）

| 方案项 | 之前 | 现在 |
|---|---|---|
| P0-1 时间线评论 | 基本✅ | ✅（评论权限补齐） |
| P0-2 缺陷关联 | ⚠️ 70% | ✅（回写动态 + 清点 + 前端 tab） |
| P0-3 迭代周期化 | ⚠️ 60% | ✅（关闭流程 + 理想线双线图 + 通知） |
| P0-4 效能报表 | ✅ | ✅ |
| P1-5 工作流引擎 | 后端✅ 前端❌ | ✅（配置 UI + 动态看板 + WIP 显示） |
| P1-6 对象级权限 | ⚠️ 40% | ✅（写操作全覆盖 + OWNER-only + 评论拦截） |
| P1-7 通知中心 | 后端70% 前端❌ | ✅（铃铛全链路 + iteration 事件补齐；doc_audit 事件仍缺写入点） |
| P2-8 AI 辅助 | ⚠️ | ✅（真·向量语义检索 + 前端入口） |
| P2-9 知识沉淀 | ✅ | ✅ |
| P2-10 周报生成 | 后端✅ 前端❌ | ✅（一键生成 + Markdown 展示） |

**结论：《功能深化迭代方案》10 项全部达成可用状态。** 剩余可选优化：doc_audit 审核通知写入点、相似缺陷分值归一化为百分比、WIP 超限看板红点（当前仅后端拦截报错提示）。证据截图：`gui-test-screenshots/deepening_*.png`。

---

## 九、第五轮执行：对标全网的 P0/P1 功能补齐（2026-10-19）

对照全局 PM 产品差距评估（需求层/子任务依赖/附件富文本/全局搜索为战略级缺口），执行 P0 全部三项 + P1 第一项。**V13 迁移自动应用，后端新增 4 控制器，前端新增 2 页面/3 组件；构建与回归全部通过。**

### P0-1 需求层（Epic → Story，对标 Jira/Linear 骨架）
- 表：`prj_requirement`（EPIC/STORY 两级、状态 OPEN/DEVELOPING/DONE/REJECTED、故事点、Epic 分组）+ `prj_task.requirement_id` 任务挂 Story
- 后端：`RequirementController`（CRUD/Epic 列表/Epic 进度聚合/Story 关联任务，@ProjectScoped REQUIREMENT 权限）
- 前端：`Requirement.vue` 需求管理页（**Epic 进度卡**可点击筛选 + Story 表格 + 新建弹窗类型切换）+ 侧栏新菜单（V13 自动插入并绑定 ADMIN/USER 角色）
- 验证：Epic「账户体系」+ Story「手机验证码登录(3点)」创建 → 进度卡 0/1 → 任务2 挂 Story → /requirements/2/tasks 返回关联任务 ✓

### P0-2 子任务与依赖
- 表：`prj_subtask`（checklist）+ `prj_task_dep`（blocked-by/blocking，唯一约束）
- 后端：`TaskExtraController`（子任务增删改查 + 依赖增删/双向视图/同项目校验/环检测）；**advance 与 PUT 状态变更在流转到终态时强制校验前置依赖**
- 前端：任务抽屉新增「子任务 n/m」（勾选完成）与「依赖」（被阻塞于/阻塞中 双列表）tab
- 验证：任务2 依赖任务3（压压报告 TODO）→ 推进 DONE 被 400 拦截「前置任务未完成：#3 压测报告」→ 任务3 完成后任务2 结项自动放行 ✓；跨项目依赖被拒 ✓

### P0-3 附件与 Markdown
- 表：`prj_attachment`；后端 `AttachmentController`（上传至 `./data/attachments`、流式下载 UTF-8 文件名、删除，项目成员校验）
- 前端：共享组件 `AttachmentList.vue`（类型图标/大小/上传人/授权下载）；任务与缺陷抽屉各加「附件」tab；任务「详情」tab 内容以 Markdown 渲染（marked+DOMPurify）
- 验证：att-test.txt 上传 59B → 列表显示 → 下载内容回环一致 ✓

### P1 全局搜索（Linear 式 ⌘K）
- 后端：`SearchController` 跨任务/缺陷/需求/文档/会话 LIKE 联查各取 5 条
- 前端：`GlobalSearch.vue` 顶栏 ⌘K 触发器 + 毛玻璃浮层（分组结果、点击跳转、ESC 关闭、Ctrl/⌘+K 快捷键）
- 验证：「登录」→ 任务(前端登录页) + 需求(手机验证码登录) 分组命中 ✓；「crash」→ 缺陷 3 条 ✓

### 回归
`mvn package` ✓ `vite build`(750ms) ✓；重新登录后「需求管理」菜单出现 ✓；任务抽屉 7 tab（详情/子任务 0/1/依赖/附件/时间线/关联缺陷/评论）✓；老页面（项目/任务/缺陷列表）无回归 ✓。证据：`gui-test-screenshots/p0_*.png、p1_*.png`。

### 累计：DevHub 现已具备 Epic→Story→任务(子任务/依赖/附件)→缺陷 的完整对象树 + 全局检索，骨架对齐主流 PM 产品。

---

## 十、第六轮执行：P2 收尾——自动化规则 / 工时负载 / 日历视图（2026-10-19）

**范围决策：IM Webhook 明确不做**（已记入项目记忆），通知只走站内信。其余 P2 三项全部落地。V14 迁移自动应用；`mvn package` / `vite build` 通过；API + GUI 双重验证。

### P2-1 自动化规则（Jira Automation lite）
- 表 `prj_automation_rule`；引擎 `AutomationService`（触发器 TASK_CREATED / STATUS_CHANGED(可限定目标状态) / ASSIGNEE_CHANGED；动作 NOTIFY / AUTO_ASSIGN / SET_PRIORITY；失败只记日志不影响业务）
- 触发点：任务 create / update / advance 三处接入，字段类动作自动回写
- 管理：`AutomationController`（项目规则 CRUD + 启停，@ProjectScoped）+ Project 行内「自动化」弹窗（规则表 + 组合表单新增）
- **验证**：项目1 建「新任务提醒开发」规则 → 建任务 → dev01 站内信收到「自动化：新任务提醒开发 | 新任务『自动化验证任务』已创建」✓

### P2-2 工时与负载
- 表 `prj_worklog`；任务抽屉「工时 Xh」tab（登记 0.5~24h 校验/日期/备注/删除/累计）；`GET /report/workload` 按人聚合未结任务 × 本周工时；Metrics 页新增「成员负载」卡（40h 满载进度条）
- **验证**：2h 登记后负载视图 admin 本周 2.0h ✓；25h 非法值被拒 ✓；dev01 未结 2 任务 ✓

### P2-3 日历视图
- 任务页三视图切换（列表/看板/日历 el-radio-group）；el-calendar 按截止日聚合任务芯片（状态色标签 + 点击开详情抽屉）
- **验证**：设任务截止日为当天 → 日历对应日期出现芯片「待办·自动化验证任务」✓

### 回归
- 前端构建 704ms ✓；日历/自动化弹窗/负载卡 GUI 实测 ✓（截图 `gui-test-screenshots/p2_*.png`）；既有列表/看板无回归 ✓
- 顺带修复：Task.vue 视图切换重构（boardV → viewMode 三态）

**至此上轮评估的 P0+P1+P2（除 IM Webhook）全部完成。** DevHub 功能版图：需求(Epic/Story) → 任务(工作流/子任务/依赖/附件/工时/自动化) → 缺陷(相似检索/知识沉淀/附件) → 效能报表(cycle/CFD/燃尽/负载) → AI 助手(RAG 问答/周报/全局搜索) + 通知中心。

---

## 十一、闭环审计与断点修复（2026-10-19）

对全系统做业务闭环审计：6 条链路（任务/缺陷/迭代/知识/通知/权限）已闭环，发现 3 个断点，本节修复其中 2 个（断点 3 需产品决策暂留）。

### 断点 1（主断点）：任务完成不联动需求状态 —— ✅ 已修复
- `RequirementController.syncStoryStatus(requirementId)`：Story 下任务**全部到达项目工作流终态 → DONE**；存在非终态 → DEVELOPING（含任务回退后从 DONE 自动回归）；REJECTED 不被覆盖；失败静默不影响任务主流程
- 挂接三处：任务 create / update（statusChanged 后）/ advance
- **验证链**：Story「手机验证码登录」OPEN → 挂新任务自动 DEVELOPING → 全部任务 DONE 后自动 DONE → Epic 进度卡 1/1 100%（API 实测）；GUI 显示 Story「已完成」标签 ✓

### 断点 2：文档审核结果不通知 —— ✅ 已修复
- `DocCenterController.audit` 增加 `notify.push(上传人, "doc_audit", 已发布/已驳回, 文件名, /#/doc)`——补上了 sys_notification 中定义却从未写入的 doc_audit 事件类型
- **验证**：文档置回待审核 → 审核 pass=true → 上传人站内信收到「doc_audit | 文档已发布 | Arthas诊断工具中文手册.md」✓

### 断点 3（未修，需决策）：缺陷 FIXED→CLOSED 无 QA 验证约束
属流程治理选项：引入"仅 QA/OWNER 可关单"会改变现有操作习惯，留待决策。

**修复后闭环状态：8 条业务链路全部闭环**（需求→任务→缺陷→迭代→知识→通知→度量→权限），「需求提出到交付验收」价值链贯通。

---

## 十二、知识库补充（2026-10-19）

补充前仅 2 份文档（Arthas 手册 + 阿里 Java 手册），4 个开放知识缺口。

### 补充内容（2 → 7 份，新增 53 切片）

| 文档 | 分类 | 状态 | 说明 |
|---|---|---|---|
| COLA架构说明.md | 架构设计 | active（冲突复核采纳） | 仓库配套文档入库 |
| SpringCloudAlibaba项目介绍.md | 架构设计 | active（已发布） | 同上 |
| cli-startup-guide.md | 工具手册 | active（冲突复核采纳） | DevHub 自身启动指南，新人问答可直接命中 |
| idea-startup-guide.md | 工具手册 | active（已发布） | 同上 |
| **AI知识：遍历HashMap用keySet还是entrySet** | —（AI 生成） | active（草稿审核采纳） | **用产品自带缺口生成能力填补真实缺口 #2**，闭环「拒答→缺口→AI 草稿→审核→入库→命中」全流程 |

### 拒答不编造原则贯彻
剩余 3 个开放缺口（ZephyrProjectX / QAToken9981 / IsoToken5566）为测试噪音词，无真实知识可依据，**保持拒答、不生成编造内容**。

### RAG 验证（新知识全部可检索、带引用）
- 「COLA架构分为哪几层」→ 引用 COLA架构说明.md，答案准确列出各层职责 ✓
- 「遍历HashMap用keySet还是entrySet」→ **引用 AI 生成知识文档**，推荐 entrySet 并标注 [1][3] ✓（此前该问题被拒答）
- 「SpringCloudAlibaba核心组件」→ 引用 SpringCloudAlibaba项目介绍.md ✓

### 顺带处理
curl GBK 上传导致两份中文文档名乱码：修正 MySQL document/document_chunk 两表文件名 + 两次重建索引同步 Milvus 引用名。截图 `gui-test-screenshots/kb_7docs.png`。








