# UNKNOWN 任务人工核对设计与共享契约

更新：2026-10-09。状态：实现、模块测试和 38 项隔离跨模块验证已完成。生产仍为 V5 只读版本，本轮 V6 尚未部署；本轮只在项目内隔离测试，未访问真实 Docker 或远程环境。具体证据与历史发布边界见[开发进度](开发进度.md)。

## 1. 语义和不可破坏的边界

人工核对只有一个决定：`ACKNOWLEDGE_UNCERTAINTY`，意思是“保留原执行结果的不确定性，记录核对依据，解除这条命令对未来独立操作的互斥”。它不执行命令，不重放原命令，也不表示原操作成功或失败。

原中央 Task 的 `status=UNKNOWN`、`resultCode/message/finishedAt/updatedAt` 和原有任务事件保持不变。核对使用独立记录和事件；即使 Agent 已有真实 SUCCEEDED/FAILED，也只保存经过身份和时序验证的终态证据，不覆盖原 UNKNOWN 轨迹。实例观测状态仍只由采集更新。

一个中央任务最多一个不可变 resolution；一个 Agent command 最多一个不可变 CLOSED 回执。原 commandId、Agent `.task`、原 storeId 和期限永久保留，用于阻止迟到的原 POST 重新执行。

**核对请求不新增 expiresAt/TTL**。人工决定是持久的核对授权；实际新动作仍须独立确认、新幂等键和新 commandId。每次处理重新检查开关、允许列表、绑定与执行器条件。原命令 expiresAt 不变，也不因核对延期。

只能处理中央 UNKNOWN、中央实例互斥仍属于原任务、原投递队列已经不存在的任务。Agent 原记录缺失、PENDING/RUNNING、身份/地址/store/mode 变化或已知执行器未退出时，不能解锁。没有“强制成功”“忽略身份”“删除旧 Inbox”入口。

## 2. 权限与配置

中央新增：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `ARGUS_TASK_REVIEW_ENABLED` | false | 新建、续办和后台核对的独立开关 |
| `ARGUS_TASK_REVIEW_ALLOWED_INSTANCE_IDS` | 空 | 可核对的中央实例 ID 精确名单 |
| `ARGUS_TASK_REVIEW_LEASE` | 60s | 核对队列租约，启用时必须大于四次完整 HTTP 预算加5秒 |
| `ARGUS_TASK_REVIEW_RETRY_DELAY` | 3s | 暂时网络失败重试间隔 |
| `ARGUS_TASK_REVIEW_MAX_ATTEMPTS` | 5 | 每次人工请求/续办最多自动尝试次数，达到后 BLOCKED |
| `ARGUS_TASK_REVIEW_POLL_INTERVAL_MS` | 1000 | 每轮最多领取一项，不重叠执行 |

复用 API 操作 Bearer 和 Agent 控制 Bearer，二者分别与对应查看令牌不同。启用核对要求 API 认证、独立非空操作令牌、Agent 网关开启、GET 网关 readOnly=true、强制主机白名单及独立非空 Agent 控制令牌；不允许空名单。

核对不要求 `ARGUS_TASK_CONTROL_ENABLED`、`ARGUS_TASK_ALLOW_DOCKER` 或 `ARGUS_AGENT_GATEWAY_ALLOW_CONTROL` 为true；这些仍只控制 Docker 动作投递。核对的显式授权由独立 review 开关和名单提供，不能调用原动作 POST。`ARGUS_API_READ_ONLY=true` 优先禁止核对写入/续办及后台向 Agent 发出核对 POST、解除中央互斥。

Agent 新增 `ARGUS_AGENT_REVIEW_ENABLED=false` / `review.enabled=false`、`ARGUS_AGENT_REVIEW_INSTANCES` / `review.instances`（局部实例ID名单，默认空）。`control.enabled=false` 时可以单独启用核对；仍要求控制令牌与读取令牌不同、Inbox健康、独立核对名单。读取任务/核对回执使用控制令牌；查看令牌不能访问这些接口。health 增加 `reviewProtocolVersion:"1.0"`、`reviewEnabled:boolean`。

