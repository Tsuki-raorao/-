export type NodeStatus = 'online' | 'offline'
export type InstanceStatus = 'running' | 'stopped' | 'starting' | 'error' | 'unknown'
export type TaskStatus = 'PENDING' | 'DISPATCHING' | 'DELIVERED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type TaskAction = 'START' | 'STOP' | 'RESTART'
export type ExecutionMode = 'MOCK' | 'DOCKER' | 'LEGACY_MOCK' | 'UNKNOWN'
export type MetricsStatus = 'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE' | 'UNKNOWN'
export type DataSource = 'HOST' | 'DOCKER' | 'MOCK' | 'LEGACY'
export type SyncStatus = 'UNKNOWN' | 'OK' | 'PARTIAL' | 'FAILED'

interface Observation {
  dataSource: DataSource
  sampledAt: string | null
  metricsStatus: MetricsStatus
}

export interface NodeItem extends Observation {
  id: string
  name: string
  host: string
  status: NodeStatus
  cpu: number | null
  memory: number | null
  lastCheckedAt: string | null
  lastSuccessfulSyncAt: string | null
  syncStatus: SyncStatus
  syncErrorCode: string | null
}

export interface Instance extends Observation {
  /** 中央实例 ID 是不透明标识，所有请求和选择器都使用此字段。 */
  id: string
  agentInstanceId: string
  nodeId: string
  name: string
  node: string
  image: string
  status: InstanceStatus
  players: number | null
  cpuPercent: number | null
  memoryBytes: number | null
  lastSeenAt: string | null
  uptime: string
}

export interface Task {
  id: string; instanceId: string; nodeId: string; agentInstanceId: string; commandId: string
  action: string; status: TaskStatus; executionMode: ExecutionMode; requestedBy: string
  message: string; createdAt: string | null; updatedAt: string | null; finishedAt: string | null
  attempts: number | null; resultCode: string | null
}
export interface TaskEvent { id: string; taskId: string; sequence: number; fromStatus: TaskStatus | null; toStatus: TaskStatus; actor: string; reason: string; occurredAt: string | null }
export interface HealthState { status: string; readOnly: boolean; databaseReady?: boolean }
export interface AgentLogs { instanceId: string; nodeId: string; agentInstanceId: string; collectedAt: string; lines: string[] }

type RawRecord = Record<string, unknown>
const asRecord = (value: unknown): RawRecord => value && typeof value === 'object' ? value as RawRecord : {}
const text = (value: unknown, fallback = ''): string => typeof value === 'string' && value.trim() ? value : fallback
const member = <T extends string>(value: unknown, values: readonly T[], fallback: T): T => values.includes(value as T) ? value as T : fallback

/** null、空串、布尔值和非法数值不能变成 0；真正的数值 0 必须保留。 */
export function measurement(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null
  return value
}

export function timestamp(value: unknown): string | null {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value)) ? value : null
}

function observation(x: RawRecord): Observation {
  return {
    dataSource: member(x.dataSource, ['HOST', 'DOCKER', 'MOCK', 'LEGACY'], 'LEGACY'),
    sampledAt: timestamp(x.sampledAt),
    metricsStatus: member(x.metricsStatus, ['AVAILABLE', 'PARTIAL', 'UNAVAILABLE', 'UNKNOWN'], 'UNKNOWN')
  }
}

export function normalizeNode(input: unknown): NodeItem {
  const x = asRecord(input)
  const total = measurement(x.memoryTotalBytes)
  const used = measurement(x.memoryBytes)
  const memory = total !== null && total > 0 && used !== null ? used * 100 / total : null
  return {
    ...observation(x), id: text(x.id), name: text(x.name, text(x.id, '未命名节点')),
    host: text(x.host, text(x.address, '—')), status: String(x.status).toLowerCase() === 'online' ? 'online' : 'offline',
    cpu: measurement(x.cpuPercent), memory,
    lastCheckedAt: timestamp(x.lastCheckedAt), lastSuccessfulSyncAt: timestamp(x.lastSuccessfulSyncAt),
    syncStatus: member(x.syncStatus, ['UNKNOWN', 'OK', 'PARTIAL', 'FAILED'], 'UNKNOWN'),
    syncErrorCode: typeof x.syncErrorCode === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(x.syncErrorCode) ? x.syncErrorCode : null
  }
}

