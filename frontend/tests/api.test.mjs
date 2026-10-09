import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTs } from './load-ts.mjs'

const { api } = await loadTs('api')
const { authSession } = await loadTs('auth-session')
const projectId = '11111111-1111-4111-8111-111111111111'
const response = (data, status = 200) => new Response(JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? 'ok' : 'Agent 不可达', data }), { status, headers: { 'Content-Type': 'application/json' } })

test('日志通过编码后的中央实例 ID 请求新接口，不用容器名或旧数据库日志路径', async t => {
  const calls = []
  const original = globalThis.fetch
  t.after(() => { globalThis.fetch = original })
  globalThis.fetch = async url => {
    calls.push(url)
    const id = calls.length === 1 ? 'central/a' : 'central/b'
    return response({ instanceId: id, nodeId: `node-${id}`, agentInstanceId: 'same-name', collectedAt: '2026-10-08T13:00:00Z', lines: [id] })
  }
  assert.equal((await api.recentLogs('central/a')).lines[0], 'central/a')
  assert.equal((await api.recentLogs('central/b')).lines[0], 'central/b')
  assert.deepEqual(calls, ['/api/instances/central%2Fa/logs?limit=100', '/api/instances/central%2Fb/logs?limit=100'])
})

test('缺少选择不发请求，服务错误不回退成模拟数据或空日志成功', async t => {
  let calls = 0
  const original = globalThis.fetch
  t.after(() => { globalThis.fetch = original })
  globalThis.fetch = async () => { calls++; return response(null, 503) }
  await assert.rejects(api.recentLogs(''), /选择实例/)
  assert.equal(calls, 0)
  await assert.rejects(api.recentLogs('central-a'), /Agent 不可达/)
  await assert.rejects(api.nodes(), /Agent 不可达/)
  assert.equal(calls, 2)
})

test('无效响应与业务错误按失败处理', async t => {
  const original = globalThis.fetch
  t.after(() => { globalThis.fetch = original })
  globalThis.fetch = async () => response({ unexpected: true })
  await assert.rejects(api.instances(), /无效列表/)
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 9, message: 'Gateway 未启用', data: [] }))
  await assert.rejects(api.recentLogs('central-a'), /Gateway 未启用/)
})

test('OIDC 项目目录与平台管理不误带当前项目；项目资源带 projectId，登出带 CSRF', async t => {
  const originalFetch = globalThis.fetch
  const originalWindow = globalThis.window
  const calls = []
  t.after(() => {
    globalThis.fetch = originalFetch
    globalThis.window = originalWindow
    authSession.configure({ mode: 'LEGACY_TOKEN', loginPath: null, csrfRequired: false, readOnly: false })
  })
  authSession.configure({ mode: 'OIDC_IDENTITY', loginPath: '/api/auth/login', csrfRequired: true, readOnly: false })
  authSession.identify({ userId: 'user-a', displayName: '操作员', platformRole: 'USER', status: 'ACTIVE', authMode: 'OIDC_SESSION', readOnly: false })
  authSession.setCsrf({ headerName: 'X-CSRF-TOKEN', token: 'csrf-a' })
  authSession.setProjects([{ id: projectId, name: '项目 A', status: 'ACTIVE', role: 'OPERATOR', permissions: ['RESOURCE_READ', 'TASK_OPERATE'], permissionVersion: 3 }])
  globalThis.window = { sessionStorage: { getItem() { return null } }, dispatchEvent() {} }
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url, method: init.method || 'GET', headers: new Headers(init.headers) })
    const path = String(url)
    if (path.endsWith('/projects')) return response([])
    return response([])
  }
  await api.authProjects()
  await api.adminUsers()
  await api.nodes()
  await api.authLogout()
  assert.deepEqual(calls.map(item => item.url), ['/api/projects', '/api/admin/users', `/api/nodes?projectId=${projectId}`, '/api/auth/logout'])
  assert.equal(calls.every(item => !item.headers.has('Authorization')), true)
  assert.equal(calls[3].method, 'POST')
  assert.equal(calls[3].headers.get('X-CSRF-TOKEN'), 'csrf-a')
})
