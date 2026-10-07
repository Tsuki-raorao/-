package com.argus.agent.model;

/** Agent 注册和健康接口返回的节点基本信息。 */
public record NodeInfo(String nodeId, String name, String status, String agentVersion,
                       String host, int port, long startedAtEpochMs) { }
