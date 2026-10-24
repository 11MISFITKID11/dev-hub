package com.devhub.module.ai.controller.openapi;

import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.infrastructure.openapi.OpenApiAuthService;
import com.devhub.module.ai.model.dto.ChatRequest;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.service.agent.AgentOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenApiControllerTest {

    @Mock AgentOrchestrator orchestrator;
    @Mock OpenApiAuthService auth;
    @Mock HttpServletRequest http;

    private OpenApiController controller;

    @BeforeEach
    void setUp() { controller = new OpenApiController(orchestrator, auth); }

    private AiApp app() {
        AiApp a = new AiApp();
        a.setId(1L); a.setAppKey("app-abc"); a.setEnabled(1);
        a.setDeptId(3L); a.setSystemPrompt("你是IT服务台助手"); a.setRateLimitPerMin(20);
        return a;
    }

    /** setScopeThen 直通执行（测试用）：设置域并执行动作 */
    private void stubScopePassthrough() {
        when(auth.setScopeThen(anyString(), any())).thenAnswer(inv -> {
            Supplier<?> s = inv.getArgument(1);
            return s.get();
        });
    }

    @Test
    @DisplayName("开放问答：persona/cacheScope 注入正确，RAG 强制开，检索域=应用部门域")
    void chat_injectsAppContext() {
        AiApp app = app();
        when(http.getAttribute("OPEN_API_APP")).thenReturn(app);
        when(auth.allowRate(app)).thenReturn(true);
        stubScopePassthrough();
        ChatRequest req = new ChatRequest();
        req.setMessage("VPN 怎么连");

        controller.chat(req, http);

        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chat(cap.capture());
        ChatRequest sent = cap.getValue();
        assertEquals("你是IT服务台助手", sent.getPersona());
        assertEquals("app:app-abc", sent.getCacheScope());
        assertTrue(sent.isEnableRag());
        verify(auth).setScopeThen(eq("dept:3"), any());
    }

    @Test
    @DisplayName("开放问答：限流超限抛 429 业务异常")
    void chat_rateLimited() {
        AiApp app = app();
        when(http.getAttribute("OPEN_API_APP")).thenReturn(app);
        when(auth.allowRate(app)).thenReturn(false);
        ChatRequest req = new ChatRequest();
        req.setMessage("hi");
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.chat(req, http));
        assertEquals(429, ex.getCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("挂件：应用禁用抛 403")
    void widget_disabledApp() {
        when(auth.authenticateApp("app-x")).thenReturn(Optional.empty());
        OpenApiController.WidgetChatRequest w = new OpenApiController.WidgetChatRequest();
        w.setAppKey("app-x"); w.setMessage("hi");
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.widget(w, http));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("挂件：Referer 不在白名单抛 403")
    void widget_originBlocked() {
        AiApp app = app();
        when(auth.authenticateApp("app-abc")).thenReturn(Optional.of(app));
        when(http.getHeader("Referer")).thenReturn("https://evil.com");
        when(auth.originAllowed(app, "https://evil.com")).thenReturn(false);
        OpenApiController.WidgetChatRequest w = new OpenApiController.WidgetChatRequest();
        w.setAppKey("app-abc"); w.setMessage("hi");
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.widget(w, http));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("挂件：通过后走 chatStream 且会话前缀 embed:")
    void widget_ok() {
        AiApp app = app();
        when(auth.authenticateApp("app-abc")).thenReturn(Optional.of(app));
        when(http.getHeader("Referer")).thenReturn(null);
        when(auth.originAllowed(app, null)).thenReturn(true);
        when(auth.allowRate(app)).thenReturn(true);
        when(auth.setScopeThen(anyString(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        when(orchestrator.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just("data"));
        OpenApiController.WidgetChatRequest w = new OpenApiController.WidgetChatRequest();
        w.setAppKey("app-abc"); w.setMessage("hi"); w.setConversationId("c1");
        controller.widget(w, http);
        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chatStream(cap.capture());
        assertTrue(cap.getValue().getConversationId().startsWith("embed:app-abc:c1"));
        assertEquals("你是IT服务台助手", cap.getValue().getPersona());
        assertEquals("app:app-abc", cap.getValue().getCacheScope());
    }
}
