# AI 与 RAG

## 官方资料

- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)
- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)

## Argus 中的 AI 边界

AI 的输入是用户问题、告警和经过授权的查询结果。AI 先检查用户、项目、节点和动作 scope，再通过受控工具读取指标、日志、配置变更、任务历史和 RAG 资料，生成带证据的诊断和 `ActionPlan`。涉及写操作时只生成计划，由用户或审批策略确认后交给 Task Orchestrator 执行，完成后再次采集结果并记录历史。

RAG 只用于相对稳定的资料：官方技术文档摘要、Adapter 文档、项目运行手册、已脱敏的事故案例和版本说明。实时 Prometheus、Loki、MySQL 查询仍走工具，不把实时状态伪装成静态知识。

## 分阶段落地

1. 只读问答：引用指标、日志和文档证据，禁止动作。
2. 方案生成：输出影响范围、风险、回滚步骤和验证条件。
3. 审批执行：用户确认后创建任务，Agent 通过 Adapter 执行并回传证据。
4. 复盘学习：保存脱敏结果和人工评价，更新运行手册与案例库。

AI 不直接获得 Docker socket、SSH、root 或任意 shell 权限；所有外部操作都必须经过现有授权和任务链路。
