import { normalizeAgentLogs, normalizeInstance, normalizeNode, normalizeTask, normalizeTaskEvents, type AgentLogs, type HealthState } from './models'
import { normalizeCapabilities, type ActionIntent } from './task-control'
import { normalizeResolution, normalizeResolutionEvents, normalizeReviewCapabilities, normalizeReviewEvidence, type ReviewIntent } from './task-resolution'
import { authSession, normalizeAuthConfig, normalizeCsrf, normalizeIdentity, normalizeProjects } from './auth-session'
import type { Task } from './models'
export type { Instance, NodeItem, Task, AgentLogs, HealthState } from './models'

interface ApiEnvelope<T> { code?: number; message?: string; data?: T }

export class ApiRequestError extends Error {
  constructor(message: string, readonly status?: number, readonly definitiveRejection = false) { super(message) }
}

/** 访问令牌仅保存在当前浏览器会话，不进入源码或 URL。 */
const API_TOKEN_KEY = 'argus.apiToken'
export const getApiToken = (): string => typeof window === 'undefined' ? '' : window.sessionStorage.getItem(API_TOKEN_KEY) || ''
export function saveApiToken(token: string): void { if (typeof window !== 'undefined') window.sessionStorage.setItem(API_TOKEN_KEY, token.trim()) }
export function clearApiToken(): void { if (typeof window !== 'undefined') window.sessionStorage.removeItem(API_TOKEN_KEY) }

/** 开发和生产均报告真实请求错误，演示由后端明确标记 MOCK，不自动回退。 */
async function request<T>(path: string, init?: RequestInit, expectedStatus?: number): Promise<T> {
  const headers = new Headers(init?.headers)
  headers.set('Content-Type', 'application/json')
  const state = authSession.get()
  const isAuthPath = path.startsWith('/auth/')
  const token = getApiToken()
  // OIDC 会话不携带旧共享令牌；认证端点也不需要把 sessionStorage 中的令牌送出。
  if (token && !isAuthPath && state.config?.mode !== 'OIDC_IDENTITY') headers.set('Authorization', `Bearer ${token}`)
  // 项目目录和平台管理接口没有“当前项目”这一层：项目目录用于建立选择，
  // 平台管理员接口管理跨项目资源。项目内资源仍必须带服务端校验的 projectId。
  const isProjectBootstrapPath = path === '/projects'
  const isPlatformAdminPath = path === '/admin/users' || path.startsWith('/admin/users/') || path === '/admin/projects' || path.startsWith('/admin/projects/')
  const needsProject = !isAuthPath && !isProjectBootstrapPath && !isPlatformAdminPath && path !== '/health'
  const oidc = state.config?.mode === 'OIDC_IDENTITY'
  const requestScope = oidc && needsProject ? authSession.capture(true) : null
  if (oidc && needsProject) {
    if (!state.project) throw new ApiRequestError('请先选择可访问的项目')
    const separator = path.includes('?') ? '&' : '?'
    path = `${path}${separator}projectId=${encodeURIComponent(state.project.id)}`
  }
  if (oidc && init?.method && init.method.toUpperCase() !== 'GET' && init.method.toUpperCase() !== 'HEAD' && state.config?.csrfRequired) {
    if (!state.csrf) throw new ApiRequestError('缺少安全校验信息，写操作已关闭')
    headers.set(state.csrf.headerName, state.csrf.token)
  }
  const controller = new AbortController()
  const abort = () => controller.abort()
  let timedOut = false
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, 12_000)
  if (init?.signal?.aborted) controller.abort()
  init?.signal?.addEventListener('abort', abort, { once: true })
  try {
    const response = await fetch(`/api${path}`, { ...init, headers, signal: controller.signal })
    let body: T | ApiEnvelope<T> | undefined
    try { body = await response.json() as T | ApiEnvelope<T> } catch { /* 非 JSON 错误页不作为业务数据。 */ }
    if (!response.ok) {
      if ((response.status === 401 || response.status === 403) && typeof window !== 'undefined' && !isAuthPath) window.dispatchEvent(new CustomEvent('argus-auth-expired', { detail: { status: response.status } }))
      const message = body && typeof body === 'object' && 'message' in body ? String(body.message || '') : ''
      throw new ApiRequestError(message || `请求失败（HTTP ${response.status}）`, response.status, [400, 401, 403, 404, 405, 409, 422].includes(response.status))
    }
    if (expectedStatus && response.status !== expectedStatus) throw new ApiRequestError('没有收到有效的排队确认，提交结果待确认', response.status)
    if (!body || typeof body !== 'object') throw new ApiRequestError('控制中心返回了无效响应')
    if ('code' in body && typeof body.code === 'number' && body.code !== 0) throw new ApiRequestError(body.message || '请求失败', response.status)
    if (requestScope && !authSession.current(requestScope)) throw new ApiRequestError('会话或项目已变化，已丢弃旧请求结果')
    return ('data' in body ? body.data : body) as T
  } catch (error) {
    if (timedOut) throw new ApiRequestError('请求超时，请稍后重试')
    throw error
  } finally {
    clearTimeout(timeout)
    init?.signal?.removeEventListener('abort', abort)
  }
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new ApiRequestError('控制中心返回了无效列表')
  return value
}

