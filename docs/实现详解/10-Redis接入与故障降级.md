# 10 Redis 接入与故障降级

## 一、Redis 当前承担什么

当前只保存任务热状态副本。主类 `RedisTaskStateCache` 使用 `StringRedisTemplate` 写入 JSON，默认 key=`argus:task:<taskId>`，TTL=120 秒。任务查询仍读 MySQL，没有“先读缓存、未命中回源”的读取代码。因此不能把本章讲成已经做过读缓存命中率优化。

`TaskQueueStore` 的任务、队列、锁、事件与 Outbox 同事务提交，`AfterCommitNotification` 在提交后调用 `TaskStateCache.put`。Redis 写失败不会回滚已经提交的 MySQL 事实；Redis 也不决定实例锁、消息去重或 Agent 是否已执行。

## 二、怎么接入，为什么不硬编码地址

`ARGUS_REDIS_ENABLED=true` 才创建实际 Redis 实现，关闭时使用 Noop 实现。业务依赖接口 `TaskStateCache`，不依赖具体客户端；主机、端口、密码由 Spring Data Redis 配置与运行环境提供，源码和 IDEA 共享配置保留占位符。

`127.0.0.1` 相对于当前 Java 进程所在机器解释：云服务器上的 Java 连接云服务器本机，电脑上的 Java 连接电脑本机或本地转发端口。它本身不能证明“Redis 必须装在你的电脑”。

本轮使用云服务器上的独立 Redis 容器，限制 64MiB/0.2 CPU，只发布到 loopback 26379，启用独立密码；没有停止生产 Redis、没有 FLUSHALL，也没有向公网开放 Redis。

## 三、超时预算为什么需要显式配置

提交后通知仍在调用线程执行，“不回滚事务”不等于“完全不增加响应耗时”。如果沿用很长的依赖超时，Redis 故障会让 API 或 Worker 等待很久。

`application.yml` 新增：

| 入口 | 默认值 | Spring 属性 |
|---|---|---|
| `ARGUS_REDIS_CONNECT_TIMEOUT` | 500ms | `spring.data.redis.connect-timeout` |
| `ARGUS_REDIS_COMMAND_TIMEOUT` | 500ms | `spring.data.redis.timeout` |

运行环境若直接覆盖 `SPRING_DATA_REDIS_TIMEOUT`，按 Spring 配置优先级生效。验收服务显式使用这两个 500ms 预算；不是把业务超时假装成 0。

这些是客户端配置预算，实际响应仍有调度、网络和序列化开销，不能承诺每次精确 500ms。若后续需要更高故障期吞吐，可以评估有界异步镜像或熔断；当前未实现这些机制，也没有把 Redis 当持久消息系统。

## 四、真实云端故障怎么做

脚本 `scripts/acceptance/redis-fault-check.py` 使用原有中央 HTTP 任务接口，只允许 `acceptance-instance`，由真实 Agent 执行独立 Docker 测试容器的 START/STOP。执行四组，每组两个任务：

1. 健康：任务完成后检查 Redis JSON 状态与 MySQL 终态一致、TTL 正常。
2. 暂停容器：TCP 可建立/保留，但 Redis 进程不能回复，验证命令超时。
3. 停止容器：验证依赖不可连接时降级。
4. 恢复容器：等待应用自己的 Lettuce 连接重新建立，再验证后续任务副本。

每个任务都通过 HTTP 接收、中央持久队列、Agent Inbox、Docker 和回执，不是只 mock `redis.opsForValue()`。每条均核对 task_events 与 task_event_outbox 数量一致，并检查最终没有未决实例锁。私有配置在 finally 恢复，测试 Redis 停止；不能在异常时直接清除 UNKNOWN 锁。

## 五、实际结果

| Redis 状态 | START 接收耗时 | STOP 接收耗时 | 任务结果 | 事实与副本 |
|---|---:|---:|---|---|
| 健康 | 1306.246ms | 37.833ms | 均 SUCCEEDED | 副本终态一致，TTL 119s |
| 暂停/命令超时 | 536.625ms | 533.486ms | 均 SUCCEEDED | MySQL 事件/Outbox 完整 |
| 停止/不可连接 | 529.812ms | 528.983ms | 均 SUCCEEDED | MySQL 事件/Outbox 完整 |
| 应用重连后 | 28.600ms | 30.238ms | 均 SUCCEEDED | 副本终态一致，TTL 119s |

第一条健康请求包含冷启动连接成本，不能删掉后说健康请求全部几十毫秒。八条正式任务各 5 个事件、5 条 Outbox，合计 40 个事件及 40 条待发事实。另有上次失败测试留下的测试容器准备清理任务，单独记录，不混入八条正式任务。

任务完成耗时约 3.7–14.4 秒，包含任务轮询和真实容器停止等待；不能将上述“接收耗时”说成 Docker 操作全程耗时。

完整结果：[Redis 故障复验](../验收证据/20261019/redis-fault.json)。

## 六、首次恢复测试为什么失败

首次测试在 Redis 服务 PING 成功后立即创建任务，期望任务热状态自动恢复，但未读到对应终态副本，验收明确失败；MySQL 任务和执行事实没有丢失。结果保留在 [初次失败记录](../验收证据/20261019/redis-initial-failure.json)。

Redis 进程恢复与应用客户端完成重连是不同时间点。日志确认 Lettuce 在断连后退避重连；本次复验用专用 client-name，从 Redis CLIENT LIST 确认**应用连接**已经恢复，再执行新任务，实测等待约 14502ms。

没有把“多等一会儿”冒充副本补偿功能：故障期间漏写的历史 key 不会自动补回；重连后的新状态写入可以恢复。当前接口本来就查 MySQL，缺少副本不改变任务查询正确性。如果以后接入缓存读取，必须另外设计过期、防旧写覆盖、回源与副本补偿，不能直接沿用现状宣称缓存一致性已完备。

## 七、关键变量与面试回答

- `prefix`：缓存命名空间，不参与数据库任务 ID 生成；测试使用独立 `argus:acceptance:`。
- `ttl`：副本有效期，过期不删除 MySQL 历史。
- `task`：本次已提交任务状态，JSON 序列化内容；不是 Redis 的锁对象。
- `acceptanceMs`：收到 HTTP 202 所需时间；`completionMs` 是任务达终态的总时间。
- `applicationReconnectWaitMs`：服务可用后等待应用连接恢复的时间，不是任务执行耗时。

被问“Redis 挂了怎么办”：先说事实保存 MySQL、客户端等待有界、当前接口查询不依赖 Redis，再给暂停/停机八条任务的实测；最后说明副本可能缺失、重连并非即时、没有自动补偿。不要回答“异常捕获所以完全无影响”。
