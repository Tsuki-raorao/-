# Argus 控制中心后端

这是 Argus 的 Java 17 / Spring Boot 3 控制中心，采用传统的 `Controller -> Service -> Repository` 分层。业务数据通过 `JdbcTemplate` 持久化，Flyway 负责数据库迁移。

开发资料：

- [开发文档](docs/开发文档.md)
- [变量与接口文档](docs/变量与接口文档.md)
- [数据库表文档](docs/数据库表文档.md)

## 运行

需要 JDK 17 和 Maven 3.9+。默认使用本地 H2 文件数据库 `./data/argus`，首次启动由 Flyway 执行 V1 至 V3 迁移：

```bash
mvn spring-boot:run
```

服务默认在 `http://localhost:8080`。构建并运行打包产物：

```bash
mvn clean package
java -jar target/argus-control-center-0.1.0-SNAPSHOT.jar
```

## 数据库配置

迁移脚本位于 `src/main/resources/db/migration`，包含 `nodes`、`instances`、`tasks`、`logs` 表及查询索引。默认 `application.yml` 使用 H2 文件数据库，重启服务后数据仍保留。测试使用 H2 内存库和同一套 Flyway 迁移。

使用 MySQL 时，先创建 `argus` 数据库和账号，再以 `mysql` profile 启动：

```bash
java -jar target/argus-control-center-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

运行前通过环境变量设置 `MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`。默认主机是本机回环地址，密码没有默认真实值；配置见 `src/main/resources/application-mysql.yml`。生产凭据由部署环境提供，不写入仓库。

## 接口

所有响应格式为 `{ "code": 0, "message": "ok", "data": ... }`。

* `GET /api/health`
* `GET /api/nodes`、`GET /api/nodes/{id}`、`POST /api/nodes`
* `POST /api/nodes/{id}/heartbeat`（可选 body：`{"status":"ONLINE"}`）
* `GET /api/instances`、`GET /api/instances/{id}`
* `POST /api/instances/{id}/actions`，body：`{"action":"START|STOP|RESTART"}`
* `GET /api/tasks`、`GET /api/tasks/{id}`
* `GET /api/logs?instanceId=mc01&limit=100`

控制中心到 Agent 的只读查询接口默认关闭，开启前需要设置 `ARGUS_AGENT_GATEWAY_ENABLED=true` 和 Agent 主机白名单 `ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS`。接口、超时、认证和安全边界见 [开发文档](docs/开发文档.md) 与项目 [接口约定](../docs/API.md)。

默认本地 profile 在对应表为空时预置演示数据；MySQL profile 默认关闭演示数据并启用只读保护。CORS 默认仅允许本地 Vue 开发地址。任务接口目前只更新数据库中的模拟状态，尚未对接 Agent 的真实执行，不能作为生产控制功能使用。
