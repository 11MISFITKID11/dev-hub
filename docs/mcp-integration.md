# MCP 客户端接入（Model Context Protocol）

> 定位：把工具层从"编译期 Java 类"升级为"运行时可插拔生态"。
> 外部 MCP server 的工具与本地 `BaseTool` 在 `ToolRegistry` 合并，模型统一 Function Calling 调用，`ToolRouter` 按 `mcp_` 前缀透明分流。

## 架构

```
ReActAgent / PlannerAgent
        │ ToolSpecification 列表（本地 6 个 + MCP 远程 N 个）
        ▼
   ToolRegistry ──合并── McpToolService ── stdio/Streamable HTTP ── 外部 MCP server
        │                                                        （filesystem / mysql / jira / web-search…）
        ▼
   ToolRouter ── mcp_ 前缀 → McpClient.executeTool → ToolResult（统一审计/耗时语义）
```

- **命名规则**：`mcp_<server>_<tool>`（server key 与工具名剔除非法字符，超长截断 64），与本地工具零冲突
- **降级语义**：单个 server 连接失败只记日志跳过，应用与其他 server 不受影响；`agent.mcp.enabled=false` 完全不建立连接
- **审计/指标**：MCP 调用经 `ToolRouter` 包装为 `ToolResult`，`agent_action` 审计与耗时记录自动生效
- **安全**：工具返回内容沿 ReAct 系统提示"不可信数据"规则处理，与 PromptGuard 协同防注入

## 配置（application.yml）

```yaml
agent:
  mcp:
    enabled: ${AGENT_MCP_ENABLED:false}   # 一键开关
    servers:
      - key: demo-order                   # 别名，进工具名前缀
        transport: stdio                  # stdio | http
        command: [node, .../mcp-order-server.mjs]
        # http 远程 server：transport: http + url + headers（鉴权头）
```

改配置后免重启：`POST /api/v1/mcp/refresh` 重连并刷新工具列表；`GET /api/v1/mcp/status` 查看连接与工具清单。

## 演示

`devhub/src/main/resources/demo/mcp-order-server.mjs` 是零依赖 node 实现的 MCP server（订单查询 + 退款政策），
`AGENT_MCP_ENABLED=true` 启动后端后，在智能问答中问"订单 SO-2024-1001 发货了吗"即可看到 agent 调用
`mcp_demo-order_query_order`。

## 测试

- `McpToolServiceTest` / `ToolRouterMcpTest`：命名规范、禁用态、前缀分流（无需 node）
- `McpDemoServerIT`（`DEVHUB_IT=local` 门控）：真连 node 子进程，覆盖 initialize / tools/list / tools/call / isError 语义

## 设计取舍

- 不引入 `McpToolProvider`（AiServices 专用）：本项目 ReAct 循环自管 `ToolSpecification` 列表，直接用底层 `McpClient` 更贴合现有架构
- server key 保留连字符（函数调用命名合法字符），其余非法字符剔除而非替换，保证路由可逆
- 评测 MRR/NDCG（V34）与 MCP 同批落地：命中率看"是否进 Top-K"，MRR/NDCG 看"排第几"，对齐 RAGAS 检索指标
