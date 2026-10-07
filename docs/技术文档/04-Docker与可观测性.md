# Docker 与可观测性

## 官方资料

- [Docker Engine](https://docs.docker.com/engine/)
- [Docker Engine Security](https://docs.docker.com/engine/security/)
- [Docker Compose](https://docs.docker.com/compose/)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Prometheus Configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [Grafana Documentation](https://grafana.com/docs/grafana/latest/)
- [Loki Documentation](https://grafana.com/docs/loki/latest/)
- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)

## Docker 权限边界

只有 Agent 可以执行受控的容器操作。浏览器和 AI 不直接接触 Docker socket；所有动作都要经过 Adapter 白名单、参数校验、超时、审计和权限范围检查。禁止任意 shell、路径穿越、未审计的 `docker exec`、任意挂载和把宿主机敏感目录暴露给容器。

## Adapter 分层

`docker-generic` 负责发现容器、生命周期、资源状态和标准输出；Minecraft、MySQL、Redis 等服务使用专用 Adapter 补充版本、健康检查、配置和业务指标。这样既保留通用能力，也避免把所有服务都当成普通容器处理。

## 指标、日志和告警

- Prometheus 采集宿主机、容器、Agent、后端和 Minecraft 指标。
- 标签只使用低基数维度，例如 `node_id`、`service_type`、`environment`；玩家名、任务 ID、请求 ID 和日志正文不得作为标签。
- Grafana 提供节点、服务、任务和容量看板；Alertmanager 负责通知，告警触发与恢复事件写入 MySQL，原始时间序列留在 Prometheus。
- Loki 接收结构化日志。Agent 应分块、压缩、批量发送，并实现有界缓存、背压、保留期和敏感字段清理。

## 部署约束

开发环境可用 Docker Compose 启动依赖；生产环境把监控数据、日志数据和业务数据库分开规划，明确保留期、备份和恢复演练。指标采集失败不应阻断任务执行，但应产生可观测告警。
