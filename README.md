# 未序 Argus

未序 Argus 是一个服务器与容器管理平台原型。目前完成了**基础只读 MVP**：Web 管理台、Java 控制中心、H2/MySQL 持久化，以及可配置的 Agent 状态与资源快照采集。真实远程控制、网页实时日志、完整用户权限、分布式任务和 AI 尚未形成可交付闭环。

## 当前能力

- Vue 管理台提供概览、节点、实例、任务和日志页面，支持 API 令牌输入与只读状态展示。
- Spring Boot 控制中心提供 REST API，使用 JdbcTemplate 与 Flyway 管理节点、实例、任务和日志数据。
- Java Agent 支持主机 CPU/内存快照、Docker 容器发现与状态/资源读取、日志查询及受令牌保护的 `/metrics`。
- 开启只读网关后，控制中心默认每轮完成后等待 60 秒再拉取 Agent 快照；当前不是 Agent 主动心跳架构。
- 默认本地配置使用 H2 与 mock Agent；生产配置模板保持只读与远程动作关闭。

**状态边界：**控制中心的任务接口目前只更新数据库状态，不会向 Agent 下发容器操作；网页日志页面读取 `logs` 表，尚未连接 Agent 的实时日志链路。玩家数和 TPS 尚无真实采集，界面中的默认值不能作为监测结果。

## 技术栈与目录

| 目录 | 内容 |
|---|---|
| `backend/` | Java 17、Spring Boot 3.3、JdbcTemplate、Flyway、H2/MySQL |
| `agent/` | Java 17 自带 HttpServer，Docker 命令适配、mock 执行器 |
| `frontend/` | Vue 3、TypeScript、Vite |
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

本地控制台为 `http://localhost:5173`，后端为 `http://localhost:8080`，Agent 为 `http://localhost:8090`。开发模式允许读取演示数据；生产构建不会用演示数据掩盖 API 不可用或授权失败。

控制中心默认使用 H2 文件库并初始化演示数据，Agent 网关默认关闭。独立启动 Agent 不等于已经接通采集链路；启用网关前需配置节点、目标白名单和令牌，详见 [交接文档](docs/交接文档.md)。

## 验证

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend ci
npm --prefix frontend run build
```

Agent 编译与本地探活步骤见 [交接文档](docs/交接文档.md)。这些检查验证当前模块，不代表真实远程控制、高并发或生产安全已经验收。

## 文档导航

- 协作辅助：[中转聊天室](relay/README.md)。它独立保存消息，不接入业务数据库，也不会自动唤醒助手或执行任务。
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
