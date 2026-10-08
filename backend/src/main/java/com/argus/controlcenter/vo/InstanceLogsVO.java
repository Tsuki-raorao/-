package com.argus.controlcenter.vo;

import java.time.Instant;
import java.util.List;

/** 日志采集结果明确标明中央身份和来源节点，不与数据库摘要混用。 */
public record InstanceLogsVO(String instanceId, String nodeId, String agentInstanceId,
                             Instant collectedAt, List<String> lines) { }
