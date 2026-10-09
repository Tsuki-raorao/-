package com.argus.controlcenter.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** 独立人工核对记录；APPLIED不覆盖原任务UNKNOWN执行证据。 */
public record TaskResolution(String id,String requestKey,String taskId,String instanceId,String nodeId,
        String agentInstanceId,String commandId,String decision,String status,String reason,String evidence,
        boolean acknowledgeNoReplay,boolean acknowledgeResidualRisk,String requestedBy,
        Instant createdAt,Instant updatedAt,Instant appliedAt,String resultCode,int attempts,JsonNode agentEvidence,
        String projectId,String actorUserId,String authMode,String requestHash,
        String activeAuthorizationId,String authorizedByUserId) {
    public record Summary(String id,String status,String resultCode,Instant appliedAt) { }
    public record Event(String id,String resolutionId,long sequence,String fromStatus,String toStatus,
                        String actor,String reason,Instant occurredAt) { }
}