中央新建及续办均要求服务端 operator 角色；actor 固定记录 `operator`，不接受浏览器提供 actor。API关闭认证的纯演示环境也不能匿名启用核对。GET记录仍可供已认证查看者查看。

## 3. 浏览器与中央 API

均使用既有 `{code,message,data}` 外壳。

| 方法与路径 | 结果 |
|---|---|
| `GET /api/tasks/{taskId}/review-capabilities` | 核对能力，见下方字段 |
| `GET /api/tasks/{taskId}/review-evidence` | 首次人工确认前按需只读检查，形状见下方；失败条件明确分类，不填伪值 |
| `POST /api/tasks/{taskId}/resolutions` | 独立 UUID `Idempotency-Key`；新建、相同请求重放或BLOCKED原请求续办均返回202及Resolution |
| `GET /api/tasks/{taskId}/resolution` | 200 + Resolution或null；任务本身不存在则404 |
| `GET /api/task-resolutions/{resolutionId}` | 200 + Resolution；不存在404 |
| `GET /api/task-resolutions/{resolutionId}/events` | sequence升序的独立审计事件 |

POST 严格正文：

```json
{
  "decision": "ACKNOWLEDGE_UNCERTAINTY",
  "reason": "核对原因",
  "evidence": "已检查的状态、日志或其他人工依据；仅纯文本，不执行、不自动访问URL",
  "acknowledgeNoReplay": true,
  "acknowledgeResidualRisk": true
}
```

拒绝未知字段、错误类型、空白reason/evidence及任何非true ACK。reason最多500个Unicode码点，evidence最多4000个Unicode码点；拒绝NUL和未配对代理字符。接受前将CRLF/CR换为LF并去除首尾空白，除此以外不改正文；持久化后的标准化文本及请求不可修改。

首尾trim严格采用ECMAScript `String.trim()`集合：U+0009..000D、0020、00A0、1680、2000..200A、2028、2029、202F、205F、3000、FEFF。后端不得用Java.strip/isBlank替代此集合；例如NBSP、BOM、全角空格会被裁掉，而U+001C不属于trim集合。Java/浏览器/Agent按同一集合验证，不做Unicode NFC等其他归一化。Agent接收的reason已是中央持久标准文本，不得静默二次转换成不同文本。

`Idempotency-Key` 为标准小写连字符UUID。同键但任务或标准化正文改变返回409 `REVIEW_IDEMPOTENCY_CONFLICT`。同任务使用不同键返回409 `TASK_ALREADY_HAS_RESOLUTION`，前端应GET该任务的resolution继续原记录，不暗中创建替代核对。

Resolution 返回字段固定为：

```text
id, requestKey, taskId, instanceId, nodeId, agentInstanceId, commandId,
decision, status, reason, evidence, acknowledgeNoReplay, acknowledgeResidualRisk,
requestedBy, createdAt, updatedAt, appliedAt, resultCode, attempts,
agentEvidence
```

字符串身份均非空；时间ISO-8601，appliedAt在未应用时null；ACK为boolean，attempts为累计非负整数。`agentEvidence`在未保存有效回执前为null，之后形状为：

```text
classification, taskStatus, taskResultCode, taskObservedStatus, taskFinishedAt,
processAssessment, observationStatus, observedInstanceStatus, observedAt, reviewedAt
```

taskObservedStatus是原Agent任务的观测证据，observedInstanceStatus是本次核对的独立采样，不能混淆。对外不返回目标地址、令牌、原HTTP正文或租约owner。

中央 resolution 状态：`PENDING / PROCESSING / RETRY_WAIT / BLOCKED / APPLIED`。202仅表示接受核对意图；只有APPLIED表示中央原互斥已条件解除。原Task仍UNKNOWN。

review-capabilities 字段：

