package com.argus.controlcenter.identity;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/** 从 Spring Security 身份映射到本地 users；邮箱和令牌声明不提供角色。 */
@Component
public class JdbcCurrentActorProvider implements CurrentActorProvider {
    private final JdbcTemplate jdbc;
    private final IdentityModeProperties properties;

    public JdbcCurrentActorProvider(JdbcTemplate jdbc, IdentityModeProperties properties) {
        this.jdbc = jdbc; this.properties = properties;
    }

    @Override
    public CurrentActor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (properties.getMode() == IdentityModeProperties.Mode.LEGACY_TOKEN) {
            // 旧令牌仅作为迁移期间的系统历史主体；不能在 OIDC 模式被此分支接受。
            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal()))
                return new CurrentActor(IdentityConstants.LEGACY_USER_ID, AuthMode.LEGACY_TOKEN, PlatformRole.SYSTEM_LEGACY, true);
            return new CurrentActor(IdentityConstants.LEGACY_USER_ID, AuthMode.LEGACY_TOKEN, PlatformRole.SYSTEM_LEGACY, true);
        }
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal()))
            throw new IdentityAuthorizationException("AUTH_REQUIRED", 401);
        Subject subject = subject(authentication);
        List<ActorRow> rows = jdbc.query(
                "SELECT id,platform_role,status FROM users WHERE issuer=? AND subject=? LIMIT 2",
                (rs, n) -> new ActorRow(rs.getString(1), rs.getString(2), rs.getString(3)), subject.issuer(), subject.subject());
        if (rows.size() != 1) throw new IdentityAuthorizationException("IDENTITY_DISABLED", 403);
        ActorRow row = rows.get(0);
        if (!IdentityStatus.ACTIVE.name().equals(row.status())) throw new IdentityAuthorizationException("IDENTITY_DISABLED", 403);
        PlatformRole role;
        try { role = PlatformRole.valueOf(row.platformRole()); }
        catch (IllegalArgumentException e) { throw new IdentityAuthorizationException("IDENTITY_DISABLED", 403); }
        return new CurrentActor(row.id(), authentication.getPrincipal() instanceof Jwt ? AuthMode.JWT : AuthMode.OIDC_SESSION, role, false);
    }

    private static Subject subject(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
            String sub = jwt.getSubject();
            if (issuer.isBlank() || sub == null || sub.isBlank()) throw new IdentityAuthorizationException("AUTH_REQUIRED", 401);
            return new Subject(issuer, sub);
        }
        if (authentication.getPrincipal() instanceof OAuth2User user) {
            Object sub = user.getAttributes().get("sub");
            Object issuer = user.getAttributes().get("iss");
            if (issuer == null) issuer = authentication.getName().contains("|") ? authentication.getName().split("\\|", 2)[0] : "";
            if (issuer == null || issuer.toString().isBlank() || sub == null || sub.toString().isBlank())
                throw new IdentityAuthorizationException("AUTH_REQUIRED", 401);
            return new Subject(issuer.toString(), sub.toString());
        }
        throw new IdentityAuthorizationException("AUTH_REQUIRED", 401);
    }

    private record Subject(String issuer, String subject) { }
    private record ActorRow(String id, String platformRole, String status) { }

}
