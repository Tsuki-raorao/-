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
| `ARGUS_AGENT_NODE_ID` / `ARGUS_AGENT_NODE_NAME` | 节点标识与显示名 |
| `ARGUS_AGENT_ADVERTISED_HOST` | 控制中心可访问的节点入口 |
| `ARGUS_AGENT_AUTH_TOKEN` | Bearer 认证令牌 |
| `ARGUS_AGENT_MOCK` | 是否使用 mock 执行器 |
| `ARGUS_AGENT_DISCOVERY` | 是否自动发现 Docker 容器 |
| `ARGUS_AGENT_CONTROL_ENABLED` | 是否允许创建执行任务，默认关闭 |
| `ARGUS_AGENT_MAX_TASKS` | 并发任务上限 |
| `ARGUS_AGENT_MAX_REQUEST_BYTES` | 请求体大小上限 |
| `ARGUS_AGENT_MAX_HTTP_THREADS` | HTTP 工作线程上限 |
| `ARGUS_AGENT_MAX_HTTP_QUEUE` | HTTP 等待队列长度，默认 64，范围 1–1024 |
| `ARGUS_AGENT_MAX_COMMAND_OUTPUT_BYTES` | 单条 Docker 命令输出字节上限，默认 1 MiB，范围 1 KiB–16 MiB |

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
| POST | `/api/agent/tasks` | 在控制开关开启时创建受限动作任务 |
| GET | `/api/agent/tasks/{taskId}` | 查询内存中的任务结果 |

配置令牌后所有接口都要求 `Authorization: Bearer <token>`，不接受查询参数传递令牌。mock 模式可不配置令牌，仅用于本地开发。指标包含 `argus_agent_up`、`argus_agent_info`、`argus_host_cpu_percent`、`argus_host_memory_bytes`、`argus_host_memory_total_bytes`、`argus_host_memory_percent`；不可用数值直接省略。`argus_host_metrics_available` 表示基础指标是否全部可用，`argus_host_cpu_available` 和 `argus_host_memory_available` 分别指示可用性，0 是明确的采集状态而非伪造的资源值。`/metrics` 不执行 Docker 命令。

## 执行边界

动作白名单为 `start`、`stop`、`restart`，实例名有字符限制。命令使用 `ProcessBuilder` 参数数组，不经过 shell；独立读取线程在等待退出期间持续读取合并输出，避免大日志填满管道。命令超时、中断或输出超限会在终止父进程前记录本次启动进程的后代，一并终止并清理读取线程；父进程已退出时仍会清理已记录的后代。不会按系统进程名查杀，也不会把截断清单当作完整结果。任务队列达到上限时返回 `429 agent_busy`；关闭控制时创建任务返回 `403 control_disabled`。

HTTP 使用固定工作线程和有界等待队列。满队列采用 `CallerRunsPolicy`：JDK 调度线程执行一项请求，给接收端施加背压；实际处理线程最多为配置工作线程数加一个调度线程。它不是 HTTP 429 限流，也不能消除慢客户端或慢任务占用线程的问题；连接层的超时与速率控制仍需反向代理等外层措施。关闭时先停止 HTTP 服务、关闭连接，再关闭执行器，不能靠静默丢弃请求实现拒绝。

任务记录仍保存在内存中，重启后丢失。控制中心当前没有向此任务接口下发命令，因此网页显示任务成功不代表 Agent 操作成功。真实控制仍需持久化任务、幂等、授权、审计和恢复流程支持。

真实只读采集与远程动作是不同开关。当前部署应保持 `control.enabled=false`，Agent 只允许控制中心或受控管理来源访问。Docker 访问权限本身具有较高影响范围，需要按运行环境单独限制。

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

测试仅依赖已安装 JDK 与 PowerShell，输出、临时目录和子进程文件都在忽略提交的 `agent/out-test/`。当前 23 项 Java 测试覆盖未知/零值、来源、空发现与失败区别、统计失败、容器别名、授权和只读、Prometheus 缺失语义、超管道容量输出、输出上限、超时清理、HTTP 突发与关闭连接；其中两项使用真实 Java 包装进程及其子进程，检查超限/超时后两层 PID 和读取线程均已结束。另外使用 PowerShell 的独立 JSON 解析器验证 null、非有限数与控制字符往返。

2026-10-08 本地验证：23 项通过，0 失败，独立 JSON 解析通过。Docker 结果全部注入模拟，子进程仅为测试 Java 程序；这不等于真实 Docker、远程服务器、负载或恢复验收。任务持久化、幂等恢复、主动通信与业务 Adapter 注册仍未实现。