export function normalizeInstance(input: unknown): Instance {
  const x = asRecord(input)
  return {
    ...observation(x), id: text(x.id), agentInstanceId: text(x.agentInstanceId, text(x.containerName)),
    nodeId: text(x.nodeId), name: text(x.name, text(x.agentInstanceId, '未命名实例')),
    node: text(x.nodeName, text(x.nodeId, '未关联节点')), image: text(x.image, text(x.version, '—')),
    status: member(String(x.status).toLowerCase(), ['running', 'stopped', 'starting', 'error'], 'unknown'),
    players: measurement(x.playerCount), cpuPercent: measurement(x.cpuPercent), memoryBytes: measurement(x.memoryBytes),
    lastSeenAt: timestamp(x.lastSeenAt), uptime: text(x.uptime, '—')
  }
}

export function normalizeTask(input: unknown): Task {
  const x = asRecord(input)
  return { id: text(x.id), instanceId: text(x.instanceId), nodeId: text(x.nodeId), agentInstanceId: text(x.agentInstanceId), commandId: text(x.commandId),
    action: text(x.action, 'UNKNOWN'), status: taskStatus(x.status), executionMode: member(x.executionMode, ['MOCK', 'DOCKER', 'LEGACY_MOCK', 'UNKNOWN'], 'UNKNOWN'),
    requestedBy: text(x.requestedBy, '未记录'), message: text(x.message), createdAt: timestamp(x.createdAt), updatedAt: timestamp(x.updatedAt), finishedAt: timestamp(x.finishedAt),
    attempts: Number.isInteger(x.attempts) ? measurement(x.attempts) : null, resultCode: text(x.resultCode) || null }
}

