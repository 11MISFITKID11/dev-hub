package com.devhub.module.ai.service.tool.mcp;

import com.devhub.module.ai.config.McpProperties;
import com.devhub.module.ai.service.tool.BaseTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 工具服务（客户端侧）
 * <p>
 * 启动时按 agent.mcp.servers 逐个建立 MCP 连接（stdio 子进程 / Streamable HTTP），
 * listTools() 拿到的 ToolSpecification 重命名后并入本地工具注册表；
 * 模型调用时由 {@code ToolRouter} 按前缀转回原始工具名发给对应 server。
 * <ul>
 *   <li>命名规则：{@code mcp_<server>_<tool>}，保证与本地工具不冲突且满足函数调用命名约束</li>
 *   <li>单个 server 连接失败只记日志，不影响应用启动（远程工具属增强能力，非关键路径）</li>
 *   <li>调用结果统一包装为 {@link BaseTool.ToolResult}，耗时/失败语义与本地工具一致，
 *       因此 agent_action 审计与 Prometheus 指标对 MCP 调用同样生效</li>
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpToolService {

    public static final String EXPOSED_PREFIX = "mcp_";
    /** 函数调用工具名长度上限（OpenAI 规范 64 字符），超长截断保前缀可路由 */
    private static final int MAX_TOOL_NAME = 64;

    private final McpProperties properties;

    /** serverKey → 客户端 */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 暴露名 mcp_<server>_<tool> → (serverKey, 原始工具名) */
    private final Map<String, McpToolRef> toolIndex = new ConcurrentHashMap<>();

    public McpToolService(McpProperties properties) {
        this.properties = properties;
    }

    /** 已连接 server 数量与工具数量（状态端点用） */
    public record McpStatus(boolean enabled, List<String> servers, List<String> tools) {}

    public record McpToolRef(String server, String originalName) {}

    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || properties.getServers().isEmpty()) {
            log.info("MCP 未启用（agent.mcp.enabled=false 或未配置 server），跳过连接");
            return;
        }
        refresh();
    }

    /**
     * （重）连接所有 server 并刷新工具索引。运行时可通过管理端点调用。
     */
    public synchronized McpStatus refresh() {
        shutdownClients();
        toolIndex.clear();
        for (McpProperties.Server server : properties.getServers()) {
            connect(server);
        }
        log.info("MCP 连接完成: {} 个 server, {} 个远程工具: {}",
                clients.size(), toolIndex.size(), toolIndex.keySet());
        return status();
    }

    private void connect(McpProperties.Server server) {
        String key = sanitizeKey(server.getKey());
        try {
            McpClient client = DefaultMcpClient.builder()
                    .key(key)
                    .clientName("helphub")
                    .clientVersion("1.0")
                    .transport(buildTransport(server))
                    .initializationTimeout(java.time.Duration.ofSeconds(server.getInitTimeoutSeconds()))
                    .toolExecutionTimeout(java.time.Duration.ofSeconds(server.getToolTimeoutSeconds()))
                    .toolExecutionTimeoutErrorMessage("MCP 工具执行超时，请基于已有信息作答")
                    .build();

            List<ToolSpecification> tools = client.listTools();
            int indexed = 0;
            for (ToolSpecification tool : tools) {
                String exposed = exposedName(key, tool.name());
                if (toolIndex.containsKey(exposed)) {
                    log.warn("MCP 工具名冲突，跳过: {}", exposed);
                    continue;
                }
                toolIndex.put(exposed, new McpToolRef(key, tool.name()));
                indexed++;
            }
            clients.put(key, client);
            log.info("MCP server [{}] 连接成功，暴露 {} 个工具（原始 {} 个）", key, indexed, tools.size());
        } catch (Exception e) {
            // 连接失败降级：该 server 的工具不可用，不影响应用与其他 server
            log.warn("MCP server [{}] 连接失败，跳过: {}", key, e.getMessage());
        }
    }

    private McpTransport buildTransport(McpProperties.Server server) {
        if ("http".equalsIgnoreCase(server.getTransport())) {
            return StreamableHttpMcpTransport.builder()
                    .url(server.getUrl())
                    .customHeaders(server.getHeaders())
                    .timeout(java.time.Duration.ofSeconds(server.getInitTimeoutSeconds()))
                    .build();
        }
        return StdioMcpTransport.builder()
                .command(server.getCommand())
                .environment(server.getEnvironment())
                .logEvents(true)
                .build();
    }

    /**
     * 暴露给模型的工具规格（已重命名）。禁用/无 server 时返回空列表。
     */
    public List<ToolSpecification> listToolSpecifications() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<ToolSpecification> result = new ArrayList<>();
        for (Map.Entry<String, McpToolRef> entry : toolIndex.entrySet()) {
            McpToolRef ref = entry.getValue();
            McpClient client = clients.get(ref.server());
            if (client == null) {
                continue;
            }
            try {
                for (ToolSpecification spec : client.listTools()) {
                    if (spec.name().equals(ref.originalName())) {
                        result.add(ToolSpecification.builder()
                                .name(entry.getKey())
                                .description(spec.description())
                                .parameters(spec.parameters())
                                .metadata(spec.metadata())
                                .build());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("MCP server [{}] 工具列表刷新失败: {}", ref.server(), e.getMessage());
            }
        }
        return result;
    }

    public boolean hasTool(String exposedName) {
        return toolIndex.containsKey(exposedName);
    }

    /**
     * 执行远程工具：转回原始工具名，发给对应 server，包装为统一 ToolResult。
     */
    public BaseTool.ToolResult execute(String exposedName, String argsJson) {
        McpToolRef ref = toolIndex.get(exposedName);
        McpClient client = ref == null ? null : clients.get(ref.server());
        if (ref == null || client == null) {
            return BaseTool.ToolResult.failure(exposedName, "MCP 工具不可用: " + exposedName, 0);
        }
        long start = System.currentTimeMillis();
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("mcp-" + System.nanoTime())
                    .name(ref.originalName())
                    .arguments(argsJson == null ? "{}" : argsJson)
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            long elapsed = System.currentTimeMillis() - start;
            log.info("MCP 工具执行: server={}, tool={}, isError={}, elapsed={}ms",
                    ref.server(), ref.originalName(), result.isError(), elapsed);
            return result.isError()
                    ? BaseTool.ToolResult.failure(exposedName, truncate(result.resultText(), 800), elapsed)
                    : BaseTool.ToolResult.success(exposedName, result.resultText(), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("MCP 工具执行异常: server={}, tool={}, cause={}", ref.server(), ref.originalName(), e.getMessage());
            return BaseTool.ToolResult.failure(exposedName, "MCP 调用失败: " + e.getMessage(), elapsed);
        }
    }

    public McpStatus status() {
        return new McpStatus(properties.isEnabled(),
                List.copyOf(clients.keySet()),
                List.copyOf(toolIndex.keySet()));
    }

    public List<String> serverTools(String serverKey) {
        return toolIndex.entrySet().stream()
                .filter(e -> e.getValue().server().equals(serverKey))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @PreDestroy
    public void shutdownClients() {
        clients.forEach((key, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("MCP client [{}] 关闭异常: {}", key, e.getMessage());
            }
        });
        clients.clear();
    }

    /** server key 规范化：只留字母数字与连字符（包级可见供单测） */
    static String sanitizeKey(String key) {
        String cleaned = key == null ? "" : key.replaceAll("[^a-zA-Z0-9-]", "");
        return cleaned.isBlank() ? "server" : cleaned.toLowerCase();
    }

    /** mcp_&lt;server&gt;_&lt;tool&gt;，超长截断（前缀必须保留，路由依赖它；包级可见供单测） */
    static String exposedName(String serverKey, String toolName) {
        String base = EXPOSED_PREFIX + serverKey + "_" + toolName.replaceAll("[^a-zA-Z0-9_-]", "");
        return base.length() <= MAX_TOOL_NAME ? base : base.substring(0, MAX_TOOL_NAME);
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
