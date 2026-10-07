package com.argus.controlcenter.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 可选的 API 访问令牌保护。
 *
 * <p>健康检查保持公开，其他 API 在开启开关后必须提供
 * {@code Authorization: Bearer <token>}。令牌只从环境变量注入，
 * 令牌缺失时 fail-closed，避免误把受保护模式当成匿名访问。</p>
 */
public class ApiAccessInterceptor implements HandlerInterceptor {
    private final SecurityProperties properties;

    public ApiAccessInterceptor(SecurityProperties properties) {
        this.properties = properties;
        if (properties.isApiAuthRequired() && properties.getApiAccessToken().isBlank()) {
            throw new IllegalStateException("ARGUS_API_ACCESS_TOKEN is required when ARGUS_API_AUTH_REQUIRED=true");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!properties.isApiAuthRequired() || isHealthPath(request)) return true;

        String header = request.getHeader("Authorization");
        String prefix = "Bearer ";
        String presented = header != null && header.startsWith(prefix)
                ? header.substring(prefix.length()).trim() : "";
        byte[] expected = properties.getApiAccessToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expected, actual)) return true;

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"API authentication required\",\"data\":null}");
        return false;
    }

    private boolean isHealthPath(HttpServletRequest request) {
        return "/api/health".equals(request.getRequestURI());
    }
}
