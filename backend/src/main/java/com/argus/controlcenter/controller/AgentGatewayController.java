package com.argus.controlcenter.controller;

import com.argus.controlcenter.service.AgentGatewayService;
import com.argus.controlcenter.vo.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制中心读取 Agent 的内部接口。
 * 浏览器只调用控制中心，不直接访问节点；此控制器没有 POST/PUT/DELETE。
 */
@RestController
@RequestMapping("/api/agent-gateway/nodes/{nodeId}")
public class AgentGatewayController {
    private final AgentGatewayService gateway;

    public AgentGatewayController(AgentGatewayService gateway) { this.gateway = gateway; }

    @GetMapping("/health")
    public ApiResponse<JsonNode> health(@PathVariable String nodeId) {
        return ApiResponse.ok(gateway.health(nodeId));
    }

    @GetMapping("/instances")
    public ApiResponse<JsonNode> instances(@PathVariable String nodeId) {
        return ApiResponse.ok(gateway.instances(nodeId));
    }

    @GetMapping("/instances/{instanceId}/logs")
    public ApiResponse<JsonNode> logs(@PathVariable String nodeId, @PathVariable String instanceId,
                                      @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(gateway.logs(nodeId, instanceId, limit));
    }
}
