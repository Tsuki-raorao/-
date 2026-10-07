# RAG 资料处理规范

## 可入库资料

- 带版本和来源的官方文档摘要、链接与变更记录。
- 经过评审的 Argus 设计文档、API、Adapter Manifest 和运行手册。
- 已脱敏的服务器画像、指标定义、错误码、操作步骤和事故复盘。
- 有明确适用版本、责任人、更新时间和可信度的 FAQ。

## 禁止入库资料

- 密码、私钥、Token、Cookie、数据库连接串和云厂商临时凭据。
- 未清洗的全量日志、玩家个人信息、IP 明细和高基数动态字段。
- 未核验的聊天内容、未经授权复制的第三方全文和无法确认版本的命令。

## 元数据模板

```yaml
source: official | project | server-observation | incident
title:
serviceType:
version:
environment:
updatedAt:
confidence: high | medium | low
sensitivity: public | internal | restricted
owner:
```

## 处理流程

采集后先确认来源、版本和授权，再脱敏；保留 Markdown 标题层级和代码块语义，按主题和版本做语义分块，同时保留关键词索引。写入向量库时附带来源、章节、版本、更新时间和访问级别；抽样验证引用准确性，过期版本进入归档，不直接删除可追溯记录。

## 检索规则

先按服务类型、版本、环境和访问级别过滤，再做关键词与向量混合检索。回答必须返回来源和适用版本；检索不到证据时明确说明未知，不用模型常识补写生产命令。
