package com.argus.controlcenter.identity;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import java.util.Set;
import org.springframework.http.HttpMethod;

/** 身份模式的唯一 Spring Security 链；旧共享令牌不作为 OIDC 降级。 */
@Configuration
public class IdentitySecurityConfig {
    private final IdentityModeProperties properties;
    public IdentitySecurityConfig(IdentityModeProperties properties) { this.properties=properties; }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll();
            auth.requestMatchers("/api/health", "/api/auth/config", "/api/auth/login",
                    "/api/auth/authorization/**", "/api/auth/callback/**", "/error", "/", "/index.html", "/assets/**").permitAll();
            if (properties.getMode() == IdentityModeProperties.Mode.LEGACY_TOKEN) auth.anyRequest().permitAll();
            else auth.anyRequest().authenticated();
        });
        if (properties.getMode() == IdentityModeProperties.Mode.OIDC_IDENTITY) {
            http.cors(Customizer.withDefaults()).csrf(Customizer.withDefaults())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .oauth2Login(o -> o.authorizationEndpoint(a -> a.baseUri("/api/auth/authorization"))
                            .redirectionEndpoint(r -> r.baseUri("/api/auth/callback/*")))
                    .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        } else {
            // 迁移期静态令牌由既有 ApiAccessInterceptor 校验；一旦切到 OIDC，此分支不可再放行。
            http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        }
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        if (properties.getMode() != IdentityModeProperties.Mode.OIDC_IDENTITY) return token -> { throw new IllegalStateException("OIDC identity mode is disabled"); };
        if (properties.getIssuer().isBlank()) throw new IllegalStateException("argus.identity.issuer is required");
        if (properties.getAudience().isBlank()) throw new IllegalStateException("argus.identity.audience is required");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.getIssuer()).build();
        @SuppressWarnings("unchecked") OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            Object algorithm = jwt.getHeaders().get("alg");
            Set<String> allowed = Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512");
            if (!(algorithm instanceof String) || !allowed.contains(algorithm) || jwt.getSubject() == null || jwt.getSubject().isBlank())
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "algorithm or subject is not allowed", null));
            return OAuth2TokenValidatorResult.success();
        };
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().size() == 1 && jwt.getAudience().contains(properties.getAudience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience is not allowed", null));
        OAuth2TokenValidator<Jwt> validator = properties.getAudience().isBlank()
                ? new DelegatingOAuth2TokenValidator<>(issuer, claims)
                : new DelegatingOAuth2TokenValidator<>(issuer, audience, claims);
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
