package com.argus.agent.model;

import com.argus.agent.Json;

/** 确认前只读取已保存任务和当前执行登记，不调用 Docker，不声称历史动作成功。 */
public record ReviewEvidence(TaskView task, String checkedAt, boolean reviewEnabled, boolean instanceAllowed,
        boolean workerActive, boolean knownProcessesActive, String lockDisposition, String processAssessment) {
    public String json() { return Json.object("commandId", task.taskId(), "nodeId", task.nodeId(), "storeId", task.storeId(),
            "executionMode", task.executionMode(), "checkedAt", checkedAt, "task", Json.raw(task.json()),
            "reviewEnabled", reviewEnabled, "instanceAllowed", instanceAllowed, "workerActive", workerActive,
            "knownProcessesActive", knownProcessesActive, "lockDisposition", lockDisposition, "processAssessment", processAssessment); }
}
