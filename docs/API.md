# Argus 接口约定

更新：2026-10-09。以下为当前源码契约，V6 人工核对已随只读版本部署；双节点 8 个当前 Docker 实例和日志已核验，生产业务控制与核对开关均关闭。没有生产核对任务样本，不把接口部署等同于真实核对操作验收。最新状态见 [开发进度](开发进度.md)。控制中心使用统一外壳 `{ "code": 0, "message": "ok", "data": ... }`，Agent 原生接口不使用此外壳。

## 控制中心

默认本地入口为 `http://localhost:8080`。

| 方法 | 路径 | 请求或参数 | 当前行为 |
|---|---|---|---|
| GET | `/api/health` | 无 | 服务与数据库健康、只读状态 |
| GET | `/api/nodes`、`/api/nodes/{id}` | 节点 ID | 节点目录、主机快照与同步结果 |
| POST | `/api/nodes` | `name`、`address` | 登记节点；只读模式拒绝 |
| POST | `/api/nodes/{id}/heartbeat` | 可选 `{"status":"ONLINE"}` | 兼容的状态更新接口，不表示 Agent 已实现主动心跳 |
| DELETE | `/api/nodes/{id}` | 节点 ID | 有任务历史时返回 409；无任务历史才事务删除关联实例/日志和节点，只读模式拒绝 |
| GET | `/api/instances`、`/api/instances/{id}` | 中央实例 ID | 查询实例身份、观测状态与可空指标 |
| GET | `/api/instances/{id}/logs?limit=100` | 中央实例 ID，limit 限制为 1–1000 | 定位所属节点与 Agent 局部 ID，按需读取最近日志 |
| GET | `/api/control/capabilities` | 同一个 Bearer 查看/操作令牌 | 返回控制开关、是否可操作、动作与目标允许列表、执行模式和原因 |
| POST | `/api/instances/{id}/actions` | UUID `Idempotency-Key`；`{"action":"RESTART","expectedExecutionMode":"MOCK"}` | START/STOP/RESTART，模式 MOCK/DOCKER；首次与幂等重查均 202 返回 Task，只表示接收 |
| GET | `/api/tasks`、`/api/tasks/{id}` | 任务 ID | 列表返回最近 100 条，详情按 ID 查询持久记录 |
| GET | `/api/tasks/{id}/events` | 任务 ID | 按 sequence 升序查询持久事件 |
| GET | `/api/tasks/{id}/review-capabilities` | 原中央任务 ID | 独立核对权限、新建/续办能力、原任务实际互斥归属 |
| GET | `/api/tasks/{id}/review-evidence` | 原中央任务 ID | 确认前只读证据：原 Agent 记录、绑定、执行器/进程与互斥条件；失败明确分类，不执行容器观察动作 |
| POST | `/api/tasks/{id}/resolutions` | 独立 UUID `Idempotency-Key` 与五字段核对正文 | 新建、同请求查询、BLOCKED 原请求续办均 202；只表示持久接受，不代表解锁 |
| GET | `/api/tasks/{id}/resolution` | 原中央任务 ID | 返回唯一核对记录或 null；含原 requestKey 和完整正文用于恢复 |
| GET | `/api/task-resolutions/{id}`、`/api/task-resolutions/{id}/events` | 核对记录 ID | 独立记录及按 sequence 递增的追加事件 |
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

设置 `ARGUS_API_AUTH_REQUIRED=true` 后，除 `GET /api/health` 外要求 `Authorization: Bearer <token>`，缺失或错误返回 401。查看令牌由 `ARGUS_API_ACCESS_TOKEN` 注入，独立操作令牌由 `ARGUS_API_CONTROL_TOKEN` 注入；操作令牌可读取，查看令牌不能调用写接口，包括旧节点/实例维护接口。仍是同一个 Bearer 头，前端不得伪造操作人。`ARGUS_API_READ_ONLY=true` 时写请求返回 405。这是基础角色与目标授权，不是完整登录/RBAC/审计。

### 可靠任务

完整协议以[可靠任务接口与恢复约定](可靠任务接口与恢复约定.md)为准。能力响应为 `controlEnabled/canControl/allowedActions/targets/reason`，targets 每项含中央 `instanceId`、`executionMode`、`allowedActions`、`blockingTaskId`、`blockedReason`、`canConfirmPending`。被锁目标动作列表为空，阻塞字段显式返回 null 或原任务 ID/`INSTANCE_HAS_UNRESOLVED_TASK`；来源是全部实例锁，不限最近 100 条任务。未知模式或读取失败不开放操作。

