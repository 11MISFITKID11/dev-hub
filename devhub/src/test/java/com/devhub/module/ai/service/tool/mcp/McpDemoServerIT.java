package com.devhub.module.ai.service.tool.mcp;

import com.devhub.module.ai.config.McpProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真连演示 MCP server（node stdio 子进程）的集成冒烟。
 * <p>
 * 环境门控与既有集成测试一致：DEVHUB_IT=local 时运行；
 * 本机无 node 时跳过（assumeTrue），不造成脆弱失败。
 */
@EnabledIfEnvironmentVariable(named = "DEVHUB_IT", matches = "local")
class McpDemoServerIT {

    private static final String SCRIPT = "src/main/resources/demo/mcp-order-server.mjs";

    @Test
    void 连接演示server_工具暴露与调用() throws Exception {
        Assumptions.assumeTrue(new ProcessBuilder("node", "--version")
                .start().waitFor() == 0, "本机无 node，跳过");
        Assumptions.assumeTrue(new File(SCRIPT).exists(), "演示脚本不存在");

        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server server = new McpProperties.Server();
        server.setKey("demo-order");
        server.setTransport("stdio");
        server.setCommand(List.of("node", new File(SCRIPT).getAbsolutePath()));
        server.setInitTimeoutSeconds(20);
        server.setToolTimeoutSeconds(15);
        props.setServers(List.of(server));

        McpToolService service = new McpToolService(props);
        try {
            service.init();

            assertTrue(service.hasTool("mcp_demo-order_query_order"), "应暴露 query_order");
            assertTrue(service.hasTool("mcp_demo-order_get_refund_policy"), "应暴露 get_refund_policy");
            assertEquals(2, service.listToolSpecifications().size());

            var ok = service.execute("mcp_demo-order_query_order", "{\"orderId\":\"SO-2024-1001\"}");
            assertTrue(ok.success(), ok.output());
            assertTrue(ok.output().contains("已发货"), ok.output());

            var miss = service.execute("mcp_demo-order_query_order", "{\"orderId\":\"NOPE\"}");
            assertFalse(miss.success(), "不存在的订单应 isError=true");
        } finally {
            service.shutdownClients();
        }
    }
}
