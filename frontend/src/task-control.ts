import { type Task, type TaskAction, type ExecutionMode } from './models'

export interface ControlTarget { instanceId: string; executionMode: 'MOCK' | 'DOCKER'; allowedActions: TaskAction[]; blockingTaskId: string | null; blockedReason: string | null; canConfirmPending: boolean }
export interface Capabilities { controlEnabled: boolean; canControl: boolean; allowedActions: TaskAction[]; targets: ControlTarget[]; reason: string }
export const disabledCapabilities = (reason = '操作权限尚未验证'): Capabilities => ({ controlEnabled: false, canControl: false, allowedActions: [], targets: [], reason })
const actions = (value: unknown): TaskAction[] => Array.isArray(value) ? ['START', 'STOP', 'RESTART'].filter(action => value.includes(action)) as TaskAction[] : []
const capabilityReason = (value: unknown): string => ({
  CONTROL_DISABLED: '控制开关未开启，当前仅可查看', OPERATOR_REQUIRED: '当前令牌没有操作权限，请使用操作令牌',
  NO_ALLOWED_TARGETS: '当前没有获准操作的实例', READY: '操作权限已验证'
})[String(value)] || '操作权限尚未确认，请刷新或核对控制中心配置'
export function normalizeCapabilities(input: unknown): Capabilities {
  if (!input || typeof input !== 'object') return disabledCapabilities('操作能力响应无效')
  const x = input as Record<string, unknown>
  if (!Array.isArray(x.targets)) return disabledCapabilities(capabilityReason(x.reason))
  const authorized = x.controlEnabled === true && x.canControl === true
  const seen = new Set<string>()
  const targets: ControlTarget[] = []
  for (const item of x.targets) {
    if (!item || typeof item !== 'object') return disabledCapabilities('操作目标响应无效')
    const t = item as Record<string, unknown>
    if (typeof t.instanceId !== 'string' || !t.instanceId || seen.has(t.instanceId)) return disabledCapabilities('操作目标身份无效')
    seen.add(t.instanceId)
    if (t.blockingTaskId !== null && (typeof t.blockingTaskId !== 'string' || !t.blockingTaskId)) return disabledCapabilities('实例互斥状态未验证')
    if (t.blockingTaskId ? t.blockedReason !== 'INSTANCE_HAS_UNRESOLVED_TASK' : t.blockedReason !== null) return disabledCapabilities('实例互斥状态无效')
    if (t.executionMode === 'MOCK' || t.executionMode === 'DOCKER') targets.push({ instanceId: t.instanceId, executionMode: t.executionMode,
      allowedActions: !authorized || t.blockingTaskId ? [] : actions(t.allowedActions), blockingTaskId: t.blockingTaskId as string | null, blockedReason: t.blockedReason as string | null,
      canConfirmPending: authorized && t.canConfirmPending === true })
  }
  return { controlEnabled: x.controlEnabled === true, canControl: authorized, allowedActions: authorized ? actions(x.allowedActions) : [], targets, reason: capabilityReason(x.reason) }
}
export function allowedTarget(capabilities: Capabilities, instanceId: string, action: TaskAction): ControlTarget | undefined {
  if (!capabilities.controlEnabled || !capabilities.canControl || !capabilities.allowedActions.includes(action)) return undefined
  return capabilities.targets.find(target => target.instanceId === instanceId && target.blockingTaskId === null && target.allowedActions.includes(action))
}

