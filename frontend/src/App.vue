<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiRequestError, api, clearApiToken, getApiToken, saveApiToken, type Instance, type NodeItem, type Task } from './api'
import { actionLabel, executionLabel, instanceQuality, metricsLabel, nodeQuality, percent, sourceLabel, taskResultLabel, timeLabel, type TaskAction } from './models'
import { createLogReader, type LogState } from './logs'
import { allowedTarget, createIntentSender, disabledCapabilities, newIntentKey, type ActionIntent, type Capabilities, type IntentState } from './task-control'
import { createTaskReader, type TaskDetailState } from './task-detail'
import InstanceTable from './components/InstanceTable.vue'
import TaskDetail from './components/TaskDetail.vue'
import TaskResolution from './components/TaskResolution.vue'
import { readReviewIntent } from './task-resolution'

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
const editToken = ref(false)
const capabilities = ref<Capabilities>(disabledCapabilities())
const intentState = ref<IntentState>({ intent: null, phase: 'idle', error: '' })
const confirmation = ref<Omit<ActionIntent, 'key'> | null>(null)
const selectedTask = ref('')
const taskDetail = ref<TaskDetailState>({ id: '', phase: 'idle', task: null, events: [], error: '' })
const pendingReviewTask = ref(''), pendingReviewError = ref('')
function readReviewRecovery() {
  try { pendingReviewTask.value = readReviewIntent(window.sessionStorage)?.taskId || ''; pendingReviewError.value = '' }
  catch { pendingReviewTask.value = ''; pendingReviewError.value = '待确认人工核对记录无法读取；请核对服务端记录，不要创建替代核对。' }
}
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
const taskReader = createTaskReader(api.task, api.taskEvents, state => {
  taskDetail.value = state
  if (state.task) mergeTask(state.task)
})
function loadTaskDetail() {
  if (page.value !== 'tasks' || !selectedTask.value) { taskReader.cancel(); return Promise.resolve() }
  return taskReader.select(selectedTask.value)
}
watch([page, selectedTask], () => { void loadTaskDetail() }, { flush: 'sync' })
const intentSender = createIntentSender(api.control, {
  getItem: key => window.sessionStorage.getItem(key),
  setItem: (key, value) => window.sessionStorage.setItem(key, value),
  removeItem: key => window.sessionStorage.removeItem(key)
}, state => { intentState.value = state })
watch(confirmation, async value => { if (value) { await nextTick(); document.getElementById('cancel-action')?.focus() } })
function trapConfirmation(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
  const first = buttons[0], last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}

let loadGeneration = 0
async function loadCapabilities(ticket: number) {
  capabilities.value = disabledCapabilities('正在验证操作权限')
  try {
    const next = await api.capabilities()
    if (ticket === loadGeneration) capabilities.value = next
  } catch {
    if (ticket === loadGeneration) capabilities.value = disabledCapabilities('操作权限读取失败，已禁用操作；仍可查看快照')
  }
}
async function load() {
  const ticket = ++loadGeneration
  loading.value = true
  void loadCapabilities(ticket)
  try {
    // 节点批次先读、实例后读，避免跨同步提交把旧实例清单与新节点批次拼在一起误判“未发现”。
    const [health, [nextNodes, nextInstances], nextTasks] = await Promise.all([
      api.health(),
      (async () => { const nextNodes = await api.nodes(); return [nextNodes, await api.instances()] as const })(),
      api.tasks()
    ])
    if (ticket !== loadGeneration) return
    connected.value = health.status === 'UP'
    readOnly.value = health.readOnly !== false
    nodes.value = nextNodes
    instances.value = nextInstances
    tasks.value = nextTasks
    readReviewRecovery()
    loadError.value = connected.value ? '' : '控制中心健康检查未通过，操作不可用'
    authRequired.value = false
    if (selectedInstance.value && !nextInstances.some(item => item.id === selectedInstance.value)) selectedInstance.value = ''
    if (page.value === 'logs' && selected.value && logState.value.phase !== 'loading') void loadLogs()
    if (page.value === 'tasks' && selectedTask.value && taskDetail.value.phase !== 'loading') void loadTaskDetail()
  } catch (error) {
    if (ticket !== loadGeneration) return
    if (error instanceof ApiRequestError && error.status === 401) authRequired.value = true
    connected.value = false
    nodes.value = []
    instances.value = []
    tasks.value = []
    selectedInstance.value = ''
    logReader.cancel()
    taskReader.cancel()
    loadError.value = error instanceof Error ? error.message : '读取失败，请重试'
  } finally {
    if (ticket === loadGeneration) loading.value = false
  }
}

