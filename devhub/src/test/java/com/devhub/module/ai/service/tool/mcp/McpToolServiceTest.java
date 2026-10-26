package com.devhub.module.ai.service.tool.mcp;

import com.devhub.module.ai.config.McpProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 工具服务：命名规范 + 禁用态行为（不依赖真实 server）
 */
class McpToolServiceTest {

    @Test
    void 暴露名_前缀与规范() {
        assertEquals("mcp_demo-order_query_order",
                McpToolService.exposedName(sanitize("demo-order"), "query_order"));
        // 非法字符剔除（server key 与工具名都只留 [a-zA-Z0-9_-]）
        assertEquals("mcp_mysrv_tool1",
                McpToolService.exposedName(sanitize("my srv!"), "tool.1"));
    }

    @Test
    void 暴露名_超长截断保路由前缀() {
        String longTool = "t".repeat(100);
        String exposed = McpToolService.exposedName(sanitize("demo"), longTool);
        assertTrue(exposed.length() <= 64);
        assertTrue(exposed.startsWith("mcp_demo_"));
    }

    @Test
    void serverKey_空与非法回退() {
        assertEquals("server", sanitize(null));
        assertEquals("server", sanitize("  "));
        assertEquals("demoorder", sanitize("Demo.Order"));
    }

    @Test
    void 未启用_不暴露工具_执行失败不抛异常() {
        McpProperties props = new McpProperties();
        props.setEnabled(false);
        McpToolService service = new McpToolService(props);

        service.init(); // enabled=false：无连接也无异常

        assertTrue(service.listToolSpecifications().isEmpty());
        assertFalse(service.hasTool("mcp_demo_query_order"));

        var result = service.execute("mcp_demo_query_order", "{\"orderId\":\"SO-2024-1001\"}");
        assertFalse(result.success());
        assertTrue(result.output().contains("不可用"));
        assertFalse(service.status().enabled());
    }

    private String sanitize(String key) {
        return McpToolService.sanitizeKey(key);
    }
}