canControl/全局动作列表按授权能力计算，不因全部目标都有锁而丢失。已有待确认意图只有在 canConfirmPending=true、全局授权通过、当前目标身份与原模式仍一致时才能显式重送原键；如果此前未入队可能首次创建，不能静默更换请求。新动作仍要求无锁且目标动作获准。

Task 保留 `id/instanceId/action/status/message/createdAt/finishedAt`，新增 `nodeId/agentInstanceId/commandId/executionMode/requestedBy/updatedAt/attempts/resultCode`；本轮增加 `blocksInstance:boolean` 与 `reviewSummary:{id,status,resultCode,appliedAt}|null`。中央状态为 `PENDING/DISPATCHING/DELIVERED/RUNNING/RETRY_WAIT/SUCCEEDED/FAILED/UNKNOWN`，执行来源为 `MOCK/DOCKER/LEGACY_MOCK/UNKNOWN`。事件为 `{id,taskId,sequence,fromStatus,toStatus,actor,reason,occurredAt}`，id 为 UUID 字符串，sequence 为递增正整数。

相同幂等键及相同实例/动作/预期模式返回原 Task；内容改变返回 409。同实例未决任务（含 UNKNOWN）保持互斥。网络超时后浏览器保留原键，只能显式重送同一请求核对，不能自动新建。UNKNOWN 不自动重放；本轮独立人工核对见下一节。旧 LEGACY_MOCK 不进入投递队列，任务成功不得修改实例观测状态。

默认 `ARGUS_TASK_CONTROL_ENABLED=false`、`ARGUS_TASK_ALLOW_DOCKER=false`，中央与 Agent 目标允许列表为空。启用需要认证、独立读取/控制令牌、网关主机白名单与 `ARGUS_AGENT_GATEWAY_ALLOW_CONTROL` 等配置；详情见本地运行与模块配置。网关原有读取接口仍保持只读，控制使用独立的受限命令通道。

常见网关错误：禁用或非只读配置返回 503，白名单不允许返回 403，远端失败/日志格式错误返回 502，不存在的中央实例返回 404。以 HTTP 状态和统一外壳共同判断，不能只看 `data` 是否为空。

### 独立 UNKNOWN 人工核对（V6，生产开关关闭）

完整形状和处理顺序以[UNKNOWN 任务人工核对契约](UNKNOWN任务人工核对设计.md)为准。能力返回 `reviewEnabled/canReview/canRecheck/allowedDecisions/reason/blocksInstance/resolutionId`。核对使用操作令牌、独立开关及允许名单；不要求容器动作开关打开，但全局只读优先。查看者可 GET 记录，不能 POST 或续办，actor 由服务端确定。

POST 固定正文：

```json
{
  "decision": "ACKNOWLEDGE_UNCERTAINTY",
  "reason": "<核对原因>",
  "evidence": "<已检查的状态、日志和人工依据>",
  "acknowledgeNoReplay": true,
  "acknowledgeResidualRisk": true
}
```

拒绝未知字段；reason/evidence 非空且最多 500/4000 个 Unicode 码点，CRLF/CR 转 LF，首尾按 ECMAScript trim 集合标准化，拒绝 NUL/未配对字符，之后正文不可改。幂等键必须标准小写连字符 UUID。同键不同任务或正文为 409 `REVIEW_IDEMPOTENCY_CONFLICT`；同任务新键为 409 `TASK_ALREADY_HAS_RESOLUTION`，应 GET 并继续已有记录。

Resolution 返回身份、原 requestKey/完整正文、独立 `status/resultCode/attempts/requestedBy/createdAt/updatedAt/appliedAt` 与 `agentEvidence`。状态为 PENDING/PROCESSING/RETRY_WAIT/BLOCKED/APPLIED；BLOCKED 可 POST 原键原正文续办，不生成新的核对 ID 或命令。202 仅接受，APPLIED 表示有效回执和原锁条件删除已同事务提交。原 Task UNKNOWN、原结果和事件均保留，不能把核对当成成功、失败或新命令。Agent 原终态及本次观察单列为证据，实例观测仍由采集更新。

