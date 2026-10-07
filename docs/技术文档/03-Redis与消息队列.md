# Redis 与消息队列

## 资料用途

本章用于支撑 Argus 的心跳高并发、异步任务、幂等、限流、重试和死信处理。Redis 与 RabbitMQ 是规划中的企业化能力，当前代码以数据库任务流和本地执行为基线。

## 官方资料

- [Redis Documentation](https://redis.io/docs/latest/)
- [Spring Data Redis Reference](https://docs.spring.io/spring-data/redis/reference/)
- [Redis Streams](https://redis.io/docs/latest/develop/data-types/streams/)
- [RabbitMQ Documentation](https://www.rabbitmq.com/docs)
- [Spring AMQP Reference](https://docs.spring.io/spring-amqp/reference/)

## Redis 在 Argus 中的边界

- 节点在线状态：`node:{id}:presence`，带 TTL，由 Agent 心跳续期。
- 热数据：节点摘要、短期指标、任务去重结果和 WebSocket 广播状态。
- 保护能力：限流、短锁、幂等键和短期重试计数。
- Redis 不作为任务、审计或日志的唯一存储；Redis 丢失后应能从 MySQL、Outbox 和消息队列恢复。

## RabbitMQ 事件约定

建议按用途划分 `task.commands`、`task.acks`、`task.results`、`task.retry` 和 `task.dead-letter`。消息应包含 `eventId`、`commandId`、`nodeId`、`adapter`、版本和时间戳。

生产配置需要持久化队列、发布确认、消费者 ACK、指数退避、死信队列和幂等消费。路由优先按节点、项目和任务类型划分，避免单一队列成为瓶颈。未来日志和高频遥测量明显增大时，再评估 Kafka/Redpanda；Redis Streams 只承担轻量补偿或边缘缓冲。

## 与 Argus 任务模型的关系

MySQL 保存任务状态机和审计事实，RabbitMQ 负责投递，Redis 负责短期协调。消费者在执行前写入 Inbox，完成后写入结果和 Outbox；重试必须携带相同的幂等键，避免重复执行危险动作。
