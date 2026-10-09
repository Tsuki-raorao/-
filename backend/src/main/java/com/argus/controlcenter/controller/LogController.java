package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.LogEntry;
import com.argus.controlcenter.service.LogService;
import com.argus.controlcenter.vo.ApiResponse;
import com.argus.controlcenter.identity.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 实例日志只读查询接口。 */
@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final LogService service;
    private final CurrentActorProvider actors;
    private final ProjectAuthorization authorization;

    public LogController(LogService service, CurrentActorProvider actors, ProjectAuthorization authorization) {
        this.service = service; this.actors = actors; this.authorization = authorization;
    }

    /** 按实例筛选日志，并限制返回数量防止一次读取过大。 */
    @GetMapping
    public ApiResponse<List<LogEntry>> list(@RequestParam(required = false) String instanceId,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String projectId) {
        String scope = projectId;
        if (scope == null || scope.isBlank()) {
            if (authorization.isIdentityMode()) throw new IdentityAuthorizationException("PROJECT_REQUIRED", 400);
            scope = IdentityConstants.LEGACY_PROJECT_ID;
        }
        authorization.require(actors.current(), scope, ProjectPermission.RESOURCE_READ);
        return ApiResponse.ok(service.find(instanceId, limit, scope));
    }
}
