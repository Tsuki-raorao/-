export type AuthMode = 'LEGACY_TOKEN' | 'OIDC_IDENTITY'
export interface AuthConfig { mode: AuthMode; loginPath: '/api/auth/login' | null; csrfRequired: boolean; readOnly: boolean }
export interface Identity { userId: string; displayName: string; platformRole: 'USER' | 'ADMIN'; status: 'ACTIVE' | 'DISABLED'; authMode: 'OIDC_SESSION' | 'JWT' | 'LEGACY_TOKEN'; readOnly: boolean }
export interface Project { id: string; name: string; status: 'ACTIVE' | 'ARCHIVED'; role: 'ADMIN' | 'OPERATOR' | 'VIEWER'; permissions: string[]; permissionVersion: number }
export interface Csrf { headerName: string; token: string }
export interface AuthState { epoch: number; config: AuthConfig | null; identity: Identity | null; projects: Project[]; project: Project | null; csrf: Csrf | null; phase: 'booting' | 'legacy' | 'anonymous' | 'authenticated' | 'ready' | 'error'; error: string }
export interface RequestScope { epoch: number; mode: AuthMode; userId: string | null; projectId: string | null; permissionVersion: number | null }
export class ScopeExpiredError extends Error { constructor() { super('会话或项目已变化，已丢弃旧请求结果') } }
const record = (x: unknown): Record<string, unknown> => x && typeof x === 'object' && !Array.isArray(x) ? x as Record<string, unknown> : {}
const text = (x: unknown): x is string => typeof x === 'string' && x.trim().length > 0
const version = (x: unknown): x is number => Number.isSafeInteger(x) && Number(x) >= 0
export const projectIdValid = (x: unknown): x is string => typeof x === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x)
const permissions = new Set(['RESOURCE_READ', 'TASK_OPERATE', 'TASK_REVIEW', 'PROJECT_MANAGE', 'MEMBER_MANAGE', 'AUDIT_READ'])
export function normalizeAuthConfig(value: unknown): AuthConfig {
  const x = record(value)
  if (!['LEGACY_TOKEN', 'OIDC_IDENTITY'].includes(String(x.mode)) || typeof x.readOnly !== 'boolean' || typeof x.csrfRequired !== 'boolean'
      || (x.mode === 'OIDC_IDENTITY' ? x.loginPath !== '/api/auth/login' || x.csrfRequired !== true : x.loginPath !== null)) throw Error('身份配置无效，未启用任何认证回退')
  return { mode: x.mode as AuthMode, loginPath: x.loginPath as AuthConfig['loginPath'], csrfRequired: x.csrfRequired, readOnly: x.readOnly }
}
export function normalizeIdentity(value: unknown): Identity {
  const x = record(value)
  if (!text(x.userId) || !text(x.displayName) || !['USER', 'ADMIN'].includes(String(x.platformRole)) || !['ACTIVE', 'DISABLED'].includes(String(x.status))
      || !['OIDC_SESSION', 'JWT', 'LEGACY_TOKEN'].includes(String(x.authMode)) || typeof x.readOnly !== 'boolean') throw Error('登录身份未通过验证')
  return { userId: x.userId, displayName: x.displayName, platformRole: x.platformRole as Identity['platformRole'], status: x.status as Identity['status'], authMode: x.authMode as Identity['authMode'], readOnly: x.readOnly }
}
export function normalizeProjects(value: unknown): Project[] {
  if (!Array.isArray(value)) throw Error('项目列表无效')
  const seen = new Set<string>()
  return value.map(raw => {
    const x = record(raw)
    if (!projectIdValid(x.id) || seen.has(x.id) || !text(x.name) || !['ACTIVE', 'ARCHIVED'].includes(String(x.status))
        || !['ADMIN', 'OPERATOR', 'VIEWER'].includes(String(x.role)) || !Array.isArray(x.permissions) || x.permissions.some(p => typeof p !== 'string') || !version(x.permissionVersion)) throw Error('项目身份或权限未通过验证')
    seen.add(x.id)
    return { id: x.id, name: x.name, status: x.status as Project['status'], role: x.role as Project['role'], permissions: [...new Set(x.permissions.filter(p => permissions.has(p)))], permissionVersion: x.permissionVersion }
  })
}
export function normalizeCsrf(value: unknown): Csrf {
  const x = record(value)
  if (!text(x.headerName) || !/^X-[A-Za-z0-9-]{1,60}$/.test(x.headerName) || !text(x.token) || /[\r\n]/.test(x.token)) throw Error('安全校验信息无效，写操作已关闭')
  return { headerName: x.headerName, token: x.token }
}

