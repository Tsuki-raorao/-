# 未序 Argus

未序 Argus 是面向个人和小团队的多节点服务器与容器管理平台原型。**2026-10-09，中央、网页与两台 Agent 已只读上线，生产数据库 V3→V5 一次成功并保留旧 ID。80/9090 的 IP 入口、认证、双节点共 8 个当前 Docker 实例及两边最近日志均已核验；此前跨机超时在云端规则调整后解决。可靠任务已有实现与隔离验证，生产业务控制仍关闭；完整权限审计、UNKNOWN 人工核对与 AI 尚未完成。** 先看 [项目说明与进度汇报](docs/项目说明与进度汇报.md)，再按需要阅读接口和设计。

## 当前能力

- Vue 管理台提供概览、节点、实例、任务和日志页面，支持 API 令牌输入与只读状态展示。
- Spring Boot 控制中心提供 REST API，使用 JdbcTemplate 与 Flyway 管理节点、实例、任务和日志数据。
- V4 迁移保留已有中央实例 ID 和任务/日志引用，新增节点内实例标识；不同节点的同名容器不会共用身份。
- V5 新增持久队列、实例互斥和任务事件；固定命令编号与浏览器幂等键区分一次操作和重复传输，旧任务标为 `LEGACY_MOCK`，不会自动下发。
- Java Agent 支持主机 CPU/内存快照、Docker 容器发现与状态/资源读取、日志查询及受令牌保护的 `/metrics`。
- 来源、采集时间、可空指标和同步结果明确区分真实零值、模拟数据、失败与旧快照；网页通过中央实例 ID 读取对应 Agent 的最近 100 行日志。
- 开启只读网关后，控制中心默认每轮完成后等待 60 秒再拉取 Agent 快照；当前不是 Agent 主动心跳架构。
- 默认本地配置使用 H2 与 mock Agent；生产配置模板保持只读与远程动作关闭。

**状态边界：**任务提交 HTTP 202 只表示接收，状态与实例观测分别记录。任务区分真实 Docker、Agent 模拟和历史数据库模拟；UNKNOWN 停止重放并保留实例互斥，人工核对与解除流程尚未提供。操作令牌和目标允许列表是基础保护，不是完整 RBAC。网页日志是按需或轮询读取，尚无持续日志流/历史检索平台；玩家数和 TPS 尚无业务采集器。

## 技术栈与目录

| 目录 | 内容 |
|---|---|
| `backend/` | Java 17、Spring Boot 3.3.5、JdbcTemplate、Flyway 10.20.1、H2/MySQL，V1–V5 |
| `agent/` | Java 17 自带 HttpServer，Docker/mock 执行器、持久 Inbox |
| `frontend/` | Vue 3.5.43、TypeScript 5.7.3、Vite 6.4.3（lockfile 版本） |
| `deploy/` | Nginx、systemd、数据库和实例备份模板 |
| `scripts/` | 本地启动、检查及项目备份脚本 |
| `relay/` | 独立协作聊天室：用户、Codex、Dot 的消息 API、网页与客户端 |
| `docs/` | 进度、交接、接口、设计与技术资料索引 |

`frontend/public/logo.png` 为银白色“未序”横版 Logo，`frontend/public/weixu-niang.png` 为“未序娘”头像。角色形象目前仅用于展示，不代表已接入模型或自动运维能力。

## 本地启动

准备 JDK 17、Maven 3.9+、Node.js 与 npm。本次验证使用 Node.js 22.23.1 和 npm 10.9.8。以下命令在仓库根目录分别打开终端执行，默认不会调用真实 Docker：

```powershell
.\scripts\start-backend.ps1
```

```powershell
.\scripts\start-agent.ps1
```

```powershell
.\scripts\start-frontend.ps1
```

本地控制台为 `http://localhost:5173`，后端为 `http://localhost:8080`，Agent 为 `http://localhost:8090`。开发和生产均不把 API 错误替换为演示成功；后端演示结果明确标为 `MOCK`。

控制中心默认使用 H2 文件库并初始化演示数据，Agent 网关默认关闭。独立启动 Agent 不等于已经接通采集链路；启用网关前需配置节点、目标白名单和令牌，详见 [交接文档](docs/交接文档.md)。

## 验证