确认前证据含中央任务/实例/节点/局部 ID/原 commandId、checkedAt、bindingMatches、classification/reason/canAcknowledge、agentTask、workerActive/knownProcessesActive、lockDisposition/processAssessment。分类包括 READY、EXECUTOR_ACTIVE、RECORD_MISSING、BINDING_CHANGED、UNAVAILABLE、INELIGIBLE、INVALID_EVIDENCE、REVIEW_DISABLED；未知值为 null。原 Agent 13 字段中的 taskId 等于 commandId，Agent 自报 nodeId 与中央 nodeId 是不同命名空间，由后端验证原绑定。前端首次提交必须先成功读取同任务证据且 canAcknowledge=true；该值不是授权票据，处理时再次验证。

## 只读 Agent 网关

默认 `ARGUS_AGENT_GATEWAY_ENABLED=false`。启用时须有匹配令牌和 `ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS`，原 GET 网关保持只读；可靠任务使用独立受限命令通道，不提供任意 URL、SSH 或 shell。默认每轮采集结束后等待 60 秒继续，是中央拉取，不是 Agent 主动上报。

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
| GET | `/api/agent/health` | 采集协议 1.1、taskProtocolVersion=1.0、storeId、executionMode、controlEnabled；本轮另加 reviewProtocolVersion=1.0、reviewEnabled |
| POST | `/api/agent/register`、`/api/agent/heartbeat` | 应答请求，不主动连接控制中心 |
| GET | `/api/agent/instances` | 原生实例 JSON 数组，包含 `instanceId` 和采集元数据 |
| GET | `/api/agent/instances/{instanceId}/logs?limit=100` | 原生字符串数组，读取已允许实例的最近日志 |
| POST | `/api/agent/tasks` | 七字段固定命令；首次持久接收 202，同命令同请求 200，冲突 409；控制默认关闭 |
| GET | `/api/agent/tasks/{commandId}` | 使用控制令牌查询持久记录，taskId 等于 commandId，不存在明确 404 |
| GET | `/api/agent/tasks/{commandId}/review-evidence` | 控制令牌读取原任务与执行器/互斥证据，不执行新动作 |
| POST | `/api/agent/tasks/{commandId}/resolutions` | 独立核对开关/名单；首次持久 CLOSED 回执 201，同核对同完整请求 200，冲突或执行未静止 409 |
| GET | `/api/agent/tasks/{commandId}/resolutions/{resolutionId}` | 控制令牌查询持久闭合回执，开关关闭也可读取，不存在 404 |
| GET | `/metrics` | Prometheus 文本，只读取主机快照，不调用 Docker |

Agent 主机来源为 HOST；容器来源为 DOCKER 或 MOCK。可空主机字段为 `hostCpuPercent/hostMemoryBytes/hostMemoryTotalBytes/hostMemoryPercent`；实例使用 `cpuPercent/memoryBytes/players`，其中玩家数目前为 null。`checkedAt` 兼容保留，与 `sampledAt` 取同一次采样时间。

发现成功为空返回 `[]`；发现失败、输出超限或格式异常返回 503，固定 `error=collection_unavailable` 和预定义 `reason`。仅 stats 失败时保留发现到的生命周期，资源为 null。`/metrics` 省略不可用资源数值并提供可用性指标，不用资源值 0 冒充失败。

命令正文必须恰含七个字符串：`commandId/instanceId/action/expectedNodeId/expectedStoreId/expectedExecutionMode/expiresAt`；action 为小写 start/stop/restart，期限为 ISO Instant，UUID 使用标准小写形式。缺项、重复键、未知字段、嵌套或尾部内容均拒绝。详见[冻结约定](可靠任务接口与恢复约定.md)。任务响应包含身份、模式、状态、创建/开始/结束时间、结果码和 observedStatus，中央不能只凭 HTTP 200 判断成功。

读取令牌不能提交或查询 Agent 控制任务；独立控制令牌可读取。关闭控制仍允许查询已有结果，创建请求在认证通过后返回 `403 control_disabled`；任务队列满为 429，Inbox 容量或落盘异常为 503。HTTP 等待队列满使用背压，不承诺 429。Inbox 原子保存任务；重启 PENDING 可恢复，RUNNING 变 UNKNOWN，不自动重放。storeId 随普通重启保持稳定；不具备完整契约不得控制。

完整配置、限制与复验见 [Agent 说明](../agent/README.md)、[可靠性与部署说明](Agent可靠性与部署说明.md)、[验收清单](验收清单.md)。所有真实连接和令牌通过运行环境注入，不写入示例。
