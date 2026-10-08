# Argus 控制台

Vue 3 + TypeScript + Vite 管理台，提供概览、节点、实例、任务记录和 Agent 最近日志五个页面。页面显示采集来源、时间、缺失指标与旧快照；真实容器任务执行仍未接通。

```powershell
npm install
npm test
npm run build
npm run dev
```

开发服务器默认将 `/api` 代理到 `http://localhost:8080`。前端解包 `{ code, message, data }`；开发与生产请求失败都不会自动切换成演示成功。后端返回的 `MOCK` 数据会明确标注为模拟。

页面使用 hash 路由，默认每 15 秒刷新；快照超过 180 秒标为旧快照。日志页按中央实例 ID 调用 `GET /api/instances/{controlId}/logs?limit=100`，选择器区分同名容器的所属节点。日志是按需/轮询读取，未使用 WebSocket，也不混用旧数据库日志接口。

详见 [前端开发说明](../docs/前端开发说明.md)；其中记录字段、访问保护、行为测试和当前限制。
