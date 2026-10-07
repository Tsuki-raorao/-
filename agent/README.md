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

真实模式缺少令牌时拒绝启动。`instance.discovery=true` 时通过 `docker ps -a` 发现容器，查询只允许已发现的名称；关闭发现时使用配置的单实例映射。容器瞬时 CPU/内存来自 `docker stats --no-stream`，不是历史监控。

主机资源由 Java 系统接口获取。权限或采集不可用时，部分数值可能为 0；玩家数尚无真实采集，不能将这些默认值当作业务状态。自动发现容器并不意味着能够自动推断该业务需要的专用指标。

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

配置令牌后所有接口都要求 `Authorization: Bearer <token>`，不接受查询参数传递令牌。mock 模式可不配置令牌，仅用于本地开发。指标包含 `argus_agent_up`、`argus_agent_info`、`argus_host_cpu_percent`、`argus_host_memory_bytes`、`argus_host_memory_total_bytes`、`argus_host_memory_percent` 与 `argus_host_metrics_available`；`/metrics` 不执行 Docker 命令。

## 执行边界

动作白名单为 `start`、`stop`、`restart`，实例名有字符限制。命令使用 `ProcessBuilder` 参数数组并设置超时，不经过 shell。任务队列达到上限时返回 `429 agent_busy`；关闭控制时创建任务返回 `403 control_disabled`。

任务记录仍保存在内存中，重启后丢失。控制中心当前没有向此任务接口下发命令，因此网页显示任务成功不代表 Agent 操作成功。真实控制仍需持久化任务、幂等、授权、审计和恢复流程支持。

真实只读采集与远程动作是不同开关。当前部署应保持 `control.enabled=false`，Agent 只允许控制中心或受控管理来源访问。Docker 访问权限本身具有较高影响范围，需要按运行环境单独限制。

## 本地检查

默认 mock 且未配置令牌时：

```powershell
Invoke-RestMethod http://localhost:8090/api/agent/health
Invoke-RestMethod http://localhost:8090/api/agent/instances
```

配置临时测试令牌后检查授权与未授权请求，不将令牌写入代码或检查结果。更多限制见 [问题记录](../docs/问题记录.md)，部署说明见 [Agent 可靠性与部署说明](../docs/Agent可靠性与部署说明.md)。
