<script setup lang="ts">
import type { Instance } from '../api'

defineProps<{ items: Instance[]; detailed?: boolean; readOnly?: boolean }>()
const emit = defineEmits<{ control: [action: 'start' | 'stop' | 'restart', instance: Instance] }>()

/** 将后端状态转换为页面显示文案，未知状态保留原值便于排查。 */
function statusLabel(status: Instance['status']): string {
  return { running: '运行中', stopped: '已停止', starting: '启动中', error: '异常' }[status] || status
}
</script>

<template>
  <div class="instance-table">
    <div class="table-header"><span>实例</span><span>节点</span><span>状态</span><span>资源</span><span>运行时间</span><span></span></div>
    <div v-for="item in items" :key="item.id" class="table-row">
      <div class="instance-name"><span class="cube">▣</span><div><strong>{{ item.name }}</strong><small>{{ item.id }} · {{ item.image }}</small></div></div>
      <span class="node-name">{{ item.node }}</span>
      <span class="status-pill" :class="item.status"><i></i>{{ statusLabel(item.status) }}</span>
      <span><b>{{ item.memory }}</b><small class="muted"> · CPU {{ item.cpuPercent.toFixed(2) }}% · {{ item.players }} 位玩家</small></span>
      <span class="uptime">{{ item.uptime }}</span>
      <div class="row-actions">
        <button :disabled="readOnly" @click="emit('control', 'restart', item)" :title="readOnly ? '生产只读模式' : '重启'">↻</button>
        <button :disabled="readOnly" @click="emit('control', item.status === 'running' ? 'stop' : 'start', item)" :title="readOnly ? '生产只读模式' : (item.status === 'running' ? '停止' : '启动')">{{ item.status === 'running' ? 'Ⅱ' : '▶' }}</button>
      </div>
    </div>
  </div>
</template>
