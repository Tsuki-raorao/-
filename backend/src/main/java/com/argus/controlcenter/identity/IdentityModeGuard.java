package com.argus.controlcenter.identity;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 在嵌入式 Web 服务接收请求前校验持久化身份模式，禁止配置误改造成旧令牌旁路。 */
@Component
@DependsOn("flywayInitializer")
public class IdentityModeGuard implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;
    private final IdentityModeProperties properties;
    public IdentityModeGuard(JdbcTemplate jdbc, IdentityModeProperties properties) { this.jdbc=jdbc; this.properties=properties; }
    @Override public void afterSingletonsInstantiated() {
        String configured = properties.getMode().name();
        String persisted = jdbc.queryForObject("SELECT mode FROM identity_config_gate WHERE id=?", String.class, IdentityConstants.IDENTITY_CONFIG_GATE_ID);
        if (!configured.equals(persisted)) {
            if ("LEGACY_TOKEN".equals(persisted) && "OIDC_IDENTITY".equals(configured)
                    && !properties.getIssuer().isBlank() && properties.getIssuer().equals(properties.getBootstrapIssuer())
                    && !properties.getBootstrapIssuer().isBlank()
                    && !properties.getBootstrapSubject().isBlank()) {
                String bootstrapId = UUID.nameUUIDFromBytes((properties.getBootstrapIssuer()+"\u0000"+properties.getBootstrapSubject()).getBytes(StandardCharsets.UTF_8)).toString();
                try { jdbc.update("INSERT INTO users(id,issuer,subject,display_name,status,platform_role,version_no,created_at,updated_at) VALUES(?,?,?,'Bootstrap administrator','ACTIVE','ADMIN',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", bootstrapId, properties.getBootstrapIssuer(), properties.getBootstrapSubject()); }
                catch (DuplicateKeyException ignored) { jdbc.update("UPDATE users SET issuer=?,subject=?,status='ACTIVE',platform_role='ADMIN',version_no=version_no+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", properties.getBootstrapIssuer(), properties.getBootstrapSubject(), bootstrapId); }
                try { jdbc.update("INSERT INTO project_members(project_id,user_id,role,version_no,created_at,updated_at) VALUES(?,?, 'ADMIN',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", IdentityConstants.LEGACY_PROJECT_ID, bootstrapId); }
                catch (DuplicateKeyException ignored) { jdbc.update("UPDATE project_members SET role='ADMIN',version_no=version_no+1,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND user_id=?", IdentityConstants.LEGACY_PROJECT_ID, bootstrapId); }
                int changed = jdbc.update("UPDATE identity_config_gate SET mode='OIDC_IDENTITY', oidc_activated=TRUE, bootstrap_issuer=?, bootstrap_subject=?, bootstrap_user_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND mode='LEGACY_TOKEN'",
                        properties.getBootstrapIssuer(), properties.getBootstrapSubject(), bootstrapId, IdentityConstants.IDENTITY_CONFIG_GATE_ID);
                if (changed != 1) throw new IllegalStateException("identity mode activation raced");
            } else {
                throw new IllegalStateException("identity mode mismatch; refusing authentication fallback");
            }
        }
    }
}
