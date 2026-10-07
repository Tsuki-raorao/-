package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AgentGatewayProperties;
import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.domain.NodeStatus;
import com.argus.controlcenter.exception.AgentGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用本机临时 HTTP 服务验证网关的只读转发和主机白名单。 */
class AgentGatewayServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsHealthFromAllowlistedAgent() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/health", exchange -> {
            byte[] body = "{\"status\":\"ONLINE\",\"readOnly\":true,\"hostCpuPercent\":12.5,\"hostMemoryBytes\":1024,\"hostMemoryTotalBytes\":4096}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        AgentGatewayProperties properties = propertiesFor("127.0.0.1");
        NodeService nodes = mock(NodeService.class);
        when(nodes.findById("node-test")).thenReturn(node("127.0.0.1:" + server.getAddress().getPort()));

        AgentGatewayService gateway = new AgentGatewayService(nodes, properties, new ObjectMapper());
        assertThat(gateway.health("node-test").path("status").asText()).isEqualTo("ONLINE");
        assertThat(gateway.health("node-test").path("readOnly").asBoolean()).isTrue();
        assertThat(gateway.health("node-test").path("hostCpuPercent").asDouble()).isEqualTo(12.5);
        assertThat(gateway.health("node-test").path("hostMemoryBytes").asLong()).isEqualTo(1024);
    }

    @Test
    void rejectsAgentOutsideAllowlistBeforeNetworkCall() {
        AgentGatewayProperties properties = propertiesFor("192.0.2.10");
        NodeService nodes = mock(NodeService.class);
        when(nodes.findById("node-test")).thenReturn(node("127.0.0.1:8090"));

        AgentGatewayService gateway = new AgentGatewayService(nodes, properties, new ObjectMapper());
        assertThatThrownBy(() -> gateway.health("node-test"))
                .isInstanceOf(AgentGatewayException.class)
                .satisfies(error -> assertThat(((AgentGatewayException) error).getStatus()).isEqualTo(403));
    }

    @Test
    void refusesEnabledGatewayWithoutToken() {
        AgentGatewayProperties properties = propertiesFor("127.0.0.1");
        properties.setAuthToken("");
        assertThatThrownBy(() -> new AgentGatewayService(mock(NodeService.class), properties, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_TOKEN");
    }

    private AgentGatewayProperties propertiesFor(String host) {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setEnabled(true);
        properties.setReadOnly(true);
        properties.setAuthToken("test-token");
        properties.setAllowedHosts(Set.of(host));
        return properties;
    }

    private Node node(String address) {
        return new Node("node-test", "Test node", address, NodeStatus.ONLINE, Instant.now());
    }
}
