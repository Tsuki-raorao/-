package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.domain.NodeStatus;
import com.argus.controlcenter.dto.CreateNodeRequest;
import com.argus.controlcenter.dto.HeartbeatRequest;
import com.argus.controlcenter.service.NodeService;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** 节点查询、注册、心跳和删除接口。生产环境写请求由只读拦截器统一保护。 */
@RestController
@RequestMapping("/api/nodes")
public class NodeController {
    private final NodeService service;
    private final CurrentActorProvider actorProvider;
    private final ProjectAuthorization authorization;

    public NodeController(NodeService service,CurrentActorProvider actorProvider,ProjectAuthorization authorization) {
        this.service = service;
        this.actorProvider=actorProvider; this.authorization=authorization;
    }

    /** 返回控制中心已登记的节点列表。 */
    @GetMapping
    public ApiResponse<List<Node>> list(@RequestParam(required=false) String projectId) {
        CurrentActor actor=actorProvider.current();
        String scope=scope(projectId); authorization.require(actor,scope,ProjectPermission.RESOURCE_READ);
        return ApiResponse.ok(service.findAll().stream().filter(n->scope.equals(n.getProjectId())).toList());
    }

    /** 按节点 ID 查询单个节点。 */
    @GetMapping("/{id}")
    public ApiResponse<Node> get(@PathVariable String id,@RequestParam(required=false) String projectId) {
        CurrentActor actor=actorProvider.current(); String scope=scope(projectId); authorization.require(actor,scope,ProjectPermission.RESOURCE_READ);
        Node node=service.findById(id); if(!scope.equals(node.getProjectId())) throw new com.argus.controlcenter.exception.NotFoundException("node not found");
        return ApiResponse.ok(node);
    }

    /** 登记一个新节点；生产只读模式下会在拦截器层拒绝。 */
    @PostMapping
    public ApiResponse<Node> create(@RequestParam String projectId,@Valid @RequestBody CreateNodeRequest request) {
        CurrentActor actor=actorProvider.current(); authorization.requirePlatformAdminForUpdate(actor,projectId);
        return ApiResponse.created(service.create(request,projectId));
    }

    /** 接收 Agent 或运维端发送的节点心跳。 */
    @PostMapping("/{id}/heartbeat")
    public ApiResponse<Node> heartbeat(@PathVariable String id,
                                       @RequestBody(required = false) HeartbeatRequest request) {
        if(authorization.isIdentityMode()) throw new com.argus.controlcenter.identity.IdentityAuthorizationException("HEARTBEAT_SYSTEM_ONLY",403);
        NodeStatus status = null;
        if (request != null && request.getStatus() != null) {
            status = NodeStatus.valueOf(request.getStatus().toUpperCase(Locale.ROOT));
        }
        return ApiResponse.ok(service.heartbeat(id, status));
    }

    /** 删除节点及其关联的实例、任务和日志。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id,@RequestParam String projectId) {
        CurrentActor actor=actorProvider.current(); authorization.requirePlatformAdminForUpdate(actor,projectId);
        Node node=service.findById(id); if(!projectId.equals(node.getProjectId())) throw new com.argus.controlcenter.exception.NotFoundException("node not found");
        service.delete(id);
        return ApiResponse.ok(null);
    }
    private String scope(String projectId){
        if(projectId==null||projectId.isBlank()) {
            if(authorization.isIdentityMode()) throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);
            return IdentityConstants.LEGACY_PROJECT_ID;
        }
        return projectId;
    }
}
