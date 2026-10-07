# Java 与 Spring

## 资料用途

这一组文档支撑 Argus 控制中心的 Java 分层、HTTP API、数据库访问、认证、WebSocket 和消息消费者。

## 官方资料

### Java 17

- [JDK 17 文档](https://docs.oracle.com/en/java/javase/17/)
- [Java SE 17 API](https://docs.oracle.com/en/java/javase/17/docs/api/)

重点阅读：`java.net.http`、并发包、`java.time`、JDBC、线程池、JMX 和安全 API。Agent 当前使用 JDK 自带 HTTP Server；后续可以使用标准 HTTP Client 或 WebSocket API。

### Spring Boot

- [Spring Boot Reference](https://docs.spring.io/spring-boot/reference/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/)

当前工程锁定 Spring Boot 3.3.5。官方最新页面用于概念查阅，具体配置以工程 `pom.xml` 和对应版本的依赖行为为准。

### Spring JDBC

- [Spring Framework JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc.html)

Argus 使用 `JdbcTemplate`，SQL 和事务边界由项目代码负责，连接管理、异常转换和资源释放由 Spring JDBC 处理。任务状态迁移、Outbox 写入和版本检查必须放在明确的事务边界内。

### Spring Security

- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)

企业化阶段用于 OIDC/JWT、RBAC、项目级资源授权、CSRF/会话策略和常见攻击防护。Minecraft 的 `online-mode` 与 Argus 管理台认证是两套独立体系。

### WebSocket

- [Spring WebSocket Reference](https://docs.spring.io/spring-framework/reference/web/websocket.html)

浏览器使用 REST 发起操作，WebSocket 只推送任务、告警和日志事件。客户端断线后必须通过 REST 查询补偿，不能把 WebSocket 当作持久化事实源。

### Spring AMQP

- [Spring AMQP Reference](https://docs.spring.io/spring-amqp/reference/)
- [Sending Messages](https://docs.spring.io/spring-amqp/reference/amqp/sending-messages.html)

后续 RabbitMQ 接入使用发布确认、消费者 ACK、重试、死信队列和固定交换机/路由键。任务消息必须携带 `taskId`、`commandId`、`attempt` 和 `fenceToken`。
