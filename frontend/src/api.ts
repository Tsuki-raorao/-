import { normalizeAgentLogs, normalizeInstance, normalizeNode, normalizeTask, normalizeTaskEvents, type AgentLogs, type HealthState } from './models'
import { normalizeCapabilities, type ActionIntent } from './task-control'
import { normalizeResolution, normalizeResolutionEvents, normalizeReviewCapabilities, normalizeReviewEvidence, type ReviewIntent } from './task-resolution'
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
  const token = getApiToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
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
      const message = body && typeof body === 'object' && 'message' in body ? String(body.message || '') : ''
      throw new ApiRequestError(message || `请求失败（HTTP ${response.status}）`, response.status, [400, 401, 403, 404, 405, 409, 422].includes(response.status))
    }
    if (expectedStatus && response.status !== expectedStatus) throw new ApiRequestError('没有收到有效的排队确认，提交结果待确认', response.status)
    if (!body || typeof body !== 'object') throw new ApiRequestError('控制中心返回了无效响应')
    if ('code' in body && typeof body.code === 'number' && body.code !== 0) throw new ApiRequestError(body.message || '请求失败', response.status)
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
  health: () => request<HealthState>('/health'),
  nodes: async () => list(await request<unknown>('/nodes')).map(normalizeNode),
  instances: async () => list(await request<unknown>('/instances')).map(normalizeInstance),
  tasks: async () => list(await request<unknown>('/tasks')).map(normalizeTask),
  task: async (id: string, signal?: AbortSignal) => {
    const task = normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}`, { signal }))
    if (!id || task.id !== id) throw new ApiRequestError('任务详情身份不匹配')
    return task
  },
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
