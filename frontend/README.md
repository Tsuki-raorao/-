# Argus 控制台

Vue 3 + TypeScript + Vite 管理台，提供概览、节点、实例、任务和 Agent 最近日志五个页面。页面显示采集来源、时间、缺失指标与旧快照，已接入受控任务的确认、排队、详情与事件接口；默认关闭控制，本轮仅本机测试，未验证真实 Docker 执行或发布到服务器。

```powershell
npm install
npm test
npm run build
npm run dev
```

开发服务器默认将 `/api` 代理到 `http://localhost:8080`。前端解包 `{ code, message, data }`；开发与生产请求失败都不会自动切换成演示成功。后端返回的 `MOCK` 数据会明确标注为模拟。

页面使用 hash 路由，默认每 15 秒刷新；快照超过 180 秒标为旧快照。日志页按中央实例 ID 调用 `GET /api/instances/{controlId}/logs?limit=100`，选择器区分同名容器的所属节点。日志是按需/轮询读取，未使用 WebSocket，也不混用旧数据库日志接口。

同一个 Bearer 输入框支持查看或操作令牌，按钮同时受只读开关和 `/api/control/capabilities` 授权限制。确认操作使用 UUID 幂等键，HTTP 202 仅表示接收；网络结果不明时在当前浏览器会话保留原键供核对，不自动重发或创建新命令。任务明确区分真实 Docker、Agent 模拟、历史数据库模拟和未知来源；UNKNOWN 不提供重新执行，任务结果不会直接改写实例采集状态。

2026-10-08：32 项行为测试、类型检查和生产构建通过，Edge 13 类任务交互检查通过。浏览器使用本机隔离接口，已检查 1366px / 390px 布局；完整用户 RBAC、WebSocket、AI 未实现。

详见 [前端开发说明](../docs/前端开发说明.md)；其中记录字段、访问保护、行为测试和当前限制。
