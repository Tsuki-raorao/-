package com.argus.controlcenter.controller;

import com.argus.controlcenter.config.SecurityProperties;
import com.argus.controlcenter.vo.ApiResponse;
import com.argus.controlcenter.vo.HealthVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 健康检查同时验证数据库连接，避免只凭 Java 进程存活返回假正常。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final SecurityProperties security;
    private final JdbcTemplate jdbc;

    public HealthController(SecurityProperties security, JdbcTemplate jdbc) {
        this.security = security;
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<HealthVO> health() {
        boolean databaseReady = databaseReady();
        String status = databaseReady ? "UP" : "DEGRADED";
        return ApiResponse.ok(new HealthVO(status, "argus-control-center", Instant.now(), security.isReadOnly(), databaseReady));
    }

    private boolean databaseReady() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
