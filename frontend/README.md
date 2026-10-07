# Argus 控制台

Vue 3 + Vite + TypeScript 实现的 Argus 管理控制台，提供概览、节点、实例、任务和日志五个页面。当前日志查询读取后端数据库，尚未接通实时容器日志；任务执行尚未形成真实控制闭环。

## 本地运行

```bash
npm install
npm run dev
```

生产构建：`npm run build`。

Vite 开发服务器默认将 `/api` 代理到 `http://localhost:8080`。接口路径遵循项目 `docs/API.md`：`GET /api/nodes`、`GET /api/instances`、`POST /api/instances/{instanceId}/actions`、`GET /api/tasks`、`GET /api/logs?instanceId=...&limit=...`。

后端接口返回 `{ code, message, data }` 时前端应读取 `data`；开发阶段后端不可用时，页面自动切换到内置 mock 数据，保证可以独立预览。状态值兼容后端的大写枚举（如 `ONLINE`、`RUNNING`、`SUCCEEDED`）。

页面使用 hash 路由（例如 `/#/nodes`），因此直接刷新页面不需要额外配置服务端 history fallback。控制台默认每 15 秒刷新一次数据，日志页可用“自动刷新”复选框关闭轮询；轮询刷新不等于已接通实时日志。

详细的接口字段、页面状态、构建检查和常见问题见 [前端开发说明](../docs/前端开发说明.md)。
