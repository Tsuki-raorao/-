export interface MonitorAlert { id: string; title: string; resourceId: string; ruleKey: string; state: string; observation: string; value: number | null; threshold: number; checkedAt: string; summary: string }
export interface MonitorPoint { value: number | null; observation: string; source: string; at: string }
export function observationUnknown(alert: MonitorAlert, now = Date.now()): boolean {
  const at = Date.parse(alert.checkedAt)
  return alert.observation !== 'KNOWN' || !Number.isFinite(at) || at > now + 5000 || now - at > 90000
}
function row(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('监控数据格式无效')
  return Object.fromEntries(Object.entries(value).map(([key, v]) => [key.toLowerCase(), v]))
}
const number = (v: unknown): number | null => typeof v === 'number' && Number.isFinite(v) ? v : null
const text = (v: unknown): string => typeof v === 'string' ? v : ''
const time = (v: unknown): string => typeof v === 'number' ? new Date(v).toISOString() : text(v)
export function monitoringSummary(value: unknown) {
  const x = row(value)
  if (typeof x.enabled !== 'boolean' || !Array.isArray(x.alerts) || !Array.isArray(x.events)) throw Error('监控响应不完整')
  return { enabled: x.enabled, alerts: x.alerts.map(v => { const a = row(v); return {
    id: text(a.id), title: text(a.title), resourceId: text(a.resource_id), ruleKey: text(a.rule_key), state: text(a.state),
    observation: text(a.observation_state), value: number('observed_value' in a ? a.observed_value : a.last_value), threshold: number(a.threshold_value) ?? 0,
    checkedAt: time(a.checked_at), summary: text(a.summary)
  } as MonitorAlert }), events: x.events.map(row) }
}
export function monitoringHistory(value: unknown): MonitorPoint[] {
  if (!Array.isArray(value)) throw Error('历史指标不是列表')
  return value.map(v => { const p = row(v); return { value: p.observation_state === 'KNOWN' ? number('metric_value' in p ? p.metric_value : p.value) : null, observation: text(p.observation_state), source: text(p.source), at: time(p.sampled_at) } }).reverse()
}
/** 未知点中断折线，不能把采集失败连成 0 或平滑正常。 */
export function chartSegments(points: MonitorPoint[]): string[] {
  const max = Math.max(100, ...points.flatMap(p => p.value === null ? [] : [p.value]))
  const segments: string[] = []; let current: string[] = []
  points.forEach((p, i) => {
    if (p.value === null) { if (current.length) segments.push(current.join(' ')); current = []; return }
    current.push(`${points.length < 2 ? 0 : i * 600 / (points.length - 1)},${160 - p.value * 150 / max}`)
  })
  if (current.length) segments.push(current.join(' '))
  return segments
}
