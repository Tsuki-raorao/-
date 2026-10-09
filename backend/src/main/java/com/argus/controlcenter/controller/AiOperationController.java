package com.argus.controlcenter.controller;

import com.argus.controlcenter.config.ApiAccessInterceptor;
import com.argus.controlcenter.dto.AiOperationRequest;
import com.argus.controlcenter.domain.Task;
import com.argus.controlcenter.identity.CurrentActor;
import com.argus.controlcenter.identity.CurrentActorProvider;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** AI 的最小执行闭环：只接受已确认的实例重启，并复用任务队列、互斥、幂等和审计。 */
@RestController
@RequestMapping("/api/ai/operations")
public class AiOperationController {
    private final TaskService tasks;
    private final CurrentActorProvider actorProvider;

    public AiOperationController(TaskService tasks, CurrentActorProvider actorProvider) {
        this.tasks = tasks;
        this.actorProvider = actorProvider;
    }

    @PostMapping("/restart")
    public ResponseEntity<ApiResponse<Task>> restart(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AiOperationRequest request, HttpServletRequest http) {
        CurrentActor actor = actorProvider.current();
        boolean legacyOperator = Boolean.TRUE.equals(http.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE)) && actor.legacyOperator();
        Task task = tasks.execute(request.getInstanceId(), "RESTART", request.getExpectedExecutionMode(), key,
                request.getProjectId(), actor, legacyOperator);
        return ResponseEntity.accepted().body(ApiResponse.ok(task));
    }
}
