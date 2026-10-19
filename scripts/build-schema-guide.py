"""Generate the readable schema dictionary from the checked metadata snapshot.
All descriptions are explicit; missing descriptions fail rather than inventing text.
"""
import json
from pathlib import Path
from collections import defaultdict

root = Path(__file__).resolve().parents[1]
source = root / "docs/实现详解/数据库结构实录.json"
data = json.loads(source.read_text(encoding="utf-8-sig"))
purposes = {
    "nodes": "中央登记的服务器及最近主机快照，不把每次采样都插入历史行。",
    "instances": "节点上的服务实例目录；中央 ID 与 Agent 局部 ID 分离，避免同名容器串数据。",
    "tasks": "一次用户操作的业务事实、状态和固定远程绑定，不能用队列记录替代。",
    "task_queue": "任务何时可以调度及当前 Worker 租约；执行事实仍在 tasks。",
    "instance_task_locks": "同一实例的未决任务互斥；原任务未核实前阻止新动作。",
    "task_events": "追加式任务状态轨迹，每个任务内序号唯一。",
    "logs": "早期数据库日志摘要；当前 Agent 最近日志走网关按需读取，不代表持续日志全文平台。",
    "task_resolutions": "UNKNOWN 人工核对意图、证据绑定、独立回执；不改写原任务历史。",
    "task_resolution_queue": "人工核对的独立调度租约，核对不是重放原 Docker 动作。",
    "task_resolution_events": "人工核对流程的追加状态轨迹，与原任务事件分开。",
    "users": "身份提供方主体到平台用户的映射；完整注册登录按需求后做。",
    "projects": "资源与权限范围，避免不同项目混读。",
    "project_members": "用户在项目中的角色和版本，用复合主键防重复成员。",
    "identity_config_gate": "固定记录身份模式切换，防 OIDC 启用后静默退回共享令牌。",
    "resolution_authorizations": "BLOCKED 核对的重新授权事实，不修改原核对意图和请求哈希。",
    "task_dispatch_permits": "一次任务投递前的后台授权复核记录，防撤权后仍使用旧授权。",
    "review_dispatch_permits": "人工核对投递前的授权复核记录。",
    "task_event_outbox": "业务事务内写入的待发布通知，MQ 失败后可以继续重试。",
    "task_event_receipts": "已核实并持久消费的通知收件记录，以稳定事件标识去重。",
    "ai_diagnoses": "AI 只读诊断结果与证据索引，不保存模型密钥；AI 模块由另一会话负责。",
    "flyway_schema_history": "Flyway 自己维护的迁移历史，不手工修改来绕过校验。",
    "monitor_alerts": "每个项目/规则/资源的一条当前告警状态，未知采样不自动解除活动告警。",
    "monitor_alert_events": "仅在触发和恢复时追加记录，重复异常采样不刷屏。",
    "monitor_samples": "按项目隔离的有限保留历史指标；NULL 表示不可确认，查询最多返回 500 点。",
}
meanings = {
    "id":"本表记录主键；跨表引用保持不变", "name":"可读名称，不作为唯一资源身份",
    "address":"登记的 Agent 访问入口，调用仍需白名单验证", "status":"本表流程/对象状态，状态集合按领域枚举解释",
    "last_heartbeat":"最近一次有效健康响应时间，失败不推进", "node_id":"中央节点 ID",
    "container_name":"容器名称；只在对应节点内解释", "version":"业务服务/迁移版本，具体取决于表",
    "updated_at":"该记录最后一次更新的时间", "cpu_percent":"CPU 使用率百分比；缺失为 NULL，不能伪造 0",
    "memory_bytes":"已用内存，单位 byte", "memory_total_bytes":"主机总内存，单位 byte",
    "player_count":"玩家人数；未采集时 NULL，不当成 0 人", "agent_instance_id":"Agent 在此节点内报告的实例标识",
    "last_seen_at":"实例最近一次在完整清单中被发现的时间", "data_source":"来源：HOST/DOCKER/MOCK/LEGACY",
    "sampled_at":"Agent 实际采样时间，区别于请求/展示时间", "metrics_status":"指标可用程度：AVAILABLE/PARTIAL/UNAVAILABLE/UNKNOWN",
    "project_id":"所属项目的资源与权限范围", "last_checked_at":"中央最近一次尝试同步时间，失败也更新",
    "last_successful_sync_at":"最近完整同步并成功持久化的时间", "sync_status":"同步结果：UNKNOWN/OK/PARTIAL/FAILED",
    "sync_error_code":"最近同步失败的稳定机器码", "instance_id":"中央实例 ID，不直接等同容器名",
    "action":"受允许列表限制的动作，如 start/stop/restart", "message":"业务说明或日志内容，不保存访问凭据",
    "created_at":"记录创建时间", "finished_at":"任务结束时间；未结束可空", "command_id":"固定远程命令编号，重试不更换",
    "idempotency_key":"客户端一次操作的幂等键", "execution_mode":"执行来源：DOCKER/MOCK/LEGACY_MOCK 等",
    "requested_by":"原始发起角色摘要，具体主体另有 actor_user_id", "attempts":"尝试/领取次数，不代表 Docker 成功执行次数",
    "result_code":"稳定业务结果码", "target_address":"任务接收时固化的目标地址，防后续节点编辑漂移",
    "agent_node_id":"预期 Agent 身份，防错误目标响应", "store_id":"Agent Inbox 的存储身份，换库后不能冒用旧执行历史",
    "expires_at":"命令有效期，执行前必须复查", "version_no":"记录状态版本，CAS 更新和并发保护使用",
    "agent_accepted":"是否已有 Agent 接纳证据；超时处理需区分是否可能已执行",
    "actor_user_id":"数据库主体 ID，用于授权与审计", "auth_mode":"本次接收时认证通道：LEGACY_TOKEN/OIDC_SESSION/JWT",
    "task_id":"中央任务 ID", "next_run_at":"队列下次可调度时间", "lease_until":"当前领取租约到期时间",
    "lease_owner":"本次 Worker 归属标识，旧 Worker 不能覆盖新持有者", "event_sequence":"本任务/核对内递增的事件序号",
    "from_status":"此条事件前一状态；初始事件可空", "to_status":"此条事件目标状态", "actor":"产生状态变化的角色摘要",
    "reason":"变化原因或稳定结果码", "occurred_at":"原业务事件发生时间，重投不改变",
    "resolution_id":"独立人工核对 ID", "request_key":"人工核对/重新授权幂等键", "binding_json":"固化的原任务、节点、命令和存储身份绑定",
    "intent_json":"原始人工核对意图与风险确认正文", "request_hash":"规范化请求摘要，同键异正文必须拒绝",
    "cycle_attempts":"本轮授权周期内核对投递次数", "applied_at":"核对已被接纳并条件提交的时间，不表示原任务被改为成功",
    "receipt_json":"Agent 的独立核对回执", "receipt_hash":"回执摘要，用于验证一致性",
    "active_authorization_id":"当前有效重新授权 ID", "issuer":"身份提供方签发者标识", "subject":"签发者范围内主体标识",
    "display_name":"用户展示名称，不作为认证凭据", "platform_role":"平台级角色，和项目成员角色区分",
    "identity_hash":"MySQL 生成的 issuer/subject SHA-256 唯一索引列，规避超长 utf8mb4 联合键限制",
    "permission_version":"项目授权版本，撤权后复核旧许可是否失效", "user_id":"用户主键", "role":"项目内角色，如 ADMIN/OPERATOR/VIEWER",
    "mode":"身份模式：LEGACY_TOKEN/OIDC_IDENTITY", "oidc_activated":"是否已激活过 OIDC，防静默降级",
    "bootstrap_issuer":"显式引导管理员的 issuer", "bootstrap_subject":"显式引导管理员的 subject", "bootstrap_user_id":"已绑定引导主体的用户 ID",
    "acknowledge_no_replay":"人工明确确认不重放原动作", "acknowledge_residual_risk":"人工明确确认残余不确定性风险",
    "kind":"授权记录种类，原始授权/接管等由业务定义", "authorization_id":"对应人工核对重新授权记录",
    "attempt_no":"此条许可对应的投递尝试编号", "issued_at":"投递许可签发时间",
    "next_attempt_at":"Outbox 下一次可重试时间", "last_error":"最近发布错误类型，不存敏感连接内容",
    "published_at":"Broker 确认后标记的发布时间，不等于消费完成", "event_id":"稳定消息标识 taskId:sequence，用作消费主键",
    "event_status":"该消息所对应的历史任务事件状态", "observed_status":"消费时回查的当前任务权威状态",
    "consumed_at":"通知收件事务保存时间", "log_timestamp":"日志自身发生时间", "level":"日志级别",
    "question":"诊断问题", "model":"模型名称", "answer":"诊断回答", "evidence_json":"证据引用与索引摘要",
    "installed_rank":"迁移安装顺序", "description":"迁移说明", "type":"迁移实现类型", "script":"迁移脚本/类名",
    "checksum":"Flyway 校验和", "installed_by":"执行迁移的数据库用户", "installed_on":"迁移安装时间",
    "execution_time":"迁移执行耗时（毫秒）", "success":"迁移是否成功",
    "rule_key":"告警规则标识，如 CPU_HIGH/NODE_OFFLINE", "resource_id":"该规则观测的资源标识，节点 ID 或 project/platform 范围",
    "title":"告警可读名称", "severity":"严重程度 WARNING/CRITICAL", "observation_state":"KNOWN 或 UNKNOWN；和活动/恢复状态分开保存",
    "observed_value":"最新可确认观测值；未知时 NULL", "threshold_value":"触发比较阈值，数值达到或超过时异常",
    "summary":"本次评估/转换说明", "opened_at":"最近一次告警触发时间", "resolved_at":"有效正常采样确认恢复的时间",
    "checked_at":"最近评估时间，页面据此标记评估过期", "episodes":"触发次数，只在新异常事件出现时递增",
    "alert_id":"所属当前告警 ID", "metric_value":"本次监控观测值；NULL 为未知", "metric":"历史指标名称",
    "source":"监控取值来源 HOST/MYSQL/RABBITMQ/DISABLED 等",
    "state":"告警生命周期状态 ACTIVE/RESOLVED/UNKNOWN；事件表中保存本次触发或恢复状态",
}

