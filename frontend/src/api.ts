export type NodeStatus = 'online' | 'offline'
export type InstanceStatus = 'running' | 'stopped' | 'starting' | 'error'
export type TaskStatus = 'running' | 'success' | 'failed' | 'pending'

export interface NodeItem { id: string; name: string; host: string; status: NodeStatus; cpu: number; memory: number; lastSeen: string }
export interface Instance { id: string; name: string; node: string; image: string; status: InstanceStatus; players: number; tps: number; cpuPercent: number; memory: string; uptime: string; port: number }
export interface Task { id: string; action: string; target: string; status: TaskStatus; createdAt: string; operator: string; duration: string }
export interface LogLine { time: string; level: 'INFO' | 'WARN' | 'ERROR'; message: string }
export interface HealthState { status: string; readOnly: boolean; databaseReady?: boolean }

/**
 * 控制中心统一响应结构。页面只消费 data，错误仍由 request 统一降级到演示数据。
 */
interface ApiEnvelope<T> { code?: number; message?: string; data?: T }

/** 写请求失败时必须保留错误，不能回退成演示成功。 */
export class ApiRequestError extends Error {
  constructor(message: string, readonly status?: number) { super(message) }
}

/**
 * 浏览器会话中的临时 API 令牌。
 * 令牌由用户手动输入，只保存在 sessionStorage，不进入源码和构建产物。
 */
const API_TOKEN_KEY = 'argus.apiToken'
export function getApiToken(): string {
  return typeof window === 'undefined' ? '' : window.sessionStorage.getItem(API_TOKEN_KEY) || ''
}
export function saveApiToken(token: string): void {
  if (typeof window !== 'undefined') window.sessionStorage.setItem(API_TOKEN_KEY, token.trim())
}
export function clearApiToken(): void {
  if (typeof window !== 'undefined') window.sessionStorage.removeItem(API_TOKEN_KEY)
}

function requestHeaders(init?: RequestInit): Headers {
  const headers = new Headers(init?.headers)
  headers.set('Content-Type', 'application/json')
  const token = getApiToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return headers
}

// 192.0.2.0/24 为文档示例地址；演示数据不包含部署环境的实际主机。
const mockNodes: NodeItem[] = [{ id: 'node-1', name: '示例节点 · mc01', host: '192.0.2.20', status: 'online', cpu: 24, memory: 38, lastSeen: '刚刚' }, { id: 'node-2', name: '本地开发机', host: '127.0.0.1', status: 'offline', cpu: 0, memory: 0, lastSeen: '2 小时前' }]
const mockInstances: Instance[] = [{ id: 'mc01', name: '零壹', node: '示例节点 · mc01', image: 'NeoForge 1.21.1', status: 'running', players: 3, tps: 20, cpuPercent: 3.2, memory: '2.8 / 6 GB', uptime: '3 天 08:42', port: 25565 }]
const mockTasks: Task[] = [{ id: 'TSK-2481', action: '重启实例', target: 'mc01 / 零壹', status: 'success', createdAt: '今天 09:12', operator: '你', duration: '42s' }, { id: 'TSK-2480', action: '拉取实时日志', target: 'mc01 / 零壹', status: 'running', createdAt: '今天 09:04', operator: '系统', duration: '进行中' }, { id: 'TSK-2479', action: '更新配置', target: 'mc01 / 零壹', status: 'success', createdAt: '昨天 22:38', operator: '你', duration: '8s' }]
const mockLogs: LogLine[] = [{ time: '09:14:26', level: 'INFO', message: 'Done (4.821s)! For help, type "help"' }, { time: '09:14:24', level: 'INFO', message: 'Preparing spawn area: 100%' }, { time: '09:14:18', level: 'INFO', message: 'Loading dimension minecraft:overworld' }, { time: '09:14:12', level: 'INFO', message: 'Starting minecraft server version 1.21.1' }, { time: '09:13:51', level: 'WARN', message: 'Server is running behind! Running 2143ms or 42 ticks behind' }, { time: '09:13:32', level: 'INFO', message: 'Player Alex joined the game' }]

/**
 * 请求控制中心 API。
 *
 * 只有 Vite 开发模式允许 mock fallback；生产环境必须把不可用状态暴露给页面，
 * 不能把旧的演示数据伪装成服务器真实状态。
 */
async function request<T>(path: string, fallback: T, init?: RequestInit): Promise<T> {
  try {
    const response = await fetch(`/api${path}`, { ...init, headers: requestHeaders(init) })
    if (!response.ok) throw new ApiRequestError(`API unavailable (${response.status})`, response.status)
    const body = await response.json() as T | ApiEnvelope<T>
    if (body && typeof body === 'object' && 'code' in body && typeof (body as ApiEnvelope<T>).code === 'number' && (body as ApiEnvelope<T>).code !== 0) throw new Error((body as ApiEnvelope<T>).message || 'API error')
    return (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'data') ? (body as ApiEnvelope<T>).data : body) as T
  } catch (error) {
    // 开发环境仍可使用 mock，但 401 必须交给页面显示令牌输入框。
    if (import.meta.env.DEV && !(error instanceof ApiRequestError && error.status === 401)) return fallback
    throw error
  }
}

