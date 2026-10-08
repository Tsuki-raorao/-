package com.argus.controlcenter.domain;

import java.time.Instant;

/** 仅供队列使用的持久绑定，不作为 HTTP DTO 返回。 */
public record TaskCommand(Task task, String idempotencyKey, String targetAddress,
                          String agentNodeId, String storeId, Instant expiresAt, long version, boolean agentAccepted) { }