```text
reviewEnabled:boolean, canReview:boolean, canRecheck:boolean,
allowedDecisions:string[], reason:string, blocksInstance:boolean,
resolutionId:string|null
```

canReview只用于新建；canRecheck只用于已有BLOCKED记录按原键原正文续办。权限/全局只读/开关/名单不满足时两者均false，allowedDecisions为空。该能力反映本地授权和互斥条件，不承诺远端已经静止；真正处理时重新验证。

事件字段：`id/resolutionId/sequence/fromStatus/toStatus/actor/reason/occurredAt`，sequence为整数严格递增，actor为operator或review-worker，reason为固定机器码，不塞入人工长正文。

### 确认前的只读证据

`review-evidence`返回固定字段：

```text
taskId, commandId, instanceId, nodeId, agentInstanceId, checkedAt,
bindingMatches, classification, reason, canAcknowledge,
agentTask, workerActive, knownProcessesActive, lockDisposition, processAssessment
```

checkedAt为中央本次读取时间。bindingMatches为true/false/null：已验证一致、已知不一致、尚无法验证。classification为`READY/EXECUTOR_ACTIVE/RECORD_MISSING/BINDING_CHANGED/UNAVAILABLE/INELIGIBLE/INVALID_EVIDENCE/REVIEW_DISABLED`，reason为固定机器码。canAcknowledge只在完整同绑定、原记录UNKNOWN或合法终态、执行器及锁门槛满足时true；它不代替operator权限与review-capabilities。

agentTask为经过既有身份/字段/时间/成功证据校验的完整13字段TaskView，缺失或未验证时null。workerActive/knownProcessesActive为boolean或null；lockDisposition为`OWNED_BY_COMMAND/NONE/OWNED_BY_OTHER`或null；processAssessment同Agent闭合回执或null。失败不得把未知boolean填false。

证据接口只访问绑定的原地址，先验证health身份，再读取下面的Agent证据；目标不存在等领域错误返回明确分类，task本身不存在404，未认证仍401。不改变任务、核对记录或锁，不执行新的容器观察命令；原task中的observedStatus/finishedAt是历史证据，不冒充本次资源采样。

## 4. 权威实例锁与前端行为

Task列表/详情增加 `blocksInstance:boolean`，按 `instance_task_locks.instance_id=task.instanceId AND task_id=task.id` 的真实数据库记录计算；增加 `reviewSummary:{id,status,resultCode,appliedAt}|null`。不能用UNKNOWN状态永久推断锁存在。

既有ControlCapabilities的targets增加 `blockingTaskId:string|null`、`blockedReason:string|null`、`canConfirmPending:boolean`。被锁目标 `allowedActions=[]`，blockedReason=`INSTANCE_HAS_UNRESOLVED_TASK`。canConfirmPending由operator、全局非只读、控制开关及目标名单/模式授权决定，不受锁影响；全局canControl/allowedActions也依据授权能力而非当前锁占用。这样全部实例被锁时仍能按原动作幂等键确认丢失的回包：已经存在同key任务时返回原command；此前根本未被中央接收时，显式重送原请求可能首次入队，仍受正常权限、互斥和期限保护，不能把这个POST当成只读查询。人工resolution流程本身永不重放原动作命令。新动作必须另检查target.allowedActions及blockingTaskId为空。权限或名单失效时canConfirmPending=false。从所有实例锁读取，而不是依赖最近100条任务；阻塞任务即便不在最近列表也可按ID打开详情。新操作POST仍在事务内重新查锁。

前端显示原UNKNOWN及独立核对时间线；APPLIED文案为“已人工核对并解除互斥，原执行结果仍不确定”。若Agent提供真实终态，单独展示“Agent原记录为……”而非改写原任务状态。两个风险确认必须分别勾选；必须确认没有重放原命令，并承认Docker daemon可能在CLI退出后继续处理请求。

首次确认前必须成功读取同task的review-evidence并展示读取时间、绑定/原任务/执行器/锁条件，且canAcknowledge=true才允许提交；切换task、令牌或证据刷新失败后废弃旧证据。证据不是可复用的授权票据，后台处理仍重新验证，不能凭客户端记录的boolean解除锁。

