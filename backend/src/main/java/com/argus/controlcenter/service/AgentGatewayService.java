package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AgentGatewayProperties;
import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.exception.AgentGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * 控制中心到节点 Agent 的最小只读网关。
 *
 * <p>此类只转发 health、instances、logs 三类 GET 请求；没有通用 URL
 * 代理，也没有远程 Docker/SSH/shell 能力。默认配置关闭，开启时还要求
 * 目标主机命中白名单。</p>
 */
@Service
public class AgentGatewayService {
    private final NodeService nodes;
    private final AgentGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public AgentGatewayService(NodeService nodes, AgentGatewayProperties properties, ObjectMapper objectMapper) {
        this.nodes = nodes;
        this.properties = properties;
        validateProductionBoundary(properties);
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout(properties.getConnectTimeout(), Duration.ofSeconds(3)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 网关一旦开启必须同时具备令牌和主机白名单，缺配置时拒绝启动。 */
    private void validateProductionBoundary(AgentGatewayProperties value) {
        if (!value.isEnabled()) return;
        if (value.getAuthToken().isBlank()) {
            throw new IllegalStateException("ARGUS_AGENT_AUTH_TOKEN is required when agent gateway is enabled");
        }
        if (value.isRequireAllowlist() && value.getAllowedHosts().isEmpty()) {
            throw new IllegalStateException("ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS is required when allowlist is enabled");
        }
    }

    /** 读取 Agent 健康状态。 */
    public JsonNode health(String nodeId) { return get(nodeId, "/api/agent/health"); }

    /** 读取节点上的实例快照。 */
    public JsonNode instances(String nodeId) { return get(nodeId, "/api/agent/instances"); }

    /** 读取某个实例最近日志，limit 在网关端限制为 1 到 1000。 */
    public JsonNode logs(String nodeId, String instanceId, int limit) {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("instanceId contains unsupported characters");
        }
        int bounded = Math.max(1, Math.min(limit, 1000));
        return get(nodeId, "/api/agent/instances/" + instanceId + "/logs?limit=" + bounded);
    }

    private JsonNode get(String nodeId, String path) {
        if (!properties.isEnabled()) {
            throw new AgentGatewayException(503, "agent gateway is disabled");
        }
        if (!properties.isReadOnly()) {
            throw new AgentGatewayException(503, "agent gateway must run in read-only mode");
        }
        Node node = nodes.findById(nodeId);
        URI target = target(node.getAddress(), path);
        assertAllowedHost(target);
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(timeout(properties.getReadTimeout(), Duration.ofSeconds(5)))
                .header("Accept", "application/json")
                .GET();
        if (!properties.getAuthToken().isBlank()) {
            request.header("Authorization", "Bearer " + properties.getAuthToken());
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 不透传远端正文，避免日志或错误响应泄露敏感数据。
                throw new AgentGatewayException(502, "agent returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (AgentGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentGatewayException(502, "agent request failed", e);
        }
    }

    private URI target(String rawAddress, String path) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new AgentGatewayException(400, "node agent address is empty");
        }
        String value = rawAddress.trim();
        if (!value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) value = "http://" + value;
        try {
            URI base = URI.create(value);
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || base.getHost() == null
                    || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalArgumentException("unsupported agent address");
            }
            int port = base.getPort();
            String authority = base.getHost() + (port < 0 ? "" : ":" + port);
            return URI.create(scheme + "://" + authority + path);
        } catch (IllegalArgumentException e) {
            throw new AgentGatewayException(400, "unsupported agent address", e);
        }
    }

    private void assertAllowedHost(URI target) {
        if (!properties.isRequireAllowlist()) return;
        boolean allowed = properties.getAllowedHosts().stream()
                .map(String::trim).filter(s -> !s.isBlank())
                .anyMatch(host -> host.equalsIgnoreCase(target.getHost()));
        if (!allowed) throw new AgentGatewayException(403, "agent host is not in the allowlist");
    }

    private Duration timeout(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
