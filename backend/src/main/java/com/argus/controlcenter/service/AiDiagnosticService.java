package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiProperties;
import com.argus.controlcenter.vo.AiReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;

@Service
public class AiDiagnosticService {
    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final NodeService nodes;
    private final InstanceService instances;
    private final LogService logs;
    private final TaskService tasks;
    private final JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AiDiagnosticService(AiProperties properties, ObjectMapper mapper, NodeService nodes, InstanceService instances, LogService logs, TaskService tasks, JdbcTemplate jdbc) { this.properties = properties; this.mapper = mapper; this.nodes = nodes; this.instances = instances; this.logs = logs; this.tasks = tasks; this.jdbc = jdbc; }
    public AiReply ask(String question) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank())
            throw new IllegalStateException("AI_READONLY_PROVIDER_NOT_CONFIGURED");
        if (question == null || question.isBlank() || question.length() > 4000) throw new IllegalArgumentException("question is required");
        try {
            var payload = mapper.createObjectNode(); payload.put("model", properties.getModel()); payload.put("max_tokens", properties.getMaxTokens());
            var messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", "你是未序娘，只能做只读运维分析。没有实时证据时必须明确说明未知，不得声称执行了任何操作。不要输出shell命令作为已执行结果。");
            messages.addObject().put("role", "user").put("content", question + "\n\n当前授权快照证据（仅供只读分析，时间以字段为准）：\n" + evidence());
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .timeout(properties.getTimeout()).header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            JsonNode root = mapper.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) throw new IllegalStateException("AI_EMPTY_RESPONSE");
            var references = List.of(
                    new AiReply.EvidenceRef("nodes", "/api/nodes", "snapshot"),
                    new AiReply.EvidenceRef("instances", "/api/instances", "snapshot"),
                    new AiReply.EvidenceRef("logs", "database", "count=" + logs.count()),
                    new AiReply.EvidenceRef("tasks", "database", "count=" + tasks.findAll().size()));
            String evidenceJson = mapper.writeValueAsString(references);
            jdbc.update("INSERT INTO ai_diagnoses(id,question,model,answer,evidence_json,created_at) VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), question, properties.getModel(), answer, evidenceJson, Instant.now());
            return new AiReply(properties.getModel(), answer, references, true);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("AI_INTERRUPTED", e); }
        catch (Exception e) { throw new IllegalStateException("AI_PROVIDER_REQUEST_FAILED", e); }
    }
    private String evidence() {
        var out = new StringBuilder("nodes=");
        nodes.findAll().forEach(n -> out.append("[id=").append(n.getId()).append(",name=").append(n.getName()).append(",status=").append(n.getStatus()).append(",sync=").append(n.getSyncStatus()).append(",sampledAt=").append(n.getSampledAt()).append("]"));
        out.append(" instances=");
        instances.findAll().forEach(i -> out.append("[id=").append(i.getId()).append(",name=").append(i.getName()).append(",nodeId=").append(i.getNodeId()).append(",status=").append(i.getStatus()).append(",source=").append(i.getDataSource()).append(",sampledAt=").append(i.getSampledAt()).append("]"));
        out.append(" logsCount=").append(logs.count()).append(" tasksCount=").append(tasks.findAll().size());
        return out.toString();
    }
}