/** 所有跨用户/项目边界都递增代次并取消旧请求，Abort 无效时仍拒绝晚到响应。 */
export function createAuthSession() {
  let state: AuthState = { epoch: 0, config: null, identity: null, projects: [], project: null, csrf: null, phase: 'booting', error: '' }
  const listeners = new Set<(state: AuthState) => void>(), controllers = new Set<AbortController>()
  const publish = (patch: Partial<AuthState>, boundary = true) => {
    if (boundary) { for (const controller of controllers) controller.abort(); controllers.clear() }
    state = { ...state, ...patch, epoch: state.epoch + (boundary ? 1 : 0) }
    for (const callback of listeners) callback(state)
  }
  return {
    get: () => state,
    subscribe(callback: (state: AuthState) => void) { listeners.add(callback); callback(state); return () => listeners.delete(callback) },
    configure(config: AuthConfig) { publish({ config, identity: null, projects: [], project: null, csrf: null, phase: config.mode === 'LEGACY_TOKEN' ? 'legacy' : 'anonymous', error: '' }) },
    identify(identity: Identity) {
      if (state.config?.mode !== 'OIDC_IDENTITY' || identity.status !== 'ACTIVE' || identity.authMode === 'LEGACY_TOKEN') throw Error('当前身份不可用')
      publish({ identity, projects: [], project: null, csrf: null, phase: 'authenticated', error: '' })
    },
    setProjects(projects: Project[]) {
      if (!state.identity) throw Error('尚未登录')
      const current = projects.find(p => p.id === state.project?.id && p.status === 'ACTIVE' && p.permissions.includes('RESOURCE_READ')) || null
      // 首次登录没有当前项目时只选第一个服务端授权的项目；后续刷新若当前项目消失则停在未选择状态，
      // 不能静默切换到另一个项目并把旧页面请求带过去。
      const project = current || (!state.project ? projects.find(p => p.status === 'ACTIVE' && p.permissions.includes('RESOURCE_READ')) || null : null)
      const changed = project?.id !== state.project?.id || project?.permissionVersion !== state.project?.permissionVersion || JSON.stringify(project?.permissions) !== JSON.stringify(state.project?.permissions)
      publish({ projects, project, phase: project ? 'ready' : 'authenticated' }, changed)
    },
    selectProject(id: string) {
      const project = state.projects.find(p => p.id === id && p.status === 'ACTIVE' && p.permissions.includes('RESOURCE_READ'))
      if (!state.identity || !project) throw Error('该项目不可访问，请刷新项目权限')
      publish({ project, phase: 'ready', error: '' })
    },
    setCsrf(csrf: Csrf) { if (!state.identity) throw Error('尚未登录'); publish({ csrf }, false) },
    expire(reason = '登录已失效，请重新登录') { publish({ identity: null, projects: [], project: null, csrf: null, phase: state.config?.mode === 'LEGACY_TOKEN' ? 'legacy' : 'anonymous', error: reason }) },
    revokeProject(reason = '当前项目权限已变化，请重新选择项目') { publish({ project: null, projects: [], phase: state.identity ? 'authenticated' : 'anonymous', error: reason }) },
    fail(reason: string) { publish({ config: null, identity: null, projects: [], project: null, csrf: null, phase: 'error', error: reason }) },
    capture(projectRequired = true): RequestScope {
      if (!state.config || state.phase === 'error') throw Error('身份配置尚未验证')
      if (state.config.mode === 'OIDC_IDENTITY' && (!state.identity || projectRequired && !state.project)) throw Error('请先登录并选择可访问的项目')
      return { epoch: state.epoch, mode: state.config.mode, userId: state.identity?.userId || null, projectId: projectRequired ? state.project?.id || null : null, permissionVersion: projectRequired ? state.project?.permissionVersion ?? null : null }
    },
    current(scope: RequestScope) { return scope.epoch === state.epoch && scope.mode === state.config?.mode && scope.userId === (state.identity?.userId || null)
      && (!scope.projectId || scope.projectId === state.project?.id && scope.permissionVersion === state.project?.permissionVersion) },
    track(controller: AbortController) { controllers.add(controller); return () => controllers.delete(controller) },
    permitted(permission: string) { return state.phase === 'ready' && state.project?.permissions.includes(permission) === true }
  }
}
export const authSession = createAuthSession()
