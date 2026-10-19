package com.devhub.module.ai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * <p>
 * 管理所有可用工具的注册、查询、生命周期。
 * 启动时自动扫描所有 {@link BaseTool} 实现并注册，
 * 同时支持运行时动态注册/注销。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    /** 工具注册表：toolName → BaseTool 实例 */
    private final Map<String, BaseTool> registry = new ConcurrentHashMap<>();

    /** Spring 容器中所有 BaseTool 实现（自动注入） */
    private final List<BaseTool> autoDiscoveredTools;

    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        for (BaseTool tool : autoDiscoveredTools) {
            register(tool);
        }
        log.info("工具注册中心初始化完成，共注册 {} 个工具: {}",
                registry.size(), registry.keySet());
    }

    /**
     * 注册工具
     */
    public void register(BaseTool tool) {
        String name = tool.getName();
        if (registry.containsKey(name)) {
            log.warn("工具名称冲突，覆盖已有注册: {}", name);
        }
        registry.put(name, tool);
        log.info("注册工具: name={}, description={}", name, tool.getDescription());
    }

    /**
     * 注销工具
     */
    public void unregister(String toolName) {
        BaseTool removed = registry.remove(toolName);
        if (removed != null) {
            log.info("注销工具: {}", toolName);
        }
    }

    /**
     * 根据名称获取工具
     */
    public Optional<BaseTool> getTool(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * 获取所有已注册工具
     */
    public Collection<BaseTool> getAllTools() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * 获取指定子集的工具
     *
     * @param toolNames 工具名称列表，为 null 则返回全部
     */
    public List<BaseTool> getTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new ArrayList<>(registry.values());
        }
        return toolNames.stream()
                .map(registry::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 生成工具描述列表（供文本协议 Prompt 使用，保留兼容）
     */
    public String buildToolDescriptions(List<String> toolNames) {
        List<BaseTool> tools = getTools(toolNames);

        StringBuilder sb = new StringBuilder();
        sb.append("可用工具列表:\n\n");
        for (BaseTool tool : tools) {
            sb.append("工具名: ").append(tool.getName()).append("\n");
            sb.append("描述: ").append(tool.getDescription()).append("\n");
            sb.append("参数: ").append(tool.getParameterSchema()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建原生 Function Calling 的工具规格（LangChain4j ToolSpecification）
     * <p>
     * 把每个工具声明的 JSON Schema 转换为 JsonObjectSchema，
     * 模型将直接生成工具原生参数（如 {"expression": "1+2"}）。
     */
    public List<ToolSpecification> buildToolSpecifications(List<String> toolNames) {
        return getTools(toolNames).stream()
                .map(tool -> ToolSpecification.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(parseSchema(tool.getParameterSchema(), tool.getName()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * JSON Schema 字符串 → JsonObjectSchema（支持 string/number/integer/boolean 常见类型）
     */
    private JsonObjectSchema parseSchema(String schemaJson, String toolName) {
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

            List<String> required = new ArrayList<>();
            JsonNode reqNode = root.get("required");
            if (reqNode != null && reqNode.isArray()) {
                reqNode.forEach(n -> required.add(n.asText()));
            }

            JsonNode props = root.get("properties");
            if (props != null && props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = props.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String type = entry.getValue().path("type").asText("string");
                    String desc = entry.getValue().path("description").asText("");
                    switch (type) {
                        case "number" -> builder.addProperty(entry.getKey(),
                                JsonNumberSchema.builder().description(desc).build());
                        case "integer" -> builder.addProperty(entry.getKey(),
                                JsonIntegerSchema.builder().description(desc).build());
                        case "boolean" -> builder.addProperty(entry.getKey(),
                                JsonBooleanSchema.builder().description(desc).build());
                        default -> builder.addStringProperty(entry.getKey(), desc);
                    }
                }
            }
            if (!required.isEmpty()) {
                builder.required(required);
            }
            return builder.build();
        } catch (Exception e) {
            log.warn("工具 {} 的参数Schema解析失败，使用通用args参数: {}", toolName, e.getMessage());
            return JsonObjectSchema.builder()
                    .addStringProperty("args", "工具参数JSON字符串")
                    .required(List.of("args"))
                    .build();
        }
    }

    /**
     * 判断工具是否已注册
     */
    public boolean contains(String toolName) {
        return registry.containsKey(toolName);
    }
}
