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
const { descriptor } = parse(readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8'))
const componentCode = ts.transpileModule(compileScript(descriptor, { id: 'refresh-test' }).content, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }
}).outputText

function component(api) {
  class ApiRequestError extends Error {}
  const modules = {
    vue: { ...vue, onMounted() {}, onUnmounted() {}, watch() {} },
    './api': { api, ApiRequestError, getApiToken: () => '', clearApiToken() {}, saveApiToken() {} },
    './models': models, './logs': logs, './components/InstanceTable.vue': { default: {} }
  }
  const exports = {}
  vm.runInNewContext(componentCode, { exports, require: name => modules[name], window: { location: { hash: '#/overview' } }, console })
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