tables = defaultdict(list)
for col in data["columns"]:
    tables[col["TABLE_NAME"]].append(col)
indexes = defaultdict(lambda: defaultdict(list))
for idx in data["indexes"]:
    indexes[idx["TABLE_NAME"]][idx["INDEX_NAME"]].append(idx)
fks = defaultdict(list)
for fk in data["foreignKeys"]:
    fks[fk["TABLE_NAME"]].append(fk)

lines = ["# 02 数据库设计与字段字典", "",
    f"本表根据隔离 MySQL 实际迁移后的 information_schema 元数据生成，当前快照为 V1–V{data['migrations'][-1]['version']}。不是手工猜测 SQL，也不包含生产业务行或数据库密码。", "",
    "## 设计主线", "",
    "资源目录（nodes/instances）保存最近观测；tasks 保存操作事实；queue 仅管调度；events 追加历史；locks 限制同实例并发；resolutions 保存人工核对；outbox/receipts 管事件投递。身份表已有骨架，完整用户体系按需求后做。", "",
    "事务内保存同一业务事实，网络调用在事务外。外键保护资源引用；唯一键保证幂等；版本/租约决定谁能更新；索引改善访问路径。它们不能相互替代。", "",
    "## 字段约定", "",
    "`_at` 一般是时间，`_bytes` 单位为字节，`_percent` 为百分比；主键 ID、Agent 局部 ID、commandId、幂等键各有作用。NULL 表示未知或不适用，0 是观测到的真实零值。TIMESTAMP(6) 保留微秒，以免同步批次和事件比较因秒级截断混淆。", "",
    "## 实际表结构", ""]
