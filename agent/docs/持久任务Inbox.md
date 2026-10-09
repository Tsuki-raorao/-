# Agent 持久任务 Inbox

本轮使用 Java 17 标准库实现，不引入数据库驱动或第三方依赖。范围是固定命令的重复传输去重与保守的进程崩溃恢复；不承诺 Docker 副作用 exactly-once、磁盘回滚防护或跨平台断电一致性。生产控制默认关闭。

2026-10-09，两台既有节点已部署本版本，动作控制与人工核对均保持关闭。发布保留原 node/store 身份、读取令牌和整个 Inbox；两台当时均为零任务、零回执，发布前后文件摘要一致。没有还原备份或重建身份，也没有重启业务容器。发布验收范围见 [Agent 已部署状态](../README.md#已部署状态2026-10-09)；真实任务崩溃恢复与核对结案的保证仍以各自测试范围为限。

## 接收与执行协议

采集 health 保留 `protocolVersion="1.1"`，新增 `taskProtocolVersion="1.0"`、`storeId`、`executionMode` 与 `controlEnabled`。无 Inbox 的只读启动返回 `storeId=null`，不会因此创建状态目录。

POST `/api/agent/tasks` 只接受七个字符串：`commandId/instanceId/action/expectedNodeId/expectedStoreId/expectedExecutionMode/expiresAt`。UUID 使用标准小写 36 字符形式，动作仅 `start/stop/restart`，期限使用 ISO Instant。正文必须是完整 JSON，缺项、重复键、未知键、非字符串、嵌套和尾部内容都拒绝。

- 新命令在 PENDING 持久化后返回 202；相同 ID、相同完整请求返回 200 原任务；改变任何字段返回 409 `command_conflict`。
- 过期、节点/Inbox/执行模式不符、目标不允许、实例有未决任务时拒绝。每个实例只允许一个 PENDING/RUNNING/UNKNOWN。
- 接收时保存解析后的目标容器。执行前再次检查目标、权限、模式和期限；配置变化不会把原模拟命令变成真实命令。
- RUNNING 持久化成功后才调用执行器。退出成功并核验目标状态才返回 SUCCEEDED；stop 要求 STOPPED，start/restart 要求 RUNNING。这是容器生命周期核验，不代表游戏或应用业务就绪。
- 调用后超时、中断、命令异常或状态无法确认时为 UNKNOWN。UNKNOWN 不自动重放、不自动释放实例互斥；独立 [人工核对回执](人工核对回执.md) 可在明确接受不确定性后释放原锁，原任务状态与证据保持不变。
- GET `/api/agent/tasks/{commandId}` 用控制令牌读取持久结果；未找到返回 404。关闭控制停止新接受/执行，但允许查询原结果。

任务响应为 `taskId/instanceId/action/status/message/createdAt/startedAt/finishedAt/executionMode/nodeId/storeId/resultCode/observedStatus`。`taskId=commandId`，未开始/未结束时间为 null。结果码为固定值，例如 ACCEPTED、EXECUTING、STATE_CONFIRMED、EXPIRED、TARGET_CHANGED、EXECUTION_CONTEXT_CHANGED、EXECUTION_UNCERTAIN、AGENT_RESTARTED；不暴露命令 stderr 或本地路径。

## 配置与授权

| 环境变量 | 文件键 | 默认值 |
|---|---|---|
| `ARGUS_AGENT_CONTROL_ENABLED` | `control.enabled` | false |
| `ARGUS_AGENT_CONTROL_TOKEN` | `control.token` | 空 |
| `ARGUS_AGENT_CONTROL_INSTANCES` | `control.instances` | 空，逗号分隔局部实例 ID |
| `ARGUS_AGENT_REVIEW_ENABLED` | `review.enabled` | false，独立人工核对开关 |
| `ARGUS_AGENT_REVIEW_INSTANCES` | `review.instances` | 空，独立核对允许列表 |
| `ARGUS_AGENT_TASK_DIR` | `task.directory` | agent/data/task-inbox |
| `ARGUS_AGENT_TASK_MAX_RECORDS` | `task.max-records` | 10000，范围 1–100000 |

开放控制要求读取令牌与控制令牌非空、不同，并有独立实例允许列表；mock 也遵守。控制令牌可读取，读取令牌不能提交或查询控制任务。默认关闭的 POST 返回 control_disabled；不接受 URL 令牌。

控制或核对开启、显式设置状态目录或目录中存在 store.id 时才打开 Inbox。默认两项写开关都关闭，不会意外创建状态目录。测试为每个 Agent 指定项目内独立目录。不要让两台逻辑节点共享目录，不把 Inbox、令牌或测试状态提交 Git。

## 文件格式与写入边界

| 文件 | 含义 |
|---|---|
| `.inbox.lock` | JDK FileLock 独占锁；第二进程拒绝启动 |
| `store.id` | 持久 UUID；普通进程重启不改变 |
| `<commandId>.task` | 每条命令的请求、目标容器、状态、时间与结果 |
| `<commandId>.resolution` | 原任务的独立 CLOSED 核对回执；最多一条，不挤占新任务容量 |
| `.write-<UUID>.tmp` | 写入中的临时文件；启动不把残留临时文件当作接受记录 |

任务记录依次为：4 字节大端格式魔数 `0x41524731`、4 字节大端 JSON 长度、UTF-8 JSON、32 字节 SHA-256 校验。JSON 含原七字段命令（编码为字符串）、目标、状态、时间、结果码和观测状态。校验用于发现损坏，不是防止本地管理员篡改的签名。

写入先在同一目录创建临时文件，完整写入后 `FileChannel.force(true)`，再以 `ATOMIC_MOVE + REPLACE_EXISTING` 替换；不支持原子替换就拒绝写入。Windows JDK 通常不支持目录 fsync；支持的系统尝试同步目录。这一限制不被表述为断电保证。

Inbox 损坏、格式不合法、身份丢失而记录仍在或目录锁失败时拒绝打开。运行中落盘失败会停用新执行；磁盘中的 RUNNING 仍按不确定结果处理。记录达到容量后只拒绝新命令，不清除旧去重记录。关闭时先停止调度、等待执行线程及子进程结束，再释放目录锁；worker 未结束时不释放锁给新进程。

核对回执也受同一目录锁和原子同步保护。加载时先读全部原任务，再验证回执的完整请求、哈希、身份、原 TaskView 与文件名，之后重建锁；已经闭合的 UNKNOWN 跳过旧锁重建。原 `.task` 仍永久保留。关闭还等待核对观察作用域及已知进程/reader 退出；超时则保留目录锁并报告失败，资源退出后可以再次关闭。进程强制退出会由操作系统释放文件锁，旧进程遗留动作属于人工需核对的边界。

## 恢复与运维限制

PENDING 可恢复排队，但先重新核对来源、目标和截止时间。RUNNING 在重启时持久化为 UNKNOWN，不能因为没有结果就再做一次 restart。终态按原 ID 查询，丢 HTTP 回应不产生第二次动作。Mock 模拟生命周期存在当前进程内，任务历史持久；重启后的模拟状态不等于真实 Docker 状态。

截止时间约束的是副作用开始：RUNNING 落盘后再次检查，可能阻塞的容器发现完成后、紧贴 Docker 命令调用或 mock 状态修改前再检查。专用截止校验在尚未执行时拒绝，保存 FAILED/EXPIRED；命令已经调用后的超时、中断或不确定结果仍是 UNKNOWN，不会据过期把不确定操作改成可重试的失败。

恢复旧 Inbox 备份会保留旧 storeId，却可能丢失备份之后的命令；中央看到 404 后可能重投。因此恢复/回滚必须先停止控制，不能沿用旧身份直接上线。应人工核对未决任务，按新身份重新接入，并让中央保持 UNKNOWN，直到完成核对。当前没有反回滚硬件或跨系统恢复证明。

## 本地验证

运行 `scripts/test-agent.ps1`，保留原 23 项测试，并运行 21 项 Inbox 测试。测试使用真实 Java 进程 `Runtime.halt` 绕过正常清理，分别在 PENDING、已产生一次模拟副作用但仍 RUNNING、终态落盘三个窗口退出。恢复后验证持久副作用计数只有一次，RUNNING 不重放且仍占有实例互斥。

另覆盖跨进程独占锁、并发同 ID 去重、冲突、容量、损坏记录、原子替换失败、期限/目标/来源变化、HTTP 角色令牌与关闭控制后的结果查询。所有文件和临时目录在项目内，未连接真实 Docker 或远程服务器。
