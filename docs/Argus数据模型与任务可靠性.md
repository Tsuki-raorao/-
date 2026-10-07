# Argus 数据模型与任务可靠性设计

更新时间：2026-09-29  
状态：设计基线，尚未进入代码改造

## 1. 领域关系

```text
Tenant / Project
      ↓
Node ── AgentSession
  ↓
Instance ── desiredState / observedState
  ↓
Task ── TaskAttempt ── TaskEvent
  ↓
OutboxEvent ── InboxCommand
```

`desiredState` 表示用户或策略希望实例达到的状态，`observedState` 表示 Agent 最近上报的真实状态。两者不一致时由 Reconciler 创建幂等任务，不能把一次 HTTP 请求成功当成实例已经完成操作。

## 2. 核心表

| 表 | 关键字段 | 约束和用途 |
|---|---|---|
| `tenants` | `id`, `name`, `status` | 多租户边界 |
| `projects` | `id`, `tenant_id`, `name` | 资源和权限范围 |
| `users` / `roles` / `project_members` | 用户、角色、项目关系 | RBAC 与项目级授权 |
| `nodes` | `id`, `agent_id`, `version`, `capabilities`, `last_seen_at`, `status`, `version_no` | 节点目录和最近快照，不保存每次心跳 |
| `agent_sessions` | `node_id`, `session_generation`, `connected_at`, `closed_at` | 连接代次和断线排障 |
| `instances` | `id`, `node_id`, `type`, `desired_state`, `observed_state`, `desired_generation`, `config_revision`, `health`, `version_no` | `(node_id, name)` 唯一；期望与观测分开 |
| `tasks` | `id`, `instance_id`, `kind`, `payload`, `status`, `attempt`, `next_run_at`, `deadline`, `idempotency_key`, `fence_token`, `version_no` | 任务当前状态和调度字段 |
| `task_attempts` | `task_id`, `attempt`, `command_id`, `lease_until`, `ack_at`, `started_at`, `finished_at`, `status`, `result` | 每次投递尝试不可变，`command_id` 唯一 |
| `task_events` | `task_id`, `seq`, `from_status`, `to_status`, `actor`, `reason`, `payload`, `occurred_at` | 状态时间线，`(task_id, seq)` 唯一 |
| `outbox_events` | `id`, `event_type`, `aggregate_id`, `partition_key`, `payload`, `published_at`, `attempts`, `next_retry_at` | 和业务事务同库提交，再可靠发布 |
| `inbox_commands` | `agent_id`, `command_id`, `payload_hash`, `received_at`, `completed_at`, `result` | Agent 端和服务端幂等去重 |
| `audit_logs` | `actor`, `project_id`, `action`, `resource`, `before`, `after`, `occurred_at` | 管理操作和安全审计 |
| `log_chunks` | `instance_id`, `seq`, `time_start`, `time_end`, `storage_uri`, `checksum` | 只保存日志索引，不保存逐行正文 |
| `incidents` / `alerts` | 告警规则、事件、恢复状态 | 连接监控和后续 AI 诊断 |

任务和事件按时间建立索引；高量日志索引按实例和时间分区或归档。分页列表禁止无条件扫描全表。

## 3. 任务状态机

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> DISPATCHING
    DISPATCHING --> DELIVERED
    DISPATCHING --> RETRY_WAIT
    DELIVERED --> RUNNING
    DELIVERED --> RETRY_WAIT
    RUNNING --> SUCCEEDED
    RUNNING --> FAILED
    RUNNING --> UNKNOWN
    RUNNING --> EXPIRED
    RETRY_WAIT --> DISPATCHING
    FAILED --> RETRY_WAIT
    UNKNOWN --> RECONCILING
    RECONCILING --> SUCCEEDED
    RECONCILING --> RETRY_WAIT
    RECONCILING --> DEAD_LETTER
    RETRY_WAIT --> DEAD_LETTER
    PENDING --> CANCELED
    DISPATCHING --> CANCELED
    [*] <-- SUCCEEDED
    [*] <-- CANCELED
    [*] <-- DEAD_LETTER
    [*] <-- EXPIRED
```

状态迁移只能通过白名单和数据库 CAS/version 检查。每次迁移追加 `task_events`，而不是覆盖历史。

- `UNKNOWN` 表示执行结果不确定，必须先查询 Agent 和实例实际状态，不能直接重试重启。
- `DEAD_LETTER` 表示达到重试上限或需要人工处理。
- `fence_token` 单调递增；旧尝试的迟到回报不能覆盖新尝试。
- `safe_to_retry` 由动作类型声明。查询、启动等动作可以幂等；安装、迁移和重启需要额外核对。

## 4. Outbox、MQ 和 Inbox

1. API 校验权限和实例版本。
2. 一个 MySQL 事务写入 `tasks` 和 `outbox_events`。
3. Outbox Relay 批量领取未发布事件，发布 RabbitMQ，发布失败指数退避。
4. MQ 消费者按节点路由任务，确认消息前不得丢弃事件。
5. Agent 收到命令后以 `command_id` 写入本地 Inbox 或磁盘 spool；重复命令返回已有结果。
6. Agent 先 ACK 已接收，再异步执行并回报 `RUNNING` 和结果。
7. 控制中心消费者使用 `command_id`、`attempt` 和 `fence_token` 幂等更新。
8. 超时租约由 Reconciler 回收；达到上限进入死信队列和人工处理。

系统采用至少一次投递和幂等执行。Redis 锁只做快速互斥，关键正确性由数据库租约、CAS 和 fencing token 最终裁决。

## 5. 失败场景

| 失败 | 系统行为 |
|---|---|
| 控制中心在任务提交后崩溃 | MySQL 中的 `PENDING + outbox` 重启后继续发布 |
| MQ 发布重复 | 消费者按 event id / command id 去重 |
| Agent 收到命令后断线 | 任务保留，重连后按 command id 同步；结果不明进入 `UNKNOWN` |
| Agent 执行中断 | 查询实际实例状态，再决定成功、补偿或人工处理 |
| Redis 暂时不可用 | 停止危险操作或退回数据库租约；不使用陈旧在线状态执行命令 |
| MySQL 重启 | 队列事件保留，任务根据 lease 和 outbox 状态恢复 |
| 日志突发 | Agent 本地有界 spool，接入层限速、压缩或采样，不能无限占内存 |
| 多控制中心同时调度 | CAS、租约和 fencing token 保证只有一个有效执行者 |

## 6. 心跳、指标和日志保留

- 心跳使用 `node_id + session_generation + seq + timestamp`，校验签名、时间窗口和单调序号。
- Redis 在线租约 TTL 设置为三个心跳周期；离线事件异步生成并写 MySQL。
- 指标进入 Prometheus，设置有限标签集合；玩家名、任务 ID、日志内容不能作为常规标签。
- 日志正文进入 Loki 或对象存储，热数据保留 7–30 天，任务摘要和审计长期保留。
- Agent 断线时保存有上限的本地 spool，重连后按序号补传，达到上限时按策略丢弃低优先级日志并记录丢失量。
