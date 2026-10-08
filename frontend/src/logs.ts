import type { AgentLogs } from './models'

export interface LogState {
  targetId: string
  phase: 'idle' | 'loading' | 'ready' | 'error'
  data: AgentLogs | null
  error: string
  errorStatus?: number
}

/** 选择变化、退出页面或注销会立即失效旧请求；即使取消被忽略，也用序号拒绝晚到响应。 */
export function createLogReader(fetchLogs: (id: string, signal: AbortSignal) => Promise<AgentLogs>, update: (state: LogState) => void) {
  let generation = 0
  let controller: AbortController | undefined
  function cancel() {
    generation++
    controller?.abort()
    controller = undefined
    update({ targetId: '', phase: 'idle', data: null, error: '' })
  }
  async function select(targetId: string) {
    cancel()
    if (!targetId) return
    const ticket = generation
    controller = new AbortController()
    const signal = controller.signal
    update({ targetId, phase: 'loading', data: null, error: '' })
    try {
      const data = await fetchLogs(targetId, signal)
      if (ticket !== generation || signal.aborted) return
      if (data.instanceId !== targetId) throw new Error('日志实例身份不匹配，已拒绝显示')
      update({ targetId, phase: 'ready', data, error: '' })
    } catch (error) {
      if (ticket !== generation || signal.aborted) return
      const errorStatus = typeof error === 'object' && error && 'status' in error && typeof error.status === 'number' ? error.status : undefined
      update({ targetId, phase: 'error', data: null, error: error instanceof Error ? error.message : '日志读取失败', errorStatus })
    }
  }
  return { select, cancel }
}
