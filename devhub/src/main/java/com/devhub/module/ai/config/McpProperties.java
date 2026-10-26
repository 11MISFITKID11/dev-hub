package com.devhub.module.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）客户端配置
 * <p>
 * 在 application.yml 中以 agent.mcp 前缀声明外部 MCP server，
 * server 暴露的工具会在启动时并入 ToolRegistry（命名 mcp_&lt;server&gt;_&lt;tool&gt;），
 * ReActAgent 经 ToolRouter 透明调用——本地工具与远程工具对模型无差别。
 * enabled=false 或某个 server 连接失败只影响该 server，不影响应用启动。
 */
@Data
@ConfigurationProperties(prefix = "agent.mcp")
public class McpProperties {

    /** 总开关：false 时完全不建立连接（默认） */
    private boolean enabled = false;

    /** 外部 MCP server 列表 */
    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {
        /** server 别名：用作工具名前缀，需为 [a-zA-Z0-9-] */
        private String key;
        /** 传输方式：stdio（本地子进程）| http（Streamable HTTP 远程） */
        private String transport = "stdio";
        /** stdio：完整命令行，如 [node, D:/path/mcp-order-server.mjs] */
        private List<String> command = new ArrayList<>();
        /** stdio：子进程环境变量 */
        private Map<String, String> environment = new LinkedHashMap<>();
        /** http：MCP server 地址（Streamable HTTP） */
        private String url;
        /** http：自定义请求头（如 Authorization） */
        private Map<String, String> headers = new LinkedHashMap<>();
        private int initTimeoutSeconds = 20;
        private int toolTimeoutSeconds = 60;
    }
}
