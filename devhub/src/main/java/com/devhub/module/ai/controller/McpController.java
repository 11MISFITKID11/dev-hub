package com.devhub.module.ai.controller;

import com.devhub.common.OperLog;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.service.tool.mcp.McpToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 客户端管理端点：连接状态查看 + 手动重连
 * <p>
 * 工具调用本身经 ReAct/Planner 的 ToolRouter 走 mcp_ 前缀路由，无需单独端点。
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpToolService mcpToolService;

    /** MCP server 连接状态与暴露的工具列表 */
    @GetMapping("/status")
    public Result<McpToolService.McpStatus> status() {
        return Result.success(mcpToolService.status());
    }

    /** 手动重连全部 server（修改 agent.mcp 配置后免重启刷新工具列表） */
    @OperLog(module = "MCP", action = "重连server")
    @PostMapping("/refresh")
    public Result<McpToolService.McpStatus> refresh() {
        return Result.success(mcpToolService.refresh());
    }
}
