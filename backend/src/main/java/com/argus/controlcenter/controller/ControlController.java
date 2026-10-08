package com.argus.controlcenter.controller;

import com.argus.controlcenter.config.ApiAccessInterceptor;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/control")
public class ControlController {
    private final TaskService tasks;
    public ControlController(TaskService tasks) { this.tasks=tasks; }
    @GetMapping("/capabilities")
    public ApiResponse<ControlCapabilities> capabilities(HttpServletRequest request) {
        return ApiResponse.ok(tasks.capabilities(Boolean.TRUE.equals(request.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE))));
    }
}
