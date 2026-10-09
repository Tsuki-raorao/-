import { timestamp, type Task } from './models'

export const REVIEW_DECISION = 'ACKNOWLEDGE_UNCERTAINTY' as const
const statuses = ['PENDING', 'PROCESSING', 'RETRY_WAIT', 'BLOCKED', 'APPLIED'] as const
export type ResolutionStatus = typeof statuses[number]
export interface ReviewBody { decision: typeof REVIEW_DECISION; reason: string; evidence: string; acknowledgeNoReplay: true; acknowledgeResidualRisk: true }
export interface ReviewIntent { key: string; taskId: string; instanceId: string; nodeId: string; agentInstanceId: string; commandId: string; body: ReviewBody }
export interface AgentEvidence {
  classification: 'ACKNOWLEDGED_UNKNOWN' | 'CONFIRMED_TERMINAL'; taskStatus: 'UNKNOWN' | 'SUCCEEDED' | 'FAILED'
  taskResultCode: string | null; taskObservedStatus: string | null; taskFinishedAt: string | null
  processAssessment: 'CURRENT_PROCESS_CLEARED' | 'PRIOR_PROCESS_UNVERIFIED'
  observationStatus: 'AVAILABLE' | 'UNAVAILABLE'; observedInstanceStatus: string | null; observedAt: string; reviewedAt: string
}
export interface Resolution extends ReviewBody {
  id: string; requestKey: string; taskId: string; instanceId: string; nodeId: string; agentInstanceId: string; commandId: string
  requestHash: string; activeAuthorizationId: string | null
  status: ResolutionStatus; requestedBy: string; createdAt: string; updatedAt: string; appliedAt: string | null
  resultCode: string | null; attempts: number; agentEvidence: AgentEvidence | null
}
export interface ResolutionEvent { id: string; resolutionId: string; sequence: number; fromStatus: ResolutionStatus | null; toStatus: ResolutionStatus; actor: string; reason: string; occurredAt: string }
export interface ReviewCapabilities { reviewEnabled: boolean; canReview: boolean; canRecheck: boolean; allowedDecisions: string[]; reason: string; blocksInstance: boolean | null; resolutionId: string | null }
export interface ReviewEvidence {
  taskId: string; commandId: string; instanceId: string; nodeId: string; agentInstanceId: string; checkedAt: string
  bindingMatches: boolean | null; classification: string; reason: string; canAcknowledge: boolean
  agentTask: Record<string, unknown> | null; workerActive: boolean | null; knownProcessesActive: boolean | null
  lockDisposition: string | null; processAssessment: string | null
}
const record = (x: unknown): Record<string, unknown> => x && typeof x === 'object' && !Array.isArray(x) ? x as Record<string, unknown> : {}
const nonempty = (x: unknown): x is string => typeof x === 'string' && x.trim().length > 0
const nullableString = (x: unknown) => x === null || nonempty(x)
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const requestHash = /^[0-9a-f]{64}$/i
const identityKeys = ['taskId', 'instanceId', 'nodeId', 'agentInstanceId', 'commandId'] as const
const isStatus = (x: unknown): x is ResolutionStatus => statuses.includes(x as ResolutionStatus)
export const disabledReview = (reason = '核对权限尚未验证'): ReviewCapabilities => ({ reviewEnabled: false, canReview: false, canRecheck: false, allowedDecisions: [], reason, blocksInstance: null, resolutionId: null })
export const resolutionStatusLabel = (status: ResolutionStatus): string => ({ PENDING: '核对已排队', PROCESSING: '正在核对', RETRY_WAIT: '等待再次查询', BLOCKED: '核对受阻 · 互斥保留', APPLIED: '已人工核对并解除原互斥' })[status]
export function reviewReasonLabel(code: string): string {
  const labels: Record<string, string> = {
    READY: '条件已读取，服务端处理时仍会重新核验', REVIEW_DISABLED: '人工核对开关未开启', OPERATOR_REQUIRED: '当前令牌没有人工核对权限',
    READ_ONLY: '控制中心处于只读模式', API_READ_ONLY: '控制中心处于只读模式', TASK_NOT_UNKNOWN: '此任务不符合 UNKNOWN 核对条件',
    REVIEW_RECHECK_REQUESTED: '操作人按原请求重新检查', REVIEW_ACCEPTED: '人工核对请求已持久接收', REVIEW_APPLIED: '核对回执已验证，原任务互斥已解除',
    REVIEW_IDEMPOTENCY_CONFLICT: '原请求标识与正文冲突，请核对已有记录', TASK_ALREADY_HAS_RESOLUTION: '此任务已有核对记录，请继续已有记录',
    EXECUTOR_ACTIVE: '执行器仍活动，当前不能提交核对', RECORD_MISSING: '原 Agent 记录缺失，当前不能核对', BINDING_CHANGED: '目标绑定已变化，当前不能核对',
    UNAVAILABLE: '暂时无法读取 Agent 证据', INVALID_EVIDENCE: '证据未通过验证', INELIGIBLE: '原任务或互斥条件不符合要求',
    INSTANCE_NOT_ALLOWED: '此实例未获准人工核对', REVIEW_PENDING: '已有核对记录正在处理', REVIEW_BLOCKED: '核对受阻，请检查证据后按原请求重新检查',
    REVIEW_TARGET_NOT_ALLOWED: '此实例未获准人工核对', REVIEW_ALREADY_APPLIED: '人工核对已经应用，原结果仍不确定', REVIEW_IN_PROGRESS: '已有核对记录正在处理',
    REVIEW_UNCONFIGURED: '核对所需配置尚未完成', REVIEW_EXECUTOR_ACTIVE: '执行器仍活动，当前不能核对', AGENT_REVIEW_DISABLED: '目标 Agent 未开启人工核对',
    REVIEW_NOT_UNKNOWN: '此任务不符合 UNKNOWN 核对条件', REVIEW_LOCK_NOT_OWNED: '原任务不再持有该实例的互斥', TARGET_CHANGED: '目标绑定发生变化，已禁止核对'
  }
  return labels[code] || '核对条件尚未满足或状态已更新；请查看技术结果码并重新读取'
}
export function normalizeReviewBody(input: unknown): ReviewBody {
  const x = record(input)
  const normalize = (value: unknown, limit: number): string => {
    if (typeof value !== 'string' || value.includes('\0')) throw Error('原因与证据必须为有效纯文本')
    for (let i = 0; i < value.length; i++) {
      const code = value.charCodeAt(i)
      if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) throw Error('文本包含未配对字符') }
      else if (code >= 0xdc00 && code <= 0xdfff) throw Error('文本包含未配对字符')
    }
    const text = value.replace(/\r\n?/g, '\n').trim()
    if (!text || Array.from(text).length > limit) throw Error(`原因不能为空且最多 500 字，证据不能为空且最多 4000 字（按 Unicode 码点计）`)
    return text
  }
  if (x.decision !== REVIEW_DECISION || x.acknowledgeNoReplay !== true || x.acknowledgeResidualRisk !== true) throw Error('必须分别确认不重放命令和剩余风险')
  return { decision: REVIEW_DECISION, reason: normalize(x.reason, 500), evidence: normalize(x.evidence, 4000), acknowledgeNoReplay: true, acknowledgeResidualRisk: true }
}
export function normalizeResolution(input: unknown): Resolution {
  const x = record(input), body = normalizeReviewBody(x)
  if (![...identityKeys, 'id', 'requestedBy'].every(key => nonempty(x[key])) || !uuid.test(String(x.requestKey)) || !requestHash.test(String(x.requestHash))
    || !(x.activeAuthorizationId === null || uuid.test(String(x.activeAuthorizationId))) || !isStatus(x.status)
    || !timestamp(x.createdAt) || !timestamp(x.updatedAt) || (x.appliedAt !== null && !timestamp(x.appliedAt))
    || (x.status === 'APPLIED') !== !!x.appliedAt || !nullableString(x.resultCode) || !Number.isSafeInteger(x.attempts) || Number(x.attempts) < 0
    || body.reason !== x.reason || body.evidence !== x.evidence) throw Error('核对记录格式或身份无效')
  let agentEvidence: AgentEvidence | null = null
  if (x.agentEvidence !== null) {
    const e = record(x.agentEvidence)
    if (!['ACKNOWLEDGED_UNKNOWN', 'CONFIRMED_TERMINAL'].includes(String(e.classification))
      || !(e.classification === 'ACKNOWLEDGED_UNKNOWN' ? e.taskStatus === 'UNKNOWN' : ['SUCCEEDED', 'FAILED'].includes(String(e.taskStatus)))
      || !['CURRENT_PROCESS_CLEARED', 'PRIOR_PROCESS_UNVERIFIED'].includes(String(e.processAssessment))
      || !['AVAILABLE', 'UNAVAILABLE'].includes(String(e.observationStatus))
      || !(e.observationStatus === 'UNAVAILABLE' ? e.observedInstanceStatus === null : ['RUNNING', 'STOPPED', 'UNKNOWN'].includes(String(e.observedInstanceStatus)))
      || !nullableString(e.taskResultCode) || !nullableString(e.taskObservedStatus) || (e.taskFinishedAt !== null && !timestamp(e.taskFinishedAt))
      || !timestamp(e.observedAt) || !timestamp(e.reviewedAt)) throw Error('Agent 核对回执证据无效')
    agentEvidence = e as unknown as AgentEvidence
  }
  if (x.status === 'APPLIED' && !agentEvidence) throw Error('已应用核对缺少经过验证的 Agent 证据')
  return { ...x, ...body, requestHash: String(x.requestHash).toLowerCase(), activeAuthorizationId: x.activeAuthorizationId as string | null, agentEvidence } as unknown as Resolution
}
export function normalizeReviewCapabilities(input: unknown): ReviewCapabilities {
  const x = record(input)
  if (!['reviewEnabled', 'canReview', 'canRecheck', 'blocksInstance'].every(key => typeof x[key] === 'boolean')
      || !nullableString(x.resolutionId) || !Array.isArray(x.allowedDecisions) || !nonempty(x.reason)) return disabledReview('核对权限响应无效，已禁止提交')
  const allowed = x.reviewEnabled === true && x.allowedDecisions.includes(REVIEW_DECISION)
  return { reviewEnabled: x.reviewEnabled as boolean, canReview: allowed && x.canReview === true && x.blocksInstance === true && x.resolutionId === null,
    canRecheck: allowed && x.canRecheck === true && x.blocksInstance === true && nonempty(x.resolutionId), allowedDecisions: allowed ? [REVIEW_DECISION] : [],
    reason: reviewReasonLabel(String(x.reason)), blocksInstance: x.blocksInstance as boolean, resolutionId: x.resolutionId as string | null }
}
export function assertReviewIdentity(value: Pick<Resolution, 'taskId' | 'instanceId' | 'nodeId' | 'agentInstanceId' | 'commandId'>, task: Task): void {
  if (value.taskId !== task.id || (['instanceId', 'nodeId', 'agentInstanceId', 'commandId'] as const).some(key => value[key] !== task[key])) throw Error('核对证据与原任务身份不匹配')
}
export function normalizeReviewEvidence(input: unknown, task: Task): ReviewEvidence {
  const x = record(input)
  assertReviewIdentity(x as unknown as ReviewEvidence, task)
  const nullableBool = (v: unknown) => v === null || typeof v === 'boolean'
  if (!timestamp(x.checkedAt) || !['READY', 'EXECUTOR_ACTIVE', 'RECORD_MISSING', 'BINDING_CHANGED', 'UNAVAILABLE', 'INELIGIBLE', 'INVALID_EVIDENCE', 'REVIEW_DISABLED'].includes(String(x.classification))
    || !['bindingMatches', 'workerActive', 'knownProcessesActive'].every(key => nullableBool(x[key])) || typeof x.canAcknowledge !== 'boolean' || !nonempty(x.reason)
    || !(x.lockDisposition === null || ['OWNED_BY_COMMAND', 'NONE', 'OWNED_BY_OTHER'].includes(String(x.lockDisposition)))
    || !(x.processAssessment === null || ['CURRENT_PROCESS_CLEARED', 'PRIOR_PROCESS_UNVERIFIED'].includes(String(x.processAssessment)))) throw Error('确认前证据格式无效')
  const a = record(x.agentTask)
  if (x.agentTask !== null && (!nonempty(a.taskId) || a.taskId !== task.commandId || a.instanceId !== task.agentInstanceId
    || String(a.action).toUpperCase() !== task.action || !nonempty(a.nodeId) || a.executionMode !== task.executionMode
    || !['PENDING', 'RUNNING', 'UNKNOWN', 'SUCCEEDED', 'FAILED'].includes(String(a.status)))) throw Error('Agent 原任务身份无效')
  if (x.canAcknowledge && (x.classification !== 'READY' || x.bindingMatches !== true || x.workerActive !== false || x.knownProcessesActive !== false
    || !['UNKNOWN', 'SUCCEEDED', 'FAILED'].includes(String(a.status)) || !x.processAssessment
    || !(a.status === 'UNKNOWN' ? x.lockDisposition === 'OWNED_BY_COMMAND' : ['OWNED_BY_COMMAND', 'NONE'].includes(String(x.lockDisposition))))) throw Error('确认前证据缺少安全条件')
  return x as unknown as ReviewEvidence
}
export function normalizeResolutionEvents(input: unknown, resolutionId: string): ResolutionEvent[] {
  if (!Array.isArray(input)) throw Error('核对事件格式无效')
  let sequence = 0
  return input.map(value => {
    const e = record(value)
    if (!nonempty(e.id) || e.resolutionId !== resolutionId || !Number.isSafeInteger(e.sequence) || Number(e.sequence) <= sequence
      || !(e.fromStatus === null || isStatus(e.fromStatus)) || !isStatus(e.toStatus) || !['operator', 'review-worker'].includes(String(e.actor))
      || !nonempty(e.reason) || !timestamp(e.occurredAt)) throw Error('核对事件身份或顺序无效')
    sequence = Number(e.sequence)
    return e as unknown as ResolutionEvent
  })
}
export function resolutionIntent(value: Resolution): ReviewIntent {
  return { key: value.requestKey, taskId: value.taskId, instanceId: value.instanceId, nodeId: value.nodeId, agentInstanceId: value.agentInstanceId, commandId: value.commandId, body: normalizeReviewBody(value) }
}
export function matchesReviewIntent(value: Resolution, intent: ReviewIntent): boolean {
  return value.requestKey === intent.key && identityKeys.every(key => value[key] === intent[key]) && JSON.stringify(normalizeReviewBody(value)) === JSON.stringify(intent.body)
}
export interface ReviewIntentState { phase: 'idle' | 'submitting' | 'uncertain'; intent: ReviewIntent | null; error: string }
export const REVIEW_STORAGE_KEY = 'argus.pendingReview.v1'
export function readReviewIntent(storage: Pick<Storage, 'getItem'>): ReviewIntent | null {
  const raw = storage.getItem(REVIEW_STORAGE_KEY)
  if (!raw) return null
  const value = JSON.parse(raw) as ReviewIntent
  if (!uuid.test(value.key) || !identityKeys.every(key => nonempty(value[key]))) throw Error('待确认核对标识无效')
  value.body = normalizeReviewBody(value.body)
  return value
}
/** 回包未知时保留原键。先 GET 恢复，再由人显式决定是否 POST；从不自动重发。 */
export function createReviewSender(send: (intent: ReviewIntent, signal: AbortSignal) => Promise<Resolution>, storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>, update: (state: ReviewIntentState) => void) {
  let generation = 0, controller: AbortController | undefined, corrupt = false
  let state: ReviewIntentState = { phase: 'idle', intent: null, error: '' }
  try {
    const value = readReviewIntent(storage)
    if (value) {
      state = { phase: 'uncertain', intent: value, error: '已恢复待确认核对；先读取已有记录，再决定是否按原请求提交' }
    }
  } catch { corrupt = true; state = { phase: 'uncertain', intent: null, error: '待确认核对记录无法读取；请核对服务端记录，不要创建替代请求' } }
  const publish = (next: ReviewIntentState) => { state = next; update(next) }
  const clear = () => { storage.removeItem(REVIEW_STORAGE_KEY); publish({ phase: 'idle', intent: null, error: '' }) }
  publish(state)
  return {
    adoptExisting(value: Resolution): boolean {
      if (!state.intent || !identityKeys.every(key => value[key] === state.intent![key]) || state.phase === 'submitting') return false
      generation++; controller?.abort()
      try { clear(); return true } catch { return false }
    },
    reconcile(value: Resolution): boolean {
      if (!state.intent || !matchesReviewIntent(value, state.intent)) return false
      generation++; controller?.abort()
      try { clear(); return true } catch { publish({ ...state, phase: 'uncertain', error: '服务端记录已核对，但无法清除本地待确认标识' }); return false }
    },
    async submit(fresh?: ReviewIntent): Promise<Resolution | null> {
      if (corrupt || state.phase === 'submitting') return null
      if (state.intent && fresh) throw Error('已有待确认核对，不能创建新请求')
      const target = state.intent || fresh
      if (!target) return null
      const wasUncertain = state.phase === 'uncertain'
      try { storage.setItem(REVIEW_STORAGE_KEY, JSON.stringify(target)) } catch { publish({ phase: 'uncertain', intent: target, error: '无法保存原请求标识，尚未发送' }); return null }
      const ticket = ++generation
      controller = new AbortController()
      publish({ phase: 'submitting', intent: target, error: '' })
      try {
        const value = await send(target, controller.signal)
        if (ticket !== generation) return null
        if (!matchesReviewIntent(value, target)) throw Error('核对接受响应的身份、原键或正文不匹配')
        clear()
        return value
      } catch (error) {
        if (ticket !== generation) return null
        const status = error && typeof error === 'object' && 'status' in error ? error.status : undefined
        const rejected = !wasUncertain && status !== 409 && error && typeof error === 'object' && 'definitiveRejection' in error && error.definitiveRejection === true
        const message = error instanceof Error ? error.message : '响应不可用'
        if (rejected) { try { clear() } catch { /* 保留原键 */ } }
        publish({ phase: rejected && state.phase === 'idle' ? 'idle' : 'uncertain', intent: rejected && state.phase === 'idle' ? null : target, error: `核对提交${rejected ? '被拒绝' : '结果待确认'}：${message}` })
        return null
      }
    },
    invalidate() { generation++; controller?.abort(); if (state.intent) publish({ ...state, phase: 'uncertain', error: '已停止等待；重新连接后先读取服务端原核对记录' }) }
  }
}
