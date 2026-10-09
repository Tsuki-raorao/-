import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import { loadTs } from './load-ts.mjs'

// 编译实际组件的 setup，替换网络和生命周期；检验请求时序及页面提交，而非复制 load 实现。
const models = await loadTs('models')
const logs = await loadTs('logs')
const taskControl = await loadTs('task-control')
const taskDetail = await loadTs('task-detail')
const taskResolution = await loadTs('task-resolution')
const authSession = await loadTs('auth-session')
const scopedRecovery = await loadTs('scoped-recovery')
const { descriptor } = parse(readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8'))
const componentCode = ts.transpileModule(compileScript(descriptor, { id: 'refresh-test' }).content, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }
}).outputText

function component(api) {
  class ApiRequestError extends Error {}
  const modules = {
    vue: { ...vue, onMounted() {}, onUnmounted() {}, watch() {} },
    './api': { api, ApiRequestError, getApiToken: () => '', clearApiToken() {}, saveApiToken() {} },
    './models': models, './logs': logs, './task-control': taskControl, './task-detail': taskDetail, './task-resolution': taskResolution,
    './auth-session': authSession, './scoped-recovery': scopedRecovery,
    './components/InstanceTable.vue': { default: {} }, './components/TaskDetail.vue': { default: {} }, './components/TaskResolution.vue': { default: {} }
  }
  const exports = {}
  vm.runInNewContext(componentCode, { exports, require: name => modules[name], window: { location: { hash: '#/overview' }, history: { pushState() {} }, sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} } }, console })
  return exports.default.setup({}, { expose() {} })
}
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

test('刷新先完成节点读取再请求实例，健康和任务仍并行，数据全部成功才提交', async () => {
  const calls = []
  const nodes = deferred(), instances = deferred(), instancesStarted = deferred()
  const app = component({
    health: async () => { calls.push('health'); return { status: 'UP', readOnly: true } },
    nodes: () => { calls.push('nodes'); return nodes.promise },
    instances: () => { calls.push('instances'); instancesStarted.resolve(); return instances.promise },
    tasks: async () => { calls.push('tasks'); return [] }
  })
  const refresh = app.load()
  assert.deepEqual(calls, ['health', 'nodes', 'tasks'])
  nodes.resolve([{ id: 'node-a' }])
  await instancesStarted.promise
  assert.deepEqual(calls, ['health', 'nodes', 'tasks', 'instances'])
  assert.equal(app.nodes.value.length, 0)
  assert.equal(app.connected.value, false)
  instances.resolve([{ id: 'central-a' }])
  await refresh
  assert.equal(app.nodes.value[0].id, 'node-a')
  assert.equal(app.instances.value[0].id, 'central-a')
  assert.equal(app.connected.value, true)
})

test('节点成功但实例失败不能提交半份页面或声称刷新成功', async () => {
  const app = component({
    health: async () => ({ status: 'UP', readOnly: true }), nodes: async () => [{ id: 'node-a' }],
    instances: async () => { throw new Error('instance read failed') }, tasks: async () => []
  })
  await app.load()
  assert.equal(app.connected.value, false)
  assert.equal(app.nodes.value.length, 0)
  assert.equal(app.instances.value.length, 0)
  assert.notEqual(app.loadError.value, '')
})

const controlledInstance = { id: 'central-a', nodeId: 'node-a', agentInstanceId: 'same', name: 'same', status: 'running', dataSource: 'MOCK' }
const capabilities = { controlEnabled: true, canControl: true, allowedActions: ['START', 'STOP', 'RESTART'], targets: [{ instanceId: 'central-a', executionMode: 'MOCK', blockingTaskId: null, blockedReason: null, canConfirmPending: true, allowedActions: ['START', 'STOP', 'RESTART'] }], reason: '' }
const baseApi = () => ({ health: async () => ({ status: 'UP', readOnly: false }), nodes: async () => [{ id: 'node-a' }], instances: async () => [controlledInstance], tasks: async () => [], capabilities: async () => capabilities })

test('能力接口失败只关闭操作，不使观察页不可用；只读始终优先', async () => {
  const app = component({ ...baseApi(), capabilities: async () => { throw Error('unavailable') } })
  await app.load()
  assert.equal(app.connected.value, true)
  assert.equal(app.instances.value.length, 1)
  assert.match(app.actionReason(controlledInstance, 'RESTART'), /权限读取失败/)
  app.capabilities.value = capabilities
  app.readOnly.value = true
  assert.match(app.actionReason(controlledInstance, 'RESTART'), /只读/)
})

test('真正编译的组件需先确认才提交，202 只显示接受；任务成功不改观测实例', async () => {
  const calls = []
  const app = component({ ...baseApi(), control: async intent => {
    calls.push(intent)
    return models.normalizeTask({ ...intent, id: 'task-a', commandId: 'command-a', status: 'SUCCEEDED', executionMode: intent.expectedExecutionMode })
  } })
  await app.load()
  app.control('RESTART', controlledInstance)
  assert.equal(calls.length, 0)
  assert.equal(app.confirmation.value.instanceId, 'central-a')
  app.confirmation.value = null
  await app.confirmControl()
  assert.equal(calls.length, 0)
  app.control('RESTART', controlledInstance)
  await app.confirmControl()
  assert.equal(calls.length, 1)
  assert.match(calls[0].key, /^[0-9a-f-]{36}$/)
  assert.match(app.notice.value, /202/)
  assert.match(app.notice.value, /不代表已经执行成功/)
  assert.equal(app.instances.value[0].status, 'running')
  assert.equal(app.page.value, 'tasks')
})