BLOCKED提供“按原核对请求重新检查”，复用原requestKey和完整原正文；不得变更理由/证据或产生新commandId。页面切换、退出登录或切换令牌废弃旧请求回调；202校验taskId/instanceId/commandId/decision/requestKey与当前意图一致。恢复浏览器会话时先GET现存resolution，不能静默换键。

## 5. 中央与 Agent 核对接口

`POST /api/agent/tasks/{commandId}/resolutions`，使用控制Bearer。新建持久回执201，相同resolutionId及完整相同请求200，冲突或执行未静止409；关闭核对/名单拒绝403，原task或查询回执缺失404，损坏Inbox/落盘错误503。

`GET /api/agent/tasks/{commandId}/resolutions/{resolutionId}` 只读返回200或404；即使核对开关关闭，已保存回执仍可用控制令牌读取。

`GET /api/agent/tasks/{commandId}/review-evidence`同样使用控制令牌，只读200；原command缺失404，不运行actions.observe、不执行容器命令。返回严格字段：

```text
commandId, nodeId, storeId, executionMode, checkedAt, task,
reviewEnabled, instanceAllowed, workerActive, knownProcessesActive,
lockDisposition, processAssessment
```

task是原13字段TaskView。reviewEnabled/instanceAllowed都是boolean，分别反映当前Agent核对开关与原实例的核对名单。workerActive包括scheduled或仍在运行的任务worker；knownProcessesActive包含已知仍存活/清理未确认的子进程与输出reader；lockDisposition为OWNED_BY_COMMAND/NONE/OWNED_BY_OTHER；processAssessment同闭合回执。缺失记录用404表达，不能造一个可核对空任务。身份/状态读取须在一致同步边界取得；所有公开任务/回执GET仍不触发新动作。

Agent POST严格字段如下；只有两个ACK为boolean，其他全为字符串：

```text
resolutionId, instanceId, action, expectedNodeId, expectedStoreId,
expectedExecutionMode, expectedCommandExpiresAt, decision, reason, evidenceDigest,
acknowledgeNoReplay, acknowledgeResidualRisk
```

action为原命令的小写start/stop/restart；expectedCommandExpiresAt为原命令期限的标准Instant字符串，仅用于比对原记录，核对不要求它尚未到期。evidenceDigest是中央已持久化、标准化evidence UTF-8字节的SHA-256小写hex。Agent不收任意文件、附件、文件路径或获取远程证据。

中央必须从已提交数据库行构造固定请求，不重新计算时间或取新的绑定。Agent逐字段比较原TaskCommand中的身份、动作、模式、期限；decision及两个ACK必须为固定合法值。

### requestHash：固定长度前缀算法

hash为SHA-256小写64位hex。按以下顺序逐项编码：将字符串转为UTF-8；先写该字节数组长度的4字节无符号大端整数，再写字节本身；不加分隔符或终止字节。两个ACK使用固定小写ASCII字符串`true`参与hash，不是单字节boolean。

```text
1  argus-resolution-v1
2  commandId（来自路径）
3  resolutionId
4  instanceId
5  action
6  expectedNodeId
7  expectedStoreId
8  expectedExecutionMode
9  expectedCommandExpiresAt
10 decision
11 reason
12 evidenceDigest
13 true（acknowledgeNoReplay）
14 true（acknowledgeResidualRisk）
```

不按JSON字符顺序或空格计算，禁止直接用分隔符拼接。所有字段均已严格验证；跨模块测试必须覆盖中文、换行、emoji和前缀歧义。

Agent回执严格字段：

```text
resolutionId, commandId, instanceId, action, nodeId, storeId, executionMode,
decision, status, requestHash, createdAt, classification, task,
processAssessment, observationStatus, observedInstanceStatus, observedAt
```

