package com.argus.controlcenter.controller;

import com.argus.controlcenter.config.ApiAccessInterceptor;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import com.argus.controlcenter.identity.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/control")
public class ControlController {
    private final TaskService tasks;
    private final CurrentActorProvider actors;
    private final ProjectAuthorization authorization;
    public ControlController(TaskService tasks,CurrentActorProvider actors,ProjectAuthorization authorization) { this.tasks=tasks; this.actors=actors; this.authorization=authorization; }
    @GetMapping("/capabilities")
    public ApiResponse<ControlCapabilities> capabilities(@RequestParam(required=false) String projectId,HttpServletRequest request) {
        if (authorization.isIdentityMode()) {
            if(projectId==null||projectId.isBlank()) throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);
            return ApiResponse.ok(tasks.capabilities(projectId,actors.current()));
        }
        return ApiResponse.ok(tasks.capabilities(Boolean.TRUE.equals(request.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE))));
    }
}
