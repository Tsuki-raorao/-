# Agent 可靠性与部署说明

更新：2026-10-08。本文描述当前 Java 17 Agent 的实现与配置，本轮仅本地开发验证，未发布到真实服务器。

## 已有保护与边界

| 机制 | 当前实现 | 尚不能据此承诺 |
|---|---|---|
| 默认安全开关 | 仓库配置 mock，远程动作默认关闭；真实模式无令牌拒绝启动 | 不代替完整用户权限和 Agent 操作审计 |
| 任务执行 | 固定命令、模式/节点/Inbox/目标绑定、独立控制令牌和允许列表，参数数组不经过 shell | 生命周期成功不等于应用业务就绪，真实 Docker 未验收 |
| 持久化与恢复 | 独占 Inbox、文件同步及原子替换，先持久 RUNNING 再执行，PENDING 恢复、RUNNING 崩溃转 UNKNOWN | 不承诺副作用 exactly-once、旧备份反回滚或跨平台断电一致性 |
| 命令输出 | 等待退出期间持续排空输出；超限/超时/中断清理进程 | 大量日志不是无限可读，超限是失败，不能使用截断清单 |
| HTTP | 固定工作线程和有界等待队列，请求体有上限 | 慢客户端仍占线程，尚无容量结论或完整连接层防护 |
| 采集 | 协议 1.1、来源、采样时间、可空指标、指标可用状态 | 尚无业务 Adapter 注册，玩家数/TPS 未采集 |
| 网关 | 目标白名单、令牌、连接/读取超时，只允许指定 GET 路径 | 不提供通用代理、SSH 或 shell |

任务队列满返回 429。HTTP 队列满采用 `CallerRunsPolicy`，由 JDK 调度线程执行一项请求形成背压，实际最多为配置工作线程加一个调度线程；它不是 HTTP 429 限流。关闭服务先关闭 HTTP 连接，再终止执行器，不能靠静默丢弃请求拒绝连接。

## 配置

环境变量优先于配置文件。文件模板只保存非敏感值，真实令牌由仓库外受限运行配置注入。

| 环境变量 | 文件键 | 默认 / 范围 |
|---|---|---|
| `ARGUS_AGENT_PORT` | `server.port` | 8090 |
| `ARGUS_AGENT_NODE_ID` / `ARGUS_AGENT_NODE_NAME` | `node.id` / `node.name` | 应显式设置稳定 ID；未配置 ID 时自动生成 |
| `ARGUS_AGENT_AUTH_TOKEN` | `auth.token` | 空；真实模式必须提供 |
| `ARGUS_AGENT_MOCK` | `executor.mock` | true |
| `ARGUS_AGENT_CONTROL_ENABLED` | `control.enabled` | false |
| `ARGUS_AGENT_CONTROL_TOKEN` | `control.token` | 空；开放控制时与读取令牌非空且不同 |
| `ARGUS_AGENT_CONTROL_INSTANCES` | `control.instances` | 空，允许操作的 Agent 局部实例 ID |
| `ARGUS_AGENT_TASK_DIR` | `task.directory` | `agent/data/task-inbox`；不同逻辑节点不可共享 |
| `ARGUS_AGENT_TASK_MAX_RECORDS` | `task.max-records` | 10000；1–100000，满后保留旧记录并拒绝新任务 |
| `ARGUS_AGENT_DISCOVERY` | `instance.discovery` | 配置类默认 true，仓库本地文件为 false |
| `ARGUS_AGENT_DOCKER` | `docker.executable` | docker |
| `ARGUS_AGENT_ADVERTISED_HOST` | `node.advertised-host` | 节点展示的可达入口，私有配置维护 |
| `ARGUS_AGENT_MAX_TASKS` | `executor.max-concurrent-tasks` | 4；1–64，任务等待队列为并发数的 4 倍 |
| `ARGUS_AGENT_MAX_REQUEST_BYTES` | `server.max-request-bytes` | 65536；1 KiB–1 MiB |
| `ARGUS_AGENT_MAX_HTTP_THREADS` | `server.max-http-threads` | 16；2–128 |
| `ARGUS_AGENT_MAX_HTTP_QUEUE` | `server.max-http-queue` | 64；1–1024 |
| `ARGUS_AGENT_MAX_COMMAND_OUTPUT_BYTES` | `executor.max-output-bytes` | 1 MiB；1 KiB–16 MiB |
| 无独立环境变量 | `executor.timeout-seconds` | 30 秒；1–600 |

