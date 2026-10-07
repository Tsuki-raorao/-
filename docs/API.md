# Argus 接口约定

更新时间：2026-09-30

除 Agent 接口外，控制中心响应统一使用：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

错误响应仍使用 `code` 和 `message`，具体 HTTP 状态以服务端处理结果为准。字段名称以 Java domain/VO 类和实际响应为最终依据。

## 控制中心

默认地址：`http://localhost:8080`

| 方法 | 路径 | 请求/参数 | 说明 |
|---|---|---|---|
| GET | `/api/health` | 无 | 服务健康状态 |
| GET | `/api/nodes` | 无 | 节点列表；包含 Agent 最近上报的 `cpuPercent`、`memoryBytes`、`memoryTotalBytes` |
| GET | `/api/nodes/{id}` | 路径 ID | 查询节点 |
| POST | `/api/nodes` | `name`、`address` | 创建节点 |
| POST | `/api/nodes/{id}/heartbeat` | 可选 `{"status":"ONLINE"}` | 更新心跳和节点状态 |
| DELETE | `/api/nodes/{id}` | 无 | 删除节点及其已采集实例；生产只读模式下返回 405 |
| GET | `/api/instances` | 无 | 实例列表；包含 `cpuPercent`、`memoryBytes`、`playerCount` 的最近快照，采集失败时资源值为 0 |
| GET | `/api/instances/{id}` | 路径 ID | 查询实例 |
| POST | `/api/instances/{id}/actions` | `{"action":"START|STOP|RESTART"}` | 记录并执行实例操作 |
| GET | `/api/tasks` | 无 | 任务列表 |
| GET | `/api/tasks/{id}` | 路径 ID | 查询任务 |
| GET | `/api/logs` | `instanceId` 可选，`limit` 默认 100 | 查询日志 |

当前控制中心的实例操作仍是服务层状态模拟，不直接调用远程 Docker。生产接入 Agent 时必须经过 Gateway/Adapter、权限、幂等、超时和审计链路。

### 控制中心 API 访问保护

默认开发配置不要求访问令牌。准备解除域名拦截前，应通过运行时环境变量开启：

```text
ARGUS_API_AUTH_REQUIRED=true
ARGUS_API_ACCESS_TOKEN=<只通过环境变量注入的令牌>
```

开启后，除 `GET /api/health` 外的控制中心 API 都必须携带
`Authorization: Bearer <token>`，缺失或错误令牌返回 HTTP 401。令牌不写入项目、前端构建产物、日志或备份；该机制是过渡保护，完整登录、RBAC、轮换和审计仍未完成。

## Agent

默认地址：`http://localhost:8090`

如果配置了 `ARGUS_AGENT_AUTH_TOKEN`，请求头必须包含：

```text
Authorization: Bearer <token>
```

控制中心读取 Agent 的内部网关默认关闭：`ARGUS_AGENT_GATEWAY_ENABLED=false`。
开启时仍保持只读，并要求 `ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS` 配置节点 Agent 主机白名单；
网关只提供下方三个 GET 路径，不提供通用代理和远程 Docker 操作：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent-gateway/nodes/{nodeId}/health` | 控制中心读取节点健康状态 |
| GET | `/api/agent-gateway/nodes/{nodeId}/instances` | 控制中心读取实例快照 |
| GET | `/api/agent-gateway/nodes/{nodeId}/instances/{instanceId}/logs?limit=100` | 控制中心读取实例日志 |

Agent 侧任务接口还受 `control.enabled=false` 保护；未显式开启时返回 `403 control_disabled`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent/health` | 节点存活、版本和主机 CPU/内存瞬时快照 |
| POST | `/api/agent/register` | 返回节点身份与能力 |
| POST | `/api/agent/heartbeat` | 更新/确认在线状态 |
| GET | `/api/agent/instances` | 实例状态快照 |
| POST | `/api/agent/tasks` | JSON：`{"instanceId":"mc01","action":"restart"}` |
| GET | `/api/agent/tasks/{taskId}` | 查询异步任务 |
| GET | `/api/agent/instances/{instanceId}/logs?limit=100` | 获取最近日志 |

## 状态值

- 节点：`ONLINE`、`OFFLINE`、`UNKNOWN`。
- 实例：`RUNNING`、`STOPPED`、`STARTING`、`STOPPING`、`UNKNOWN`。
- 任务：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。

## 安全要求

浏览器和 AI 不直接调用 Docker、SSH 或 shell。真实执行必须由 Agent 经过动作白名单、实例名校验、权限 scope、超时、审计和回滚策略后完成。任何示例 Token 只允许使用占位符，不能写入仓库。



## 本地访问示例

本地控制中心健康接口为 `http://localhost:8080/api/health`。生产入口由使用者在私有部署配置中设置，仓库不提供可直连的生产服务器地址。只读保护由运行时配置决定，不随访问域名或 IP 改变。

## Agent Prometheus 指标

每台 Agent 在同一个 8090 端口提供 `GET /metrics`。请求必须带 Agent Bearer 令牌，返回 Prometheus 0.0.4 文本格式的 Agent 存活、版本和主机 CPU/内存指标。该接口只读主机快照，不执行 Docker 命令；Prometheus 部署时应通过内网或安全组白名单采集，不经过公网 Nginx。
