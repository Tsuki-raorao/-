<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiRequestError, api, clearApiToken, getApiToken, saveApiToken, type Instance, type NodeItem, type Task } from './api'
import { instanceQuality, metricsLabel, nodeQuality, percent, sourceLabel, timeLabel } from './models'
import { createLogReader, type LogState } from './logs'
import InstanceTable from './components/InstanceTable.vue'

type Page = 'overview' | 'nodes' | 'instances' | 'tasks' | 'logs'
const nav = [{ id: 'overview', label: '概览', icon: '◈' }, { id: 'nodes', label: '节点', icon: '⌘' }, { id: 'instances', label: '实例', icon: '▣' }, { id: 'tasks', label: '任务', icon: '✓' }, { id: 'logs', label: '日志', icon: '≡' }] as const
const page = ref<Page>(readPage())
const nodes = ref<NodeItem[]>([])
const instances = ref<Instance[]>([])
const tasks = ref<Task[]>([])
const selectedInstance = ref('')
const logState = ref<LogState>({ targetId: '', phase: 'idle', data: null, error: '' })
const notice = ref('')
const readOnly = ref(true)
const connected = ref(false)
const loading = ref(false)
const loadError = ref('')
const search = ref('')
const autoRefresh = ref(true)
const authRequired = ref(false)
const tokenInput = ref('')
const hasApiToken = ref(Boolean(getApiToken()))
const now = ref(Date.now())
const currentLabel = computed(() => nav.find(n => n.id === page.value)?.label || '概览')
const nodeMap = computed(() => new Map(nodes.value.map(node => [node.id, node])))
const selectableInstances = computed(() => instances.value.filter(item => item.id))
const selected = computed(() => selectableInstances.value.find(item => item.id === selectedInstance.value))
const verifiedNodes = computed(() => nodes.value.filter(node => nodeQuality(node, now.value).state === 'fresh'))
const runningInstances = computed(() => instances.value.filter(item => item.status === 'running' && instanceQuality(item, nodeMap.value.get(item.nodeId), now.value).state === 'fresh').length)
const cpuSamples = computed(() => verifiedNodes.value.filter(node => node.cpu !== null && (node.metricsStatus === 'AVAILABLE' || node.metricsStatus === 'PARTIAL')))
const averageCpu = computed(() => cpuSamples.value.length ? `${(cpuSamples.value.reduce((sum, node) => sum + node.cpu!, 0) / cpuSamples.value.length).toFixed(2)}%` : '—')
const hasMockData = computed(() => nodes.value.some(node => node.dataSource === 'MOCK') || instances.value.some(item => item.dataSource === 'MOCK'))
const filteredLogs = computed(() => (logState.value.data?.lines || []).filter(line => !search.value || line.toLowerCase().includes(search.value.toLowerCase())))
const selectedQuality = computed(() => selected.value ? instanceQuality(selected.value, nodeMap.value.get(selected.value.nodeId), now.value) : null)
const logAge = computed(() => logState.value.data ? Math.max(0, Math.floor((now.value - Date.parse(logState.value.data.collectedAt)) / 1000)) : null)

