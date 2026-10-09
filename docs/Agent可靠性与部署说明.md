# Agent 可靠性与部署说明

更新：2026-10-09。本文描述当前 Java 17 Agent 的实现与配置。人工核对代码已完成，Agent 89 项 Java/3 组独立 JSON 检查通过；包含核对协议的新 Agent 已部署到两台既有节点，控制与核对仍关闭。中央已一次切换到 V6 并通过服务及公网接口验收，2 节点/8 实例原 ID 保留；新启动后双节点各 4 个 DOCKER 实例及日志身份检查通过。本次 Agent 发布另核验只读健康、发现、日志及业务容器不变；最新浏览器验收由 [开发进度](开发进度.md) 单独记录，不以接口检查代替。

## 已有保护与边界

| 机制 | 当前实现 | 尚不能据此承诺 |
|---|---|---|
| 默认安全开关 | 仓库配置 mock，远程动作默认关闭；真实模式无令牌拒绝启动 | 不代替完整用户权限和 Agent 操作审计 |
| 任务执行 | 固定命令、模式/节点/Inbox/目标绑定、独立控制令牌和允许列表，参数数组不经过 shell | 独立真实容器验收通过，生命周期成功仍不等于应用业务就绪或网页真实整链路通过 |
| 持久化与恢复 | 独占 Inbox、文件同步及原子替换，先持久 RUNNING 再执行，PENDING 恢复、RUNNING 崩溃转 UNKNOWN | 不承诺副作用 exactly-once、旧备份反回滚或跨平台断电一致性 |
| 人工核对 | 独立默认关闭的 review 开关/名单、固定请求与持久 CLOSED 回执；先落盘再条件释放原锁，原 UNKNOWN 不改为成功 | 生产尚未执行核对；当前状态与已知进程清理不能证明 Docker daemon 历史副作用结束 |
| 命令输出 | 等待退出期间持续排空输出；超限/超时/中断清理进程 | 大量日志不是无限可读，超限是失败，不能使用截断清单 |
| HTTP | 固定工作线程和有界等待队列，请求体有上限 | 慢客户端仍占线程，尚无容量结论或完整连接层防护 |
| 采集 | 协议 1.1、来源、采样时间、可空指标、指标可用状态 | 尚无业务 Adapter 注册，玩家数/TPS 未采集 |
| 网关 | 只读网关限制固定 GET；任务与核对使用独立授权的固定通道，均有主机白名单和完整响应截止 | 不提供通用代理、SSH 或 shell |

任务队列满返回 429。HTTP 队列满采用 `CallerRunsPolicy`，由 JDK 调度线程执行一项请求形成背压，实际最多为配置工作线程加一个调度线程；它不是 HTTP 429 限流。关闭服务先关闭 HTTP 连接，再终止执行器，不能靠静默丢弃请求拒绝连接。

## 配置

环境变量优先于配置文件。文件模板只保存非敏感值，真实令牌由仓库外受限运行配置注入。

| 环境变量 | 文件键 | 默认 / 范围 |
|---|---|---|
| `ARGUS_AGENT_PORT` | `server.port` | 8090 |
| `ARGUS_AGENT_BIND_ADDRESS` | `server.bind-address` | 默认 `0.0.0.0` 保持兼容；显式 IPv4/IPv6 字面量，空值/无效值拒绝启动 |
| `ARGUS_AGENT_NODE_ID` / `ARGUS_AGENT_NODE_NAME` | `node.id` / `node.name` | 应显式设置稳定 ID；未配置 ID 时自动生成 |
| `ARGUS_AGENT_AUTH_TOKEN` | `auth.token` | 空；真实模式必须提供 |
| `ARGUS_AGENT_MOCK` | `executor.mock` | true |
| `ARGUS_AGENT_CONTROL_ENABLED` | `control.enabled` | false |
| `ARGUS_AGENT_CONTROL_TOKEN` | `control.token` | 空；开放控制时与读取令牌非空且不同 |
| `ARGUS_AGENT_CONTROL_INSTANCES` | `control.instances` | 空，允许操作的 Agent 局部实例 ID |
| `ARGUS_AGENT_REVIEW_ENABLED` | `review.enabled` | false，独立人工核对开关 |
| `ARGUS_AGENT_REVIEW_INSTANCES` | `review.instances` | 空，允许核对的 Agent 局部实例 ID；使用独立控制令牌 |
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

`ARGUS_AGENT_BIND_ADDRESS` 控制实际 socket 监听，`ARGUS_AGENT_ADVERTISED_HOST` 只是公布入口，两者不能互相替代。同机部署模板显式绑定回环；跨机中央读取需选择其可达的受控网卡并限制入站来源。绑定或 HTTP 初始化失败必须释放已取得的端口、执行器与 Inbox 锁，避免进程启动失败后不能重启。

真实模式中，发现成功为空返回空数组，发现失败返回明确 503 错误；stats 失败保留生命周期但指标为 null。单实例配置中 `instance.id` 可与 `instance.container` 不同，采集按真实容器名匹配。缺失指标不伪造 0，`/metrics` 省略不可用值并暴露可用性标记。

## 与控制中心配合

Agent 目前没有主动注册或定时心跳上报。中央网关默认关闭；启用后每轮完成等待 60 秒读取 health/instances，完整校验成功后事务保存。中央实例 ID 与 Agent 局部 ID 分离；网页最近日志由中央 ID 解析正确节点和局部 ID。

`lastCheckedAt` 记录尝试，`lastSuccessfulSyncAt` 记录完整成功批次，实例同批 `lastSeenAt` 与其相等。失败、部分同步、过期或本轮未发现不会被网页当作新鲜状态。HTTP/格式失败保留历史观测，合法空清单不删除历史实例。

