<script setup lang="ts">
import { actionLabel, executionLabel, taskMessageLabel, taskResultLabel, taskStatusLabel, timeLabel } from '../models'
import type { TaskDetailState } from '../task-detail'
defineProps<{ state: TaskDetailState }>()
defineEmits<{ refresh: []; close: [] }>()
</script>

<template>
  <section class="panel task-detail" aria-label="任务详情" :aria-busy="state.phase === 'loading'">
    <div class="panel-head"><div><h2>任务详情与事件</h2><p class="identifier">{{ state.id }}</p></div><div class="detail-actions"><button class="text-btn" :disabled="state.phase === 'loading'" @click="$emit('refresh')">刷新详情</button><button class="text-btn" @click="$emit('close')">关闭详情</button></div></div>
    <p v-if="state.phase === 'loading'" class="empty-state">正在读取所选任务…</p>
    <p v-else-if="state.phase === 'error'" class="data-banner error" role="alert">任务读取失败：{{ state.error }}。未显示其他任务的结果。</p>
    <template v-else-if="state.task">
      <div class="task-detail-body">
        <p :class="['data-banner', state.task.executionMode === 'DOCKER' ? '' : 'mock']">{{ executionLabel(state.task.executionMode) }} · {{ taskResultLabel(state.task) }}。任务结果不会改写实例观测状态。</p>
        <p v-if="state.task.status === 'UNKNOWN'" class="data-banner error" role="alert">结果不确定，需要核对 Agent 与容器。禁止自动重新执行；当前页面只查询，不提供解除互斥或重做操作。</p>
        <dl class="task-facts">
          <div><dt>操作 / 发起人</dt><dd>{{ actionLabel(state.task.action) }} / {{ state.task.requestedBy }}</dd></div>
          <div><dt>所属节点</dt><dd>{{ state.task.nodeId || '未记录' }}</dd></div>
          <div><dt>中央实例 ID</dt><dd>{{ state.task.instanceId || '未记录' }}</dd></div>
          <div><dt>Agent 局部 ID</dt><dd>{{ state.task.agentInstanceId || '未记录' }}</dd></div>
          <div><dt>命令 ID</dt><dd>{{ state.task.commandId || '历史任务未记录' }}</dd></div>
          <div><dt>投递次数 / 结果码</dt><dd>{{ state.task.attempts ?? '未记录' }} / {{ state.task.resultCode || '暂无' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ timeLabel(state.task.createdAt) }}</dd></div>
          <div><dt>最近更新 / 结束</dt><dd>{{ timeLabel(state.task.updatedAt) }} / {{ state.task.finishedAt ? timeLabel(state.task.finishedAt) : '未结束' }}</dd></div>
        </dl>
        <p class="task-message">{{ taskMessageLabel(state.task.message) || '暂无任务说明' }}</p>
        <h3>事件时间线</h3><p v-if="!state.events.length" class="empty-state">此任务暂无事件记录，历史模拟任务不会补造执行事件。</p>
        <ol v-else class="task-events"><li v-for="event in state.events" :key="event.id"><div><b>#{{ event.sequence }} {{ event.fromStatus ? taskStatusLabel(event.fromStatus) + ' → ' : '' }}{{ taskStatusLabel(event.toStatus) }}</b><time>{{ timeLabel(event.occurredAt) }}</time></div><p>{{ taskMessageLabel(event.reason) || '未提供说明' }}</p><small>{{ event.actor }}</small></li></ol>
      </div>
    </template>
  </section>
</template>
