# Argus Node Agent

Agent 是运行在被管节点上的 Java 17 服务，使用 JDK 自带 HttpServer，不依赖 Maven 或第三方 JAR。它提供主机与容器状态、资源快照、日志读取和受配置保护的执行接口。

当前没有主动注册或定时心跳上报逻辑；控制中心通过只读网关拉取快照。Agent 中存在 `register`、`heartbeat` 接口，但它们只响应请求，不能据此视为主动上报机制已经完成。

## 本地运行

在仓库根目录执行：

```powershell
.\scripts\start-agent.ps1
```

或在 `agent` 目录独立编译和启动：

```powershell
$agentSources = Get-ChildItem src/main/java -Recurse -Filter '*.java' | ForEach-Object FullName
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out $agentSources
java -cp out com.argus.agent.AgentApplication --config=config/agent.properties
```

本地入口为 `http://localhost:8090`。仓库配置使用 `executor.mock=true`、`instance.discovery=false`，不会调用 Docker；`control.enabled` 默认 `false`。

## 配置与采集

生产配置从 `config/agent.properties.example` 准备，真实环境配置与秘密放在仓库外。环境变量优先于配置文件：

| 变量 | 含义 |
|---|---|
| `ARGUS_AGENT_PORT` | 监听端口 |
| `ARGUS_AGENT_BIND_ADDRESS` | 实际监听 IP，对应 `server.bind-address`；默认 `0.0.0.0`，本机隔离验证使用 `127.0.0.1` |
| `ARGUS_AGENT_NODE_ID` / `ARGUS_AGENT_NODE_NAME` | 节点标识与显示名 |
| `ARGUS_AGENT_ADVERTISED_HOST` | 控制中心可访问的节点入口 |
| `ARGUS_AGENT_AUTH_TOKEN` | Bearer 认证令牌 |
| `ARGUS_AGENT_MOCK` | 是否使用 mock 执行器 |
| `ARGUS_AGENT_DISCOVERY` | 是否自动发现 Docker 容器 |
| `ARGUS_AGENT_CONTROL_ENABLED` | 是否允许创建执行任务，默认关闭 |
| `ARGUS_AGENT_CONTROL_TOKEN` | 独立控制令牌，必须与读取令牌不同；POST/GET tasks 使用它 |
| `ARGUS_AGENT_CONTROL_INSTANCES` | 允许控制的 Agent 局部 ID，逗号分隔；自动发现不授予控制权 |
| `ARGUS_AGENT_REVIEW_ENABLED` | 是否允许人工核对旧任务，默认 false；与动作控制开关独立 |
| `ARGUS_AGENT_REVIEW_INSTANCES` | 可人工核对的局部实例 ID 精确名单；默认空，不能自动继承发现清单 |
| `ARGUS_AGENT_TASK_DIR` | 持久 Inbox 目录，默认 `agent/data/task-inbox` |
| `ARGUS_AGENT_TASK_MAX_RECORDS` | Inbox 记录容量，默认 10000；满后拒绝新命令，原命令仍可查询 |
| `ARGUS_AGENT_MAX_TASKS` | 并发任务上限 |
| `ARGUS_AGENT_MAX_REQUEST_BYTES` | 请求体大小上限 |
| `ARGUS_AGENT_MAX_HTTP_THREADS` | HTTP 工作线程上限 |
| `ARGUS_AGENT_MAX_HTTP_QUEUE` | HTTP 等待队列长度，默认 64，范围 1–1024 |
| `ARGUS_AGENT_MAX_COMMAND_OUTPUT_BYTES` | 单条 Docker 命令输出字节上限，默认 1 MiB，范围 1 KiB–16 MiB |

监听地址仅接受 IPv4/IPv6 字面量，如 `127.0.0.1`、`::1`；不支持主机名或带协议/端口的 URL。显式空值、无效地址会拒绝启动，包括空的 `ARGUS_AGENT_BIND_ADDRESS`，不会退回配置文件或全接口监听。不存在于本机的地址、端口占用等绑定失败会清理 HTTP 资源和 Inbox 锁；启动日志显示实际绑定的地址/端口。默认 `0.0.0.0` 保留历史部署兼容性，不能据此认为默认仅本机可达。

