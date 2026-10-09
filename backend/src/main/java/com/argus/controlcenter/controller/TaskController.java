package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.Task;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.argus.controlcenter.identity.*;

import java.util.List;

/** 任务及追加事件查询；控制关闭后仍可读取已保存结果。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;
    private final CurrentActorProvider actorProvider;
    private final ProjectAuthorization authorization;

    public TaskController(TaskService service,CurrentActorProvider actorProvider,ProjectAuthorization authorization) {
        this.service = service;
        this.actorProvider=actorProvider; this.authorization=authorization;
    }

    /** 返回最近任务及其执行状态。 */
    @GetMapping
    public ApiResponse<List<Task>> list(@RequestParam(required=false) String projectId) {
        String scope=scope(projectId); return ApiResponse.ok(service.findAll(scope,actorProvider.current()));
    }

    @GetMapping("/by-request-key/{key}")
    public ApiResponse<Task> byRequestKey(@PathVariable String key,@RequestParam(required=false) String projectId) {
        String scope=scope(projectId); return ApiResponse.ok(service.byRequestKey(key,scope,actorProvider.current()));
    }

    /** 按任务 ID 查询执行详情。 */
    @GetMapping("/{id}")
    public ApiResponse<Task> get(@PathVariable String id,@RequestParam(required=false) String projectId) {
        return ApiResponse.ok(service.findById(id,scope(projectId),actorProvider.current()));
    }
    @GetMapping("/{id}/events")
    public ApiResponse<List<com.argus.controlcenter.domain.TaskEvent>> events(@PathVariable String id,@RequestParam(required=false) String projectId) {
        service.findById(id,scope(projectId),actorProvider.current()); return ApiResponse.ok(service.events(id));
    }
    private String scope(String projectId){if(projectId==null||projectId.isBlank()){if(authorization.isIdentityMode())throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);return IdentityConstants.LEGACY_PROJECT_ID;}return projectId;}
}
