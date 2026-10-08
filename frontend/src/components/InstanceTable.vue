<script setup lang="ts">
import type { Instance, NodeItem } from '../models'
import { bytes, instanceQuality, metricsLabel, missingMetric, percent, sourceLabel, timeLabel } from '../models'

const props = defineProps<{ items: Instance[]; nodes: NodeItem[]; now: number; readOnly?: boolean }>()
const emit = defineEmits<{ control: [action: 'start' | 'stop' | 'restart', instance: Instance] }>()
const parent = (item: Instance) => props.nodes.find(node => node.id === item.nodeId)
const quality = (item: Instance) => instanceQuality(item, parent(item), props.now)
const statusLabel = (status: Instance['status']) => ({ running: '运行中', stopped: '已停止', starting: '启动中', error: '异常', unknown: '未知' })[status]
</script>

<template>
  <div class="instance-table">
    <div class="table-header"><span>实例 / 中央 ID</span><span>所属节点</span><span>观测状态</span><span>资源与来源</span><span>采集时间</span><span>模拟操作</span></div>
    <div v-if="!items.length" class="empty-state">暂无实例数据</div>
    <div v-for="item in items" :key="item.id" class="table-row" :data-instance-id="item.id">
      <div class="instance-name"><span class="cube">▣</span><div><strong>{{ item.name }}</strong><small>{{ item.agentInstanceId || '未报告容器标识' }} · {{ item.image }}</small><small class="identifier" :title="item.id">ID：{{ item.id || '缺少中央标识' }}</small></div></div>
      <div class="node-name"><span>{{ parent(item)?.name || item.node }}</span><small class="identifier">{{ item.nodeId || '未关联节点' }}</small></div>
      <div class="observed-state"><span class="status-pill" :class="item.status"><i></i>{{ statusLabel(item.status) }}</span><span :class="['snapshot-badge', quality(item).state]" :title="quality(item).detail">{{ quality(item).label }}</span></div>
      <div class="resource-values"><b>{{ bytes(item.memoryBytes, item.metricsStatus) }}</b><small>CPU {{ percent(item.cpuPercent, item.metricsStatus) }}</small><small>玩家：{{ item.players === null ? missingMetric(item.metricsStatus) : `${item.players} 人` }}</small><small :class="{ 'mock-text': item.dataSource === 'MOCK' }">{{ sourceLabel(item.dataSource) }} · {{ metricsLabel(item.metricsStatus) }}</small></div>
      <div class="sample-time"><span>{{ timeLabel(item.sampledAt) }}</span><small>最近发现：{{ timeLabel(item.lastSeenAt) }}</small><small>{{ quality(item).detail }}</small></div>
      <div class="row-actions"><button :disabled="readOnly || !item.id" @click="emit('control', 'restart', item)" :title="readOnly ? '只读模式' : '记录重启模拟请求，不执行容器动作'">↻</button><button :disabled="readOnly || !item.id" @click="emit('control', item.status === 'running' ? 'stop' : 'start', item)" :title="readOnly ? '只读模式' : '记录启停模拟请求，不执行容器动作'">{{ item.status === 'running' ? 'Ⅱ' : '▶' }}</button></div>
    </div>
  </div>
</template>
