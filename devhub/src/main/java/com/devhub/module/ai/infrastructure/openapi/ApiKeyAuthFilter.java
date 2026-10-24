package com.devhub.module.ai.infrastructure.openapi;

import com.devhub.module.ai.model.entity.AiApp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * /api/open/v1/chat 的 X-API-Key 鉴权：通过后应用放请求属性 OPEN_API_APP。
 * widget 端点不走此 Filter（appKey 在 Controller 内校验）
 */
@Order(2)
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final OpenApiAuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/open/v1/chat")) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AiApp> app = authService.authenticateKey(request.getHeader("X-API-Key"));
        if (app.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Invalid API key\"}");
            return;
        }
        request.setAttribute("OPEN_API_APP", app.get());
        chain.doFilter(request, response);
    }
}