本机隔离验证可在配置中填 `server.bind-address=127.0.0.1`，或在启动该 Agent 的进程环境中设置 `ARGUS_AGENT_BIND_ADDRESS=127.0.0.1`。这只允许本机访问该监听入口；远程控制中心需要受控代理或隧道。`node.advertised-host` 是对外描述的入口信息，不能替代实际绑定配置。

真实模式缺少令牌时拒绝启动。`instance.discovery=true` 时通过 `docker ps -a` 发现容器，实例 ID 与实际容器名一致，查询只允许已发现的名称；关闭发现时使用配置的单实例映射，此时 `instance.id` 可与 `instance.container` 不同，指标仍按真实容器名匹配。容器瞬时 CPU/内存来自 `docker stats --no-stream`，不是历史监控。

主机资源由 Java 系统接口获取。未知、无效或不可用指标为 `null`，真实零值仍为 `0`。玩家数和 TPS 没有真实采集，实例 `players` 始终为 `null`。自动发现容器并不意味着能够自动推断该业务需要的专用指标。

## 采集协议 1.1

健康响应保留旧字段并新增 `protocolVersion="1.1"`、`dataSource="HOST"`、`sampledAt` 和 `metricsStatus`。即使容器使用 mock，主机采集仍来自当前运行主机，不能标成 Docker 样本。主机 CPU、已用内存、总内存、内存比例均允许 `null`。

实例接口仍返回 JSON 数组。每项新增 `dataSource="DOCKER"|"MOCK"`、`sampledAt`、`metricsStatus`，`checkedAt` 兼容保留，取同一时间。CPU 和内存允许 `null`，mock 样本明确标为 `MOCK`。

| `metricsStatus` | 含义 |
|---|---|
| `AVAILABLE` | 本类基础指标全部采集成功，包括真实的 0 |
| `PARTIAL` | 一部分基础指标可用 |
| `UNAVAILABLE` | 基础指标全部缺失 |

主机按 CPU、已用内存和总内存判断；实例只按 CPU 与内存判断，不把不支持的玩家数算作采集失败。`sampledAt` 是本轮采集完成/尝试时间，并不意味着指标一定成功；应一起检查 `metricsStatus`，控制中心另外判断过期。

容器发现成功但为空时返回 `[]`。发现失败、输出超限或发现格式异常返回 HTTP 503，固定 `error=collection_unavailable`，`reason` 只含预定义代码，不回显原始命令、stderr 或路径。stats 单独失败时仍保留成功发现的实例与生命周期，CPU、内存为 `null`，不会补造默认实例。

自动发现和单实例模式使用统一生命周期映射：running → RUNNING，created/restarting → STARTING，paused/exited/dead/removing → STOPPED，其他 → UNKNOWN。

