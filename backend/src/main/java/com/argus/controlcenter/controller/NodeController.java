package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.domain.NodeStatus;
import com.argus.controlcenter.dto.CreateNodeRequest;
import com.argus.controlcenter.dto.HeartbeatRequest;
import com.argus.controlcenter.service.NodeService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** 节点查询、注册、心跳和删除接口。生产环境写请求由只读拦截器统一保护。 */
@RestController
@RequestMapping("/api/nodes")
public class NodeController {
    private final NodeService service;

    public NodeController(NodeService service) {
        this.service = service;
    }

    /** 返回控制中心已登记的节点列表。 */
    @GetMapping
    public ApiResponse<List<Node>> list() {
        return ApiResponse.ok(service.findAll());
    }

    /** 按节点 ID 查询单个节点。 */
    @GetMapping("/{id}")
    public ApiResponse<Node> get(@PathVariable String id) {
        return ApiResponse.ok(service.findById(id));
    }

    /** 登记一个新节点；生产只读模式下会在拦截器层拒绝。 */
    @PostMapping
    public ApiResponse<Node> create(@Valid @RequestBody CreateNodeRequest request) {
        return ApiResponse.created(service.create(request));
    }

    /** 接收 Agent 或运维端发送的节点心跳。 */
    @PostMapping("/{id}/heartbeat")
    public ApiResponse<Node> heartbeat(@PathVariable String id,
                                       @RequestBody(required = false) HeartbeatRequest request) {
        NodeStatus status = null;
        if (request != null && request.getStatus() != null) {
            status = NodeStatus.valueOf(request.getStatus().toUpperCase(Locale.ROOT));
        }
        return ApiResponse.ok(service.heartbeat(id, status));
    }

    /** 删除节点及其关联的实例、任务和日志。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
