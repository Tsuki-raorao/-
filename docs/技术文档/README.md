# Argus 技术文档资料库

更新时间：2026-09-30  
用途：开发查阅、架构评审和后续 RAG 知识库的受控资料入口

本目录不复制第三方网站的整套手册，而是保存官方来源、适用版本、与 Argus 的关系和本地归纳。使用时先看本索引，再打开对应官方文档。

## 当前项目版本基线

| 技术 | 当前工程/服务器版本 | 使用阶段 |
|---|---|---|
| Java | 17 | 已使用，控制中心和 Agent |
| Spring Boot | 3.3.5 | 已使用，控制中心 |
| Spring JDBC | 随 Spring Boot 管理 | 已使用，Repository 持久化 |
| Flyway | 10.20.1 | 已使用，数据库迁移 |
| MySQL | 8.0.27 | 已接入远程数据库 |
| H2 | Spring Boot 管理版本 | 本地开发和测试 |
| Vue / TypeScript / Vite | Vue 3.5.13 / TypeScript 5.7.2 / Vite 6.0.7 | 已使用，管理台；以 `frontend/package.json` 为准 |
| Docker | 节点服务器 Docker 29.x | Agent 执行和实例管理 |
| Redis | 尚未接入代码 | 企业化阶段 P2 |
| RabbitMQ | 尚未接入代码 | 企业化阶段 P2 |
| Prometheus / Grafana / Loki | 尚未接入代码 | 企业化阶段 P3 |
| LangChain4j / 模型 API | 尚未接入代码 | AI 最后阶段 |

## 资料目录

| 文件 | 内容 | 优先级 |
|---|---|---|
| [01-Java与Spring.md](01-Java与Spring.md) | Java 17、Spring Boot、JDBC、Security、WebSocket、Spring AMQP | P0 |
| [02-数据库与迁移.md](02-数据库与迁移.md) | MySQL 8.0、Flyway、H2、索引和备份 | P0 |
| [03-Redis与消息队列.md](03-Redis与消息队列.md) | Redis、Spring Data Redis、RabbitMQ、Spring AMQP | P1 |
| [04-Docker与可观测性.md](04-Docker与可观测性.md) | Docker、Prometheus、Grafana、Loki、OpenTelemetry | P1 |
| [05-Agent与服务适配器.md](05-Agent与服务适配器.md) | Agent 协议、Adapter、NeoForge、Docker 服务接入 | P1 |
| [06-AI与RAG.md](06-AI与RAG.md) | LangChain4j、工具调用、RAG 和 AI 安全边界 | P3 |
| [07-RAG资料处理规范.md](07-RAG资料处理规范.md) | 文档清洗、版本、元数据、切分和审核规则 | P1 |
| [08-AI工具与证据契约.md](08-AI工具与证据契约.md) | AI 工具边界、证据结构和只读/操作分层 | P2 |
| [sources.yaml](sources.yaml) | 官方来源、项目用途和适用阶段清单 | P0 |

## 使用规则

1. 生产实现优先参考与当前依赖版本匹配的官方文档；“latest”页面只用于理解概念，升级前必须重新验证版本。
2. 官方文档、项目代码、服务器实测和故障记录的可信度不同，RAG 入库必须记录来源和更新时间。
3. 不把密码、私钥、Token、完整原始日志和未验证聊天内容放入资料库。
4. 实时指标、实时日志和任务状态通过 Prometheus、Loki、MySQL 工具查询，不把每次运行数据做成静态知识片段。
5. 外部资料只保存链接、摘要和必要的短摘录；复制整篇内容前先确认许可证和分发范围。

资料状态：已完成首轮官方来源整理和项目归纳，最后审核日期为 2026-09-30。版本升级或服务器环境变化后，应重新核对 `sources.yaml` 和对应章节。
