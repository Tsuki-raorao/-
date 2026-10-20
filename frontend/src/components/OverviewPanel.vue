<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Instance, NodeItem, Task, TaskAction } from '../models'
import { bytes, instanceQuality, nodeQuality, percent, sourceLabel, timeLabel } from '../models'
import InstanceTable from './InstanceTable.vue'
const props = defineProps<{ items: Instance[]; nodes: NodeItem[]; tasks: Task[]; now: number; actionReason: (item: Instance, action: TaskAction) => string; blockingTask: (item: Instance) => string }>()
const emit = defineEmits<{ control: [action: TaskAction, instance: Instance]; showTask: [id: string]; logs: [instance: Instance]; tasks: []; monitoring: [] }>()
const query = ref(''), nodeFilter = ref(''), status = ref('all'), selectedId = ref('')
const tabs = [{ id: 'all', label: '全部' }, { id: 'running', label: '运行中' }, { id: 'stopped', label: '已停止' }]
const scoped = computed(() => props.items.filter(i => (!nodeFilter.value || i.nodeId === nodeFilter.value) && (!query.value.trim() || [i.name, i.agentInstanceId, i.id, props.nodes.find(n => n.id === i.nodeId)?.name || ''].some(v => v.toLowerCase().includes(query.value.trim().toLowerCase())))))
const filtered = computed(() => scoped.value.filter(i => status.value === 'all' || i.status === status.value))
const selected = computed(() => filtered.value.find(i => i.id === selectedId.value) || filtered.value[0])
watch(filtered, list => { if (!list.some(i => i.id === selectedId.value)) selectedId.value = list[0]?.id || '' })
const parent = computed(() => props.nodes.find(n => n.id === selected.value?.nodeId))
const quality = computed(() => selected.value ? instanceQuality(selected.value, parent.value, props.now) : null)
const latestTask = computed(() => props.tasks.find(t => t.instanceId === selected.value?.id))
const label = (i: Instance) => ({ running: '运行中', stopped: '已停止', starting: '启动中', error: '异常', unknown: '未知' })[i.status]
function taskLink() { latestTask.value ? emit('showTask', latestTask.value.id) : emit('tasks') }
</script>

<template>
  <div class="overview-layout">
    <section class="panel overview-instances">
      <div class="overview-list-head"><h2>服务实例 <span>{{ items.length }}</span></h2><div class="overview-filters"><label class="instance-search"><span aria-hidden="true">⌕</span><input v-model="query" aria-label="搜索实例" placeholder="搜索实例或节点…" /></label><select v-model="nodeFilter" aria-label="按节点筛选"><option value="">全部节点</option><option v-for="node in nodes" :key="node.id" :value="node.id">{{ node.name }}</option></select></div></div>
      <div class="instance-tabs" role="group" aria-label="按观测状态筛选"><button v-for="tab in tabs" :key="tab.id" :aria-pressed="status === tab.id" :class="{ active: status === tab.id }" @click="status = tab.id">{{ tab.label }} <span>{{ tab.id === 'all' ? scoped.length : scoped.filter(i => i.status === tab.id).length }}</span></button></div>
      <InstanceTable :items="filtered" :nodes="nodes" :now="now" :action-reason="actionReason" :blocking-task="blockingTask" compact :selected-id="selected?.id" @select="selectedId = $event" @control="(action, item) => emit('control', action, item)" @show-task="emit('showTask', $event)" />
      <div v-if="!filtered.length" class="filter-empty"><p>{{ items.length ? '没有匹配的实例' : '暂无实例数据，请先连接控制中心' }}</p><button v-if="items.length" class="text-btn" @click="query = ''; nodeFilter = ''; status = 'all'">清除筛选</button></div>
      <div class="overview-list-foot">每 15 秒刷新视图 · 观测状态以采集时间为准</div>
    </section>
    <aside class="overview-rail">
      <section class="panel instance-inspector" aria-label="实例详情" aria-live="polite">
        <div class="inspector-heading"><h2>实例详情</h2><span v-if="selected" class="status-pill" :class="selected.status"><i></i>{{ label(selected) }}</span></div>
        <template v-if="selected"><div class="inspector-identity"><span class="inspector-cube">◇</span><div><h3>{{ selected.name }}</h3><p>{{ selected.agentInstanceId || '未报告容器标识' }}</p></div></div>
          <dl class="inspector-facts"><dt>所属节点</dt><dd>{{ parent?.name || selected.node }}</dd><dt>数据来源</dt><dd>{{ sourceLabel(selected.dataSource) }}</dd><dt>CPU 使用率</dt><dd>{{ percent(selected.cpuPercent, selected.metricsStatus) }}</dd><dt>内存占用</dt><dd>{{ bytes(selected.memoryBytes, selected.metricsStatus) }}</dd><dt>最近采集</dt><dd>{{ timeLabel(selected.sampledAt) }}</dd></dl>
          <div v-if="quality" :class="['inspector-quality', quality.state]"><b>{{ quality.label }}</b><span>{{ quality.detail }}</span></div>
          <details class="inspector-identifiers"><summary>实例标识与镜像</summary><p>中央 ID：{{ selected.id }}</p><p>节点 ID：{{ selected.nodeId }}</p><p>镜像 / 版本：{{ selected.image }}</p></details>
          <div class="inspector-links"><button class="primary" @click="emit('logs', selected)">查看日志</button><button class="refresh" @click="taskLink">{{ latestTask ? '最近任务' : '查看任务' }}</button></div>
        </template><p v-else class="empty-state">选择一个实例，查看运行信息</p>
      </section>
      <section class="panel mascot-card"><div class="mascot-card-head"><span class="mascot-avatar"><img src="/weixu-niang-full.png" alt="未序娘头像" /></span><div><h3>未序娘</h3><p>陪你看清每一次运行</p></div><span class="mascot-tag">未序 · Argus</span></div><p class="mascot-message">从节点到实例，把运行信息放在一起。需要排查时，先看看采集时间、日志和任务记录。</p><button class="text-btn" @click="emit('monitoring')">查看监控与告警 <span>→</span></button></section>
    </aside>
  </div>
  <section class="panel overview-nodes"><div class="overview-node-title"><h2>节点状态 <span>{{ nodes.length }}</span></h2><span>真实采集 · 缺失指标显示为未知</span></div><div class="overview-node-grid"><article v-for="node in nodes" :key="node.id" class="overview-node" :data-overview-node-id="node.id"><div class="overview-node-head"><h3>{{ node.name }}</h3><span :class="['snapshot-badge', nodeQuality(node, now).state]">{{ nodeQuality(node, now).label }}</span></div><div class="overview-node-values"><span>观测运行实例<b>{{ items.filter(i => i.nodeId === node.id && i.status === 'running').length }} / {{ items.filter(i => i.nodeId === node.id).length }}</b></span><span>CPU<b>{{ percent(node.cpu, node.metricsStatus) }}</b></span><span>内存<b>{{ percent(node.memory, node.metricsStatus) }}</b></span></div><div class="overview-memory" aria-hidden="true"><i :style="{ width: node.memory === null ? '0%' : Math.min(100, node.memory) + '%' }"></i></div><p>{{ sourceLabel(node.dataSource) }} · {{ timeLabel(node.sampledAt) }}</p></article></div></section>
</template>
