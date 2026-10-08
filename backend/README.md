# Argus 控制中心后端

这是 Argus 的 Java 17 / Spring Boot 3 控制中心，采用传统的 `Controller -> Service -> Repository` 分层。业务数据通过 `JdbcTemplate` 持久化，Flyway 负责数据库迁移。

开发资料：

- [开发文档](docs/开发文档.md)
- [变量与接口文档](docs/变量与接口文档.md)
- [数据库表文档](docs/数据库表文档.md)

## 运行

需要 JDK 17 和 Maven 3.9+。默认使用本地 H2 文件数据库 `./data/argus`，首次启动由 Flyway 执行 V1 至 V5 迁移：

```bash
mvn spring-boot:run
```

服务默认在 `http://localhost:8080`。构建并运行打包产物：

```bash
mvn clean package
java -jar target/argus-control-center-0.1.0-SNAPSHOT.jar
```

## 数据库配置

V1–V3 SQL 位于 `src/main/resources/db/migration`，V4/V5 Java 迁移位于 `src/main/java/db/migration`。除了节点、实例、任务、日志，还有任务队列、实例互斥和追加事件表。默认使用 H2 文件数据库，重启后保留数据；测试使用隔离 H2/MySQL 和同一套 Flyway 迁移。

使用 MySQL 时，先创建 `argus` 数据库和账号，再以 `mysql` profile 启动：

```bash
java -jar target/argus-control-center-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

运行前通过环境变量设置 `MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`，四项均为必填占位符；`MYSQL_PORT` 默认 `3306`。配置见 `src/main/resources/application-mysql.yml`，不要将占位符替换成真实值。

IDEA 中导入本目录的 `pom.xml`，使用 JDK 17，在 `Run → Edit Configurations` 创建 `Application` 或 `Spring Boot` 配置：启动类 `com.argus.controlcenter.ArgusControlCenterApplication`，模块 `argus-control-center`，工作目录为本目录。在环境变量中配置 `SPRING_PROFILES_ACTIVE=mysql` 和 `MYSQL_*`；不要勾选 `Store as project file`。详细步骤见 [数据库接入](../docs/数据库接入.md)。

本地与 Git 保持同一份源码，真实连接只保存在个人运行配置或部署环境。自动化测试使用 H2 内存库与本机 mock 服务，不复用 MySQL 运行配置；测试通过不能替代在独立测试库进行真实 MySQL 验证。

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
* `GET /api/logs?instanceId=<控制中心实例ID>&limit=100`：数据库日志摘要

控制中心到 Agent 的只读查询接口默认关闭，开启前需要设置 `ARGUS_AGENT_GATEWAY_ENABLED=true` 和 Agent 主机白名单 `ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS`。接口、超时、认证和安全边界见 [开发文档](docs/开发文档.md) 与项目 [接口约定](../docs/API.md)。

默认本地 profile 在对应表为空时预置演示数据；MySQL profile 默认关闭演示数据并启用只读保护。CORS 默认仅允许本地 Vue 开发地址。新任务写入持久队列，通过 Agent 任务协议查询/投递，已经移除数据库模拟状态路径；旧任务标为 `LEGACY_MOCK`，不会下发。任务成功不修改实例观测状态。

控制默认关闭：`ARGUS_TASK_CONTROL_ENABLED=false`、`ARGUS_TASK_ALLOW_DOCKER=false`。仅在独立测试环境显式提供查看/操作令牌、Agent 读取/控制令牌、主机和中央实例允许列表，并打开旧网关控制 gate 后，才可接收新任务；生产仍维持只读。查看令牌不能写节点或创建任务。已接收任务通过固定 commandId 查询恢复，UNKNOWN 保留互斥、不自动重做。配置、有限恢复窗口和吞吐边界见开发文档，不宣称 Docker 副作用 exactly-once。

实例使用中央 ID 与 `nodeId + agentInstanceId` 两种身份：已有 ID 和历史任务日志保持不变，新发现实例使用 UUID。采集响应明确区分真实零值、未支持的 `null`、`MOCK` 演示和 `LEGACY` 未验证数据。完整快照校验通过后整批入库，失败不会更新成功时间或用零覆盖旧指标。具体时间语义和迁移边界见上述开发文档。

2026-10-08 可靠任务版本覆盖 54 项唯一用例，均分批实际验证通过；包括 19 项任务集成、3 项开关策略、H2 与真实隔离 MySQL V4→V5 迁移，以及原采集/日志回归。准确计数和分次运行记录见 [本地验证](docs/开发文档.md#6-本地验证)；本机模拟执行通过不等于生产或真实 Docker 控制验收。