/** 严格请求用于写操作；网络或只读保护失败都必须反馈给用户。 */
async function requestStrict<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, headers: requestHeaders(init) })
  let body: T | ApiEnvelope<T> | undefined
  try { body = await response.json() as T | ApiEnvelope<T> } catch { body = undefined }
  if (!response.ok) {
    const message = body && typeof body === 'object' && 'message' in body ? String((body as ApiEnvelope<T>).message || '') : ''
    throw new ApiRequestError(message || `API unavailable (${response.status})`, response.status)
  }
  if (body && typeof body === 'object' && 'code' in body && typeof (body as ApiEnvelope<T>).code === 'number' && (body as ApiEnvelope<T>).code !== 0) {
    throw new ApiRequestError((body as ApiEnvelope<T>).message || 'API error', response.status)
  }
  return (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'data') ? (body as ApiEnvelope<T>).data : body) as T
}

type RawRecord = Record<string, unknown>
const asRecord = (value: unknown): RawRecord => value && typeof value === 'object' ? value as RawRecord : {}

function normalizeInstanceStatus(value: unknown): InstanceStatus {
  const status = String(value || '').toLowerCase()
  if (status === 'running' || status === 'succeeded') return 'running'
  if (status === 'starting') return 'starting'
  if (status === 'stopped' || status === 'stopping') return 'stopped'
  return 'error'
}

function normalizeTaskStatus(value: unknown): TaskStatus {
  const status = String(value || '').toLowerCase()
  if (status === 'running') return 'running'
  if (status === 'succeeded' || status === 'success') return 'success'
  if (status === 'failed' || status === 'error') return 'failed'
  return 'pending'
}

const normalizeNode = (input: unknown): NodeItem => {
  const x = asRecord(input)
  const status: NodeStatus = String(x.status || '').toLowerCase() === 'online' ? 'online' : 'offline'
  const total = Number(x.memoryTotalBytes ?? 0)
  const used = Number(x.memoryBytes ?? 0)
  const memoryPercent = total > 0 ? used * 100 / total : Number(x.memoryPercent ?? x.memory ?? x.memoryUsage ?? 0)
  return { id: String(x.id || ''), name: String(x.name || x.id || '未命名节点'), host: String(x.host || x.address || '—'), status, cpu: status === 'online' ? Math.max(0, Math.min(100, Number(x.cpuPercent ?? x.cpu ?? x.cpuUsage ?? 0))) : 0, memory: status === 'online' && Number.isFinite(memoryPercent) ? Math.max(0, Math.min(100, memoryPercent)) : 0, lastSeen: String(x.lastSeen || x.lastHeartbeat || '—') }
}
function formatBytes(value: unknown): string {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes <= 0) return '—'
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024).toFixed(1)} KB`
}
const normalizeInstance = (input: unknown): Instance => { const x = asRecord(input); return { id: String(x.id || ''), name: String(x.name || x.id || '未命名实例'), node: String(x.node || x.nodeName || x.nodeId || '—'), image: String(x.image || x.version || '—'), status: normalizeInstanceStatus(x.status), players: Number(x.players ?? x.playerCount ?? 0), tps: Number(x.tps ?? 20), cpuPercent: Number(x.cpuPercent ?? x.cpu ?? 0), memory: x.memory !== undefined && x.memory !== null ? String(x.memory) : formatBytes(x.memoryBytes), uptime: String(x.uptime || '—'), port: Number(x.port ?? 0) } }
const normalizeTask = (input: unknown): Task => { const x = asRecord(input); return { id: String(x.id || ''), action: String(x.action || '操作'), target: String(x.target || x.instanceId || '—'), status: normalizeTaskStatus(x.status), createdAt: String(x.createdAt || '—'), operator: String(x.operator || '系统'), duration: String(x.duration || (x.finishedAt ? '已完成' : '—')) } }
const normalizeLog = (input: unknown): LogLine => { const x = asRecord(input); const level = String(x.level || 'INFO').toUpperCase(); return { time: String(x.time || (x.timestamp ? new Date(String(x.timestamp)).toLocaleTimeString('zh-CN', { hour12: false }) : '—')), level: level === 'WARN' || level === 'ERROR' ? level : 'INFO', message: String(x.message || '') } }
export const api = {
  health: async () => request<HealthState>('/health', { status: 'UNKNOWN', readOnly: false }),
  nodes: async () => (await request<unknown[]>('/nodes', mockNodes)).map(normalizeNode),
  instances: async () => (await request<unknown[]>('/instances', mockInstances)).map(normalizeInstance),
  tasks: async () => (await request<unknown[]>('/tasks', mockTasks)).map(normalizeTask),
  logs: async (id = 'mc01') => (await request<unknown[]>(`/logs?instanceId=${encodeURIComponent(id)}&limit=100`, mockLogs)).map(normalizeLog),
  control: (id: string, action: 'start' | 'stop' | 'restart') => requestStrict(`/instances/${encodeURIComponent(id)}/actions`, { method: 'POST', body: JSON.stringify({ action }) })
}