## 接口

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/agent/health` | 版本、只读状态、能力声明与主机资源快照 |
| GET | `/metrics` | Prometheus 文本格式的 Agent 与主机指标 |
| POST | `/api/agent/register` | 返回节点身份与能力声明，不向控制中心主动注册 |
| POST | `/api/agent/heartbeat` | 返回节点在线确认，不执行主动上报 |
| GET | `/api/agent/instances` | 实例状态与资源快照 |
| GET | `/api/agent/instances/{instanceId}/logs?limit=100` | 读取已允许实例的最近日志 |
| POST | `/api/agent/tasks` | 控制令牌、开关与允许列表通过后，持久接收七字段命令 |
| GET | `/api/agent/tasks/{commandId}` | 用控制令牌查询持久结果；关闭控制后仍可查询 |
| GET | `/api/agent/tasks/{commandId}/review-evidence` | 用控制令牌读取原任务、锁归属与本进程执行资源事实，不运行 Docker 观察 |
| POST | `/api/agent/tasks/{commandId}/resolutions` | 独立 review 开关/名单授权后持久保存人工核对回执；不重放原动作 |
| GET | `/api/agent/tasks/{commandId}/resolutions/{resolutionId}` | 查询固定回执；关闭 review 后仍可查询 |

配置令牌后读取接口要求 `Authorization: Bearer <read-token>`；任务 POST/GET 要求独立控制令牌，控制令牌也可读取。不接受查询参数传递令牌。纯只读 mock 模式可不配置令牌；一旦开放控制，mock 也要求两个非空且不同的令牌和明确允许列表。指标包含 `argus_agent_up`、`argus_agent_info`、`argus_host_cpu_percent`、`argus_host_memory_bytes`、`argus_host_memory_total_bytes`、`argus_host_memory_percent`；不可用数值直接省略。`argus_host_metrics_available` 表示基础指标是否全部可用，`argus_host_cpu_available` 和 `argus_host_memory_available` 分别指示可用性，0 是明确的采集状态而非伪造的资源值。`/metrics` 不执行 Docker 命令。

## 执行边界

动作白名单为 `start`、`stop`、`restart`，实例名有字符限制。命令使用 `ProcessBuilder` 参数数组，不经过 shell；独立读取线程在等待退出期间持续读取合并输出，避免大日志填满管道。命令超时、中断或输出超限会在终止父进程前记录本次启动进程的后代，一并终止并清理读取线程；父进程已退出时仍会清理已记录的后代。不会按系统进程名查杀，也不会把截断清单当作完整结果。任务队列达到上限时返回 `429 agent_busy`；关闭控制时创建任务返回 `403 control_disabled`。

HTTP 使用固定工作线程和有界等待队列。满队列采用 `CallerRunsPolicy`：JDK 调度线程执行一项请求，给接收端施加背压；实际处理线程最多为配置工作线程数加一个调度线程。它不是 HTTP 429 限流，也不能消除慢客户端或慢任务占用线程的问题；连接层的超时与速率控制仍需反向代理等外层措施。关闭时先停止 HTTP 服务、关闭连接，再关闭执行器，不能靠静默丢弃请求实现拒绝。

任务现有持久 Inbox：中央 UUID 为任务 ID，相同七字段请求返回原结果，冲突返回 409。PENDING 落盘后才确认接收，RUNNING 落盘后才执行；执行后核验实例状态。重启发现 RUNNING 则改为 UNKNOWN，不自动重放，UNKNOWN 持续占用该实例的控制互斥。独立人工核对通过后只保存 CLOSED 回执并条件解除原锁，原 UNKNOWN 记录永不改成成功；旧 command 重试仍返回原记录。MOCK 的 stop/start/restart 会改变进程内模拟状态并核验，但不会控制真实 Docker。完整协议、磁盘格式和恢复边界见 [持久任务 Inbox](docs/持久任务Inbox.md) 与 [人工核对回执](docs/人工核对回执.md)。

核对 health 增加 `reviewProtocolVersion="1.0"`、`reviewEnabled`；采集协议 1.1 和任务协议 1.0 保持不变。`controlEnabled` 与已有 `readOnly` 字段仍描述动作执行开关，不能用它们推断核对授权；例如 control=false、review=true 时只能按独立规则核对旧任务，仍不能 start/stop/restart。开启 review 同样必须配置不同的读取/控制令牌与精确核对名单，即使使用 MOCK。核对始终使用控制令牌，默认配置没有新增写权限。

核对首次提交前检查原 worker、命令作用域、已知子进程及输出读取线程；当前观察之后再检查一次。限时 kill/等待不是退出证明，仍存活或清理无法确认时为 `review_executor_active`，原锁保留。`CURRENT_PROCESS_CLEARED` 只表示本进程已知资源清理完成；重启恢复旧任务只能报告 `PRIOR_PROCESS_UNVERIFIED`。Docker daemon 在 CLI 退出后仍可能继续动作，必须由人工核对并明确接受这一风险，不能把当前容器状态当成原任务成功证据。

真实只读采集与远程动作是不同开关。当前部署保持 `control.enabled=false`、`review.enabled=false`，Agent 只允许控制中心或受控管理来源访问。Docker 访问权限本身具有较高影响范围，需要按运行环境单独限制。

## 已部署状态（2026-10-09）

包含持久任务 Inbox 与人工核对协议的 Agent 已按 MC 节点、控制节点的顺序部署到两台既有节点，各只重启一次 Agent。采集协议为 1.1、任务协议为 1.0、核对协议为 1.0；线上 `controlEnabled=false`、`reviewEnabled=false`，尚未开放远程动作或人工核对。

发布前备份了旧类、实际配置和 Inbox；发布采用独立版本目录与可撤销的启动覆盖，原 node/store 身份、读取令牌、监听地址和端口保持不变。两台 Inbox 当时均无任务或回执，发布前后全部文件摘要一致，没有恢复或重建 Inbox。回退只撤销本次 Agent 启动覆盖，不能通过恢复旧 Inbox 备份重置任务身份。

两节点均通过授权健康读取、未认证 401、真实 HOST/DOCKER 采集、容器发现与日志读取检查；读取令牌访问核对证据接口返回 401。全部业务容器的 ID、启动时间、重启次数和状态与发布前一致，未重启业务容器。本次没有提交生产动作或核对请求，因此“已部署”不代表真实生产 UNKNOWN 核对已执行或已验收。

## 本地检查

默认 mock 且未配置令牌时：

```powershell
Invoke-RestMethod http://localhost:8090/api/agent/health
Invoke-RestMethod http://localhost:8090/api/agent/instances
```

配置临时测试令牌后检查授权与未授权请求，不将令牌写入代码或检查结果。更多限制见 [问题记录](../docs/问题记录.md)，部署说明见 [Agent 可靠性与部署说明](../docs/Agent可靠性与部署说明.md)。

独立测试从仓库根目录运行：

```powershell
.\scripts\test-agent.ps1
```

测试仅依赖已安装 JDK 与 PowerShell，输出、临时目录和子进程文件都在忽略提交的 `agent/out-test/`。基础采集/进程/HTTP 共 27 项（保留原 23 项，增加 4 项监听配置及失败清理检查），另有 21 项 Inbox 测试覆盖严格命令 JSON、并发去重、冲突、身份与允许列表、期限、UNKNOWN 互斥、容量、损坏/写失败、跨进程目录锁与真实进程强制退出恢复。三个崩溃窗口验证 PENDING 可恢复、RUNNING 不重放、终态可重查，持久副作用计数始终为一次。另用 PowerShell 独立解析器验证采集与任务 JSON 契约。

2026-10-08 本地验证：23 项原测试与 21 项 Inbox 测试通过，0 失败，独立 JSON 解析通过。新增期限测试以受控时钟模拟 RUNNING 持久化、执行入口容器发现期间过期，验证动作次数均为零。Docker 结果与动作使用注入模拟或 MOCK，子进程仅为测试 Java 程序；这不等于真实 Docker、远程服务器、负载或断电恢复验收。主动通信、业务 Adapter 注册及完整用户/项目权限仍未实现；跨模块验收以根目录进度文档为准。

2026-10-09 监听配置增量验收：27 项基础测试 + 21 项 Inbox 测试共 48 项全部通过，两个独立 JSON 检查通过。新增检查验证配置优先级及显式空值拒绝、实际 socket 只绑定 loopback 且日志一致、端口占用失败后 Inbox 能立即重开，以及 HTTP 初始化失败后端口和 Inbox 均不残留。执行器在端口绑定前构造，避免 JDK 未启动 HttpServer 的 stop 无法可靠释放已绑定端口。此轮未连接远程服务器或执行真实 Docker。

2026-10-09 人工核对增量：保留上述 48 项，新增 5 项执行资源登记与 36 项核对测试，总计 89 项；结果以本轮脚本实际输出为准。覆盖两项 boolean 确认的严格类型、中文/换行/emoji 跨语言 hash、原任务文件字节保持、并发同请求、容量、坏盘/坏回执、真实 Java 崩溃前后恢复、旧回执与新任务锁、当前 worker/helper/reader、观察自身残留进程、独立开关与令牌、回执时间矛盾，以及关闭时等待核对观察退出。另有三组独立 JSON 检查。所有增量测试使用本机模拟执行与自建 Java 进程，不能据此宣称新核对模块已在生产开放。
