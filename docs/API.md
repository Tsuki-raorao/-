# Argus 接口约定

更新：2026-10-08。以下为当前源码契约；本轮尚未部署。控制中心使用统一外壳 `{ "code": 0, "message": "ok", "data": ... }`，Agent 原生接口不使用此外壳。

## 控制中心

默认本地入口为 `http://localhost:8080`。

| 方法 | 路径 | 请求或参数 | 当前行为 |
|---|---|---|---|
| GET | `/api/health` | 无 | 服务与数据库健康、只读状态 |
| GET | `/api/nodes`、`/api/nodes/{id}` | 节点 ID | 节点目录、主机快照与同步结果 |
| POST | `/api/nodes` | `name`、`address` | 登记节点；只读模式拒绝 |
| POST | `/api/nodes/{id}/heartbeat` | 可选 `{"status":"ONLINE"}` | 兼容的状态更新接口，不表示 Agent 已实现主动心跳 |
| DELETE | `/api/nodes/{id}` | 节点 ID | 显式事务删除节点及关联实例、任务、日志；只读模式拒绝 |
| GET | `/api/instances`、`/api/instances/{id}` | 中央实例 ID | 查询实例身份、观测状态与可空指标 |
| GET | `/api/instances/{id}/logs?limit=100` | 中央实例 ID，limit 限制为 1–1000 | 定位所属节点与 Agent 局部 ID，按需读取最近日志 |
| POST | `/api/instances/{id}/actions` | `{"action":"RESTART"}`（也支持 START/STOP） | 当前只记录并完成中央数据库模拟流程，不下发 Docker |
| GET | `/api/tasks`、`/api/tasks/{id}` | 任务 ID | 查询中央任务记录 |
| GET | `/api/logs` | `instanceId` 可选，`limit` 默认 100 | 保留的数据库日志摘要，与 Agent 最近日志分开 |

### 实例身份与最近日志

`Instance.id` 是中央不透明 ID；旧记录保留原值，新发现使用 UUID。`nodeId + agentInstanceId` 代表节点内身份，Agent 局部 ID 区分大小写。详情、任务、数据库日志及网页选择器均使用中央 ID，不能用容器名替代。