任务走独立受控通道：中央持久队列先按固定 commandId 查询，只有绑定身份一致、明确 404 且中央尚无 Agent 已接收证据时，才可用原请求投递。确认已接收后记录消失必须转 UNKNOWN，不得重放。Agent 首次接受在 PENDING 落盘后返回 202，重复同请求返回 200，冲突为 409。接收和执行前重新核对目标、允许列表、模式与期限；慢落盘或最后一次目标发现也不能把已过期动作继续执行。状态不确定时保留实例互斥。UNKNOWN 人工核对已实现：独立授权、人工确认不重放并接受残余风险后，Agent 保存 CLOSED 回执再释放原锁；中央验证回执后记 APPLIED，保留原 UNKNOWN 历史。完整格式与恢复限制见 [持久任务 Inbox](../agent/docs/持久任务Inbox.md) 和 [人工核对设计](UNKNOWN任务人工核对设计.md)。

核对以当前 worker、已知子进程和 reader 退出为前提；当前观察后再检查一次。进程重启后的清理来源明确为 PRIOR_PROCESS_UNVERIFIED，不能当成已证明外部副作用结束。重复旧回执不重新采样或删除新任务的锁；更换 store 或原记录缺失时不强制解锁。health 的 `reviewProtocolVersion=1.0` 表示支持协议，不表示 `reviewEnabled=true`。

Inbox 损坏、原子替换失败或存储身份缺失应停止新执行，不能删除状态目录后直接重启。回滚旧备份可能丢失后续命令但保留旧 storeId，必须先停止控制并核对未决任务，再以新身份重新接入。只读且未显式指定目录、没有既存 Inbox 时不创建任务目录；显式目录或既有 store.id 仍会打开，以供查询旧结果。

## 部署前步骤

1. 复现本地测试，固定 Agent/后端/前端版本；核对协议与数据库迁移。
2. 在私有配置中准备稳定节点 ID、匹配令牌、必要网络来源，保持 `control.enabled=false`、`review.enabled=false`。
3. 先以 mock 验证认证、来源标记和日志路由；不得以模拟结果代替真实资源验收。
4. 真实只读采集前确认 Docker 权限、允许实例、命令输出上限和网络边界；关闭 mock 不等于开放远程动作。
5. 发布前备份产物、运行配置、数据库及 Inbox，在独立环境验证对应 V4/V5/V6 迁移与恢复流程；MySQL DDL 失败不能假定自动整体回滚。
6. 只读采集发布验收独立于业务容器维护，不顺带启停或替换业务服务。

可靠任务此前已完成独立真实 Docker 测试容器验收；本次两台 Agent 各只重启一次，保留原 Inbox、node/store 身份、读取令牌与监听范围。业务容器的 ID、StartedAt、RestartCount、状态均与发布前相同。生产业务控制和核对均关闭，没有提交真实核对或生成生产回执。基础任务/核对事件审计已实现，完整用户体系和 RBAC 仍未完成。真实节点、凭据和备份位置保存在私有资料中。开启控制或核对不能仅翻一个开关：中央与 Agent 均需独立令牌、对应目标授权和匹配持久身份，中央全局只读优先。

## 测试与故障定位

2026-10-09 最新 **89 项 Java 检查（基础 27 + Inbox 21 + 执行资源登记 5 + 人工核对 36）与 3 组独立 JSON 检查通过**。核对增量覆盖并发幂等、权限撤销、原证据不变、旧回执与新锁、坏盘/坏回执、真实 Java 崩溃、已知进程/reader 未退出、跨语言 hash 和回执时间顺序；这些测试只在本机隔离环境运行。下文 48 项及更早数字是历史切片，不能累加为本轮通过数。

```powershell
.\scripts\test-agent.ps1
```

2026-10-09 此前监听配置切片 **48 项 Java 检查（基础 27 + Inbox 21）与 2 项独立 JSON 检查通过**。新增配置优先级/空值拒绝、实际回环绑定、端口占用及 HTTP 初始化失败后资源不残留。独立真实测试容器另完成 START/STOP、停止状态下 RESTART、重复 commandId 无新增 Docker 事件、Agent 重启后 storeId 与旧结果保留，以及权限保护核对。该次容器无网络且限制 64 MiB/0.25 CPU，测试容器与临时 Agent 已清理；原有 4 个业务容器元数据不变。运行中 RESTART 的 StartedAt 变化及生命周期事件也已独立补测通过，重复命令和 Agent 重启后重查无新增事件；此证据不覆盖业务恢复、负载或断电一致性。

2026-10-08 历史基线为 **44 项 Java 检查（原 23 + Inbox 21）和独立 JSON 解析**。包含并发去重、实例互斥、坏记录/落盘失败、跨进程目录锁、真实 Java 进程在 PENDING/RUNNING/终态三个窗口崩溃恢复，以及慢落盘和最后发现跨期限时动作次数为 0。该阶段使用本机 HTTP、模拟 Docker 和 Java 子进程，没有调用真实 Docker 或远程服务器。历史后端 54 个唯一用例分批通过，本机实际任务联调 28 项断言通过；最新迁移和上线状态见 [开发进度](开发进度.md)。

故障应分别定位：401 检查令牌角色，403 检查控制开关/允许列表；任务 429 为队列忙，503 也可能为 Inbox 容量或持久化失败；快照 PARTIAL 是完整采集未完成。UNKNOWN 不能当失败重做，先保留现场与原命令核对。日志和错误记录只保存必要脱敏信息。