test('确认期间模式变化不发送；已有 UNKNOWN 任务不能重新控制', async () => {
  let sent = 0
  const app = component({ ...baseApi(), control: async () => { sent++ } })
  await app.load()
  app.control('RESTART', controlledInstance)
  app.capabilities.value = { ...capabilities, targets: [{ ...capabilities.targets[0], executionMode: 'DOCKER' }] }
  await app.confirmControl()
  assert.equal(sent, 0)
  assert.match(app.notice.value, /模式已变化/)
  app.capabilities.value = { ...capabilities, targets: [{ ...capabilities.targets[0], blockingTaskId: 'task-a', blockedReason: 'INSTANCE_HAS_UNRESOLVED_TASK', allowedActions: [] }] }
  app.tasks.value = [models.normalizeTask({ id: 'task-a', instanceId: 'central-a', status: 'UNKNOWN', executionMode: 'MOCK', blocksInstance: true })]
  assert.match(app.actionReason(controlledInstance, 'START'), /未决任务/)
})

test('权威锁覆盖最近100任务范围；APPLIED旧UNKNOWN不挡独立新动作，旧回执不能解除新锁', async () => {
  const calls = []
  const app = component({ ...baseApi(), control: async intent => {
    calls.push(intent)
    return models.normalizeTask({ ...intent, id: `new-${calls.length}`, commandId: `new-command-${calls.length}`, status: 'PENDING', executionMode: intent.expectedExecutionMode, blocksInstance: true })
  } })
  await app.load()
  app.tasks.value = []
  app.capabilities.value = { ...capabilities, targets: [{ ...capabilities.targets[0], blockingTaskId: 'older-than-100', blockedReason: 'INSTANCE_HAS_UNRESOLVED_TASK', allowedActions: [] }] }
  assert.equal(app.blockingTask(controlledInstance), 'older-than-100')
  assert.match(app.actionReason(controlledInstance, 'START'), /未决任务/)
  app.tasks.value = [models.normalizeTask({ id: 'old', instanceId: controlledInstance.id, status: 'UNKNOWN', executionMode: 'MOCK', blocksInstance: false, reviewSummary: { id: 'review', status: 'APPLIED' } })]
  app.capabilities.value = capabilities
  assert.equal(app.actionReason(controlledInstance, 'START'), '')
  app.control('START', controlledInstance); await app.confirmControl()
  assert.equal(calls.length, 1); assert.match(calls[0].key, /^[0-9a-f-]{36}$/)
  app.capabilities.value = { ...capabilities, targets: [{ ...capabilities.targets[0], blockingTaskId: 'new-1', blockedReason: 'INSTANCE_HAS_UNRESOLVED_TASK', allowedActions: [] }] }
  app.mergeTask(models.normalizeTask({ id: 'old', instanceId: controlledInstance.id, status: 'UNKNOWN', executionMode: 'MOCK', blocksInstance: false, reviewSummary: { id: 'review', status: 'APPLIED' } }))
  assert.equal(app.blockingTask(controlledInstance), 'new-1')
  assert.match(app.actionReason(controlledInstance, 'START'), /未决任务/)
  assert.equal(app.instances.value[0].status, 'running')
})

test('全部目标有锁禁止新动作，但丢ACK原请求仍按显式canConfirmPending授权同键核对', async () => {
  const app = component(baseApi()); await app.load()
  app.capabilities.value = taskControl.normalizeCapabilities({ ...capabilities, targets: [{ ...capabilities.targets[0], blockingTaskId: 'accepted-before-ack-loss', blockedReason: 'INSTANCE_HAS_UNRESOLVED_TASK', allowedActions: [] }] })
  app.intentState.value = { phase: 'uncertain', error: '', intent: { key: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instanceId: 'central-a', nodeId: 'node-a', agentInstanceId: 'same', name: 'same', action: 'RESTART', expectedExecutionMode: 'MOCK' } }
  assert.equal(app.canConfirmPending.value, true)
  assert.notEqual(app.actionReason(controlledInstance, 'RESTART'), '')
  const authorized = app.capabilities.value
  app.capabilities.value = { ...authorized, targets: [{ ...authorized.targets[0], canConfirmPending: false }] }
  assert.equal(app.canConfirmPending.value, false)
  app.capabilities.value = authorized
  for (const patch of [{ nodeId: 'changed-node' }, { agentInstanceId: 'changed-container' }, { dataSource: 'LEGACY' }, { dataSource: 'DOCKER' }]) {
    app.instances.value = [{ ...controlledInstance, ...patch }]
    assert.equal(app.canConfirmPending.value, false)
  }
  app.instances.value = []; assert.equal(app.canConfirmPending.value, false)
  app.instances.value = [controlledInstance]
  app.readOnly.value = true; assert.equal(app.canConfirmPending.value, false)
})