新日志接口返回示例（全部为占位数据）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "instanceId": "<central-instance-id>",
    "nodeId": "<node-id>",
    "agentInstanceId": "<agent-local-id>",
    "collectedAt": "2026-10-08T12:00:00Z",
    "lines": ["<log line>"]
  }
}
```

`collectedAt` 是此次读取完成时间。合法空数组表示成功但没有日志；网关禁用、不可达或格式不正确保持错误，不转为空成功。前端每次最多请求 100 行；这是按需/轮询读取，没有 WebSocket、持续流或历史全文检索。

### 采集字段与时间语义

| 对象 | 字段 | 含义 |
|---|---|---|
| Node | `lastCheckedAt` | 最近尝试时间，失败也更新 |
| Node | `lastSuccessfulSyncAt` | 完整清单校验及事务持久化成功的批次时间 |
| Node | `syncStatus` | `UNKNOWN` / `OK` / `PARTIAL` / `FAILED`，与资源可用性分别判断 |
| Node | `syncErrorCode` | 固定错误码或 null，不保存远端错误正文 |
| Instance | `lastSeenAt` | 实例在完整清单中被看到的中央时间，同批等于节点成功时间 |
| 二者 | `dataSource` | HOST 主机、DOCKER 容器、MOCK 模拟、LEGACY 未验证旧数据 |
| 二者 | `sampledAt` | Agent 本轮采样时间，不等于所有指标采集成功 |
| 二者 | `metricsStatus` | `AVAILABLE` / `PARTIAL` / `UNAVAILABLE` / `UNKNOWN` |

节点 `cpuPercent/memoryBytes/memoryTotalBytes` 和实例 `cpuPercent/memoryBytes/playerCount` 均允许 null。真实 0 保留；未支持玩家数不补成 0。`lastHeartbeat` 是兼容字段，不替代最近尝试与完整成功时间。

健康失败为 `FAILED/OFFLINE`；健康成功但清单失败为 `PARTIAL`。完整清单校验后才入库，坏行、重复局部 ID、非数组或写库失败不留下半份成功快照。空清单是合法结果，不删除或刷新旧实例；比节点新成功批次更早的 `lastSeenAt` 表示本轮未发现。HTTP/解析失败保留此前成功采样，有效响应中本次不可用指标为 null。V4 采集时间使用 `TIMESTAMP(6)`，避免不同批次秒精度混淆。

同步错误码包括 `INVALID_HEALTH`、`HEALTH_REQUEST_FAILED`、`INVALID_INSTANCES`、`INSTANCES_REQUEST_FAILED`、`PERSISTENCE_FAILED`。前端另以 180 秒判断过期；旧协议或无时间为未验证，MOCK 明确标为模拟。

### 认证与只读

设置 `ARGUS_API_AUTH_REQUIRED=true` 并通过私有环境注入 `ARGUS_API_ACCESS_TOKEN` 后，除 `GET /api/health` 外要求 `Authorization: Bearer <token>`，缺失或错误返回 401。`ARGUS_API_READ_ONLY=true` 时写请求返回 405。它是基础保护，不是完整登录/RBAC/审计。

常见网关错误：禁用或非只读配置返回 503，白名单不允许返回 403，远端失败/日志格式错误返回 502，不存在的中央实例返回 404。以 HTTP 状态和统一外壳共同判断，不能只看 `data` 是否为空。

## 只读 Agent 网关

默认 `ARGUS_AGENT_GATEWAY_ENABLED=false`。启用时须有匹配令牌和 `ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS`，保持只读；当前不提供通用 URL 代理或远程命令。默认每轮采集结束后等待 60 秒继续，是中央拉取，不是 Agent 主动上报。

| 方法 | 路径 | ID 语义 |
|---|---|---|
| GET | `/api/agent-gateway/nodes/{nodeId}/health` | 中央节点 ID |
| GET | `/api/agent-gateway/nodes/{nodeId}/instances` | 中央节点 ID，返回原生实例数组 |
| GET | `/api/agent-gateway/nodes/{nodeId}/instances/{instanceId}/logs?limit=100` | 此处 instanceId 是该节点的 Agent 局部 ID |

网页优先使用 `/api/instances/{centralId}/logs`，由服务端解析目标，避免浏览器自行拼接节点与局部 ID。

## Agent 1.1

默认本地入口为 `http://localhost:8090`。配置令牌后所有接口要求 Bearer 头，不接受 URL 令牌；真实模式无令牌拒绝启动。本地 mock 可不配令牌。

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/agent/health` | 节点身份、`protocolVersion="1.1"`、主机采样与能力声明 |
| POST | `/api/agent/register`、`/api/agent/heartbeat` | 应答请求，不主动连接控制中心 |
| GET | `/api/agent/instances` | 原生实例 JSON 数组，包含 `instanceId` 和采集元数据 |
| GET | `/api/agent/instances/{instanceId}/logs?limit=100` | 原生字符串数组，读取已允许实例的最近日志 |
| POST | `/api/agent/tasks` | `{"instanceId":"<agent-local-id>","action":"restart"}`；控制默认关闭 |
| GET | `/api/agent/tasks/{taskId}` | 查询内存任务记录 |
| GET | `/metrics` | Prometheus 文本，只读取主机快照，不调用 Docker |

Agent 主机来源为 HOST；容器来源为 DOCKER 或 MOCK。可空主机字段为 `hostCpuPercent/hostMemoryBytes/hostMemoryTotalBytes/hostMemoryPercent`；实例使用 `cpuPercent/memoryBytes/players`，其中玩家数目前为 null。`checkedAt` 兼容保留，与 `sampledAt` 取同一次采样时间。

发现成功为空返回 `[]`；发现失败、输出超限或格式异常返回 503，固定 `error=collection_unavailable` 和预定义 `reason`。仅 stats 失败时保留发现到的生命周期，资源为 null。`/metrics` 省略不可用资源数值并提供可用性指标，不用资源值 0 冒充失败。

关闭控制时任务创建返回 `403 control_disabled`；任务队列满返回 `429 agent_busy`。HTTP 等待队列满使用背压，不承诺返回 429。任务内存保存，尚无持久化恢复。

完整配置、限制与复验见 [Agent 说明](../agent/README.md)、[可靠性与部署说明](Agent可靠性与部署说明.md)、[验收清单](验收清单.md)。所有真实连接和令牌通过运行环境注入，不写入示例。
