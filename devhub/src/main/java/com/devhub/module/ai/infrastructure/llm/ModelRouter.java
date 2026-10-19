package com.devhub.module.ai.infrastructure.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 模型路由器
 * <p>
 * 职责：
 * <ul>
 *   <li>按请求指定的模型名路由到对应 provider（多模型切换）</li>
 *   <li>结合熔断器与配置的降级链实现自动降级</li>
 *   <li>统一模型调用入口，屏蔽底层差异</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final ChatModelRegistry registry;
    private final CircuitBreaker circuitBreaker;
    private final LlmCallLimiter callLimiter;

    /**
     * 同步调用模型，失败后沿降级链自动切换（受全局并发限制保护）
     *
     * @param promptText    提示词文本
     * @param requestedModel 指定模型别名（为 null 时使用主模型）
     * @return 模型回答文本
     */
    public String call(String promptText, String requestedModel) {
        return callLimiter.guard(() -> doCall(promptText, requestedModel));
    }

    private String doCall(String promptText, String requestedModel) {
        List<String> chain = resolveChain(requestedModel);
        String lastError = null;

        for (String name : chain) {
            try {
                ChatResponse response = registry.get(name).chat(ChatRequest.builder()
                        .messages(List.of(dev.langchain4j.data.message.UserMessage.from(promptText)))
                        .build());
                circuitBreaker.recordSuccess();
                recordUsage(response);
                log.debug("模型调用成功: {}", name);
                return response.aiMessage().text();
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                lastError = e.getMessage();
                log.warn("模型 [{}] 调用失败: {}，尝试降级链下一个模型", name, e.getMessage());
            }
        }
        throw new RuntimeException("所有模型均不可用，最后错误: " + lastError);
    }

    /**
     * 带工具的同步调用（原生 Function Calling），失败沿降级链切换
     *
     * @param messages       消息列表（会被复制，调用方可安全复用）
     * @param tools          工具规格（为空时等价于普通调用）
     * @param requestedModel 指定模型别名（null 用主模型）
     * @return 模型响应（可能包含 toolExecutionRequests）
     */
    public ChatResponse callWithTools(List<ChatMessage> messages, List<ToolSpecification> tools,
                                      String requestedModel) {
        return callLimiter.guard(() -> doCallWithTools(messages, tools, requestedModel));
    }

    private ChatResponse doCallWithTools(List<ChatMessage> messages, List<ToolSpecification> tools,
                                         String requestedModel) {
        List<String> chain = resolveChain(requestedModel);
        String lastError = null;

        for (String name : chain) {
            try {
                ChatResponse response = registry.get(name).chat(ChatRequest.builder()
                        .messages(new ArrayList<>(messages))
                        .toolSpecifications(tools)
                        .build());
                circuitBreaker.recordSuccess();
                recordUsage(response);
                log.debug("带工具调用成功: {}", name);
                return response;
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                lastError = e.getMessage();
                log.warn("模型 [{}] 带工具调用失败: {}，尝试降级链下一个模型", name, e.getMessage());
            }
        }
        throw new RuntimeException("所有模型均不可用，最后错误: " + lastError);
    }

    private void recordUsage(ChatResponse response) {
        try {
            if (response != null && response.tokenUsage() != null) {
                UsageContext.add(
                        response.tokenUsage().inputTokenCount() == null ? 0 : response.tokenUsage().inputTokenCount(),
                        response.tokenUsage().outputTokenCount() == null ? 0 : response.tokenUsage().outputTokenCount());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 流式调用模型
     *
     * @param promptText    提示词文本
     * @param requestedModel 指定模型别名（为 null 时使用主模型）
     * @return 逐 token 的回答流
     */
    public Flux<String> stream(String promptText, String requestedModel) {
        // 订阅时获取许可，流结束时释放——SSE 长流也计入并发额度
        return Flux.defer(() -> {
            callLimiter.acquire();
            try {
                return doStream(promptText, requestedModel)
                        .doFinally(signal -> callLimiter.release());
            } catch (Exception e) {
                callLimiter.release();
                throw e;
            }
        });
    }

    private Flux<String> doStream(String promptText, String requestedModel) {
        String name = resolveChain(requestedModel).get(0);
        StreamingChatModel model = registry.streaming(name);
        log.debug("流式调用模型: {}", name);

        return Flux.create(emitter -> model.chat(promptText, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                emitter.next(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                circuitBreaker.recordSuccess();
                recordUsage(completeResponse);
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                circuitBreaker.recordFailure();
                emitter.error(error);
            }
        }));
    }

    /**
     * 解析调用链：[指定模型或主模型] + 降级链（去重，仅保留已注册模型）
     */
    private List<String> resolveChain(String requestedModel) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();

        if (requestedModel != null && !requestedModel.isBlank()) {
            if (registry.has(requestedModel)) {
                chain.add(requestedModel);
            } else {
                log.warn("请求的模型 [{}] 未注册，回退到默认调用链", requestedModel);
            }
        }

        if (circuitBreaker.allowRequest()) {
            chain.add(registry.primaryName());
        } else {
            log.info("熔断器开启，自动降级到轻量模型");
            chain.add(registry.lightModelName());
        }
        chain.addAll(registry.fallbackNames());

        List<String> result = new ArrayList<>(chain);
        if (result.isEmpty()) {
            result.add(registry.primaryName());
        }
        return result;
    }
}
