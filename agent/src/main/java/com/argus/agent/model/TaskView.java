package com.argus.agent.model;
import com.argus.agent.Json;
import java.util.Map;
import java.util.Set;

/** Agent 持久化任务的对外只读视图；UNKNOWN 不能自动重放。 */
public record TaskView(String taskId, String instanceId, String action, String status,
                       String message, String createdAt, String startedAt, String finishedAt,
                       String executionMode, String nodeId, String storeId, String resultCode, String observedStatus) {
    public String json() { return Json.object("taskId", taskId, "instanceId", instanceId, "action", action, "status", status,
            "message", message, "createdAt", createdAt, "startedAt", startedAt, "finishedAt", finishedAt,
            "executionMode", executionMode, "nodeId", nodeId, "storeId", storeId, "resultCode", resultCode, "observedStatus", observedStatus); }
    public static TaskView parse(String json) {
        Map<String, String> v = Json.flatObject(json);
        if (!v.keySet().equals(Set.of("taskId", "instanceId", "action", "status", "message", "createdAt", "startedAt", "finishedAt",
                "executionMode", "nodeId", "storeId", "resultCode", "observedStatus"))) throw new IllegalArgumentException("invalid task view");
        return new TaskView(v.get("taskId"), v.get("instanceId"), v.get("action"), v.get("status"), v.get("message"), v.get("createdAt"),
                v.get("startedAt"), v.get("finishedAt"), v.get("executionMode"), v.get("nodeId"), v.get("storeId"), v.get("resultCode"), v.get("observedStatus"));
    }
}
