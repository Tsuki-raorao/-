package com.argus.controlcenter.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/** 生产只读模式下拦截 API 写请求，避免未完成认证时暴露控制能力。 */
public class WriteProtectionInterceptor implements HandlerInterceptor {
    private final SecurityProperties properties;

    public WriteProtectionInterceptor(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!properties.isReadOnly() || isReadMethod(request.getMethod())) return true;
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":405,\"message\":\"production read-only mode\",\"data\":null}");
        return false;
    }

    private boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }
}
