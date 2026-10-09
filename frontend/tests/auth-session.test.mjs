import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTs } from './load-ts.mjs'

const auth = await loadTs('auth-session')
const recovery = await loadTs('scoped-recovery')
const projectA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: '项目 A', status: 'ACTIVE', role: 'OPERATOR', permissions: ['RESOURCE_READ', 'TASK_OPERATE'], permissionVersion: 7 }
const projectB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', name: '项目 B', status: 'ACTIVE', role: 'VIEWER', permissions: ['RESOURCE_READ'], permissionVersion: 2 }
const oidcConfig = { mode: 'OIDC_IDENTITY', loginPath: '/api/auth/login', csrfRequired: true, readOnly: false }
const identity = { userId: 'user-a', displayName: '操作员', platformRole: 'USER', status: 'ACTIVE', authMode: 'OIDC_SESSION', readOnly: false }

function memoryStorage() {
  const values = new Map()
  return { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key), values }
}

test('身份配置不接受错误登录入口或隐式认证回退，项目列表拒绝重复和非法权限', () => {
  assert.throws(() => auth.normalizeAuthConfig({ ...oidcConfig, loginPath: '/login' }), /身份配置无效/)
  assert.throws(() => auth.normalizeAuthConfig({ mode: 'LEGACY_TOKEN', loginPath: '/api/auth/login', csrfRequired: false, readOnly: false }), /身份配置无效/)
  assert.throws(() => auth.normalizeProjects([projectA, projectA]), /项目身份或权限未通过验证/)
  assert.deepEqual(auth.normalizeProjects([{ ...projectA, permissions: ['RESOURCE_READ', 'UNKNOWN', 'RESOURCE_READ'] }])[0].permissions, ['RESOURCE_READ'])
  assert.throws(() => auth.normalizeProjects([{ ...projectA, id: 'project-name' }]), /项目身份或权限未通过验证/)
})

test('切换用户或项目递增边界，旧请求作用域和旧权限版本都失效', () => {
  const session = auth.createAuthSession()
  session.configure(oidcConfig)
  session.identify(identity)
  session.setCsrf({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
  session.setProjects([projectA, projectB])
  const old = session.capture()
  assert.equal(session.current(old), true)
  session.selectProject(projectB.id)
  assert.equal(session.current(old), false)
  const projectScope = session.capture()
  session.setProjects([{ ...projectB, permissionVersion: 3 }])
  assert.equal(session.current(projectScope), false)
  assert.throws(() => session.selectProject(projectA.id), /不可访问/)
})

test('OIDC 恢复引用按用户和项目隔离，只保存 requestKey，禁止替换不同原键', () => {
  const storage = memoryStorage()
  const scopeA = { userId: 'user-a', projectId: projectA.id }
  const scopeB = { userId: 'user-b', projectId: projectA.id }
  const sender = recovery.scopedIntentStorage(storage, scopeA, 'action')
  const key = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
  sender.setItem('argus.pendingAction.v1', JSON.stringify({ key, body: 'secret-not-stored' }))
  const stored = [...storage.values.values()][0]
  assert.doesNotMatch(stored, /secret-not-stored/)
  assert.equal(recovery.readRecovery(storage, scopeA, 'action').requestKey, key)
  assert.equal(recovery.readRecovery(storage, scopeB, 'action'), null)
  assert.throws(() => sender.setItem('argus.pendingAction.v1', JSON.stringify({ key: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd' })), /不得替换/)
  sender.removeItem('argus.pendingAction.v1')
  assert.equal(recovery.readRecovery(storage, scopeA, 'action'), null)
})

test('退出清除共享令牌和旧正文，避免身份模式读取 legacy pending', () => {
  const storage = memoryStorage()
  storage.setItem('argus.apiToken', 'old-token')
  storage.setItem('argus.pendingAction.v1', '{"key":"old"}')
  storage.setItem('argus.pendingReview.v1', '{"key":"old"}')
  assert.equal(recovery.clearLegacySecrets(storage), true)
  assert.equal(storage.getItem('argus.apiToken'), null)
  assert.equal(storage.getItem('argus.pendingAction.v1'), null)
  assert.equal(storage.getItem('argus.pendingReview.v1'), null)
})
