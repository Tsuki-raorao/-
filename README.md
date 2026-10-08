# 未序 Argus

未序 Argus 是面向个人和小团队的多节点服务器与容器管理平台原型。**基础只读 MVP 已具备；2026-10-08 已实现多节点实例身份、可信采集展示和网页 Agent 最近日志查询，并通过模块与本机跨模块验证。本轮尚未部署，完整非 AI 远程执行与权限审计闭环仍未完成。** 先看 [项目说明与进度汇报](docs/项目说明与进度汇报.md)，再按需要阅读接口和设计。

## 当前能力

- Vue 管理台提供概览、节点、实例、任务和日志页面，支持 API 令牌输入与只读状态展示。
- Spring Boot 控制中心提供 REST API，使用 JdbcTemplate 与 Flyway 管理节点、实例、任务和日志数据。
- V4 迁移保留已有中央实例 ID 和任务/日志引用，新增节点内实例标识；不同节点的同名容器不会共用身份。
- Java Agent 支持主机 CPU/内存快照、Docker 容器发现与状态/资源读取、日志查询及受令牌保护的 `/metrics`。
- 来源、采集时间、可空指标和同步结果明确区分真实零值、模拟数据、失败与旧快照；网页通过中央实例 ID 读取对应 Agent 的最近 100 行日志。
- 开启只读网关后，控制中心默认每轮完成后等待 60 秒再拉取 Agent 快照；当前不是 Agent 主动心跳架构。
- 默认本地配置使用 H2 与 mock Agent；生产配置模板保持只读与远程动作关闭。

**状态边界：**控制中心任务接口目前只更新数据库中的模拟状态，不会向 Agent 下发容器操作；Agent 任务仍在内存中。网页日志是按需或轮询读取，尚无持续日志流/历史检索平台；旧 `/api/logs` 保留为数据库日志接口。玩家数和 TPS 尚无业务采集器，缺失值不再展示为默认正常值。

## 技术栈与目录

| 目录 | 内容 |
|---|---|
| `backend/` | Java 17、Spring Boot 3.3.5、JdbcTemplate、Flyway 10.20.1、H2/MySQL，V1–V4 |
| `agent/` | Java 17 自带 HttpServer，Docker 命令适配、mock 执行器 |
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

2026-10-08 已验证：Agent 23 项 Java 检查及独立 JSON 解析通过；后端 29 项均实际执行通过（分两次运行：H2/mock 28 项通过、1 项 MySQL 跳过；随后隔离 MySQL 8.0.27 专项 1/1 通过）；前端 17 项行为测试、类型检查/构建和 Edge 14 类场景通过。普通后端测试不配置专用 MySQL 测试变量时会跳过该项，不能当作完整 MySQL 验证。最终版跨模块 22 项断言也已通过，使用两个实际 Java Agent mock 进程、打包后端和本机隔离 MySQL；详情见 [开发进度](docs/开发进度.md)。本轮未连接真实 Docker、远程服务器或修改生产环境。

## 文档导航

- 统一阅读入口：[项目说明与进度汇报](docs/项目说明与进度汇报.md)，包含项目职责、实际能力、验证范围和服务器 Agent 后续开发顺序。
- 协作辅助：[中转聊天室](relay/README.md)。由另一会话维护，当前功能以 `relay/README.md` 为准，不计入业务 Agent 成果。
- 当前交付：[开发进度](docs/开发进度.md)、[问题记录](docs/问题记录.md)、[验收清单](docs/验收清单.md)、[交接文档](docs/交接文档.md)
- 开发与运行：[接口约定](docs/API.md)、[本地运行](docs/本地运行.md)、[数据库接入](docs/数据库接入.md)、[前端开发说明](docs/前端开发说明.md)、[Agent 说明](agent/README.md)
- 部署：[部署角色说明](docs/部署角色说明.md)、[部署与备份方案](docs/部署与备份方案.md)、[部署模板](deploy/README.md)、[备份恢复操作手册](docs/备份恢复操作手册.md)
- 后端：[开发文档](backend/docs/开发文档.md)、[变量与接口](backend/docs/变量与接口文档.md)、[数据库表](backend/docs/数据库表文档.md)
- 设计与后续计划：[平台设计](docs/Argus平台设计文档.md)、[企业级架构重设计](docs/Argus企业级架构重设计.md)、[数据模型与任务可靠性](docs/Argus数据模型与任务可靠性.md)、[迁移计划](docs/Argus从MVP到生产迁移计划.md)、[技术资料索引](docs/技术文档/README.md)

设计文档描述目标架构；是否已实现以进度与验收文档为准。Redis、消息队列、完整 RBAC/审计、Prometheus/Grafana/Loki、AI/RAG 均属于后续工作。

## 配置与隐私

仓库只提供源码、静态品牌素材、通用文档和配置模板。真实服务器清单、域名、账户、密钥、数据库文件、日志与备份不属于公开交付内容。模板中的 `localhost` 仅供本地运行，`example.com` 与 TEST-NET 地址均为示例，不对应运行中的环境。

数据库密码、API 访问令牌与 Agent 令牌通过环境变量或仓库外受限配置注入。提交前应检查暂存区与 Git 历史；忽略规则不能清除已经提交的敏感文件。

公开文件与示例值约定见 [公开仓库说明](docs/公开仓库说明.md)。
