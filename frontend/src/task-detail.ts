import type { Task, TaskEvent } from './models'

export interface TaskDetailState { id: string; phase: 'idle' | 'loading' | 'ready' | 'error'; task: Task | null; events: TaskEvent[]; error: string }
export function createTaskReader(fetchTask: (id: string, signal: AbortSignal) => Promise<Task>, fetchEvents: (id: string, signal: AbortSignal) => Promise<TaskEvent[]>, update: (state: TaskDetailState) => void) {
  let generation = 0
  let controller: AbortController | undefined
  return {
    cancel() { generation++; controller?.abort(); update({ id: '', phase: 'idle', task: null, events: [], error: '' }) },
    async select(id: string) {
      const ticket = ++generation
      controller?.abort()
      if (!id) { update({ id: '', phase: 'idle', task: null, events: [], error: '' }); return }
      controller = new AbortController()
      const signal = controller.signal
      update({ id, phase: 'loading', task: null, events: [], error: '' })
      try {
        const [task, events] = await Promise.all([fetchTask(id, signal), fetchEvents(id, signal)])
        if (ticket !== generation || signal.aborted) return
        if (task.id !== id || events.some(event => event.taskId !== id)) throw new Error('任务详情身份不匹配')
        update({ id, phase: 'ready', task, events, error: '' })
      } catch (error) {
        if (ticket === generation && !signal.aborted) update({ id, phase: 'error', task: null, events: [], error: error instanceof Error ? error.message : '读取失败' })
      }
    }
  }
}
