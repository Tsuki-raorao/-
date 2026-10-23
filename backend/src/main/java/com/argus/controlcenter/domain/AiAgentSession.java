package com.argus.controlcenter.domain;

import java.time.Instant;

public record AiAgentSession(String id, String projectId, String actorUserId, String title, String status, Instant createdAt, Instant updatedAt, long versionNo) { }
