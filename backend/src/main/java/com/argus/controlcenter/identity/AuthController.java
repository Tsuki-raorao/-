package com.argus.controlcenter.identity;

import com.argus.controlcenter.config.SecurityProperties;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 身份入口只返回最小状态；不把 issuer、subject、令牌或内部权限材料返回浏览器。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final IdentityModeProperties identity;
    private final SecurityProperties security;
    private final CurrentActorProvider actors;
    private final ProjectAuthorization authorization;
    private final JdbcTemplate jdbc;

    public AuthController(IdentityModeProperties identity, SecurityProperties security, CurrentActorProvider actors,
                          ProjectAuthorization authorization, JdbcTemplate jdbc) {
        this.identity = identity; this.security = security; this.actors = actors; this.authorization = authorization; this.jdbc = jdbc;
    }

    @GetMapping("/config")
    public ApiResponse<AuthConfig> config() {
        boolean oidc = identity.getMode() == IdentityModeProperties.Mode.OIDC_IDENTITY;
        return ApiResponse.ok(new AuthConfig(identity.getMode().name(), oidc ? "/api/auth/login" : null, oidc, security.isReadOnly()));
    }

    @GetMapping("/me")
    public ApiResponse<MeView> me() {
        CurrentActor actor = actors.requireCurrent();
        UserRow row = jdbc.queryForObject("SELECT display_name,platform_role,status,version_no FROM users WHERE id=?",
                (rs, n) -> new UserRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)), actor.userId());
        return ApiResponse.ok(new MeView(actor.userId(), row.displayName(), row.platformRole(), row.status(), actor.authMode().name(), security.isReadOnly()));
    }

    @GetMapping("/csrf")
    public ApiResponse<CsrfView> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ApiResponse.ok(token == null ? null : new CsrfView(token.getHeaderName(), token.getToken()));
    }

    @GetMapping("/login")
    public RedirectView login() {
        if (identity.getMode() != IdentityModeProperties.Mode.OIDC_IDENTITY) return new RedirectView("/api/auth/config", true);
        return new RedirectView("/api/auth/authorization/" + identity.getClientRegistration(), false);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ApiResponse.ok(null);
    }

    public record AuthConfig(String mode, String loginPath, boolean csrfRequired, boolean readOnly) { }
    public record MeView(String userId, String displayName, String platformRole, String status, String authMode, boolean readOnly) { }
    public record CsrfView(String headerName, String token) { }
    private record UserRow(String displayName, String platformRole, String status, long version) { }
}
