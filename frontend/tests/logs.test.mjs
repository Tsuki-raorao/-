import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTs } from './load-ts.mjs'

const { createLogReader } = await loadTs('logs')
const result = id => ({ instanceId: id, nodeId: `node-${id}`, agentInstanceId: 'same-name', collectedAt: '2026-10-08T13:00:00Z', lines: [`logs-from-${id}`] })
function pending() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('切换同名实例后，晚到的上一实例结果被丢弃，即使网络忽略取消', async () => {
  const a = pending(), b = pending()
  const signals = []
  let state
  const reader = createLogReader((id, signal) => { signals.push(signal); return id === 'a' ? a.promise : b.promise }, next => { state = next })
  const readA = reader.select('a')
  const readB = reader.select('b')
  assert.equal(signals[0].aborted, true)
  assert.equal(state.targetId, 'b')
  assert.equal(state.data, null)
  b.resolve(result('b'))
  await readB
  a.resolve(result('a'))
  await readA
  assert.equal(state.data.instanceId, 'b')
  assert.deepEqual(state.data.lines, ['logs-from-b'])
})

test('迟到错误不能覆盖新实例，当前错误不会保留上一份日志', async () => {
  const a = pending(), b = pending()
  let state
  const reader = createLogReader(id => id === 'a' ? a.promise : id === 'b' ? b.promise : Promise.reject(Object.assign(new Error('Agent 不可达'), { status: 502 })), next => { state = next })
  const readA = reader.select('a')
  const readB = reader.select('b')
  b.resolve(result('b'))
  await readB
  a.reject(new Error('旧请求失败'))
  await readA
  assert.equal(state.phase, 'ready')
  await reader.select('c')
  assert.equal(state.phase, 'error')
  assert.equal(state.data, null)
  assert.equal(state.errorStatus, 502)
  assert.match(state.error, /Agent 不可达/)
})

test('空选择不发默认请求，退出/注销会清空并使未完成请求失效', async () => {
  const a = pending()
  let calls = 0, state
  const reader = createLogReader(() => { calls++; return a.promise }, next => { state = next })
  await reader.select('')
  assert.equal(calls, 0)
  const active = reader.select('a')
  reader.cancel()
  a.resolve(result('a'))
  await active
  assert.equal(state.phase, 'idle')
  assert.equal(state.data, null)
})

test('响应中错误的中央实例 ID 被拒绝展示', async () => {
  let state
  const reader = createLogReader(async () => result('different-id'), next => { state = next })
  await reader.select('a')
  assert.equal(state.phase, 'error')
  assert.equal(state.data, null)
})