export const api = {
  authConfig: async () => normalizeAuthConfig(await request<unknown>('/auth/config')),
  authMe: async () => normalizeIdentity(await request<unknown>('/auth/me')),
  authCsrf: async () => normalizeCsrf(await request<unknown>('/auth/csrf')),
  authProjects: async () => normalizeProjects(await request<unknown>('/projects')),
  authLogin: () => { window.location.assign('/api/auth/login') },
  authLogout: () => request<null>('/auth/logout', { method: 'POST' }),
  projectMembers: async (projectId: string, signal?: AbortSignal) => request<unknown[]>(`/projects/${encodeURIComponent(projectId)}/members`, { signal }),
  updateProjectMember: async (projectId: string, userId: string, body: unknown, signal?: AbortSignal) => request<unknown>(`/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(userId)}`, { method: 'PUT', signal, body: JSON.stringify(body) }),
  removeProjectMember: async (projectId: string, userId: string, expectedVersion: number, signal?: AbortSignal) => request<null>(`/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(userId)}?expectedVersion=${expectedVersion}`, { method: 'DELETE', signal }),
  adminUsers: async (signal?: AbortSignal) => request<unknown[]>('/admin/users', { signal }),
  adminProjects: async (signal?: AbortSignal) => request<unknown[]>('/admin/projects', { signal }),
  createProject: async (body: unknown, signal?: AbortSignal) => request<unknown>('/admin/projects', { method: 'POST', signal, body: JSON.stringify(body) }),
  updateProject: async (projectId: string, body: unknown, signal?: AbortSignal) => request<unknown>(`/projects/${encodeURIComponent(projectId)}`, { method: 'PUT', signal, body: JSON.stringify(body) }),
  updateAdminUser: async (userId: string, body: unknown, signal?: AbortSignal) => request<unknown>(`/admin/users/${encodeURIComponent(userId)}`, { method: 'PUT', signal, body: JSON.stringify(body) }),
  health: () => request<HealthState>('/health'),
  nodes: async () => list(await request<unknown>('/nodes')).map(normalizeNode),
  instances: async () => list(await request<unknown>('/instances')).map(normalizeInstance),
  tasks: async () => list(await request<unknown>('/tasks')).map(normalizeTask),
  task: async (id: string, signal?: AbortSignal) => {
    const task = normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}`, { signal }))
    if (!id || task.id !== id) throw new ApiRequestError('任务详情身份不匹配')
    return task
  },
  taskByRequestKey: async (key: string, signal?: AbortSignal) => normalizeTask(await request<unknown>(`/tasks/by-request-key/${encodeURIComponent(key)}`, { signal })),
  resolutionByRequestKey: async (key: string, signal?: AbortSignal) => {
    const value = await request<unknown>(`/task-resolutions/by-request-key/${encodeURIComponent(key)}`, { signal })
    if (value === null) return null
    return normalizeResolution(value)
  },
  adoptResolution: async (resolutionId: string, key: string, body: unknown, signal?: AbortSignal) => request<unknown>(`/task-resolutions/${encodeURIComponent(resolutionId)}/authorizations`, {
    method: 'POST', signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body)
  }, 202),
  adoptionByRequestKey: async (resolutionId: string, key: string, signal?: AbortSignal) => request<unknown>(`/task-resolutions/${encodeURIComponent(resolutionId)}/authorizations/by-request-key/${encodeURIComponent(key)}`, { signal }),
  taskEvents: async (id: string, signal?: AbortSignal) => normalizeTaskEvents(await request<unknown>(`/tasks/${encodeURIComponent(id)}/events`, { signal }), id),
  capabilities: async () => normalizeCapabilities(await request<unknown>('/control/capabilities')),
  reviewCapabilities: async (id: string, signal?: AbortSignal) => normalizeReviewCapabilities(await request<unknown>(`/tasks/${encodeURIComponent(id)}/review-capabilities`, { signal })),
  reviewEvidence: async (task: Task, signal?: AbortSignal) => normalizeReviewEvidence(await request<unknown>(`/tasks/${encodeURIComponent(task.id)}/review-evidence`, { signal }), task),
  taskResolution: async (id: string, signal?: AbortSignal) => {
    const value = await request<unknown>(`/tasks/${encodeURIComponent(id)}/resolution`, { signal })
    if (value === null) return null
    const result = normalizeResolution(value)
    if (result.taskId !== id) throw new ApiRequestError('核对记录不属于所选任务')
    return result
  },
  resolutionEvents: async (id: string, signal?: AbortSignal) => normalizeResolutionEvents(await request<unknown>(`/task-resolutions/${encodeURIComponent(id)}/events`, { signal }), id),
  resolveTask: async (intent: ReviewIntent, signal?: AbortSignal) => normalizeResolution(await request<unknown>(`/tasks/${encodeURIComponent(intent.taskId)}/resolutions`, {
    method: 'POST', signal, headers: { 'Idempotency-Key': intent.key }, body: JSON.stringify(intent.body)
  }, 202)),
  recentLogs: async (id: string, signal?: AbortSignal): Promise<AgentLogs> => {
    if (!id) throw new ApiRequestError('请先选择实例')
    return normalizeAgentLogs(await request<unknown>(`/instances/${encodeURIComponent(id)}/logs?limit=100`, { signal }), id)
  },
  control: async (intent: ActionIntent, signal?: AbortSignal) => normalizeTask(await request<unknown>(`/instances/${encodeURIComponent(intent.instanceId)}/actions`, {
    method: 'POST', signal, headers: { 'Idempotency-Key': intent.key },
    body: JSON.stringify({ action: intent.action, expectedExecutionMode: intent.expectedExecutionMode })
  }, 202))
}