function submitApiToken() {
  const token = tokenInput.value.trim()
  if (!token) { notice.value = '请输入控制中心访问令牌'; return }
  invalidateRequests()
  saveApiToken(token)
  hasApiToken.value = true
  authRequired.value = false
  tokenInput.value = ''
  editToken.value = false
  void load()
}
function invalidateRequests() {
  loadGeneration++
  confirmation.value = null
  capabilities.value = disabledCapabilities()
  intentSender.invalidate()
  connected.value = false
  loading.value = false
  nodes.value = []
  instances.value = []
  tasks.value = []
  selectedInstance.value = ''
  logReader.cancel()
  selectedTask.value = ''
  pendingReviewTask.value = ''; pendingReviewError.value = ''
  taskReader.cancel()
}
function removeApiToken() {
  invalidateRequests()
  clearApiToken()
  hasApiToken.value = false
  authRequired.value = true
  tokenInput.value = ''
}

function actionReason(instance: Instance, action: TaskAction): string {
  if (loading.value || !connected.value) return '控制中心状态尚未验证'
  if (readOnly.value) return '控制中心处于只读模式'
  if (intentState.value.phase !== 'idle') return '已有提交待确认，请先核对原请求'
  if (!instance.id || !instance.nodeId || !instance.agentInstanceId) return '实例身份不完整'
  if (blockingTask(instance)) return '实例已有未决任务，请查看阻塞任务详情'
  if (!allowedTarget(capabilities.value, instance.id, action)) return capabilities.value.reason || '当前令牌或目标不允许此操作'
  return ''
}
function blockingTask(instance: Instance): string {
  const target = capabilities.value.targets.find(target => target.instanceId === instance.id)
  return target ? target.blockingTaskId || '' : tasks.value.find(task => task.instanceId === instance.id && task.blocksInstance === true)?.id || ''
}
function reviewChanged() { void load(); void loadTaskDetail() }
function control(action: TaskAction, instance: Instance) {
  const reason = actionReason(instance, action)
  if (reason) { notice.value = reason; return }
  const target = allowedTarget(capabilities.value, instance.id, action)!
  confirmation.value = { instanceId: instance.id, nodeId: instance.nodeId, agentInstanceId: instance.agentInstanceId, name: instance.name, action, expectedExecutionMode: target.executionMode }
}
function mergeTask(task: Task) {
  const current = tasks.value.find(item => item.id === task.id)
  if (current?.updatedAt && task.updatedAt && Date.parse(task.updatedAt) < Date.parse(current.updatedAt)) return
  tasks.value = current ? tasks.value.map(item => item.id === task.id ? task : item) : [task, ...tasks.value]
}
function showTask(id: string) { selectedTask.value = id; go('tasks') }
async function sendIntent(intent?: ActionIntent) {
  const task = await intentSender.submit(intent)
  if (!task) { notice.value = intentState.value.error; return }
  // 提交前已在途的任务列表不能用旧清单覆盖新收到的任务。
  loadGeneration++
  loading.value = false
  mergeTask(task)
  notice.value = '控制中心已接收任务（202）；请在任务详情查询结果，这不代表已经执行成功。'
  showTask(task.id)
}
async function confirmControl() {
  const choice = confirmation.value
  if (!choice) return
  const instance = instances.value.find(item => item.id === choice.instanceId)
  const target = allowedTarget(capabilities.value, choice.instanceId, choice.action)
  if (!instance || actionReason(instance, choice.action) || target?.executionMode !== choice.expectedExecutionMode
      || instance.nodeId !== choice.nodeId || instance.agentInstanceId !== choice.agentInstanceId) {
    confirmation.value = null; notice.value = '操作权限、目标或执行模式已变化，请刷新后重新确认'; return
  }
  confirmation.value = null
  try { await sendIntent({ ...choice, key: newIntentKey() }) } catch (error) { notice.value = error instanceof Error ? error.message : '无法创建请求标识，未发送' }
}
const canConfirmPending = computed(() => {
  const intent = intentState.value.intent
  if (!intent || intentState.value.phase !== 'uncertain' || !connected.value || loading.value || readOnly.value) return false
  const instance = instances.value.find(item => item.id === intent.instanceId)
  if (!instance || instance.nodeId !== intent.nodeId || instance.agentInstanceId !== intent.agentInstanceId || instance.dataSource !== intent.expectedExecutionMode) return false
  const caps = capabilities.value, target = caps.targets.find(item => item.instanceId === intent.instanceId)
  // 已有互斥不能拦住同键查询：这里仅重送持久保留的原请求，不给新操作放行。
  return caps.controlEnabled && caps.canControl && caps.allowedActions.includes(intent.action) && target?.executionMode === intent.expectedExecutionMode
    && target.canConfirmPending === true
})
async function confirmPending() {
  if (!canConfirmPending.value) return
  await sendIntent()
}
function go(nextPage: Page) {
  if (page.value !== nextPage) window.history.pushState({}, '', `#/${nextPage}`)
  page.value = nextPage
}
function showUnavailable(feature: string) { notice.value = `${feature}功能将在后续版本开放` }

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
  taskReader.cancel()
  intentSender.invalidate()
  window.removeEventListener('hashchange', syncPageFromLocation)
  window.removeEventListener('popstate', syncPageFromLocation)
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (clockTimer) window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar" :inert="Boolean(confirmation)">
      <div class="brand"><img class="brand-logo" src="/logo.png?v=20261007" alt="未序 Logo" /><small>智能运维控制台</small></div>
      <div class="workspace"><span :class="['dot', connected ? 'online' : 'offline']"></span><div><small>当前工作区</small><strong>我的服务器</strong></div></div>
      <nav><button v-for="item in nav" :key="item.id" :class="{ active: page === item.id }" @click="go(item.id)"><span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}</button></nav>
      <div class="sidebar-bottom"><div class="profile"><img class="avatar avatar-image" src="/weixu-niang.png" alt="未序娘" /><div><b>未序娘</b><small>形象展示 · AI 尚未接入</small></div></div></div>
    </aside>
    <main class="main" :inert="Boolean(confirmation)">
      <header class="topbar"><div class="crumb">工作区 <span>/</span> <b>{{ currentLabel }}</b></div><div class="top-actions"><span class="connection"><i :class="{ offline: !connected }"></i>{{ loading ? '正在读取' : connected ? '控制中心可访问' : '控制中心不可用' }}</span><button class="auth-clear" @click="editToken = !editToken">{{ hasApiToken ? '更换令牌' : '输入令牌' }}</button><button v-if="hasApiToken" class="auth-clear" @click="removeApiToken">清除令牌</button><img class="top-avatar avatar-image" src="/weixu-niang.png" alt="未序娘" /></div></header>
      <div class="content">
        <div class="page-head"><div><p class="eyebrow">ARGUS / {{ currentLabel }}</p><h1>{{ page === 'overview' ? '欢迎回来' : currentLabel }}</h1><p class="subtitle">按采集时间查看节点和实例，过期状态会明确标记。</p></div><button class="refresh" :disabled="loading" @click="load">↻ <span>刷新数据</span></button></div>
        <div v-if="authRequired || editToken" class="auth-banner"><div><strong>连接控制中心</strong><p>可输入查看或操作令牌；权限由服务端核验。仅保存在当前浏览器会话。</p></div><div class="auth-actions"><input v-model="tokenInput" type="password" placeholder="粘贴 Bearer 令牌" aria-label="访问令牌" @keyup.enter="submitApiToken" /><button class="primary" @click="submitApiToken">连接</button></div></div>
        <div v-if="loadError" class="data-banner error" role="alert">读取失败：{{ loadError }}。未使用演示数据替代。</div>
        <div v-if="readOnly" class="data-banner">当前为只读模式：可以查询快照和 Agent 最近日志，写操作关闭。</div>
        <div v-if="hasMockData" class="data-banner mock" role="status">页面包含模拟数据，相关条目已标记，不代表真实服务器状态。</div>
        <div v-if="connected && (pendingReviewTask || pendingReviewError)" class="data-banner pending-review-recovery" role="status"><strong>人工核对提交仍需确认</strong><p>{{ pendingReviewError || '保留了原请求标识。打开原任务会先查询已有核对记录，不自动重新提交。' }}</p><button v-if="pendingReviewTask" class="text-btn" @click="showTask(pendingReviewTask)">恢复待确认人工核对</button></div>
        <div v-if="intentState.phase !== 'idle'" class="data-banner pending-intent" role="status"><strong>{{ intentState.phase === 'submitting' ? '正在等待排队确认' : '提交结果待确认' }}</strong><p v-if="intentState.intent">{{ executionLabel(intentState.intent.expectedExecutionMode) }} · {{ actionLabel(intentState.intent.action) }} {{ intentState.intent.name }} · 节点 {{ intentState.intent.nodeId }} · 实例 {{ intentState.intent.instanceId }}</p><p>{{ intentState.error || '请等待，重复点击不会创建新命令。' }}</p><p>待确认请求保留原标识。核对会重送同一个请求；如果之前未入队，本次可能首次排队。</p><button v-if="intentState.phase === 'uncertain' && intentState.intent" class="refresh" :disabled="!canConfirmPending" @click="confirmPending">用原请求核对</button><p v-if="intentState.phase === 'uncertain' && !canConfirmPending">恢复连接并验证操作权限后可核对。不要清理浏览器中的待确认记录。</p></div>

        <template v-if="page === 'overview'">
          <section class="metrics">
            <div class="metric-card"><span class="metric-label">近期验证的节点</span><strong>{{ verifiedNodes.length }}<small> / {{ nodes.length }}</small></strong><span class="metric-foot neutral">排除旧快照和模拟数据</span></div>
            <div class="metric-card"><span class="metric-label">近期运行中实例</span><strong>{{ runningInstances }}<small> / {{ instances.length }}</small></strong><span class="metric-foot neutral">以最近成功采集为准</span></div>
            <div class="metric-card"><span class="metric-label">平均 CPU 使用率</span><strong>{{ averageCpu }}</strong><span class="metric-foot neutral">{{ cpuSamples.length }} 个可用近期样本</span></div>
            <div class="metric-card"><span class="metric-label">活跃告警</span><strong>—</strong><span class="metric-foot neutral">告警源尚未接入</span></div>
          </section>
          <section class="grid-two"><div class="panel"><div class="panel-head"><div><h2>资源概览</h2><p>接入指标存储后显示历史曲线</p></div></div><div class="empty-state">当前仅展示采集快照，尚无历史指标曲线。</div></div><div class="panel"><div class="panel-head"><div><h2>需要关注</h2><p>当前没有接入告警源</p></div></div><div class="empty-state">暂无可验证的告警事件。</div></div></section>
          <section class="panel instance-panel"><div class="panel-head"><div><h2>实例快照</h2><p>180 秒后标为旧快照；保留最近一次观测值</p></div><button class="text-btn" @click="go('instances')">查看实例 →</button></div><InstanceTable :items="instances" :nodes="nodes" :now="now" :action-reason="actionReason" :blocking-task="blockingTask" @control="control" @show-task="showTask" /></section>
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
            <div class="data-banner">{{ capabilities.canControl && capabilities.controlEnabled ? '受控操作可用；提交前确认目标与执行模式，排队后到任务页查询结果。' : capabilities.reason }} 默认关闭控制。任务结果与实例采集状态分别记录。</div>
            <InstanceTable :items="instances" :nodes="nodes" :now="now" :action-reason="actionReason" :blocking-task="blockingTask" @control="control" @show-task="showTask" />
          </section>
        </template>

        <template v-else-if="page === 'tasks'">
          <section class="panel full task-panel"><div class="panel-head"><div><h2>任务与执行结果</h2><p>排队不等于执行完成。区分真实 Docker、Agent 模拟与历史数据库模拟；每 15 秒查询。</p></div></div>
            <div v-if="!tasks.length" class="empty-state">暂无任务记录</div>
            <div class="task-list"><button v-for="task in tasks" :key="task.id" class="task-row task-select" :class="{ selected: selectedTask === task.id }" :data-task-id="task.id" :disabled="!task.id" @click="showTask(task.id)"><span class="task-check" :class="task.status.toLowerCase()">{{ task.status === 'UNKNOWN' ? '?' : task.status === 'FAILED' ? '!' : '·' }}</span><div class="task-main"><strong>{{ actionLabel(task.action) }} · {{ executionLabel(task.executionMode) }}</strong><span>{{ task.nodeId || '历史节点未记录' }} / {{ task.agentInstanceId || task.instanceId }}</span><small class="identifier">实例：{{ task.instanceId }} · 任务：{{ task.id }}</small></div><span :class="['task-status', task.executionMode === 'UNKNOWN' ? 'unknown' : task.status.toLowerCase()]">{{ taskResultLabel(task) }}</span><time class="task-time">{{ timeLabel(task.updatedAt || task.createdAt) }}</time><span class="text-btn">查看详情 →</span></button></div>
          </section>
          <TaskDetail v-if="selectedTask" :state="taskDetail" @refresh="loadTaskDetail" @close="selectedTask = ''" />
          <TaskResolution v-if="selectedTask && connected" :key="selectedTask" :task-id="selectedTask" :read-only="readOnly" @changed="reviewChanged" @select-task="showTask" />
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
    <div v-if="confirmation" class="confirm-overlay" @keydown.esc="confirmation = null" @keydown="trapConfirmation"><section class="confirm-card" role="dialog" aria-modal="true" aria-labelledby="confirm-title"><p class="eyebrow">确认受控操作</p><h2 id="confirm-title">{{ actionLabel(confirmation.action) }} {{ confirmation.name }}</h2><p :class="['mode-warning', confirmation.expectedExecutionMode === 'DOCKER' ? 'danger' : 'mock']">{{ executionLabel(confirmation.expectedExecutionMode) }}</p><p v-if="confirmation.expectedExecutionMode === 'DOCKER'">这会对真实 Docker 容器执行{{ actionLabel(confirmation.action) }}。停止或重启可能中断服务与用户连接。</p><p v-else>这次只由 Agent 模拟执行，不会操作 Docker，也不能证明真实服务已启动或停止。</p><dl class="confirm-target"><dt>所属节点</dt><dd>{{ confirmation.nodeId }}</dd><dt>中央实例 ID</dt><dd>{{ confirmation.instanceId }}</dd><dt>Agent 局部 ID</dt><dd>{{ confirmation.agentInstanceId }}</dd></dl><p>确认后先进入任务队列。网络中断时保留原请求核对，不能当成失败直接重做。</p><div class="confirm-buttons"><button id="cancel-action" class="refresh" @click="confirmation = null">取消</button><button class="primary" data-testid="confirm-action" @click="confirmControl">确认{{ actionLabel(confirmation.action) }}并排队</button></div></section></div>
    <div v-if="notice" class="toast" role="status">{{ notice }} <button class="text-btn" aria-label="关闭提示" @click="notice = ''">×</button></div>
  </div>
</template>
