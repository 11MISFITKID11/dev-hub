package com.devhub.module.ai.service.tool;

import com.devhub.module.ai.service.tool.mcp.McpToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ToolRouter 的 MCP 分流：mcp_ 前缀转发远程 server，本地工具照旧
 */
class ToolRouterMcpTest {

    private final McpToolService mcp = mock(McpToolService.class);

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new EchoTool()), new ObjectMapper(), mcp);
        registry.init();
        return registry;
    }

    @Test
    void mcp前缀_转发远程执行() {
        when(mcp.execute("mcp_demo_query_order", "{\"orderId\":\"SO-2024-1001\"}"))
                .thenReturn(BaseTool.ToolResult.success("mcp_demo_query_order", "订单已发货", 12));

        BaseTool.ToolResult result = new ToolRouter(registry(), new ObjectMapper(), mcp)
                .route("mcp_demo_query_order", "{\"orderId\":\"SO-2024-1001\"}");

        assertTrue(result.success());
        assertEquals("订单已发货", result.output());
        verify(mcp, times(1)).execute("mcp_demo_query_order", "{\"orderId\":\"SO-2024-1001\"}");
    }

    @Test
    void 本地工具_不经过MCP() {
        BaseTool.ToolResult result = new ToolRouter(registry(), new ObjectMapper(), mcp)
                .route("echo", "{\"text\":\"hi\"}");

        assertTrue(result.success());
        assertEquals("hi", result.output());
        verifyNoInteractions(mcp);
    }

    /** 最小本地工具实现（验证路由不破坏本地链路） */
    static class EchoTool extends BaseTool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "回显文本"; }
        @Override public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
        }
        @Override protected String doExecute(Map<String, Object> parameters) {
            return String.valueOf(parameters.get("text"));
        }
    }
}