```powershell
mvn -f backend/pom.xml test
.\scripts\test-agent.ps1
npm --prefix frontend test
npm --prefix frontend run build
```

2026-10-09 验证：Agent **48 项 Java/2 项 JSON**、后端 **57 项一次全通过、0 跳过**（含 5 项真实 MySQL 专项），修复版实际整链路 **28 项再次通过**。独立真实容器的启停、运行中重启、去重与持久结果查询通过；另有 **12 项代理/鉴权断言与原生 Nginx 语法检查通过**。停写备份取回校验、隔离恢复升级复验后完成正式切换：双节点采集协议 1.1、每节点 4 个当前 DOCKER 实例、两边日志身份、80/9090 静态资源与 401/查看令牌 200 均通过；任务队列为空、控制关闭。线上双入口浏览器 1440px/390px 五页无横向溢出、0 页面异常/写请求；聊天入口、ACME 路径及业务容器保持不变。

2026-10-08 历史可靠任务基线：Agent **44 项 Java 检查及独立 JSON 解析通过**；后端 **54 个唯一用例分批实际通过**（含独立启用的 MySQL 专项，不能把跳过当通过或累加重复运行）；前端 **32 项行为测试、类型检查/构建和 Edge 13 类任务交互通过**。实际跨模块 **28 项断言**使用生产构建前端与 Edge、打包后端、双 Java mock Agent 和隔离 MySQL。该阶段未访问真实 Docker 或远程环境，不能用其结论概括 10 月 9 日的现场验证。更早只读链路的 29 项后端与 22 项跨模块断言另记为历史基线。最新迁移和上线状态见 [开发进度](docs/开发进度.md)。

## 文档导航

- 统一阅读入口：[项目说明与进度汇报](docs/项目说明与进度汇报.md)，包含项目职责、实际能力、验证范围和服务器 Agent 后续开发顺序。
- 协作辅助：[中转聊天室](relay/README.md)。由另一会话维护，当前功能以 `relay/README.md` 为准，不计入业务 Agent 成果。
- 当前交付：[开发进度](docs/开发进度.md)、[问题记录](docs/问题记录.md)、[验收清单](docs/验收清单.md)、[交接文档](docs/交接文档.md)
- 开发与运行：[接口约定](docs/API.md)、[本地运行](docs/本地运行.md)、[数据库接入](docs/数据库接入.md)、[前端开发说明](docs/前端开发说明.md)、[Agent 说明](agent/README.md)
- 可靠任务：[冻结接口与恢复约定](docs/可靠任务接口与恢复约定.md)、[Agent Inbox](agent/docs/持久任务Inbox.md)
- 部署：[部署角色说明](docs/部署角色说明.md)、[部署与备份方案](docs/部署与备份方案.md)、[部署模板](deploy/README.md)、[备份恢复操作手册](docs/备份恢复操作手册.md)
- 后端：[开发文档](backend/docs/开发文档.md)、[变量与接口](backend/docs/变量与接口文档.md)、[数据库表](backend/docs/数据库表文档.md)
- 设计与后续计划：[平台设计](docs/Argus平台设计文档.md)、[企业级架构重设计](docs/Argus企业级架构重设计.md)、[数据模型与任务可靠性](docs/Argus数据模型与任务可靠性.md)、[迁移计划](docs/Argus从MVP到生产迁移计划.md)、[技术资料索引](docs/技术文档/README.md)

设计文档描述目标架构；是否已实现以进度与验收文档为准。当前可靠投递使用数据库队列和本地 Inbox，不依赖 Redis/RabbitMQ；这些组件、完整 RBAC/审计、Prometheus/Grafana/Loki 与 AI/RAG 仍属于后续工作。

## 配置与隐私

仓库只提供源码、静态品牌素材、通用文档和配置模板。真实服务器清单、域名、账户、密钥、数据库文件、日志与备份不属于公开交付内容。模板中的 `localhost` 仅供本地运行，`example.com` 与 TEST-NET 地址均为示例，不对应运行中的环境。

数据库密码、API 访问令牌与 Agent 令牌通过环境变量或仓库外受限配置注入。提交前应检查暂存区与 Git 历史；忽略规则不能清除已经提交的敏感文件。

公开文件与示例值约定见 [公开仓库说明](docs/公开仓库说明.md)。
