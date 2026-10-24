package com.devhub.module.ai.controller.openapi;

import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.openapi.OpenApiAuthService;
import com.devhub.module.ai.model.dto.ChatRequest;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.enums.AgentMode;
import com.devhub.module.ai.service.agent.AgentOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 开放问答 API（规格 §5.1/§5.2）：
 * /chat 走 ApiKeyAuthFilter（X-API-Key）；/widget/stream 走 appKey + Referer 白名单 + 应用级限流。
 * 检索域=应用部门域，语义缓存域=app:{appKey}，记账 username=app:{appKey}
 */
@RestController
@RequestMapping("/api/open/v1")
@RequiredArgsConstructor
public class OpenApiController {

    private final AgentOrchestrator agentOrchestrator;
    private final OpenApiAuthService auth;

    /** 服务端集成：同步问答（Filter 已完成鉴权，应用信息在请求属性） */
    @PostMapping("/chat")
    public Result<com.devhub.module.ai.model.dto.ChatResponse> chat(
            @Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        AiApp app = (AiApp) http.getAttribute("OPEN_API_APP");
        if (!auth.allowRate(app)) {
            throw new BusinessException(429, "应用调用量超过限额，请稍后再试");
        }
        applyAppContext(request, app);
        return auth.setScopeThen(retrievalScope(app), () -> Result.success(agentOrchestrator.chat(request)));
    }

    /** 挂件流式（SSE 协议与内部 /api/v1/chat/stream 一致：__META__ / 分片 / [DONE]） */
    @PostMapping(value = "/widget/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> widget(@RequestBody WidgetChatRequest body, HttpServletRequest http) {
        AiApp app = auth.authenticateApp(body.getAppKey())
                .orElseThrow(() -> new BusinessException(403, "服务暂时下线，请联系管理员"));
        if (!auth.originAllowed(app, http.getHeader("Referer"))) {
            throw new BusinessException(403, "来源不在白名单");
        }
        if (!auth.allowRate(app)) {
            throw new BusinessException(429, "提问太频繁，请稍后再试");
        }
        ChatRequest request = new ChatRequest();
        request.setMessage(body.getMessage());
        request.setConversationId("embed:" + app.getAppKey() + ":"
                + (body.getConversationId() == null ? "default" : body.getConversationId()));
        applyAppContext(request, app);
        return auth.setScopeThen(retrievalScope(app), () -> agentOrchestrator.chatStream(request));
    }

    /** 挂件元信息：应用名 + 开场白（应用禁用返回空 name，前端静默） */
    @GetMapping("/widget/meta/{appKey}")
    public Result<Map<String, String>> widgetMeta(@PathVariable String appKey) {
        return auth.authenticateApp(appKey).map(app -> {
            Map<String, String> m = new HashMap<>();
            m.put("name", app.getName());
            m.put("greeting", app.getOpeningGreeting() == null ? "" : app.getOpeningGreeting());
            return Result.success(m);
        }).orElseGet(() -> Result.success(new HashMap<>(Map.of("name", "", "greeting", ""))));
    }

    private void applyAppContext(ChatRequest request, AiApp app) {
        request.setEnableRag(true);
        request.setMode(AgentMode.DIRECT);
        request.setPersona(app.getSystemPrompt());
        request.setCacheScope("app:" + app.getAppKey());
    }

    private String retrievalScope(AiApp app) {
        return app.getDeptId() == null ? "all" : "dept:" + app.getDeptId();
    }

    /** 挂件请求体 */
    @Data
    public static class WidgetChatRequest {
        private String appKey;
        private String message;
        private String conversationId;
    }
}
