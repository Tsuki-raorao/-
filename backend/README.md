# Argus 控制中心后端

这是 Argus 的 Java 17 / Spring Boot 3 控制中心，采用传统的 `Controller -> Service -> Repository` 分层。业务数据通过 `JdbcTemplate` 持久化，Flyway 负责数据库迁移。

开发资料：

- [开发文档](docs/开发文档.md)
- [变量与接口文档](docs/变量与接口文档.md)
- [数据库表文档](docs/数据库表文档.md)

## 运行

需要 JDK 17 和 Maven 3.9+。默认使用本地 H2 文件数据库 `./data/argus`，首次启动由 Flyway 执行 V1 至 V6 迁移：

```bash
mvn spring-boot:run
```

服务默认在 `http://localhost:8080`。构建并运行打包产物：

```bash
mvn clean package
java -jar target/argus-control-center-0.1.0-SNAPSHOT.jar
```

## 数据库配置

V1–V3 SQL 位于 `src/main/resources/db/migration`，V4–V6 Java 迁移位于 `src/main/java/db/migration`。除了节点、实例、任务、日志，还有任务队列、实例互斥、人工核对意图/队列和追加事件表。默认使用 H2 文件数据库，重启后保留数据；测试使用隔离 H2/MySQL 和同一套 Flyway 迁移。V5 已正式发布，本轮只追加 V6，不能重写旧迁移。

使用 MySQL 时，先创建 `argus` 数据库和账号，再以 `mysql` profile 启动：

