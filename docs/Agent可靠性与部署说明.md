# Agent 可靠性与部署说明

本文说明 Agent 当前实现的安全边界、配置变量和本地备份方式。它只描述项目配置，不会自动连接或修改任何服务器。

## 当前保护措施

1. **默认 mock 执行**：仓库中的 `agent/config/agent.properties` 使用 `executor.mock=true`，本地启动不会调用 Docker。真实环境必须显式设置 `ARGUS_AGENT_MOCK=false`，并先确认 Docker 权限和实例白名单。
2. **任务并发上限**：`executor.max-concurrent-tasks` 默认 4，任务等待队列最多为并发数的 4 倍。队列满时返回 HTTP 429，调用方应稍后重试，避免无限创建线程。
3. **HTTP 线程上限**：`server.max-http-threads` 默认 16，避免突发请求创建无限线程。
4. **请求体上限**：`server.max-request-bytes` 默认 65536 字节；超大请求会被拒绝，降低内存耗尽风险。
5. **Docker 命令超时**：`executor.timeout-seconds` 默认 30 秒。超时会强制结束子进程并返回失败状态。
6. **动作白名单和实例名校验**：仅允许 `start`、`stop`、`restart`，实例名只能包含字母、数字、点、下划线和短横线。
7. **远程控制开关**：`control.enabled` 默认 false；只读健康、实例和日志接口不受影响，但提交 start/stop/restart 会返回 `403 control_disabled`。
8. **容器自动发现**：真实模式默认执行只读 `docker ps -a`，展示节点上的全部容器；随后尝试只读 `docker stats --no-stream` 采集 CPU、内存瞬时值。stats 失败时不影响生命周期状态，资源值保持 0；日志和状态查询只允许访问已发现的容器名，避免把任意字符串当作 Docker 目标。
9. **节点可达地址**：`node.advertised-host` 用于注册信息，不能填写 `0.0.0.0`；控制中心应使用云安全组允许的实际地址。

## 配置变量

| 变量 | 文件配置 | 默认值 | 用途 |
|---|---|---:|---|
| `ARGUS_AGENT_PORT` | `server.port` | `8090` | Agent HTTP 端口 |
| `ARGUS_AGENT_NODE_ID` | `node.id` | 随机值 | 节点唯一标识 |
| `ARGUS_AGENT_NODE_NAME` | `node.name` | 节点 ID | 展示名称 |
| `ARGUS_AGENT_AUTH_TOKEN` | `auth.token` | 空 | Bearer 认证令牌；生产环境必须使用环境变量 |
| `ARGUS_AGENT_MOCK` | `executor.mock` | `true` | 是否使用模拟执行器 |
| `ARGUS_AGENT_DOCKER` | `docker.executable` | `docker` | Docker 可执行文件路径 |
| `ARGUS_AGENT_MAX_TASKS` | `executor.max-concurrent-tasks` | `4` | 同时执行的任务数，范围 1–64 |
| `ARGUS_AGENT_MAX_REQUEST_BYTES` | `server.max-request-bytes` | `65536` | 单次请求体上限，范围 1 KiB–1 MiB |
| `ARGUS_AGENT_MAX_HTTP_THREADS` | `server.max-http-threads` | `16` | HTTP 工作线程数，范围 2–128 |
| `ARGUS_AGENT_CONTROL_ENABLED` | `control.enabled` | `false` | 是否允许远程 start/stop/restart；完成备份、审计和权限配置后才开启 |
| `ARGUS_AGENT_DISCOVERY` | `instance.discovery` | `true` | 真实模式是否自动读取 `docker ps -a` |
| `ARGUS_AGENT_ADVERTISED_HOST` | `node.advertised-host` | 空 | 注册给控制中心的可达主机地址 |

文件配置用于非敏感项。`auth.token` 不要写入 Git、备份包或日志；生产部署通过环境变量注入，并由外层防火墙或反向代理限制来源。

## 推荐部署顺序

1. 复制 `agent/config/agent.properties.example` 到部署目录，填写节点 ID、实例名和非敏感参数。
2. 使用 `ARGUS_AGENT_AUTH_TOKEN` 注入令牌，先保持 `ARGUS_AGENT_MOCK=true` 做连通性检查。
3. 验证 `/api/agent/health`、`/api/agent/instances` 和任务查询接口。
4. 只有在确认容器名、Docker 权限、命令超时和回滚方案后，才设置 `ARGUS_AGENT_MOCK=false`。
5. 改配置或升级前运行 `scripts/backup-project-state.ps1` 保存项目配置快照；服务器容器变更需要另外在服务器侧执行备份和回滚，不由本项目脚本自动操作。

部署原则：需要采集的业务节点单独安装 Agent，令牌通过受限运行配置注入，管理端口只允许控制中心来源。初始部署保持 `control.enabled=false`，具体节点列表和网络规则由部署者在私有运维资料中维护。

## 故障处理

- HTTP 429：任务队列已满，等待已有任务完成后重试，不要循环高速提交。
- `UNKNOWN`：Agent 无法在超时时间内获得容器状态；检查 Docker 权限、容器名称和磁盘空间。
- Agent 进程重启：当前任务状态只保存在内存中，重启后丢失，控制中心尚无已完成的恢复编排；生产版需要 Inbox/Outbox 和持久化任务尝试记录。
- 认证失败：检查令牌是否通过环境变量注入，避免把令牌直接写进配置文件或命令历史。

## 本地验证

```powershell
.\scripts\start-agent.ps1
.\scripts\check-local-stack.ps1 -SkipFrontend
```

脚本默认只访问本机 `localhost`。它们不会连接用户的云服务器。

## 生产启动保护

真实模式（`executor.mock=false`）启动时必须提供 `ARGUS_AGENT_AUTH_TOKEN` 或 `auth.token`；当前代码在令牌为空时直接拒绝启动，避免误把 8090 的状态接口暴露给公网。生产 systemd 环境应把令牌放在权限为 `0600` 的独立 EnvironmentFile 中，不要写入项目配置、日志或备份。