for table, columns in tables.items():
    lines += [f"### {table}", "", purposes[table], "",
              "| 字段 | SQL 类型 | 可空 | 默认值 | 含义 |", "|---|---|---|---|---|"]
    for col in columns:
        name = col["COLUMN_NAME"]
        if name not in meanings:
            raise RuntimeError(f"Missing field meaning: {table}.{name}")
        default = col["COLUMN_DEFAULT"] if col["COLUMN_DEFAULT"] is not None else "无/NULL"
        meaning = "监控服务评估并记录该点的时间，原始 Agent 采样新鲜度在写入前校验" if table == "monitor_samples" and name == "sampled_at" else meanings[name]
        lines.append(f"| `{name}` | `{col['COLUMN_TYPE']}` | {col['IS_NULLABLE']} | `{default}` | {meaning} |")
    lines += ["", "**实际索引/约束：**", ""]
    for name, parts in indexes[table].items():
        kind = "唯一" if str(parts[0]["NON_UNIQUE"]) == "0" else "普通"
        names = ", ".join(p["COLUMN_NAME"] + (" DESC" if p.get("COLLATION") == "D" else "") for p in parts)
        lines.append(f"- `{name}`（{kind}）：`{names}`。")
    for fk in fks[table]:
        lines.append(f"- 外键 `{fk['CONSTRAINT_NAME']}`：`{fk['COLUMN_NAME']}` → `{fk['REFERENCED_TABLE_NAME']}.{fk['REFERENCED_COLUMN_NAME']}`。")
    lines.append("")
lines += ["## 为什么用迁移而不是直接改表", "",
          "Flyway 根据已应用的版本顺序执行新迁移并校验旧记录。已发布迁移不能随意修改后重新执行；新增表和索引用新版本迁移。MySQL DDL 不是整个迁移的事务回滚，发布前要在隔离副本演练并保留备份。本轮修复 V7 的元数据 catalog/schema 限定，解决同一 MySQL 多数据库与 H2 大小写发现错误；此修复只影响尚未执行 V7 的安装，不会让已迁移生产库重新执行 V7。Java 迁移的默认 checksum 可为空，不能把校验通过当成所有迁移源码未变化的证明。", "",
          "## 面试追问", "",
          "- 为什么任务与队列分表？任务是长期事实，队列是可调度状态；历史查询不依赖队列是否还存在。",
          "- 唯一索引和幂等校验为什么都要？业务校验给稳定错误，数据库唯一约束兜住并发竞争。",
          "- 为什么不直接把 UNKNOWN 改成功？那会篡改原始观测；用独立核对事实解释如何处理残余风险。",
          "- MySQL 和 H2 都通过才行吗？H2 适合快速回归，不能覆盖 MySQL 外键字符集、索引长度和 DDL 行为。",
          "- 有索引就算优化完成吗？不算；阶段③必须给出数据量、执行计划、耗时和扫描量前后对比。", ""]
(root / "docs/实现详解/02-数据库设计与字段字典.md").write_text("\n".join(lines), encoding="utf-8")
print(f"Generated {len(tables)} tables, {sum(map(len,tables.values()))} columns")
