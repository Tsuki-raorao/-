package com.argus.agent.model;

import com.argus.agent.Json;
import java.time.Instant;
import java.util.*;

/** CLOSED 仅结案原互斥；原 TaskView 完整保留，当前观测另存，不能据它伪造历史成功。 */
public record ResolutionReceipt(ResolutionRequest request, TaskView task, String createdAt, String classification,
        String processAssessment, String observationStatus, String observedInstanceStatus, String observedAt) {
    public ResolutionReceipt {
        require(request.commandId().equals(task.taskId()) && request.instanceId().equals(task.instanceId()) && request.action().equals(task.action())
                && request.expectedNodeId().equals(task.nodeId()) && request.expectedStoreId().equals(task.storeId()) && request.expectedExecutionMode().equals(task.executionMode()));
        require(Set.of("UNKNOWN", "SUCCEEDED", "FAILED").contains(task.status()));
        require(classification.equals(task.status().equals("UNKNOWN") ? "ACKNOWLEDGED_UNKNOWN" : "CONFIRMED_TERMINAL"));
        require(Set.of("CURRENT_PROCESS_CLEARED", "PRIOR_PROCESS_UNVERIFIED").contains(processAssessment));
        require(Set.of("AVAILABLE", "UNAVAILABLE").contains(observationStatus));
        require(observationStatus.equals("UNAVAILABLE") ? observedInstanceStatus == null
                : Set.of("RUNNING", "STOPPED", "UNKNOWN").contains(observedInstanceStatus == null ? "" : observedInstanceStatus));
        Instant accepted = instant(createdAt), observed = instant(observedAt), originalCreated = instant(task.createdAt()), finished = instant(task.finishedAt());
        require(!observed.isAfter(accepted) && !finished.isBefore(originalCreated) && !finished.isAfter(observed));
        if (task.startedAt() != null) { Instant started = instant(task.startedAt()); require(!started.isBefore(originalCreated) && !started.isAfter(finished)); }
        require(task.message() != null && task.resultCode() != null && task.resultCode().matches("[A-Za-z0-9_.-]{1,64}"));
        if (task.status().equals("SUCCEEDED")) require(task.startedAt() != null
                && (task.action().equals("stop") ? "STOPPED" : "RUNNING").equals(task.observedStatus()));
    }
    public String json() { return Json.object("resolutionId", request.resolutionId(), "commandId", request.commandId(), "instanceId", request.instanceId(),
            "action", request.action(), "nodeId", request.expectedNodeId(), "storeId", request.expectedStoreId(), "executionMode", request.expectedExecutionMode(),
            "decision", request.decision(), "status", "CLOSED", "requestHash", request.requestHash(), "createdAt", createdAt,
            "classification", classification, "task", Json.raw(task.json()), "processAssessment", processAssessment,
            "observationStatus", observationStatus, "observedInstanceStatus", observedInstanceStatus, "observedAt", observedAt); }
    public String diskJson() { return Json.object("commandId", request.commandId(), "request", request.json(), "requestHash", request.requestHash(),
            "task", task.json(), "createdAt", createdAt, "classification", classification, "processAssessment", processAssessment,
            "observationStatus", observationStatus, "observedInstanceStatus", observedInstanceStatus, "observedAt", observedAt); }
    public static ResolutionReceipt fromDiskJson(String json) {
        Map<String, String> v = Json.flatObject(json);
        require(v.keySet().equals(Set.of("commandId", "request", "requestHash", "task", "createdAt", "classification", "processAssessment",
                "observationStatus", "observedInstanceStatus", "observedAt")));
        ResolutionRequest request = ResolutionRequest.parse(v.get("commandId"), v.get("request"));
        require(request.requestHash().equals(v.get("requestHash")));
        return new ResolutionReceipt(request, TaskView.parse(v.get("task")), v.get("createdAt"), v.get("classification"), v.get("processAssessment"),
                v.get("observationStatus"), v.get("observedInstanceStatus"), v.get("observedAt"));
    }
    private static Instant instant(String value) { try { return Instant.parse(value); } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid resolution receipt"); } }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("invalid resolution receipt"); }
}
