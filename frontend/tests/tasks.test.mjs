import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTs } from './load-ts.mjs'

const models = await loadTs('models')
const control = await loadTs('task-control')
const { createTaskReader } = await loadTs('task-detail')
const { api } = await loadTs('api')
const time = '2026-10-08T15:00:00Z'
const intent = { key: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instanceId: 'central-a', nodeId: 'node-a', agentInstanceId: 'same', name: 'same', action: 'RESTART', expectedExecutionMode: 'MOCK' }
const rawTask = { id: 'task-a', ...intent, executionMode: 'MOCK', commandId: 'command-a', status: 'PENDING', createdAt: time, updatedAt: time, attempts: 0 }
const task = models.normalizeTask(rawTask)
const defer = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const storage = () => { const map = new Map(); return { getItem: key => map.get(key) || null, setItem: (key, value) => map.set(key, value), removeItem: key => map.delete(key) } }
const capabilities = { controlEnabled: true, canControl: true, allowedActions: ['START', 'RESTART'], targets: [{ instanceId: 'central-a', executionMode: 'MOCK', blockingTaskId: null, blockedReason: null, allowedActions: ['RESTART'] }] }

test('任务状态完整保留，未知值不折算排队或成功，执行来源分开', () => {
  for (const status of ['PENDING', 'DISPATCHING', 'DELIVERED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'UNKNOWN']) assert.equal(models.normalizeTask({ ...rawTask, status }).status, status)
  for (const status of [undefined, 'success', 'unrecognized']) assert.equal(models.normalizeTask({ ...rawTask, status }).status, 'UNKNOWN')
  assert.equal(models.normalizeTask({ ...rawTask, executionMode: undefined }).executionMode, 'UNKNOWN')
  const labels = ['MOCK', 'DOCKER', 'LEGACY_MOCK', 'UNKNOWN'].map(executionMode => models.taskResultLabel(models.normalizeTask({ ...rawTask, status: 'SUCCEEDED', executionMode })))
  assert.equal(new Set(labels).size, 4)
  assert.match(labels[0], /模拟成功.*未操作 Docker/)
  assert.match(labels[2], /旧模拟记录/)
  assert.doesNotMatch(labels[3], /成功/)
  assert.equal(models.taskTerminal(models.normalizeTask({ ...rawTask, status: 'SUCCEEDED', executionMode: 'UNKNOWN' })), false)
  assert.equal(task.attempts, 0)
})

test('能力接口严格验证开关、权限、目标 ID、模式与动作交集', () => {
  const caps = control.normalizeCapabilities(capabilities)
  assert.equal(control.allowedTarget(caps, 'central-a', 'RESTART').executionMode, 'MOCK')
  assert.equal(control.allowedTarget(caps, 'central-a', 'START'), undefined)
  assert.equal(control.allowedTarget(caps, 'same', 'RESTART'), undefined)
  for (const value of [null, {}, { ...capabilities, canControl: 'true' }, { ...capabilities, controlEnabled: false }, { ...capabilities, targets: [...capabilities.targets, ...capabilities.targets] }]) {
    assert.equal(control.allowedTarget(control.normalizeCapabilities(value), 'central-a', 'RESTART'), undefined)
  }
  assert.equal(control.normalizeCapabilities({ ...capabilities, targets: [{ ...capabilities.targets[0], executionMode: 'UNKNOWN' }] }).targets.length, 0)
  const readonly = control.normalizeCapabilities({ ...capabilities, controlEnabled: false, canControl: false, targets: [{ ...capabilities.targets[0], blockingTaskId: 'old-outside-list', blockedReason: 'INSTANCE_HAS_UNRESOLVED_TASK', canConfirmPending: true }] })
  assert.equal(readonly.targets[0].blockingTaskId, 'old-outside-list')
  assert.equal(readonly.targets[0].canConfirmPending, false)
  assert.equal(control.allowedTarget(readonly, 'central-a', 'RESTART'), undefined)
})

test('安全随机 UUID 键每个明确新操作不同', () => {
  const a = control.newIntentKey(), b = control.newIntentKey()
  assert.match(a, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.notEqual(a, b)
})

test('HTTP 预览没有 randomUUID 仍用安全随机生成；没有安全随机源则拒绝', t => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  const random = crypto.getRandomValues.bind(crypto)
  t.after(() => Object.defineProperty(globalThis, 'crypto', descriptor))
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues: random } })
  assert.equal(crypto.randomUUID, undefined)
  assert.match(control.newIntentKey(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {} })
  assert.throws(control.newIntentKey, /缺少安全随机数/)
})

test('重复点击只发一次；结果不明后恢复浏览器记录，用原键和原请求核对', async () => {
  const saved = storage(), pending = defer(), calls = []
  let state
  const sender = control.createIntentSender(async value => { calls.push(value); return pending.promise }, saved, next => { state = next })
  const first = sender.submit(intent)
  assert.equal(state.phase, 'submitting')
  assert.equal(await sender.submit({ ...intent, key: control.newIntentKey() }), null)
  pending.reject(new Error('connection lost'))
  await first
  assert.equal(state.phase, 'uncertain')
  await assert.rejects(sender.submit({ ...intent, key: control.newIntentKey() }), /不能创建/)
  const restored = control.createIntentSender(async value => { calls.push(value); return task }, saved, next => { state = next })
  assert.equal(state.phase, 'uncertain')
  assert.equal((await restored.submit()).id, task.id)
  assert.deepEqual(calls, [intent, intent])
  assert.equal(state.phase, 'idle')
  assert.equal(saved.getItem(control.INTENT_STORAGE_KEY), null)
})

