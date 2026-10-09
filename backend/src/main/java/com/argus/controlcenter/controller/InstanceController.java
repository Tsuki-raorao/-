package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.Instance;
import com.argus.controlcenter.dto.ActionRequest;
import com.argus.controlcenter.service.InstanceService;
import com.argus.controlcenter.service.InstanceLogService;
import com.argus.controlcenter.vo.InstanceLogsVO;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import com.argus.controlcenter.config.ApiAccessInterceptor;
import com.argus.controlcenter.identity.CurrentActor;
import com.argus.controlcenter.identity.CurrentActorProvider;
import com.argus.controlcenter.identity.ProjectAuthorization;
import com.argus.controlcenter.identity.ProjectPermission;
import com.argus.controlcenter.identity.IdentityAuthorizationException;
import com.argus.controlcenter.identity.IdentityConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 服务实例查询和动作入口；生产环境动作接口当前保持只读保护。 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {
    private final InstanceService instances;
    private final TaskService tasks;
    private final InstanceLogService logs;
    private final CurrentActorProvider actorProvider;
    private final ProjectAuthorization authorization;

    public InstanceController(InstanceService instances, TaskService tasks, InstanceLogService logs, CurrentActorProvider actorProvider,ProjectAuthorization authorization) {
        this.instances = instances;
        this.tasks = tasks;
        this.logs = logs;
        this.actorProvider = actorProvider;
        this.authorization=authorization;
    }

    /** 返回所有节点同步到控制中心的实例快照。 */
    @GetMapping
    public ApiResponse<List<Instance>> list(@RequestParam(required=false) String projectId) {
        String scope=scope(projectId); authorization.require(actorProvider.current(),scope,ProjectPermission.RESOURCE_READ);
        return ApiResponse.ok(instances.findAll().stream().filter(i->scope.equals(i.getProjectId())).toList());
    }

    /** 按实例 ID 查询状态和资源指标。 */
    @GetMapping("/{id}")
    public ApiResponse<Instance> get(@PathVariable String id,@RequestParam(required=false) String projectId) {
        String scope=scope(projectId); authorization.require(actorProvider.current(),scope,ProjectPermission.RESOURCE_READ);
        Instance instance=instances.findById(id); if(!scope.equals(instance.getProjectId())) throw new com.argus.controlcenter.exception.NotFoundException("instance not found");
        return ApiResponse.ok(instance);
    }

    /** 中央 ID 定位节点，按需读取最近日志（非持续流）；失败不返回伪空列表。 */
    @GetMapping("/{id}/logs")
    public ApiResponse<InstanceLogsVO> logs(@PathVariable String id,@RequestParam(required=false) String projectId, @RequestParam(defaultValue = "100") int limit) {
        String scope=scope(projectId); authorization.require(actorProvider.current(),scope,ProjectPermission.RESOURCE_READ);
        Instance instance=instances.findById(id); if(!scope.equals(instance.getProjectId())) throw new com.argus.controlcenter.exception.NotFoundException("instance not found");
        return ApiResponse.ok(logs.read(id, limit));
    }

    /** 操作令牌、只读开关及目标授权通过后持久接收任务；202 不表示执行完成。 */
    @PostMapping("/{id}/actions")
    public ResponseEntity<ApiResponse<com.argus.controlcenter.domain.Task>> action(@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestParam(required=false) String projectId,
            @Valid @RequestBody ActionRequest request,HttpServletRequest http) {
        CurrentActor actor=actorProvider.current();
        return ResponseEntity.accepted().body(ApiResponse.ok(tasks.execute(id,request.getAction(),request.getExpectedExecutionMode(),key,
                projectId,actor,Boolean.TRUE.equals(http.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE)) && actor.legacyOperator())));
    }
    private String scope(String projectId){if(projectId==null||projectId.isBlank()){if(authorization.isIdentityMode())throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);return IdentityConstants.LEGACY_PROJECT_ID;}return projectId;}
}