真实模式中，发现成功为空返回空数组，发现失败返回明确 503 错误；stats 失败保留生命周期但指标为 null。单实例配置中 `instance.id` 可与 `instance.container` 不同，采集按真实容器名匹配。缺失指标不伪造 0，`/metrics` 省略不可用值并暴露可用性标记。

## 与控制中心配合

Agent 目前没有主动注册或定时心跳上报。中央网关默认关闭；启用后每轮完成等待 60 秒读取 health/instances，完整校验成功后事务保存。中央实例 ID 与 Agent 局部 ID 分离；网页最近日志由中央 ID 解析正确节点和局部 ID。

`lastCheckedAt` 记录尝试，`lastSuccessfulSyncAt` 记录完整成功批次，实例同批 `lastSeenAt` 与其相等。失败、部分同步、过期或本轮未发现不会被网页当作新鲜状态。HTTP/格式失败保留历史观测，合法空清单不删除历史实例。

任务走独立受控通道：中央持久队列先按固定 commandId 查询，只有绑定身份一致、明确 404 且中央尚无 Agent 已接收证据时，才可用原请求投递。确认已接收后记录消失必须转 UNKNOWN，不得重放。Agent 首次接受在 PENDING 落盘后返回 202，重复同请求返回 200，冲突为 409。接收和执行前重新核对目标、允许列表、模式与期限；慢落盘或最后一次目标发现也不能把已过期动作继续执行。状态不确定时保留实例互斥，UNKNOWN 的人工核对/解锁仍待实现。完整文件格式与恢复限制见 [持久任务 Inbox](../agent/docs/持久任务Inbox.md)。

Inbox 损坏、原子替换失败或存储身份缺失应停止新执行，不能删除状态目录后直接重启。回滚旧备份可能丢失后续命令但保留旧 storeId，必须先停止控制并核对未决任务，再以新身份重新接入。只读且未显式指定目录、没有既存 Inbox 时不创建任务目录；显式目录或既有 store.id 仍会打开，以供查询旧结果。

## 部署前步骤

1. 复现本地测试，固定 Agent/后端/前端版本；核对协议与数据库迁移。
2. 在私有配置中准备稳定节点 ID、匹配令牌、必要网络来源，保持 `control.enabled=false`。
3. 先以 mock 验证认证、来源标记和日志路由；不得以模拟结果代替真实资源验收。
4. 真实只读采集前确认 Docker 权限、允许实例、命令输出上限和网络边界；关闭 mock 不等于开放远程动作。
5. 发布前备份产物、运行配置、数据库及 Inbox，在独立环境验证 V4/V5 与恢复流程；MySQL DDL 失败不能假定自动整体回滚。
6. 只读采集发布验收独立于业务容器维护，不顺带启停或替换业务服务。

可靠任务已有本地实现，但完整权限审计、人工核对、真实 Docker 验收和生产发布未完成，继续保持真实控制关闭。systemd/Nginx 仅提供模板；真实节点、凭据和备份位置保存在私有运维资料中。开启控制不能仅翻一个开关：中央与 Agent 均需独立令牌、目标授权和匹配持久身份。

## 测试与故障定位

```powershell
.\scripts\test-agent.ps1
```

2026-10-08 已通过 **44 项 Java 检查（原 23 + Inbox 21）和独立 JSON 解析**。保留采集、输出/队列测试，新增并发去重、实例互斥、坏记录/落盘失败、跨进程目录锁、真实 Java 进程在 PENDING/RUNNING/终态三个窗口崩溃恢复，以及慢落盘和最后发现跨期限时动作次数为 0。测试使用本机 HTTP、模拟 Docker 和 Java 子进程，没有调用真实 Docker 或远程服务器。后端 54 个唯一用例分批通过，根本机实际任务联调 28 项断言通过，最新汇总见 [开发进度](开发进度.md)。

故障应分别定位：401 检查令牌角色，403 检查控制开关/允许列表；任务 429 为队列忙，503 也可能为 Inbox 容量或持久化失败；快照 PARTIAL 是完整采集未完成。UNKNOWN 不能当失败重做，先保留现场与原命令核对。日志和错误记录只保存必要脱敏信息。