export interface ActionIntent { key: string; instanceId: string; nodeId: string; agentInstanceId: string; name: string; action: TaskAction; expectedExecutionMode: Extract<ExecutionMode, 'MOCK' | 'DOCKER'> }
export interface IntentState { intent: ActionIntent | null; phase: 'idle' | 'submitting' | 'uncertain'; error: string }
export const INTENT_STORAGE_KEY = 'argus.pendingAction.v1'
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
export function newIntentKey(): string {
  // IP 的 HTTP 预览可能没有 randomUUID，但 getRandomValues 仍提供安全随机数。
  if (typeof crypto === 'undefined' || typeof crypto.getRandomValues !== 'function') throw new Error('浏览器缺少安全随机数，无法创建请求标识，未发送')
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
export function readIntent(storage: Pick<Storage, 'getItem'>): ActionIntent | null {
  const value = storage.getItem(INTENT_STORAGE_KEY)
  if (!value) return null
  const x = JSON.parse(value) as ActionIntent
  if (!x || !uuidPattern.test(x.key) || !x.instanceId || !actions([x.action]).length || !['MOCK', 'DOCKER'].includes(x.expectedExecutionMode)
      || !['nodeId', 'agentInstanceId', 'name'].every(key => typeof x[key as keyof ActionIntent] === 'string')) throw new Error('待确认操作记录损坏；请核对任务历史后处理浏览器记录')
  return x
}

/** POST 不自动重试。网络结果不明时保留原意图，退出或重载也不能悄悄换键。 */
export function createIntentSender(
  send: (intent: ActionIntent, signal: AbortSignal) => Promise<Task>,
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>,
  update: (state: IntentState) => void
) {
  let generation = 0
  let controller: AbortController | undefined
  let storedError = ''
  let initial: ActionIntent | null = null
  try { initial = readIntent(storage) } catch (error) { storedError = error instanceof Error ? error.message : '无法读取待确认操作' }
  let state: IntentState = { intent: initial, phase: initial || storedError ? 'uncertain' : 'idle', error: storedError || (initial ? '已恢复待确认操作；请使用原请求核对，不要创建新命令' : '') }
  const publish = (next: IntentState) => { state = next; update(next) }
  publish(state)
  return {
    async submit(intent?: ActionIntent): Promise<Task | null> {
      if (state.phase === 'submitting' || storedError) return null
      const wasUncertain = state.phase === 'uncertain'
      if (state.intent && intent) throw new Error('已有待确认操作，不能创建新请求')
      const target = state.intent || intent
      if (!target) return null
      // 先可靠保留幂等键，再发请求；会话存储不可写时不发送。
      try { storage.setItem(INTENT_STORAGE_KEY, JSON.stringify(target)) } catch { publish({ intent: target, phase: 'uncertain', error: '无法保存请求标识，尚未发送；请检查浏览器存储后用原请求重试' }); return null }
      const ticket = ++generation
      controller = new AbortController()
      publish({ intent: target, phase: 'submitting', error: '' })
      try {
        const task = await send(target, controller.signal)
        if (ticket !== generation) return null
        if (!task.id || task.instanceId !== target.instanceId || task.action !== target.action || task.executionMode !== target.expectedExecutionMode
            || task.nodeId !== target.nodeId || task.agentInstanceId !== target.agentInstanceId || !task.commandId) throw new Error('任务响应身份或模式不匹配，提交结果待确认')
        storage.removeItem(INTENT_STORAGE_KEY)
        publish({ intent: null, phase: 'idle', error: '' })
        return task
      } catch (error) {
        if (ticket !== generation) return null
        const message = error instanceof Error ? error.message : '控制中心不可达'
        const rejected = !wasUncertain && error && typeof error === 'object' && 'definitiveRejection' in error && error.definitiveRejection === true
        if (rejected) {
          try { storage.removeItem(INTENT_STORAGE_KEY) } catch { /* 保守保留供下次核对。 */ }
          publish({ intent: null, phase: 'idle', error: `请求被拒绝：${message}` })
        } else publish({ intent: target, phase: 'uncertain', error: `提交结果待确认：${message}` })
        return null
      }
    },
    invalidate() {
      generation++
      controller?.abort()
      if (state.intent) publish({ ...state, phase: 'uncertain', error: '请求已停止等待，提交结果仍待确认；重新连接后用原请求核对' })
    },
    /** 会话或项目边界切换时丢弃内存中的旧意图；其最小引用由上层按旧项目继续对账。 */
    reset() {
      generation++
      controller?.abort()
      controller = undefined
      storedError = ''
      initial = null
      publish({ intent: null, phase: 'idle', error: '' })
    }
  }
}