function readPage(): Page {
  if (typeof window === 'undefined') return 'overview'
  const candidate = window.location.hash.replace(/^#\/?/, '')
  return nav.some(item => item.id === candidate) ? candidate as Page : 'overview'
}
function syncPageFromLocation() { page.value = readPage() }
function logTargetLabel(item: Instance): string {
  return `${nodeMap.value.get(item.nodeId)?.name || item.node} · ${item.name} (${item.agentInstanceId || '未报告容器标识'}) · ${item.id}`
}

const logReader = createLogReader(async (id, signal) => {
  const target = selectableInstances.value.find(item => item.id === id)
  if (!target) throw new Error('所选实例已不存在，请重新选择')
  const result = await api.recentLogs(id, signal)
  if ((target.nodeId && target.nodeId !== result.nodeId) || (target.agentInstanceId && target.agentInstanceId !== result.agentInstanceId)) {
    throw new Error('日志所属节点或容器与所选实例不匹配，已拒绝显示')
  }
  return result
}, state => {
  logState.value = state
  if (state.errorStatus === 401) authRequired.value = true
})

function loadLogs() {
  if (page.value !== 'logs' || !selected.value) { logReader.cancel(); return Promise.resolve() }
  return logReader.select(selected.value.id)
}
watch([page, selectedInstance], () => { void loadLogs() }, { flush: 'sync' })

let loadGeneration = 0
async function load() {
  const ticket = ++loadGeneration
  loading.value = true
  try {
    // 节点批次先读、实例后读，避免跨同步提交把旧实例清单与新节点批次拼在一起误判“未发现”。
    const [health, [nextNodes, nextInstances], nextTasks] = await Promise.all([
      api.health(),
      (async () => { const nextNodes = await api.nodes(); return [nextNodes, await api.instances()] as const })(),
      api.tasks()
    ])
    if (ticket !== loadGeneration) return
    connected.value = health.status === 'UP'
    readOnly.value = health.readOnly
    nodes.value = nextNodes
    instances.value = nextInstances
    tasks.value = nextTasks
    loadError.value = connected.value ? '' : '控制中心健康检查未通过，操作不可用'
    authRequired.value = false
    if (selectedInstance.value && !nextInstances.some(item => item.id === selectedInstance.value)) selectedInstance.value = ''
    if (page.value === 'logs' && selected.value && logState.value.phase !== 'loading') void loadLogs()
  } catch (error) {
    if (ticket !== loadGeneration) return
    if (error instanceof ApiRequestError && error.status === 401) authRequired.value = true
    connected.value = false
    nodes.value = []
    instances.value = []
    tasks.value = []
    selectedInstance.value = ''
    logReader.cancel()
    loadError.value = error instanceof Error ? error.message : '读取失败，请重试'
  } finally {
    if (ticket === loadGeneration) loading.value = false
  }
}

function submitApiToken() {
  const token = tokenInput.value.trim()
  if (!token) { notice.value = '请输入控制中心访问令牌'; return }
  saveApiToken(token)
  hasApiToken.value = true
  authRequired.value = false
  tokenInput.value = ''
  void load()
}
function removeApiToken() {
  loadGeneration++
  clearApiToken()
  hasApiToken.value = false
  authRequired.value = true
  connected.value = false
  loading.value = false
  nodes.value = []
  instances.value = []
  tasks.value = []
  selectedInstance.value = ''
  logReader.cancel()
}

async function control(action: 'start' | 'stop' | 'restart', instance: Instance) {
  if (!connected.value) { notice.value = '控制中心不可用，未提交记录'; return }
  if (readOnly.value) { notice.value = '当前控制中心处于只读模式，未提交记录'; return }
  notice.value = `正在记录 ${instance.name} 的模拟操作请求…`
  try {
    await api.control(instance.id, action)
    await load()
    notice.value = '模拟任务已记录；没有向容器下发操作'
  } catch (error) {
    notice.value = error instanceof ApiRequestError ? `提交失败：${error.message}` : '提交失败：控制中心不可用'
  }
}
function go(nextPage: Page) {
  if (page.value !== nextPage) window.history.pushState({}, '', `#/${nextPage}`)
  page.value = nextPage
}
function showUnavailable(feature: string) { notice.value = `${feature}功能将在后续版本开放` }
const taskStatusLabel = (value: Task['status']) => ({ success: '记录完成', running: '处理中', failed: '失败', pending: '待处理' })[value]

let refreshTimer: number | undefined
let clockTimer: number | undefined
onMounted(() => {
  void load()
  window.addEventListener('hashchange', syncPageFromLocation)
  window.addEventListener('popstate', syncPageFromLocation)
  refreshTimer = window.setInterval(() => { if (autoRefresh.value && !loading.value) void load() }, 15000)
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => {
  loadGeneration++
  logReader.cancel()
  window.removeEventListener('hashchange', syncPageFromLocation)
  window.removeEventListener('popstate', syncPageFromLocation)
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (clockTimer) window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><img class="brand-logo" src="/logo.png?v=20261007" alt="未序 Logo" /><small>智能运维控制台</small></div>
      <div class="workspace"><span :class="['dot', connected ? 'online' : 'offline']"></span><div><small>当前工作区</small><strong>我的服务器</strong></div></div>
      <nav><button v-for="item in nav" :key="item.id" :class="{ active: page === item.id }" @click="go(item.id)"><span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}</button></nav>
      <div class="sidebar-bottom"><div class="profile"><img class="avatar avatar-image" src="/weixu-niang.png" alt="未序娘" /><div><b>未序娘</b><small>形象展示 · AI 尚未接入</small></div></div></div>
    </aside>
    <main class="main">
      <header class="topbar"><div class="crumb">工作区 <span>/</span> <b>{{ currentLabel }}</b></div><div class="top-actions"><span class="connection"><i :class="{ offline: !connected }"></i>{{ loading ? '正在读取' : connected ? '控制中心可访问' : '控制中心不可用' }}</span><button v-if="hasApiToken" class="auth-clear" @click="removeApiToken">清除令牌</button><img class="top-avatar avatar-image" src="/weixu-niang.png" alt="未序娘" /></div></header>
      <div class="content">
        <div class="page-head"><div><p class="eyebrow">ARGUS / {{ currentLabel }}</p><h1>{{ page === 'overview' ? '欢迎回来' : currentLabel }}</h1><p class="subtitle">按采集时间查看节点和实例，过期状态会明确标记。</p></div><button class="refresh" :disabled="loading" @click="load">↻ <span>刷新数据</span></button></div>
        <div v-if="authRequired" class="auth-banner"><div><strong>需要访问令牌</strong><p>令牌只保存在当前浏览器会话中。</p></div><div class="auth-actions"><input v-model="tokenInput" type="password" placeholder="粘贴 Bearer 令牌" aria-label="访问令牌" @keyup.enter="submitApiToken" /><button class="primary" @click="submitApiToken">连接</button></div></div>
        <div v-if="loadError" class="data-banner error" role="alert">读取失败：{{ loadError }}。未使用演示数据替代。</div>
        <div v-if="readOnly" class="data-banner">当前为只读模式：可以查询快照和 Agent 最近日志，写操作关闭。</div>
        <div v-if="hasMockData" class="data-banner mock" role="status">页面包含模拟数据，相关条目已标记，不代表真实服务器状态。</div>

        <template v-if="page === 'overview'">
          <section class="metrics">
            <div class="metric-card"><span class="metric-label">近期验证的节点</span><strong>{{ verifiedNodes.length }}<small> / {{ nodes.length }}</small></strong><span class="metric-foot neutral">排除旧快照和模拟数据</span></div>
            <div class="metric-card"><span class="metric-label">近期运行中实例</span><strong>{{ runningInstances }}<small> / {{ instances.length }}</small></strong><span class="metric-foot neutral">以最近成功采集为准</span></div>
            <div class="metric-card"><span class="metric-label">平均 CPU 使用率</span><strong>{{ averageCpu }}</strong><span class="metric-foot neutral">{{ cpuSamples.length }} 个可用近期样本</span></div>
            <div class="metric-card"><span class="metric-label">活跃告警</span><strong>—</strong><span class="metric-foot neutral">告警源尚未接入</span></div>
          </section>
          <section class="grid-two"><div class="panel"><div class="panel-head"><div><h2>资源概览</h2><p>接入指标存储后显示历史曲线</p></div></div><div class="empty-state">当前仅展示采集快照，尚无历史指标曲线。</div></div><div class="panel"><div class="panel-head"><div><h2>需要关注</h2><p>当前没有接入告警源</p></div></div><div class="empty-state">暂无可验证的告警事件。</div></div></section>
          <section class="panel instance-panel"><div class="panel-head"><div><h2>实例快照</h2><p>180 秒后标为旧快照；保留最近一次观测值</p></div><button class="text-btn" @click="go('instances')">查看实例 →</button></div><InstanceTable :items="instances" :nodes="nodes" :now="now" :read-only="readOnly || !connected" @control="control" /></section>
        </template>

        <template v-else-if="page === 'nodes'">
          <section class="panel full"><div class="panel-head"><div><h2>节点列表</h2><p>控制中心定时读取 Agent；不是主动心跳</p></div><button class="primary" @click="showUnavailable('添加节点')">＋ 添加节点</button></div>
            <div v-if="!nodes.length" class="empty-state">暂无节点数据</div>
            <div class="node-grid"><div v-for="node in nodes" :key="node.id" class="node-card" :data-node-id="node.id">
              <div class="node-top"><span class="server-icon">⌘</span><span :class="['snapshot-badge', nodeQuality(node, now).state]" :title="nodeQuality(node, now).detail">{{ nodeQuality(node, now).label }}</span></div>
              <h3>{{ node.name }}</h3><p class="mono">{{ node.host }}</p><p class="source-line">{{ sourceLabel(node.dataSource) }} · {{ metricsLabel(node.metricsStatus) }}</p>
              <div class="node-stats"><span>CPU <b>{{ percent(node.cpu, node.metricsStatus) }}</b></span><span>内存 <b>{{ percent(node.memory, node.metricsStatus) }}</b></span></div>
              <p class="snapshot-detail">最近记录状态：{{ node.status === 'online' ? '在线' : '离线' }} · {{ nodeQuality(node, now).detail }}</p>
              <p class="last-seen">采样时间：{{ timeLabel(node.sampledAt) }}</p><p class="last-seen">最近尝试：{{ timeLabel(node.lastCheckedAt) }}</p><p class="last-seen">最近成功同步：{{ timeLabel(node.lastSuccessfulSyncAt) }}</p>
            </div></div>
          </section>
        </template>

        <template v-else-if="page === 'instances'">
          <section class="panel full"><div class="panel-head"><div><h2>服务实例</h2><p>同名容器按所属节点和中央实例 ID 区分</p></div><button class="primary" @click="showUnavailable('创建实例')">＋ 创建实例</button></div>
            <div class="data-banner">远程执行尚未接通；当前操作按钮仅记录模拟任务，不会改变真实容器。</div>
            <InstanceTable :items="instances" :nodes="nodes" :now="now" :read-only="readOnly || !connected" @control="control" />
          </section>
        </template>

        <template v-else-if="page === 'tasks'">
          <section class="panel full"><div class="panel-head"><div><h2>任务记录（远程执行未接通）</h2><p>记录状态只反映中央数据库流程，不表示容器已经执行；完整审计尚未接入。</p></div></div>
            <div v-if="!tasks.length" class="empty-state">暂无任务记录</div>
            <div class="task-list"><div v-for="task in tasks" :key="task.id" class="task-row"><span class="task-check" :class="task.status">{{ task.status === 'success' ? '✓' : task.status === 'failed' ? '!' : '…' }}</span><div class="task-main"><strong>{{ task.action }}</strong><span>{{ task.target }}</span></div><span class="task-id">{{ task.id }}</span><span class="task-time">{{ task.createdAt }}</span><span :class="['task-status', task.status]">{{ taskStatusLabel(task.status) }}</span><span class="task-duration">{{ task.duration }}</span></div></div>
          </section>
        </template>

        <template v-else-if="page === 'logs'">
          <section class="panel full log-panel"><div class="panel-head"><div><h2>Agent 最近日志</h2><p>按所选实例读取最近 100 行；自动刷新每 15 秒查询，不是持续日志流。</p></div></div>
            <div class="log-controls"><label for="log-instance">选择节点与实例</label><select id="log-instance" v-model="selectedInstance"><option value="">请选择实例</option><option v-for="item in selectableInstances" :key="item.id" :value="item.id">{{ logTargetLabel(item) }}</option></select><label class="auto-refresh"><input v-model="autoRefresh" type="checkbox" /> 自动刷新页面与日志</label><button class="refresh" :disabled="!selected || logState.phase === 'loading'" @click="loadLogs">读取日志</button></div>
            <div v-if="selected && selectedQuality" :class="['data-banner', selectedQuality.state]">{{ selectedQuality.label }}：{{ selectedQuality.detail }}。{{ sourceLabel(selected.dataSource) }}；最近实例采集：{{ timeLabel(selected.sampledAt) }}。日志结果以本次读取为准。</div>
            <div class="terminal" :aria-busy="logState.phase === 'loading'">
              <div class="terminal-head"><span>{{ selected ? `${selected.node} / ${selected.agentInstanceId || selected.name}` : '尚未选择实例' }}</span><span v-if="logState.data">读取于 {{ timeLabel(logState.data.collectedAt) }}<template v-if="logAge !== null"> · {{ logAge }} 秒前</template></span></div>
              <div class="terminal-filter"><span>⌕</span><input v-model="search" aria-label="搜索日志" placeholder="筛选本次读取的日志…" /><span class="log-count">{{ filteredLogs.length }} / {{ logState.data?.lines.length || 0 }} 行</span><button class="text-btn" @click="search = ''">清空筛选</button></div>
              <div v-if="logState.phase === 'idle'" class="empty-state" role="status">先选择实例，再读取该实例的 Agent 日志。</div>
              <div v-else-if="logState.phase === 'loading'" class="empty-state" role="status">正在读取所选实例的日志…</div>
              <div v-else-if="logState.phase === 'error'" class="empty-state log-error" role="alert">日志读取失败：{{ logState.error }}。没有使用缓存或模拟日志替代。</div>
              <div v-else-if="!logState.data?.lines.length" class="empty-state" role="status">本次读取成功，Agent 返回空日志。</div>
              <div v-else-if="!filteredLogs.length" class="empty-state" role="status">本次日志中没有匹配内容。</div>
              <div v-else class="log-lines"><div v-for="(line, index) in filteredLogs" :key="index" class="log-line"><span>{{ line }}</span></div></div>
            </div>
          </section>
        </template>
      </div>
    </main>
    <div v-if="notice" class="toast" role="status">{{ notice }} <button class="text-btn" aria-label="关闭提示" @click="notice = ''">×</button></div>
  </div>
</template>
