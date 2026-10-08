import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import { loadTs } from './load-ts.mjs'

const review = await loadTs('task-resolution'), models = await loadTs('models'), control = await loadTs('task-control')
const time = '2026-10-09T02:00:00Z'
const body = { decision: 'ACKNOWLEDGE_UNCERTAINTY', reason: '核对原因', evidence: '日志及观察依据', acknowledgeNoReplay: true, acknowledgeResidualRisk: true }
const task = models.normalizeTask({ id: 'old-task', instanceId: 'central-a', nodeId: 'node-a', agentInstanceId: 'same', commandId: 'old-command', action: 'RESTART', status: 'UNKNOWN', executionMode: 'MOCK', blocksInstance: true })
const intent = { key: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', taskId: task.id, instanceId: task.instanceId, nodeId: task.nodeId, agentInstanceId: task.agentInstanceId, commandId: task.commandId, body }
const resolution = (patch = {}) => ({ id: 'resolution-a', requestKey: intent.key, ...intent, body: undefined, key: undefined, ...body,
  status: 'PENDING', requestedBy: 'operator', createdAt: time, updatedAt: time, appliedAt: null, resultCode: 'REVIEW_ACCEPTED', attempts: 0, agentEvidence: null, ...patch })
const capabilities = { reviewEnabled: true, canReview: true, canRecheck: false, allowedDecisions: ['ACKNOWLEDGE_UNCERTAINTY'], reason: 'READY', blocksInstance: true, resolutionId: null }
const evidence = { taskId: task.id, instanceId: task.instanceId, nodeId: task.nodeId, agentInstanceId: task.agentInstanceId, commandId: task.commandId,
  checkedAt: time, bindingMatches: true, classification: 'READY', reason: 'READY', canAcknowledge: true,
  agentTask: { taskId: task.commandId, instanceId: task.agentInstanceId, action: 'restart', status: 'UNKNOWN', executionMode: 'MOCK', nodeId: task.nodeId },
  workerActive: false, knownProcessesActive: false, lockDisposition: 'OWNED_BY_COMMAND', processAssessment: 'PRIOR_PROCESS_UNVERIFIED' }
const receipt = { classification: 'ACKNOWLEDGED_UNKNOWN', taskStatus: 'UNKNOWN', taskResultCode: 'EXECUTION_UNCERTAIN', taskObservedStatus: null, taskFinishedAt: time,
  processAssessment: 'PRIOR_PROCESS_UNVERIFIED', observationStatus: 'UNAVAILABLE', observedInstanceStatus: null, observedAt: time, reviewedAt: time }
const store = () => { const map = new Map(); return { getItem: key => map.get(key) || null, setItem: (key, value) => map.set(key, value), removeItem: key => map.delete(key) } }
const defer = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

test('核对正文仅标准化换行首尾，码点计数保留 emoji，拒绝空值/NUL/代理字符和未确认风险', () => {
  assert.deepEqual(review.normalizeReviewBody({ ...body, reason: '  原因\r\n😀 \r尾  ' }), { ...body, reason: '原因\n😀 \n尾' })
  assert.equal(review.normalizeReviewBody({ ...body, reason: '😀'.repeat(500) }).reason.length, 1000)
  assert.equal(review.normalizeReviewBody({ ...body, reason: '\u00a0\ufeff\u3000😀\u3000\ufeff\u00a0' }).reason, '😀')
  assert.equal(review.normalizeReviewBody({ ...body, reason: '\u001c原文\u001c' }).reason, '\u001c原文\u001c')
  for (const patch of [{ reason: '😀'.repeat(501) }, { evidence: '证'.repeat(4001) }, { reason: ' ' }, { evidence: 'a\0b' }, { reason: '\ud800' }, { evidence: '\udc00' }, { acknowledgeNoReplay: false }, { acknowledgeResidualRisk: false }, { decision: 'SUCCESS' }]) assert.throws(() => review.normalizeReviewBody({ ...body, ...patch }))
})
test('核对状态与 Agent 证据严格校验，缺失不能成为 APPLIED 或成功', () => {
  for (const status of ['PENDING', 'PROCESSING', 'RETRY_WAIT', 'BLOCKED']) assert.equal(review.normalizeResolution(resolution({ status })).status, status)
  const applied = review.normalizeResolution(resolution({ status: 'APPLIED', appliedAt: time, agentEvidence: receipt }))
  assert.equal(applied.agentEvidence.taskStatus, 'UNKNOWN')
  for (const patch of [{ status: 'SUCCESS' }, { status: 'APPLIED', appliedAt: time }, { requestKey: 'invalid' }, { attempts: -1 }, { agentEvidence: { ...receipt, observationStatus: 'UNAVAILABLE', observedInstanceStatus: 'RUNNING' } }, { agentEvidence: { ...receipt, classification: 'CONFIRMED_TERMINAL' } }]) assert.throws(() => review.normalizeResolution(resolution(patch)))
})
test('确认前证据必须同目标、同原命令，未知布尔值不冒充清理完成', () => {
  assert.equal(review.normalizeReviewEvidence(evidence, task).canAcknowledge, true)
  for (const patch of [{ taskId: 'other' }, { commandId: 'other' }, { bindingMatches: null }, { workerActive: null }, { knownProcessesActive: true }, { agentTask: null }, { lockDisposition: 'OWNED_BY_OTHER' }, { classification: 'RECORD_MISSING' }]) assert.throws(() => review.normalizeReviewEvidence({ ...evidence, ...patch }, task))
  const unavailable = review.normalizeReviewEvidence({ ...evidence, classification: 'UNAVAILABLE', canAcknowledge: false, bindingMatches: null, agentTask: null, workerActive: null, knownProcessesActive: null, lockDisposition: null, processAssessment: null }, task)
  assert.equal(unavailable.workerActive, null)
  // Agent 自报 nodeId 与中央节点主键属于不同命名空间；绑定由中央 bindingMatches 验证。
  assert.equal(review.normalizeReviewEvidence({ ...evidence, agentTask: { ...evidence.agentTask, nodeId: 'agent-reported-node' } }, task).canAcknowledge, true)
})
test('核对权限独立验证，缺字段/假布尔/无互斥/目标记录不匹配都关闭', () => {
  assert.equal(review.normalizeReviewCapabilities(capabilities).canReview, true)
  for (const patch of [{ reviewEnabled: false }, { canReview: 'true' }, { blocksInstance: false }, { blocksInstance: undefined }, { resolutionId: 'existing' }, { allowedDecisions: [] }]) assert.equal(review.normalizeReviewCapabilities({ ...capabilities, ...patch }).canReview, false)
  assert.equal(review.normalizeReviewCapabilities({ ...capabilities, canReview: false, canRecheck: true, resolutionId: 'existing' }).canRecheck, true)
})
test('重复核对点击只发一次，断线后恢复原键；GET 同记录可消除不确定性且不 POST', async () => {
  const storage = store(), pending = defer(), calls = []; let state
  const sender = review.createReviewSender(async x => { calls.push(x); return pending.promise }, storage, x => { state = x })
  const first = sender.submit(intent)
  assert.equal(await sender.submit(intent), null)
  pending.reject(Error('lost ACK')); await first
  assert.equal(state.phase, 'uncertain')
  await assert.rejects(sender.submit({ ...intent, key: control.newIntentKey() }), /不能创建/)
  const restored = review.createReviewSender(async () => { throw Error('must not send') }, storage, x => { state = x })
  assert.equal(state.intent.key, intent.key)
  assert.equal(restored.reconcile(resolution()), true)
  assert.equal(state.phase, 'idle'); assert.equal(calls.length, 1)
})
test('BLOCKED 由服务端 GET 的原键和完整正文恢复；不生成新命令', () => {
  const stored = resolution({ status: 'BLOCKED' })
  assert.deepEqual(review.resolutionIntent(stored), intent)
  assert.equal(review.matchesReviewIntent(stored, intent), true)
  assert.equal(review.matchesReviewIntent({ ...stored, evidence: 'changed' }, intent), false)
})
test('409 保留原意图；同任务另有唯一记录必须显式采用，其他任务不能清除', async () => {
  let state; const storage = store()
  const sender = review.createReviewSender(async () => { throw Object.assign(Error('TASK_ALREADY_HAS_RESOLUTION'), { status: 409, definitiveRejection: true }) }, storage, x => { state = x })
  await sender.submit(intent)
  assert.equal(state.phase, 'uncertain')
  const existing = resolution({ requestKey: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', reason: '另一操作者已记录' })
  assert.equal(sender.reconcile(existing), false)
  assert.equal(sender.adoptExisting({ ...existing, taskId: 'other' }), false)
  assert.equal(sender.adoptExisting(existing), true)
  assert.equal(state.phase, 'idle')
})
test('退出或切页后迟到接受响应不能覆盖新页面，原键保留', async () => {
  const storage = store(), pending = defer(); let state
  const sender = review.createReviewSender(async () => pending.promise, storage, x => { state = x })
  const request = sender.submit(intent); sender.invalidate(); pending.resolve(resolution())
  assert.equal(await request, null); assert.equal(state.phase, 'uncertain')
  assert.ok(storage.getItem(review.REVIEW_STORAGE_KEY))
})
test('首次明确拒绝结束意图；此前不确定的拒绝/身份不符仍保留；存储不可写不发', async () => {
  let state; const rejection = Object.assign(Error('forbidden'), { status: 403, definitiveRejection: true })
  await review.createReviewSender(async () => { throw rejection }, store(), x => { state = x }).submit(intent)
  assert.equal(state.phase, 'idle')
  const storage = store(); storage.setItem(review.REVIEW_STORAGE_KEY, JSON.stringify(intent))
  await review.createReviewSender(async () => { throw rejection }, storage, x => { state = x }).submit()
  assert.equal(state.phase, 'uncertain')
  await review.createReviewSender(async () => resolution({ commandId: 'other' }), store(), x => { state = x }).submit(intent)
  assert.equal(state.phase, 'uncertain')
  let called = false
  await review.createReviewSender(async () => { called = true }, { ...store(), setItem() { throw Error('quota') } }, x => { state = x }).submit(intent)
  assert.equal(called, false)
})
test('独立核对事件必须同记录且递增；不接受另一个任务时间线', () => {
  const event = { id: 'e', resolutionId: 'resolution-a', sequence: 1, fromStatus: null, toStatus: 'PENDING', actor: 'operator', reason: 'REVIEW_ACCEPTED', occurredAt: time }
  assert.equal(review.normalizeResolutionEvents([event], 'resolution-a').length, 1)
  for (const events of [[event, event], [{ ...event, resolutionId: 'other' }], [{ ...event, toStatus: 'SUCCEEDED' }]]) assert.throws(() => review.normalizeResolutionEvents(events, 'resolution-a'))
})

const { descriptor } = parse(readFileSync(new URL('../src/components/TaskResolution.vue', import.meta.url), 'utf8'))
const code = ts.transpileModule(compileScript(descriptor, { id: 'review-test' }).content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
function component(apiOverrides = {}, storage = store(), props = { taskId: task.id, readOnly: false }) {
  let unmount; const emitted = []
  const api = { task: async () => task, taskResolution: async () => null, reviewCapabilities: async () => review.normalizeReviewCapabilities(capabilities),
    reviewEvidence: async () => evidence, resolutionEvents: async () => [], resolveTask: async () => resolution(), ...apiOverrides }
  const modules = { vue: { ...vue, onMounted() {}, onUnmounted(f) { unmount = f } }, '../api': { api }, '../models': models, '../task-control': control, '../task-resolution': review }
  const exports = {}
  vm.runInNewContext(code, { exports, require: name => modules[name], window: { sessionStorage: storage, clearInterval() {} }, AbortController, console })
  const app = exports.default.setup(props, { expose() {}, emit: (...args) => emitted.push(args) })
  return { app, emitted, unmount: () => unmount(), props }
}
async function fill(app) { await app.refresh(); await app.readEvidence(); app.reason.value = body.reason; app.manualEvidence.value = body.evidence; app.noReplay.value = true; app.residualRisk.value = true }
test('实际核对组件需同任务证据及两个未预选确认，202 只显示持久接收', async () => {
  const calls = []; const { app } = component({ resolveTask: async value => { calls.push(value); return resolution({ requestKey: value.key }) } })
  await app.refresh()
  assert.equal(app.noReplay.value, false); assert.equal(app.residualRisk.value, false)
  app.reason.value = body.reason; app.manualEvidence.value = body.evidence; app.noReplay.value = true
  await app.readEvidence(); await app.submitNew(); assert.equal(calls.length, 0)
  app.residualRisk.value = true; await app.submitNew()
  assert.equal(calls.length, 1); assert.match(app.notice.value, /202.*尚不代表/)
  assert.equal(app.task.value.status, 'UNKNOWN')
})
test('实际组件：权限失败/全局只读/证据失败关闭，失效证据立即清空', async () => {
  const { app, props } = component(); await fill(app)
  assert.equal(app.canSubmit.value, true)
  props.readOnly = true
  // props 在实际 Vue 中响应式；此处直接重新构造只读组件验证。
  const readonly = component({}, store(), { taskId: task.id, readOnly: true }); await fill(readonly.app)
  assert.equal(readonly.app.canSubmit.value, false)
  const failed = component({ reviewCapabilities: async () => { throw Error('offline') } }); await fill(failed.app)
  assert.equal(failed.app.canSubmit.value, false); assert.equal(failed.app.ready.value, true)
  let fail = false; const item = component({ reviewEvidence: async () => { if (fail) throw Error('unavailable'); return evidence } })
  await fill(item.app); fail = true; const read = item.app.readEvidence(); assert.equal(item.app.evidence.value, null); await read
  assert.equal(item.app.canSubmit.value, false)
})
test('实际组件：恢复先 GET，丢回应后发现记录无需 POST；BLOCKED 显式续办原键原正文', async () => {
  const storage = store(); storage.setItem(review.REVIEW_STORAGE_KEY, JSON.stringify(intent))
  let posts = 0, current = resolution({ status: 'BLOCKED' })
  const { app } = component({ taskResolution: async () => current, reviewCapabilities: async () => review.normalizeReviewCapabilities({ ...capabilities, canReview: false, canRecheck: true, resolutionId: current.id }),
    resolveTask: async value => { posts++; assert.deepEqual(value, intent); current = resolution(); return current } }, storage)
  await app.refresh(); assert.equal(posts, 0); assert.equal(app.intentState.value.phase, 'idle'); assert.equal(app.canRecheck.value, true)
  await app.recheck(); assert.equal(posts, 1); assert.equal(app.record.value.id, current.id)
})
test('实际组件：晚到证据/记录及接受响应在切任务或注销卸载后不能更新', async () => {
  const pending = defer(); const item = component({ reviewEvidence: async () => pending.promise })
  await item.app.refresh(); const reading = item.app.readEvidence(); item.unmount(); pending.resolve(evidence); await reading
  assert.equal(item.app.evidence.value, null)
  const response = defer(); const view = component({ resolveTask: async () => response.promise })
  await fill(view.app); const sending = view.app.submitNew(); view.unmount(); response.resolve(resolution()); await sending
  assert.equal(view.emitted.length, 0); assert.equal(view.app.intentState.value.phase, 'uncertain')
})
test('实际组件：APPLIED 保留原 UNKNOWN；Agent 历史终态是独立证据', async () => {
  const applied = resolution({ status: 'APPLIED', appliedAt: time, agentEvidence: { ...receipt, classification: 'CONFIRMED_TERMINAL', taskStatus: 'SUCCEEDED', taskObservedStatus: 'RUNNING' } })
  const { app } = component({ task: async () => ({ ...task, blocksInstance: false }), taskResolution: async () => applied })
  await app.refresh(); assert.equal(app.task.value.status, 'UNKNOWN'); assert.equal(app.task.value.blocksInstance, false)
  assert.equal(app.record.value.agentEvidence.taskStatus, 'SUCCEEDED'); assert.equal(app.canSubmit.value, false)
})
test('核对 API 严格 202 与独立 URL/原键/固定正文，200 不能当接受', async t => {
  const original = globalThis.fetch; t.after(() => { globalThis.fetch = original })
  const { api } = await loadTs('api'); const calls = []
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify({ code: 0, data: resolution() }), { status: 202 }) }
  await api.resolveTask(intent)
  assert.equal(calls[0].url, '/api/tasks/old-task/resolutions'); assert.equal(calls[0].init.headers.get('Idempotency-Key'), intent.key)
  assert.deepEqual(JSON.parse(calls[0].init.body), body)
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 0, data: resolution() }), { status: 200 })
  await assert.rejects(api.resolveTask(intent), /提交结果待确认/)
})