```bash
java -jar target/argus-control-center-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

运行前通过环境变量设置 `MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`，四项均为必填占位符；`MYSQL_PORT` 默认 `3306`。配置见 `src/main/resources/application-mysql.yml`，不要将占位符替换成真实值。

IDEA 中导入本目录的 `pom.xml`，使用 JDK 17，在 `Run → Edit Configurations` 创建 `Application` 或 `Spring Boot` 配置：启动类 `com.argus.controlcenter.ArgusControlCenterApplication`，模块 `argus-control-center`，工作目录为本目录。在环境变量中配置 `SPRING_PROFILES_ACTIVE=mysql` 和 `MYSQL_*`；不要勾选 `Store as project file`。详细步骤见 [数据库接入](../docs/数据库接入.md)。

本地与 Git 保持同一份源码，真实连接只保存在个人运行配置或部署环境。自动化测试使用 H2 内存库与本机 mock 服务，不复用 MySQL 运行配置；测试通过不能替代在独立测试库进行真实 MySQL 验证。

### Redis 与 RabbitMQ（面试版可选基础设施）

MySQL 仍然保存任务和事件事实。设置 `ARGUS_REDIS_ENABLED=true` 后，任务状态会写入带 TTL 的 Redis 热缓存，Redis 不可用时自动回源 MySQL；设置 `ARGUS_MQ_ENABLED=true` 后，事务内写入的 Outbox 由后台 worker 投递到 RabbitMQ，失败按租约和退避重试，消费者必须回查 MySQL 获取权威状态。连接参数使用 Spring Boot 的 `SPRING_DATA_REDIS_*` 与 `SPRING_RABBITMQ_*` 环境变量，默认均关闭，不影响 H2 本地启动。

面试联调可使用 `deploy/docker-compose.interview.yml` 启动 Redis 和 RabbitMQ；RabbitMQ 的本地账号通过命令行环境变量提供，不写入仓库。容器只绑定本机管理端口，不作为生产部署模板。

### 身份页面联调夹具

暂不接入真实注册时，可在 IDEA 的本地运行配置中增加 `ARGUS_IDENTITY_TEST_DATA_ENABLED=true`。启动后会在当前本地数据库补充 3 个固定测试身份和 2 个项目：测试管理员（`admin`）、测试操作员（`operator`）和测试查看者（`viewer`）。身份使用固定的测试 issuer/subject 供 mock OIDC 或接口测试映射，不生成密码、令牌或外部账号；重复启动不会覆盖已有记录。该开关默认关闭，MySQL 配置也默认关闭，禁止在生产环境打开。

夹具只负责数据库中的用户、项目和成员关系，不能替代真实 OIDC 登录。要在网页中切换这些身份，需要本地 mock 身份提供商或测试 JWT，其 issuer/subject 应分别映射到上述三组测试主体。

## 接口

所有响应格式为 `{ "code": 0, "message": "ok", "data": ... }`。

* `GET /api/health`
* `GET /api/nodes`、`GET /api/nodes/{id}`、`POST /api/nodes`
* `POST /api/nodes/{id}/heartbeat`（可选 body：`{"status":"ONLINE"}`）
* `GET /api/instances`、`GET /api/instances/{id}`
* `GET /api/instances/{id}/logs?limit=100`：中央 ID 自动定位正确节点，按需读取最近日志，非持续日志流
* `GET /api/control/capabilities`：当前令牌可操作的目标、动作与执行模式
* `POST /api/instances/{id}/actions`：操作 Bearer 令牌、UUID `Idempotency-Key`，body：`{"action":"START","expectedExecutionMode":"MOCK"}`；首次与同键重试均 HTTP 202
* `GET /api/tasks`（最近 100 条）、`GET /api/tasks/{id}`、`GET /api/tasks/{id}/events`
* `GET /api/tasks/{id}/review-capabilities`、`GET /api/tasks/{id}/review-evidence`：核对权限与确认前远端证据
* `POST /api/tasks/{id}/resolutions`：独立核对幂等键、理由/证据及两个风险确认；HTTP 202 只代表持久接收
* `GET /api/tasks/{id}/resolution`、`GET /api/task-resolutions/{id}`、`GET /api/task-resolutions/{id}/events`
* `GET /api/logs?instanceId=<控制中心实例ID>&limit=100`：数据库日志摘要

控制中心到 Agent 的只读查询接口默认关闭，开启前需要设置 `ARGUS_AGENT_GATEWAY_ENABLED=true` 和 Agent 主机白名单 `ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS`。接口、超时、认证和安全边界见 [开发文档](docs/开发文档.md) 与项目 [接口约定](../docs/API.md)。

默认本地 profile 在对应表为空时预置演示数据；MySQL profile 默认关闭演示数据并启用只读保护。CORS 默认仅允许本地 Vue 开发地址。新任务写入持久队列，通过 Agent 任务协议查询/投递，已经移除数据库模拟状态路径；旧任务标为 `LEGACY_MOCK`，不会下发。任务成功不修改实例观测状态。

控制默认关闭：`ARGUS_TASK_CONTROL_ENABLED=false`、`ARGUS_TASK_ALLOW_DOCKER=false`。仅在独立测试环境显式提供查看/操作令牌、Agent 读取/控制令牌、主机和中央实例允许列表，并打开旧网关控制 gate 后，才可接收新任务；生产仍维持只读。查看令牌不能写节点或创建任务。已接收任务通过固定 commandId 查询恢复，UNKNOWN 保留互斥、不自动重做。配置、有限恢复窗口和吞吐边界见开发文档，不宣称 Docker 副作用 exactly-once。

UNKNOWN 人工核对默认另行关闭：`ARGUS_TASK_REVIEW_ENABLED=false`、允许列表为空。操作者先读取原 Agent 记录和执行器证据，再提交理由、证据和两个明确确认。核对意图先入库，后台按原绑定查询/闭合 Agent 回执，最终才条件解除中央原锁；原 UNKNOWN 结果不变、原动作永不重放。BLOCKED 可同键同正文显式续办；APPLIED 仅指核对完成，不能当成原动作成功。详情见 [人工核对契约](../docs/UNKNOWN任务人工核对设计.md)。2026-10-09中央已完成V6只读部署及接口验证，全局只读保持开启；任务/Docker控制和人工核对保持关闭，没有操作令牌，不对业务容器执行动作。

实例使用中央 ID 与 `nodeId + agentInstanceId` 两种身份：已有 ID 和历史任务日志保持不变，新发现实例使用 UUID。采集响应明确区分真实零值、未支持的 `null`、`MOCK` 演示和 `LEGACY` 未验证数据。完整快照校验通过后整批入库，失败不会更新成功时间或用零覆盖旧指标。具体时间语义和迁移边界见上述开发文档。

2026-10-09 最新后端验证共84项，其中78项通过、6项按环境跳过、0失败、0错误；包含 V8 Outbox 迁移、人工核对集成和旧采集/任务兼容测试。V8 将任务事件与业务事务一起写入 MySQL，RabbitMQ 启用后由后台 worker 按租约和退避重试；Redis/MQ 默认关闭，测试不宣称真实消息容器往返已验收。完整记录见[本地验证](docs/开发文档.md#6-本地验证)，测试通过不等于已部署生产或真实Docker核对验收。

本轮上线前使用新鲜V5单库备份在隔离MySQL恢复并升级V6，保留全部旧业务行与ID；备份中的旧JAR在同一V6库实际只读启动通过，常规回退可恢复代码/网页并保留V6。这仅适用于本次新旧构建与只读配置，未来版本须重新验证。上线API验证确认原2节点8实例、空任务及核对表、两节点新采集与日志、原查看令牌和只读边界保持；浏览器验收与最终发布状态以[开发进度](../docs/开发进度.md)为准。
