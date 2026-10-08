import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTs } from './load-ts.mjs'

const m = await loadTs('models')
const time = '2026-10-08T13:00:00Z'
const now = Date.parse(time)
const rawNode = { id: 'node-a', status: 'ONLINE', dataSource: 'HOST', metricsStatus: 'AVAILABLE', syncStatus: 'OK', sampledAt: time, lastSuccessfulSyncAt: time, lastCheckedAt: time, cpuPercent: 0, memoryBytes: 0, memoryTotalBytes: 1024 }
const rawInstance = { id: 'central-a', nodeId: 'node-a', agentInstanceId: 'same-name', status: 'RUNNING', dataSource: 'DOCKER', metricsStatus: 'PARTIAL', sampledAt: time, lastSeenAt: time, cpuPercent: 0, memoryBytes: 0, playerCount: null }

test('缺失和非法指标保持未知，不伪造成 0 或默认 TPS', () => {
  for (const value of [undefined, null, '', '0', false, NaN, Infinity, -1]) assert.equal(m.measurement(value), null)
  const instance = m.normalizeInstance({ id: 'control-id', cpuPercent: null, memoryBytes: null })
  assert.equal(instance.cpuPercent, null)
  assert.equal(instance.memoryBytes, null)
  assert.equal(instance.players, null)
  assert.equal('tps' in instance, false)
  assert.equal(m.percent(instance.cpuPercent, 'UNAVAILABLE'), '不可用')
  assert.equal(m.bytes(instance.memoryBytes, 'UNKNOWN'), '未采集')
})

test('真实的 CPU/内存/玩家 0 均保留，离线也不篡改最近观测值', () => {
  const node = m.normalizeNode(rawNode)
  const instance = m.normalizeInstance({ ...rawInstance, playerCount: 0 })
  assert.equal(node.cpu, 0)
  assert.equal(node.memory, 0)
  assert.equal(instance.players, 0)
  assert.equal(m.percent(instance.cpuPercent, 'AVAILABLE'), '0.00%')
  assert.equal(m.bytes(instance.memoryBytes, 'AVAILABLE'), '0 B')
  assert.equal(m.normalizeNode({ ...rawNode, status: 'OFFLINE', cpuPercent: 9 }).cpu, 9)
})

test('MOCK 和旧协议数据都有明确来源，不能被判定为真实近期快照', () => {
  const node = m.normalizeNode(rawNode)
  const mock = m.normalizeInstance({ ...rawInstance, dataSource: 'MOCK' })
  assert.equal(m.instanceQuality(mock, node, now).state, 'mock')
  assert.equal(m.sourceLabel(mock.dataSource), '模拟数据')
  const legacy = m.normalizeInstance({ id: 'old', nodeId: 'node-a', containerName: 'same-name', cpuPercent: 0, lastHeartbeat: time })
  assert.equal(legacy.dataSource, 'LEGACY')
  assert.equal(legacy.lastSeenAt, null)
  assert.equal(m.instanceQuality(legacy, node, now).state, 'unknown')
})

test('同名容器保留不同中央标识和所属节点，不回退为容器名 ID', () => {
  const a = m.normalizeInstance(rawInstance)
  const b = m.normalizeInstance({ ...rawInstance, id: 'central-b', nodeId: 'node-b' })
  assert.equal(a.agentInstanceId, b.agentInstanceId)
  assert.notEqual(a.id, b.id)
  assert.notEqual(a.nodeId, b.nodeId)
  assert.equal(m.normalizeInstance({ containerName: 'same-name' }).id, '')
})

test('近期快照必须同时有所属节点成功采集和实例自身有效时间', () => {
  const node = m.normalizeNode(rawNode)
  const instance = m.normalizeInstance(rawInstance)
  assert.equal(m.instanceQuality(instance, node, now).state, 'fresh')
  assert.equal(m.instanceQuality(instance, undefined, now).state, 'unknown')
  assert.equal(m.instanceQuality({ ...instance, sampledAt: null }, node, now).state, 'unknown')
  for (const changes of [{ status: 'OFFLINE' }, { syncStatus: 'FAILED' }, { syncStatus: 'PARTIAL' }]) {
    assert.equal(m.instanceQuality(instance, m.normalizeNode({ ...rawNode, ...changes }), now).state, 'stale')
  }
  assert.equal(m.nodeQuality(m.normalizeNode({ ...rawNode, syncStatus: 'UNKNOWN' }), now).state, 'unknown')
})

test('180 秒后过期，未来时间和缺失时间不能伪装为新鲜', () => {
  const node = m.normalizeNode(rawNode)
  assert.equal(m.nodeQuality(node, now + m.SNAPSHOT_MAX_AGE_MS).state, 'fresh')
  assert.equal(m.nodeQuality(node, now + m.SNAPSHOT_MAX_AGE_MS + 1).state, 'stale')
  assert.equal(m.nodeQuality(node, now - 61_000).state, 'unknown')
  assert.equal(m.nodeQuality({ ...node, lastSuccessfulSyncAt: null }, now).state, 'unknown')
})

test('成功空清单或实例消失后立即标本轮未发现，保留微秒精度区分同步批次', () => {
  const node = m.normalizeNode({ ...rawNode, lastSuccessfulSyncAt: '2026-10-08T13:00:00.000002Z' })
  const missed = m.normalizeInstance({ ...rawInstance, lastSeenAt: '2026-10-08T13:00:00.000001Z' })
  assert.match(m.instanceQuality(missed, node, now).label, /本轮未发现/)
  assert.equal(missed.status, 'running')
  assert.equal(missed.cpuPercent, 0)
  const sameBatch = { ...missed, lastSeenAt: node.lastSuccessfulSyncAt }
  assert.equal(m.instanceQuality(sameBatch, node, now).state, 'fresh')
})

test('日志响应校验身份与结构，合法空日志仍是成功结果', () => {
  const response = { instanceId: 'central-a', nodeId: 'node-a', agentInstanceId: 'same-name', collectedAt: time, lines: [] }
  assert.deepEqual(m.normalizeAgentLogs(response, 'central-a').lines, [])
  assert.throws(() => m.normalizeAgentLogs(response, 'central-b'), /不匹配/)
  assert.throws(() => m.normalizeAgentLogs({ ...response, lines: [{ message: 'untrusted' }] }, 'central-a'), /不匹配/)
})
