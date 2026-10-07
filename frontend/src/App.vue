<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiRequestError, api, clearApiToken, getApiToken, saveApiToken, type Instance, type LogLine, type NodeItem, type Task } from './api'
import InstanceTable from './components/InstanceTable.vue'

type Page = 'overview' | 'nodes' | 'instances' | 'tasks' | 'logs'
const nav = [{ id: 'overview', label: '概览', icon: '◈' }, { id: 'nodes', label: '节点', icon: '⌘' }, { id: 'instances', label: '实例', icon: '▣' }, { id: 'tasks', label: '任务', icon: '✓' }, { id: 'logs', label: '日志', icon: '≡' }] as const
const page = ref<Page>(readPage())
const nodes = ref<NodeItem[]>([])
const instances = ref<Instance[]>([])
const tasks = ref<Task[]>([])
const logs = ref<LogLine[]>([])
const selectedInstance = ref('mc01')
const notice = ref('')
const readOnly = ref(false)
const connected = ref(true)
const search = ref('')
const autoRefresh = ref(true)
const authRequired = ref(false)
const tokenInput = ref('')
const hasApiToken = ref(Boolean(getApiToken()))
const currentLabel = computed(() => nav.find(n => n.id === page.value)?.label || '概览')
const onlineNodes = computed(() => nodes.value.filter(n => n.status === 'online').length)
const runningInstances = computed(() => instances.value.filter(i => i.status === 'running').length)
const nodeMetrics = computed(() => nodes.value.filter(n => Number.isFinite(n.cpu) && Number.isFinite(n.memory) && (n.cpu > 0 || n.memory > 0)))
const averageCpu = computed(() => nodeMetrics.value.length ? `${Math.round(nodeMetrics.value.reduce((sum, n) => sum + n.cpu, 0) / nodeMetrics.value.length)}%` : '—')
const filteredLogs = computed(() => logs.value.filter(l => !search.value || l.message.toLowerCase().includes(search.value.toLowerCase()) || l.level.toLowerCase().includes(search.value.toLowerCase())))

/** 从地址栏读取页面，使用 hash 可在没有服务端 history fallback 时直接刷新。 */
function readPage(): Page {
  if (typeof window === 'undefined') return 'overview'
  const candidate = window.location.hash.replace(/^#\/?/, '')
  return nav.some(item => item.id === candidate) ? candidate as Page : 'overview'
}

function syncPageFromLocation() { page.value = readPage() }

async function loadLogs() { logs.value = await api.logs(selectedInstance.value) }

async function load() {
  // 页面数据统一从控制中心加载；生产环境不可用时清空页面，避免演示数据冒充真实状态。
  try {
    const [health, nextNodes, nextInstances, nextTasks] = await Promise.all([api.health(), api.nodes(), api.instances(), api.tasks()])
    connected.value = health.status === 'UP'
    readOnly.value = health.readOnly
    nodes.value = nextNodes
    instances.value = nextInstances
    tasks.value = nextTasks
    if (instances.value.length && !instances.value.some(item => item.id === selectedInstance.value)) selectedInstance.value = instances.value[0].id
    await loadLogs()
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401) authRequired.value = true
    connected.value = false
    nodes.value = []
    instances.value = []
    tasks.value = []
    logs.value = []
  }
}

/** 保存本次浏览器会话的令牌并重新读取真实数据。 */
function submitApiToken() {
  const token = tokenInput.value.trim()
  if (!token) { notice.value = '请输入控制中心访问令牌'; return }
  saveApiToken(token)
  hasApiToken.value = true
  authRequired.value = false
  tokenInput.value = ''
  void load()
}

/** 清除浏览器中的令牌，避免共用电脑留下访问凭据。 */
function removeApiToken() {
  clearApiToken()
  hasApiToken.value = false
  authRequired.value = true
  nodes.value = []
  instances.value = []
  tasks.value = []
  logs.value = []
}

async function control(action: 'start' | 'stop' | 'restart', instance: Instance) {
  if (!connected.value) { notice.value = '控制中心不可用，未执行任何操作'; return }
  if (readOnly.value) { notice.value = '当前控制中心处于只读模式，未执行任何操作'; return }
  notice.value = `正在${action === 'restart' ? '重启' : action === 'start' ? '启动' : '停止'} ${instance.name}…`
  try {
    await api.control(instance.id, action)
    await load()
    notice.value = `${instance.name} 操作已提交`
  } catch (error) {
    notice.value = error instanceof ApiRequestError ? `操作未执行：${error.message}` : '操作未执行：控制中心不可用'
  }
  window.setTimeout(() => { notice.value = '' }, 3200)
}

