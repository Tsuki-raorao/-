# Agent 与服务适配器

## 项目内资料

- [Agent 说明](../../agent/README.md)
- [接口说明](../API.md)
- [Argus 平台设计文档](../Argus平台设计文档.md)
- 服务器总览：由部署人员维护在外部受控运维档案中；公开仓库不包含实际节点清单或个人本地路径。

## 官方资料

- [Java SE 17 Documentation](https://docs.oracle.com/en/java/javase/17/)
- [Java SE 17 API](https://docs.oracle.com/en/java/javase/17/docs/api/)
- [Docker Engine](https://docs.docker.com/engine/)
- [NeoForge Documentation](https://docs.neoforged.net/)
- [NeoForge 1.21.1 Documentation](https://docs.neoforged.net/docs/1.21.1/)

Minecraft 服务端属性可参考 [Minecraft Wiki 的 server.properties 页面](https://minecraft.wiki/w/Server.properties)；该页面是社区资料，接入前仍需用目标 NeoForge 版本实机验证。

## Agent 职责

当前 Agent 使用 Java 17 JDK HttpServer，带有 Mock 执行器用于开发联调。生产版需要加入 mTLS、节点身份、断线重连、Inbox 去重、Spool 缓冲、审计和 Adapter Registry。Agent 只执行已经授权、可验证和可回滚的动作。

## Adapter 合同

每个 Adapter 至少实现：`discover`、`probeVersion`、`healthCheck`、`collectMetrics`、`collectLogs`、`validateAction` 和 `executeAction`。返回值应包含服务版本、能力、权限范围、指标单位、日志游标、错误码和执行证据。

Manifest 需要声明服务版本范围、发现规则、能力清单、权限 scope、动作参数 schema、超时与幂等策略、指标单位、日志游标和资源限制。优先实现 `docker-generic`、`minecraft-neoforge`、`mysql`、`redis`，再扩展 Nginx、Java 服务和 OpenWebUI。

遇到未知版本、未声明能力或权限不足时，Adapter 自动降级为只读探测，不猜测命令、不执行危险动作。
