import type { RequestScope } from './auth-session'
export type RecoveryKind = 'action' | 'review' | 'authorization'
export interface RecoveryReference { version: 2; kind: RecoveryKind; userId: string; projectId: string; requestKey: string }
export interface RecoveryScope { userId: string; projectId: string }
const prefix = 'argus.recovery.v2.'
const uuid = (s: unknown): s is string => typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s)
const keyFor = (scope: RecoveryScope, kind: RecoveryKind) => `${prefix}${encodeURIComponent(scope.userId)}.${scope.projectId}.${kind}`
export function recoveryScope(scope: RequestScope): RecoveryScope | null { return scope.mode === 'OIDC_IDENTITY' && scope.userId && scope.projectId ? { userId: scope.userId, projectId: scope.projectId } : null }
export function readRecovery(storage: Pick<Storage, 'getItem'>, scope: RecoveryScope, kind: RecoveryKind): RecoveryReference | null {
  const raw = storage.getItem(keyFor(scope, kind))
  if (!raw) return null
  const value = JSON.parse(raw) as RecoveryReference
  if (value.version !== 2 || value.kind !== kind || value.userId !== scope.userId || value.projectId !== scope.projectId || !uuid(value.requestKey)
      || Object.keys(value).some(k => !['version', 'kind', 'userId', 'projectId', 'requestKey'].includes(k))) throw Error('当前项目的恢复引用无效，已停止新提交')
  return value
}
export function clearRecovery(storage: Pick<Storage, 'removeItem'>, scope: RecoveryScope, kind: RecoveryKind) { storage.removeItem(keyFor(scope, kind)) }
/** 身份模式只落最小对账引用。正文仅在该组件内存中；跨项目或重载后不得凭引用 POST。 */
export function scopedIntentStorage(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>, scope: RecoveryScope, kind: RecoveryKind) {
  let active: string | null = null
  return {
    getItem: (_key: string) => active,
    setItem(_key: string, raw: string) {
      const intent = JSON.parse(raw) as { key?: string }
      if (!uuid(intent.key)) throw Error('请求标识无效')
      const existing = readRecovery(storage, scope, kind)
      if (existing && existing.requestKey !== intent.key) throw Error('当前项目已有待核对请求，不得替换原标识')
      const reference: RecoveryReference = { version: 2, kind, userId: scope.userId, projectId: scope.projectId, requestKey: intent.key }
      storage.setItem(keyFor(scope, kind), JSON.stringify(reference))
      active = raw
    },
    removeItem(_key: string) { clearRecovery(storage, scope, kind); active = null },
    discardActive() { active = null }
  }
}
export function clearLegacySecrets(storage: Pick<Storage, 'getItem' | 'removeItem'>): boolean {
  const legacyPending = Boolean(storage.getItem('argus.pendingAction.v1') || storage.getItem('argus.pendingReview.v1'))
  for (const key of ['argus.apiToken', 'argus.pendingAction.v1', 'argus.pendingReview.v1']) storage.removeItem(key)
  return legacyPending
}