export const taskStatus = (value: unknown): TaskStatus => member(value, ['PENDING', 'DISPATCHING', 'DELIVERED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'UNKNOWN'], 'UNKNOWN')
export const taskStatusLabel = (value: TaskStatus): string => ({ PENDING: '已排队', DISPATCHING: '正在投递', DELIVERED: 'Agent 已接收', RUNNING: '执行中', RETRY_WAIT: '等待确认 / 投递', SUCCEEDED: '成功', FAILED: '失败', UNKNOWN: '结果不确定 · 需核对' })[value]
export const executionLabel = (value: ExecutionMode): string => ({ MOCK: 'Agent 模拟', DOCKER: '真实 Docker', LEGACY_MOCK: '历史数据库模拟', UNKNOWN: '执行来源未验证' })[value]
export function taskMessageLabel(value: string): string {
  const labels: Record<string, string> = {
    ACCEPTED: '任务已接收并排队', DISPATCHING: '控制中心正在投递或核对任务', EXECUTING: 'Agent 正在执行',
    STATE_CONFIRMED: 'Agent 已核验容器生命周期状态；应用业务是否就绪需另行确认', STATE_NOT_CONFIRMED: '暂未核验到预期状态，需要核对',
    EXECUTION_UNCERTAIN: '操作可能已经产生影响，结果不确定，需要核对', AGENT_RESTARTED: 'Agent 执行期间重启，结果不确定，不会自动重做',
    EXPIRED: '任务已过执行期限', COMMAND_EXPIRED_ABSENT: '任务过期，Agent 确认未接收此命令', CONFIRMATION_EXPIRED: '超过结果确认期限，请核对 Agent 与容器',
    TARGET_CHANGED: '操作目标已变化，已停止继续投递', TARGET_UNAVAILABLE: '操作目标无法核验', INSTANCE_NOT_ALLOWED: '实例不在操作允许列表',
    EXECUTION_CONTEXT_CHANGED: '节点、持久记录或执行模式已变化', EXECUTION_MODE_CHANGED: '执行模式已变化，需要重新核对',
    AGENT_BINDING_CHANGED: 'Agent 身份或持久记录已变化，已停止继续投递', AGENT_RECORD_LOST: '已接收的命令记录缺失，结果待人工核对',
    AGENT_COMMAND_CONFLICT: 'Agent 中已有相同命令标识但内容不一致的记录', AGENT_REJECTED: 'Agent 已明确拒绝命令',
    AGENT_QUERY_UNCONFIRMED: '暂未查明 Agent 的任务结果', AGENT_ACCEPTANCE_UNCONFIRMED: '尚未确认 Agent 是否已接收',
    CONTROL_DISABLED: '控制已关闭，停止新投递', LEGACY_MOCK: '历史数据库模拟记录，不会自动下发'
  }
  return labels[value] || (/^[A-Z][A-Z0-9_]+$/.test(value) ? '任务状态已更新，技术结果码见详情' : value)
}
export const actionLabel = (value: string): string => ({ START: '启动', STOP: '停止', RESTART: '重启' })[value] || value
export const taskTerminal = (task: Task): boolean => task.executionMode !== 'UNKNOWN' && (task.status === 'SUCCEEDED' || task.status === 'FAILED')
export function taskResultLabel(task: Task): string {
  if (task.status !== 'SUCCEEDED') return taskStatusLabel(task.status)
  return task.executionMode === 'MOCK' ? '模拟成功 · 未操作 Docker' : task.executionMode === 'LEGACY_MOCK' ? '旧模拟记录完成' : task.executionMode === 'DOCKER' ? 'Agent 报告执行成功' : '结果未验证 · 来源未知'
}
export function normalizeTaskEvents(input: unknown, taskId: string): TaskEvent[] {
  if (!Array.isArray(input)) throw new Error('任务事件格式无效')
  let previous = -1
  return input.map(value => {
    const x = asRecord(value)
    if (x.taskId !== taskId || !text(x.id) || typeof x.sequence !== 'number' || !Number.isSafeInteger(x.sequence) || x.sequence < 1 || x.sequence <= previous || !timestamp(x.occurredAt)) throw new Error('任务事件身份、顺序或格式不匹配')
    previous = x.sequence
    return { id: text(x.id), taskId, sequence: x.sequence, fromStatus: x.fromStatus == null ? null : taskStatus(x.fromStatus), toStatus: taskStatus(x.toStatus), actor: text(x.actor, '未记录'), reason: text(x.reason), occurredAt: timestamp(x.occurredAt) }
  })
}

/** 校验日志响应身份，防止错误路由被展示成另一实例的日志。 */
export function normalizeAgentLogs(input: unknown, expectedId: string): AgentLogs {
  const x = asRecord(input)
  if (x.instanceId !== expectedId || !text(x.nodeId) || !text(x.agentInstanceId) || !timestamp(x.collectedAt)
      || !Array.isArray(x.lines) || !x.lines.every(line => typeof line === 'string')) {
    throw new Error('日志响应格式或实例身份不匹配，已拒绝显示')
  }
  return { instanceId: expectedId, nodeId: text(x.nodeId), agentInstanceId: text(x.agentInstanceId),
    collectedAt: text(x.collectedAt), lines: x.lines as string[] }
}

/** 超过此时间只能称旧快照；页面时钟独立更新，即使关闭轮询也会过期。 */
export const SNAPSHOT_MAX_AGE_MS = 180_000
const FUTURE_CLOCK_TOLERANCE_MS = 60_000
export interface SnapshotQuality { state: 'fresh' | 'stale' | 'unknown' | 'mock'; label: string; detail: string }
const quality = (state: SnapshotQuality['state'], label: string, detail: string): SnapshotQuality => ({ state, label, detail })

function ageQuality(times: (string | null)[], now: number): SnapshotQuality | null {
  if (times.some(value => !value || !Number.isFinite(Date.parse(value)))) return quality('unknown', '未验证快照', '缺少有效采集时间')
  const ages = times.map(value => now - Date.parse(value!))
  if (ages.some(age => age < -FUTURE_CLOCK_TOLERANCE_MS)) return quality('unknown', '未验证快照', '采集时间与浏览器时钟不一致')
  if (ages.some(age => age > SNAPSHOT_MAX_AGE_MS)) return quality('stale', '旧快照', '采集已超过 180 秒')
  return null
}

function observedBefore(observedAt: string, successfulAt: string): boolean {
  const difference = Date.parse(observedAt) - Date.parse(successfulAt)
  if (difference !== 0) return difference < 0
  // 后端保留微秒；Date.parse 只保留毫秒，不能把同毫秒内更早的清单误认成同批。
  const subMillisecond = (value: string) => (value.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1] || '').padEnd(9, '0').slice(3, 9)
  return subMillisecond(observedAt) < subMillisecond(successfulAt)
}

export function nodeQuality(node: NodeItem, now = Date.now()): SnapshotQuality {
  if (node.dataSource === 'MOCK') return quality('mock', '模拟数据', '不代表真实服务器采集结果')
  if (node.status === 'offline') return quality('stale', '旧快照 · 节点离线', '保留最近一次数据，当前状态未验证')
  if (node.syncStatus === 'FAILED') return quality('stale', '旧快照 · 同步失败', node.syncErrorCode || '本轮读取失败')
  if (node.syncStatus === 'PARTIAL') return quality('stale', '旧快照 · 部分同步', node.syncErrorCode || '本轮采集不完整')
  if (node.dataSource === 'LEGACY' || node.syncStatus !== 'OK') return quality('unknown', '未验证快照', '历史数据或尚未完成同步')
  return ageQuality([node.lastSuccessfulSyncAt, node.sampledAt], now) || quality('fresh', '近期快照', '最近一次同步成功；按周期采集')
}

export function instanceQuality(instance: Instance, node: NodeItem | undefined, now = Date.now()): SnapshotQuality {
  if (instance.dataSource === 'MOCK') return quality('mock', '模拟数据', '不代表真实服务器采集结果')
  if (instance.dataSource === 'LEGACY') return quality('unknown', '未验证快照', '历史数据尚未通过新采集协议验证')
  if (!node) return quality('unknown', '未验证快照', '没有可核对的所属节点')
  const parentQuality = nodeQuality(node, now)
  if (parentQuality.state !== 'fresh') return parentQuality
  if (instance.lastSeenAt && node.lastSuccessfulSyncAt && observedBefore(instance.lastSeenAt, node.lastSuccessfulSyncAt)) {
    return quality('stale', '旧快照 · 本轮未发现', '最近一次完整清单没有此实例，保留历史观测值')
  }
  return ageQuality([instance.lastSeenAt, instance.sampledAt], now) || quality('fresh', '近期快照', '状态来自最近一次节点采集')
}

export const sourceLabel = (value: DataSource): string => ({ HOST: '主机采集', DOCKER: 'Docker 采集', MOCK: '模拟数据', LEGACY: '历史数据 / 来源未验证' })[value]
export const metricsLabel = (value: MetricsStatus): string => ({ AVAILABLE: '指标可用', PARTIAL: '指标部分可用', UNAVAILABLE: '指标不可用', UNKNOWN: '指标未验证' })[value]
export const missingMetric = (status: MetricsStatus): string => status === 'UNAVAILABLE' ? '不可用' : '未采集'
export const percent = (value: number | null, status: MetricsStatus): string => value === null ? missingMetric(status) : `${value.toFixed(2)}%`
export function bytes(value: number | null, status: MetricsStatus): string {
  if (value === null) return missingMetric(status)
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`
  return `${value.toFixed(0)} B`
}
export const timeLabel = (value: string | null): string => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未采集'
