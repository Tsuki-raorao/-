package com.argus.controlcenter.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/** Web API 的跨域配置。允许来源通过环境变量覆盖，避免生产环境使用通配符。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final SecurityProperties securityProperties;

    public WebConfig(@Value("${argus.web.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String allowedOrigins,
                     SecurityProperties securityProperties) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        this.securityProperties = securityProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "Idempotency-Key")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiAccessInterceptor(securityProperties))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health");
        registry.addInterceptor(new WriteProtectionInterceptor(securityProperties)).addPathPatterns("/api/**");
    }
}
