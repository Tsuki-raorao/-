package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.Instance;
import com.argus.controlcenter.exception.AgentGatewayException;
import com.argus.controlcenter.vo.InstanceLogsVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 浏览器只给中央 ID，由控制中心解析节点及 Agent 内部 ID。 */
@Service
public class InstanceLogService {
    private final InstanceService instances;
    private final AgentGatewayService gateway;
    public InstanceLogService(InstanceService instances, AgentGatewayService gateway) { this.instances = instances; this.gateway = gateway; }

    public InstanceLogsVO read(String id, int limit) {
        Instance instance = instances.findById(id);
        int bounded = Math.max(1, Math.min(limit, 1000));
        JsonNode response = gateway.logs(instance.getNodeId(), instance.getAgentInstanceId(), bounded);
        if (response == null || !response.isArray()) throw invalid();
        List<String> lines = new ArrayList<>();
        for (JsonNode value : response) {
            if (!value.isTextual()) throw invalid();
            lines.add(value.asText());
        }
        if (lines.size() > bounded) lines = new ArrayList<>(lines.subList(lines.size() - bounded, lines.size()));
        return new InstanceLogsVO(id, instance.getNodeId(), instance.getAgentInstanceId(), Instant.now(), List.copyOf(lines));
    }
    private AgentGatewayException invalid() { return new AgentGatewayException(502, "agent returned invalid logs"); }
}
