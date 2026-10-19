<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { api, type NodeItem } from '../api'
import { chartSegments, observationUnknown, type MonitorAlert, type MonitorPoint } from '../monitoring'
const props = defineProps<{ nodes: NodeItem[] }>()
const alerts = ref<MonitorAlert[]>([]), points = ref<MonitorPoint[]>([]), events = ref<Record<string,unknown>[]>([])
const enabled = ref(false), loading = ref(false), error = ref(''), metric = ref('CPU_PERCENT'), nodeId = ref(props.nodes[0]?.id || '')
let generation = 0, timer: number | undefined
const resource = computed(() => ['CPU_PERCENT','MEMORY_PERCENT'].includes(metric.value) ? nodeId.value : metric.value.startsWith('MQ_') ? 'platform' : 'project')
const segments = computed(() => chartSegments(points.value))
const active = computed(() => alerts.value.filter(a => a.state === 'ACTIVE'))
async function refresh() {
  const ticket = ++generation; loading.value = true; error.value = ''
  try {
    const [summary, history] = await Promise.all([api.monitoring(), resource.value ? api.monitoringHistory(resource.value,metric.value) : Promise.resolve([])])
    if (ticket !== generation) return
    alerts.value = summary.alerts; events.value = summary.events; enabled.value = summary.enabled; points.value = history
  } catch (e) { if (ticket === generation) { error.value = e instanceof Error ? e.message : '读取失败'; alerts.value = []; points.value = []; events.value = [] } }
  finally { if (ticket === generation) loading.value = false }
}
watch([metric,nodeId], () => { void refresh() })
onMounted(() => { void refresh(); timer = window.setInterval(() => { if (!loading.value) void refresh() },15000) })
onUnmounted(() => { generation++; if (timer) window.clearInterval(timer) })
const date = (s: unknown) => s ? new Date(s as string).toLocaleString() : '—'
</script>
<template>
  <section class="panel monitor-panel">
    <div class="panel-head"><div><h2>监控与告警</h2><p>活动告警 {{ active.length }} · 每 15 秒刷新 · 仅观察，不自动修复</p></div><button class="refresh" :disabled="loading" @click="refresh">刷新监控</button></div>
    <p v-if="error" class="data-banner error" role="alert">{{ error }}。未使用示例数据替代。</p>
    <p v-if="!enabled" class="data-banner">监控采样未启用，历史记录可能来自此前采样。</p>
    <div class="log-controls"><label>历史指标</label><select v-model="metric"><option value="CPU_PERCENT">CPU 使用率</option><option value="MEMORY_PERCENT">内存使用率</option><option value="OUTBOX_BACKLOG">待发事件积压</option><option value="TASK_FAILURES">近期任务失败</option><option value="MQ_QUEUE_BACKLOG">消息队列积压</option><option value="MQ_DEAD_LETTERS">死信数量</option></select><select v-if="metric.endsWith('PERCENT')" v-model="nodeId" aria-label="监控节点"><option value="">选择节点</option><option v-for="node in nodes" :key="node.id" :value="node.id">{{ node.name }}</option></select></div>
    <div v-if="!points.length" class="empty-state">当前指标还没有历史采样。</div>
    <div v-else class="monitor-chart"><svg viewBox="0 0 600 180" role="img" aria-label="历史指标折线；未知值断开"><path d="M0 160H600 M0 85H600 M0 10H600" stroke="#d8e5f2" fill="none"/><polyline v-for="(segment,i) in segments" :key="i" :points="segment" fill="none" stroke="#2873df" stroke-width="2"/></svg><p>{{ date(points[0]?.at) }} → {{ date(points[points.length-1]?.at) }} · 最新 {{ points[points.length-1]?.value ?? '未知' }} · 未知点不连线</p><details><summary>查看采样明细</summary><div v-for="(p,i) in points" :key="i">{{ date(p.at) }} · {{ p.value ?? '未知' }} · {{ p.source }}</div></details></div>
    <h3>告警状态</h3>
    <div v-if="!alerts.length" class="empty-state">没有告警评估记录。</div>
    <article v-for="alert in alerts" :key="alert.id" class="monitor-alert" :data-alert-id="alert.id"><div><strong>{{ alert.title }}</strong><span :class="['snapshot-badge',alert.state==='ACTIVE'||observationUnknown(alert)?'stale':'fresh']">{{ alert.state==='ACTIVE'?'告警中':observationUnknown(alert)?'尚未确认':'正常 / 已恢复' }}</span><span v-if="observationUnknown(alert)" class="snapshot-badge stale">指标未知或评估已过期</span></div><p>{{ alert.resourceId }} · {{ alert.value ?? '未知' }} / 阈值 {{ alert.threshold }} · {{ date(alert.checkedAt) }}</p><small>{{ alert.summary }}</small></article>
    <h3>触发与恢复记录</h3><p v-if="!events.length">暂无状态变化。</p><div v-for="(event,i) in events" :key="i" class="monitor-event">{{ date(event.occurred_at) }} · {{ event.state === 'ACTIVE' ? '触发告警' : '恢复' }} · {{ event.summary }}</div>
  </section>
</template>
<style scoped>
.monitor-chart svg{display:block;width:100%;height:220px}.monitor-chart p,.monitor-chart details,.monitor-alert p,.monitor-alert small,.monitor-event{color:#657a91;font-size:12px;line-height:1.8}.monitor-alert{border:1px solid #dce6f0;border-radius:8px;padding:14px;margin:10px 0}.monitor-alert>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.monitor-panel h3{font-size:15px;margin-top:28px}.monitor-event{padding:9px;border-bottom:1px solid #e5edf4}
</style>