function go(nextPage: Page) {
  if (page.value !== nextPage) window.history.pushState({}, '', `#/${nextPage}`)
  page.value = nextPage
  if (nextPage === 'logs') void loadLogs()
}

function showUnavailable(feature: string) { notice.value = `${feature}功能将在后续版本开放`; window.setTimeout(() => { notice.value = '' }, 3200) }

let refreshTimer: number | undefined
onMounted(() => {
  void load()
  window.addEventListener('hashchange', syncPageFromLocation)
  window.addEventListener('popstate', syncPageFromLocation)
  refreshTimer = window.setInterval(() => { if (autoRefresh.value) void load() }, 15000)
})
onUnmounted(() => {
  window.removeEventListener('hashchange', syncPageFromLocation)
  window.removeEventListener('popstate', syncPageFromLocation)
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><img class="brand-logo" src="/logo.png?v=20261007" alt="未序 Logo" /><small>智能运维控制台</small></div>
      <div class="workspace"><span class="dot online"></span><div><small>当前工作区</small><strong>我的服务器</strong></div><span class="chevron">⌄</span></div>
      <nav><button v-for="item in nav" :key="item.id" :class="{active: page === item.id}" @click="go(item.id)"><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}<span v-if="item.id === 'tasks' && tasks.some(t => t.status === 'running')" class="badge">1</span></button></nav>
      <div class="sidebar-bottom"><button class="quiet"><span>◌</span>帮助与文档</button><div class="profile"><img class="avatar avatar-image" src="/weixu-niang.png" alt="未序娘" /><div><b>未序娘</b><small>AI 运维助手</small></div><span class="more">•••</span></div></div>
    </aside>
    <main class="main">
      <header class="topbar"><div class="crumb">工作区 <span>/</span> <b>{{ currentLabel }}</b></div><div class="top-actions"><span class="connection"><i :class="{offline: !connected}"></i>{{ connected ? '系统运行正常' : '控制中心不可用' }}</span><button class="icon-btn">⌕</button><button class="icon-btn">◔</button><img class="top-avatar avatar-image" src="/weixu-niang.png" alt="未序娘" /></div></header>
      <div class="content">
        <div class="page-head"><div><p class="eyebrow">ARGUS / {{ page === 'overview' ? 'COMMAND CENTER' : currentLabel.toUpperCase() }}</p><h1>{{ page === 'overview' ? '欢迎回来' : currentLabel }}</h1><p class="subtitle">{{ page === 'overview' ? '这是你的基础设施当前状态。' : '统一管理和观察你的基础设施。' }}</p></div><button class="refresh" @click="load">↻ <span>刷新数据</span></button></div>
        <div v-if="authRequired" class="auth-banner"><div><strong>需要访问令牌</strong><p>控制中心已开启访问保护。令牌只保存在当前浏览器会话中。</p></div><div class="auth-actions"><input v-model="tokenInput" type="password" placeholder="粘贴 Bearer 令牌" @keyup.enter="submitApiToken" /><button class="primary" @click="submitApiToken">连接</button><button v-if="hasApiToken" class="auth-clear" @click="removeApiToken">清除</button></div></div>
        <div v-if="readOnly" class="readonly-banner">当前为生产只读模式：可以查看节点、实例和日志，写操作尚未开放。</div>

        <template v-if="page === 'overview'">
          <section class="metrics"><div class="metric-card"><span class="metric-label">在线节点</span><strong>{{ onlineNodes }}<small> / {{ nodes.length }}</small></strong><span class="metric-foot positive">实时快照 <em>可用</em></span><div class="spark blue"></div></div><div class="metric-card"><span class="metric-label">运行中实例</span><strong>{{ runningInstances }}<small> / {{ instances.length }}</small></strong><span class="metric-foot positive">实时快照 <em>运行</em></span><div class="spark green"></div></div><div class="metric-card"><span class="metric-label">平均 CPU 使用率</span><strong>{{ averageCpu }}</strong><span class="metric-foot neutral">Agent 指标</span><div class="spark purple"></div></div><div class="metric-card"><span class="metric-label">活跃告警</span><strong>—</strong><span class="metric-foot neutral">告警源尚未接入</span><div class="spark orange"></div></div></section>
          <section class="grid-two"><div class="panel"><div class="panel-head"><div><h2>资源概览</h2><p>接入 Prometheus 后显示历史指标</p></div><button class="select" disabled>暂无历史数据</button></div><div class="empty-state">当前 Agent 仅提供节点和容器状态，CPU、内存历史曲线将在指标采集链路接入后显示。</div></div><div class="panel alert-panel"><div class="panel-head"><div><h2>需要关注</h2><p>当前没有接入告警源</p></div><button class="text-btn" @click="go('tasks')">查看任务 →</button></div><div class="empty-state">暂无可验证的告警事件。</div></div></section>
          <section class="panel instance-panel"><div class="panel-head"><div><h2>实例状态</h2><p>当前工作区中的服务实例</p></div><button class="text-btn" @click="go('instances')">管理实例 →</button></div><InstanceTable :items="instances" :read-only="readOnly || !connected" @control="control" /></section>
        </template>

        <template v-else-if="page === 'nodes'"><section class="panel full"><div class="panel-head"><div><h2>节点列表</h2><p>连接到 Argus 的主机和 Agent</p></div><button class="primary" @click="showUnavailable('添加节点')">＋ 添加节点</button></div><div class="node-grid"><div v-for="node in nodes" :key="node.id" class="node-card"><div class="node-top"><span class="server-icon">⌘</span><span :class="['status-pill', node.status]">{{ node.status === 'online' ? '在线' : '离线' }}</span></div><h3>{{ node.name }}</h3><p class="mono">{{ node.host }}</p><div class="node-stats"><span>CPU <b>{{ node.status === 'online' ? `${node.cpu}%` : '暂无指标' }}</b></span><span>内存 <b>{{ node.status === 'online' ? `${node.memory}%` : '暂无指标' }}</b></span></div><div class="progress"><i :style="{width: node.cpu + '%'}"></i></div><p class="last-seen">最后心跳 {{ node.lastSeen }}</p></div></div></section></template>
        <template v-else-if="page === 'instances'"><section class="panel full"><div class="panel-head"><div><h2>服务实例</h2><p>查看、启动和停止你的服务</p></div><button class="primary" @click="showUnavailable('创建实例')">＋ 创建实例</button></div><InstanceTable :items="instances" :read-only="readOnly || !connected" @control="control" detailed /></section></template>
        <template v-else-if="page === 'tasks'"><section class="panel full"><div class="panel-head"><div><h2>任务记录</h2><p>所有操作都会被记录，便于追踪和审计</p></div><div class="tabs"><button class="tab active">全部</button><button class="tab">运行中</button><button class="tab">已完成</button></div></div><div class="task-list"><div v-for="task in tasks" :key="task.id" class="task-row"><span class="task-check" :class="task.status">{{ task.status === 'success' ? '✓' : task.status === 'running' ? '…' : '!' }}</span><div class="task-main"><strong>{{ task.action }}</strong><span>{{ task.target }}</span></div><span class="task-id">{{ task.id }}</span><span class="task-time">{{ task.createdAt }}</span><span :class="['task-status', task.status]">{{ task.status === 'success' ? '已完成' : task.status === 'running' ? '执行中' : '失败' }}</span><span class="task-duration">{{ task.duration }}</span><button class="dots">•••</button></div></div></section></template>
        <template v-else-if="page === 'logs'"><section class="panel full log-panel"><div class="panel-head"><div><h2>实时日志</h2><p>查看服务实例的运行日志</p></div><div class="log-actions"><select v-model="selectedInstance" @change="loadLogs"><option v-for="item in instances" :key="item.id" :value="item.id">{{ item.id }} · {{ item.name }}</option></select><label><input v-model="autoRefresh" type="checkbox" /> 自动刷新</label></div></div><div class="terminal"><div class="terminal-head"><span><i></i> LIVE / {{ selectedInstance }}</span><div><button @click="search = ''">清空筛选</button><button @click="showUnavailable('日志导出')">⇩ 导出</button></div></div><div class="terminal-filter"><span>⌕</span><input v-model="search" placeholder="搜索日志内容…" /><span class="log-count">{{ filteredLogs.length }} 条</span></div><div class="log-lines"><div v-for="(line, i) in filteredLogs" :key="i" class="log-line"><time>{{ line.time }}</time><b :class="line.level.toLowerCase()">{{ line.level }}</b><span>{{ line.message }}</span></div></div></div></section></template>
      </div>
    </main>
    <div v-if="notice" class="toast">✓ {{ notice }}</div>
  </div>
</template>