test('第一次明确拒绝可结束意图，先前未知请求随后被拒绝仍不能当作从未执行', async () => {
  let state
  const rejected = Object.assign(new Error('permission denied'), { definitiveRejection: true })
  const first = control.createIntentSender(async () => { throw rejected }, storage(), value => { state = value })
  await first.submit(intent)
  assert.equal(state.phase, 'idle')
  const saved = storage()
  saved.setItem(control.INTENT_STORAGE_KEY, JSON.stringify(intent))
  const retry = control.createIntentSender(async () => { throw rejected }, saved, value => { state = value })
  await retry.submit()
  assert.equal(state.phase, 'uncertain')
  assert.equal(state.intent.key, intent.key)
})

test('注销后的晚到任务不能重新显示，原键仍保留', async () => {
  const saved = storage(), pending = defer()
  let state
  const sender = control.createIntentSender(() => pending.promise, saved, value => { state = value })
  const work = sender.submit(intent)
  sender.invalidate()
  pending.resolve(task)
  assert.equal(await work, null)
  assert.equal(state.phase, 'uncertain')
  assert.equal(control.readIntent(saved).key, intent.key)
})

test('存储不可写不发送，损坏记录保持阻止新操作', async () => {
  let calls = 0, state
  const blocked = control.createIntentSender(async () => { calls++; return task }, { getItem: () => null, setItem: () => { throw Error('blocked') }, removeItem() {} }, value => { state = value })
  await blocked.submit(intent)
  assert.equal(calls, 0)
  assert.equal(state.phase, 'uncertain')
  const corrupt = control.createIntentSender(async () => { calls++; return task }, { getItem: () => 'broken json', setItem() {}, removeItem() {} }, value => { state = value })
  await corrupt.submit(intent)
  assert.equal(calls, 0)
  assert.equal(state.phase, 'uncertain')
})

test('排队响应错节点、实例或模式不能解除待确认请求', async () => {
  for (const changes of [{ instanceId: 'central-b' }, { nodeId: 'node-b' }, { executionMode: 'DOCKER' }, { agentInstanceId: 'different' }, { action: 'START' }, { commandId: '' }]) {
    let state
    const sender = control.createIntentSender(async () => ({ ...task, ...changes }), storage(), value => { state = value })
    assert.equal(await sender.submit(intent), null)
    assert.equal(state.phase, 'uncertain')
  }
})

test('任务详情晚到成功或错误不能串到新选择，注销清空结果', async () => {
  const pending = defer(), states = []
  const reader = createTaskReader(id => id === 'task-a' ? pending.promise : Promise.resolve({ ...task, id }), async () => [], state => states.push(state))
  const old = reader.select('task-a')
  await reader.select('task-b')
  pending.reject(new Error('late failure'))
  await old
  assert.equal(states.at(-1).task.id, 'task-b')
  const late = defer()
  const reader2 = createTaskReader(() => late.promise, async () => [], state => states.push(state))
  const work = reader2.select('task-a')
  reader2.cancel()
  late.resolve(task)
  await work
  assert.equal(states.at(-1).phase, 'idle')
  assert.equal(states.at(-1).task, null)
})

test('任务详情错误与不匹配响应不伪装成功，事件必须按同任务严格递增', async () => {
  let state
  const reader = createTaskReader(async () => task, async () => [{ taskId: 'other' }], value => { state = value })
  await reader.select('task-a')
  assert.equal(state.phase, 'error')
  const event = { id: 'event-a', taskId: 'task-a', sequence: 1, fromStatus: null, toStatus: 'PENDING', actor: 'operator', reason: 'queued', occurredAt: time }
  assert.equal(models.normalizeTaskEvents([event], 'task-a')[0].sequence, 1)
  for (const events of [[{ ...event, taskId: 'other' }], [event, event], [{ ...event, sequence: 0 }], [{ ...event, occurredAt: null }]]) assert.throws(() => models.normalizeTaskEvents(events, 'task-a'), /不匹配/)
})

test('POST 精确发送中央 ID、固定幂等键和预期模式，仅 HTTP202 是有效接受', async t => {
  const previous = globalThis.fetch
  t.after(() => { globalThis.fetch = previous })
  const calls = []
  globalThis.fetch = async (url, init) => { calls.push([url, init]); return new Response(JSON.stringify({ code: 0, data: rawTask }), { status: 202 }) }
  await api.control({ ...intent, instanceId: 'central/a' })
  assert.equal(calls[0][0], '/api/instances/central%2Fa/actions')
  assert.equal(calls[0][1].headers.get('Idempotency-Key'), intent.key)
  assert.deepEqual(JSON.parse(calls[0][1].body), { action: 'RESTART', expectedExecutionMode: 'MOCK' })
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 0, data: rawTask }), { status: 200 })
  await assert.rejects(api.control(intent), /待确认/)
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 403, message: 'forbidden' }), { status: 403 })
  await assert.rejects(api.control(intent), error => error.status === 403 && error.definitiveRejection === true)
})