status固定`CLOSED`；classification为`ACKNOWLEDGED_UNKNOWN`或`CONFIRMED_TERMINAL`。task为既有完整13字段TaskView对象，原记录逐字义保留；第一类task.status必须UNKNOWN，第二类必须SUCCEEDED/FAILED并满足原任务响应校验（成功必须有合理开始/结束时间及与动作一致的状态证据）。中央核验全套身份、hash、原任务和分类后才能接受，不能只看HTTP200。

三种可闭合状态的task.finishedAt均必须存在；时间必须满足 `task.finishedAt <= observedAt <= receipt.createdAt`。不能用早于原执行结束的“本次观察”或闭合回执证明核对完成。

`processAssessment`为`CURRENT_PROCESS_CLEARED`或`PRIOR_PROCESS_UNVERIFIED`。前者只证明本进程已知worker/子进程/读取线程清理完成，不证明Docker daemon一定完成；后者只用于新JVM无法证明上个进程执行上下文的历史记录，不能绕过当前JVM已知仍存活的进程。

`observationStatus`为`AVAILABLE/UNAVAILABLE`；`observedInstanceStatus`为RUNNING/STOPPED/UNKNOWN或null，UNAVAILABLE必须null；observedAt是本次观察时间，即使无法采样也记录尝试时间。该观察只是核对证据，不是原命令成功证明。观察过程中若产生已知未结束子进程，同样不得关闭回执。

## 6. Agent 持久闭合与执行器守护

首次核对必须在同一个同步边界验证：Inbox健康，原command存在且绑定相同；原状态UNKNOWN或真实终态；scheduled/运行中worker都不包含原command；该command关联的已知ProcessHandle后代与输出读取线程均已退出。已知存活/无法完成本轮进程清理时返回409 `review_executor_active`，不能仅以scheduled为空放行。

UNKNOWN时本机unresolved锁必须精确属于原command；真实终态的unresolved锁允许已不存在，但不能属于另一个command。先完成当前状态观察，再复查执行器/绑定/锁，先原子写入并同步CLOSED回执，之后才compare-remove原command的unresolved锁。真实终态不会被改成UNKNOWN或CLOSED。

重复的相同resolution POST仍必须通过当前review开关、实例名单、存储健康和未关闭进程门禁；通过后只返回已持久化原回执，不重新采样、不更改createdAt，不再次释放任何锁，否则可能误删后来新任务的锁。关闭review后GET原回执仍可读取。Agent启动加载回执，验证其原task、绑定、hash及内容后，对已闭合UNKNOWN跳过原互斥重建，仍保留原task用于永久去重。回执损坏或缺少对应原task时fail-closed。

每个已有command最多一条回执，不挤占新任务容量；Inbox已满仍可核对，不得删原.task腾位置。与现有Inbox同样采用原子替换与文件同步，先落盘后更改内存。进程/文件系统/备份回滚与Docker daemon的能力边界必须如实说明。

## 7. 中央 V6 持久化与处理流程

新增三表，不改已上线V5，不改原任务执行结果：

1. `task_resolutions`：UUID主键；task_id唯一外键；request_key唯一；原身份/地址/store/mode/commandExpiresAt绑定；decision/reason/evidence/ACK/requestHash；原UNKNOWN快照；status/result_code/attempts/cycle_attempts/version_no；requested_by与微秒时间；经过验证的receipt JSON及其SHA-256。人工正文/绑定/原快照不可更新。
2. `task_resolution_queue`：resolution_id主键外键；next_run_at/lease_until/lease_owner。提交人工决定与入队必须同事务。
3. `task_resolution_events`：UUID主键、resolution_id外键、sequence唯一、from/to/actor/reason/occurred_at；只追加。

MySQL的task_id逐列继承实际tasks.id类型、长度、字符集和排序规则；两个新子表外键也与resolution父列完全一致。H2和真实MySQL旧V5含UNKNOWN/锁/历史事件迁移都要验证。时间统一TIMESTAMP(6)；不为旧任务自动创建核对意图，不级联删除审计。MySQL DDL并非原子回滚，迁移失败需备份恢复，不能盲repair。

处理顺序：

1. API短事务锁定节点/实例/原任务，复核UNKNOWN、原中央互斥、原投递队列不存在、目标身份及权限；写resolution+queue+事件。旧请求先查同key严格去重。
2. 核对worker通过数据库租约CAS领取，保存PROCESSING；每轮最多一条，HTTP在事务外。调度池需保留原采集及任务worker的独立余量（当前两线程扩为至少三线程），同一个worker不并发重入。
3. 每轮先查本地当前地址/实例关系和持久绑定完全相同，再读Agent health并验证review协议/node/store/mode。只访问原地址，不因节点修改重定向。
4. 先GET原resolution回执；200验证后进入第6步。明确404时，再GET原task，验证其仍存在且为UNKNOWN或真实终态；否则BLOCKED。始终不调用原命令POST。
5. 发送核对POST前短事务再次验证租约、独立review权限及原中央锁归属，再投递完全相同的固定resolution。201/200回执均按同一规则验证。丢响应后下一轮先查回执，不创造新resolution/command。
6. 最后短事务验证有效租约owner、resolution版本、原UNKNOWN和完整绑定、当前review gate、中央锁仍属于原task、原投递队列仍不存在；持久回执+APPLIED+追加事件，并执行 `DELETE ... WHERE instance_id=? AND task_id=?`，要求恰好删除一行。一起提交后移除核对queue。不能按instanceId无条件删除。

网络/可恢复5xx按固定延迟进入RETRY_WAIT；每个周期最多5次，达到上限转BLOCKED并出队。401/403、原记录缺失、活动执行器、身份变化、非法回执等立即BLOCKED并出队。BLOCKED总保留原互斥。

**BLOCKED续办入口必须实现**：操作人再次POST原task、同Idempotency-Key、完全相同原正文，当前权限通过后，将同一resolution转PENDING、cycle_attempts归零、原ID原payload重新入队，追加`REVIEW_RECHECK_REQUESTED`事件。累计attempts不归零。APPLIED重放只返回已保存记录，不重新入队或再次删锁。开关关闭时GET仍可查看；全局只读始终拒绝POST。

租约必须覆盖最多四次完整HTTP总预算（health、query resolution、query task、POST resolution），正文有上限并且完整future有截止与取消。旧owner过期后不能保存回执/改状态/解除锁。关review或全局只读后不得发新的核对POST或解除中央锁；已到达Agent的请求可能已闭合，重新启用后按原ID查询恢复。

## 8. 必须覆盖的故障验收

- 同键并发只产生一条resolution/outbox，内容冲突409；同task不同key不能绕过唯一意图。
- UNKNOWN本体、原事件、实例观测完全不变；仅精确原锁释放；其他节点同名实例不受影响。
- POST回包丢失、中央在Agent落盘后崩溃、中央应用事务前崩溃，重启靠GET原回执恢复；原动作POST次数不增加。
- 两worker竞争同租约、过期owner迟到、旧原任务worker回调都不能覆盖或误删后续新任务锁。
- Agent落盘后解锁前崩溃、重启重建互斥、重复旧回执在新任务已占锁时返回但不解新锁。
- Agent PENDING/RUNNING/known-live子进程或reader/缺失record/store变化/地址变化全部保守BLOCKED。
- 同store真实终态回执可核对，原中央UNKNOWN保留；人工证据不能把矛盾成功回执变为有效。
- MAX_ATTEMPTS后BLOCKED可用原键原正文显式续办；无新TTL造成的永久过期死角。
- 关闭task控制但打开独立review时可核对；查看token、全局只读、review关闭/名单拒绝均不能核对；默认配置没有新增写权限。
- 列表超过100条仍能显示真实blockingTaskId；APPLIED后原UNKNOWN不再阻挡获准的独立新操作。
- H2/真实MySQL V5→V6旧数据迁移、父列排序规则不同、重启重复migrate与回执字段null语义。
