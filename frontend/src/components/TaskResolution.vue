<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { executionLabel, taskStatusLabel, timeLabel, type Task } from '../models'
import { newIntentKey } from '../task-control'
import { assertReviewIdentity, createReviewSender, disabledReview, matchesReviewIntent, normalizeReviewBody, resolutionIntent, resolutionStatusLabel, reviewReasonLabel, REVIEW_DECISION,
  type Resolution, type ResolutionEvent, type ReviewCapabilities, type ReviewEvidence, type ReviewIntent, type ReviewIntentState } from '../task-resolution'

const props = defineProps<{ taskId: string; readOnly: boolean }>()
const emit = defineEmits<{ changed: []; selectTask: [id: string] }>()
const task = ref<Task | null>(null)
const record = ref<Resolution | null>(null)
const events = ref<ResolutionEvent[]>([])
const caps = ref<ReviewCapabilities>(disabledReview())
const evidence = ref<ReviewEvidence | null>(null)
const reading = ref(false), readingEvidence = ref(false), ready = ref(false)
const error = ref(''), evidenceError = ref(''), notice = ref('')
const reason = ref(''), manualEvidence = ref(''), noReplay = ref(false), residualRisk = ref(false)
const intentState = ref<ReviewIntentState>({ phase: 'idle', intent: null, error: '' })
let generation = 0, evidenceGeneration = 0, alive = true
let controller: AbortController | undefined, evidenceController: AbortController | undefined, timer: number | undefined
const sender = createReviewSender(api.resolveTask, {
  getItem: key => window.sessionStorage.getItem(key), setItem: (key, value) => window.sessionStorage.setItem(key, value), removeItem: key => window.sessionStorage.removeItem(key)
}, value => { intentState.value = value })
const samePending = computed(() => intentState.value.intent?.taskId === props.taskId)
const conflict = computed(() => samePending.value && record.value && !matchesReviewIntent(record.value, intentState.value.intent!))
const canSubmit = computed(() => ready.value && !reading.value && !props.readOnly && task.value?.status === 'UNKNOWN'
  && caps.value.canReview && !record.value && evidence.value?.canAcknowledge === true && !readingEvidence.value
  && intentState.value.phase === 'idle' && noReplay.value && residualRisk.value && !!reason.value.trim() && !!manualEvidence.value.trim())
const canRecheck = computed(() => ready.value && !reading.value && !props.readOnly && caps.value.canRecheck && record.value?.status === 'BLOCKED'
  && caps.value.resolutionId === record.value.id && intentState.value.phase === 'idle')
const canRetryPending = computed(() => ready.value && !reading.value && !props.readOnly && samePending.value && !record.value
  && caps.value.canReview && evidence.value?.canAcknowledge === true && !readingEvidence.value && intentState.value.phase === 'uncertain')
const boolLabel = (value: boolean | null) => value === null ? '未验证' : value ? '是' : '否'
const processLabel = (value: string | null) => value === 'CURRENT_PROCESS_CLEARED' ? '本进程已知执行器已退出' : value === 'PRIOR_PROCESS_UNVERIFIED' ? '上个进程的执行上下文无法证明' : '未验证'
const stateLabel = (value: string | null) => value === 'RUNNING' ? '运行中' : value === 'STOPPED' ? '已停止' : value === 'UNKNOWN' ? '未知' : '不可用'
function clearEvidence() {
  evidenceGeneration++; evidenceController?.abort(); evidence.value = null; readingEvidence.value = false; evidenceError.value = ''
}
async function refresh() {
  const ticket = ++generation
  controller?.abort(); controller = new AbortController()
  const signal = controller.signal
  reading.value = true; ready.value = false; error.value = ''; caps.value = disabledReview('正在读取核对权限')
  // 每轮权限刷新也废弃先前的确认前证据，避免权限/绑定变化仍沿用旧门槛。
  clearEvidence()
  try {
    const [original, existing, capability] = await Promise.all([api.task(props.taskId, signal), api.taskResolution(props.taskId, signal),
      api.reviewCapabilities(props.taskId, signal).catch(() => disabledReview('核对权限读取失败，已禁止提交'))])
    if (!alive || ticket !== generation) return
    if (original.id !== props.taskId) throw Error('原任务身份不匹配')
    if (existing) assertReviewIdentity(existing, original)
    const timeline = existing ? await api.resolutionEvents(existing.id, signal) : []
    if (!alive || ticket !== generation) return
    task.value = original; record.value = existing; events.value = timeline; caps.value = capability; ready.value = true
    if (existing && samePending.value && sender.reconcile(existing)) emit('changed')
  } catch (failure) {
    if (!alive || ticket !== generation) return
    task.value = null; record.value = null; events.value = []; caps.value = disabledReview('核对记录读取失败，已禁止提交')
    error.value = failure instanceof Error ? failure.message : '核对读取失败'
  } finally { if (alive && ticket === generation) reading.value = false }
}
async function readEvidence() {
  clearEvidence()
  const original = task.value
  if (!ready.value || reading.value || !original) return
  const ticket = ++evidenceGeneration
  evidenceController = new AbortController(); readingEvidence.value = true
  try {
    const value = await api.reviewEvidence(original, evidenceController.signal)
    if (!alive || ticket !== evidenceGeneration) return
    assertReviewIdentity(value, original)
    evidence.value = value
  } catch (failure) {
    if (alive && ticket === evidenceGeneration) evidenceError.value = failure instanceof Error ? failure.message : '确认前证据读取失败'
  } finally { if (alive && ticket === evidenceGeneration) readingEvidence.value = false }
}
async function submit(intent?: ReviewIntent) {
  const result = await sender.submit(intent)
  if (!alive) return
  notice.value = result ? '核对请求已持久接收（202），尚不代表互斥已经解除；请查看独立核对状态。' : intentState.value.error
  if (result) { reason.value = ''; manualEvidence.value = ''; noReplay.value = false; residualRisk.value = false }
  // 包括 409 或丢响应：只 GET 已有记录，不自动重发原请求，也不创建替代键。
  await refresh()
  if (alive) emit('changed')
}
async function submitNew() {
  if (!canSubmit.value || !task.value) return
  try {
    const body = normalizeReviewBody({ decision: REVIEW_DECISION, reason: reason.value, evidence: manualEvidence.value, acknowledgeNoReplay: noReplay.value, acknowledgeResidualRisk: residualRisk.value })
    const original = task.value
    await submit({ key: newIntentKey(), taskId: original.id, instanceId: original.instanceId, nodeId: original.nodeId, agentInstanceId: original.agentInstanceId, commandId: original.commandId, body })
  } catch (failure) { notice.value = failure instanceof Error ? failure.message : '核对未发送' }
}
async function recheck() { if (canRecheck.value && record.value) await submit(resolutionIntent(record.value)) }
async function retryPending() { if (canRetryPending.value) await submit() }
function adoptExisting() {
  if (conflict.value && record.value && sender.adoptExisting(record.value)) { notice.value = '已确认服务端唯一核对记录，后续只继续这条记录；没有发送新请求。'; emit('changed') }
}
onMounted(() => {
  void refresh()
  timer = window.setInterval(() => {
    const awaitingResult = task.value && ['PENDING', 'DISPATCHING', 'DELIVERED', 'RUNNING', 'RETRY_WAIT'].includes(task.value.status)
    // 原任务仍执行时同步状态与互斥；进入 UNKNOWN 且无核对记录后，保留正在填写的表单与确认前证据。
    if (!reading.value && intentState.value.phase !== 'submitting' && (record.value || !ready.value || awaitingResult)) void refresh()
  }, 15000)
})
onUnmounted(() => { alive = false; generation++; controller?.abort(); clearEvidence(); sender.invalidate(); if (timer) window.clearInterval(timer) })
</script>

<template>
  <section class="panel task-resolution" aria-label="UNKNOWN 人工核对">
    <div class="panel-head"><div><h2>独立人工核对</h2><p>记录依据并核对原互斥；不重放命令，不改写原 UNKNOWN。</p></div><button class="text-btn" :disabled="reading || intentState.phase === 'submitting'" @click="refresh">刷新核对记录</button></div>
    <div class="task-detail-body">
      <p v-if="reading" class="data-banner">正在读取核对记录和权限…</p>
      <p v-if="error" class="data-banner error" role="alert">{{ error }}；人工核对已禁用。</p>
      <p v-if="notice" class="data-banner" role="status">{{ notice }}</p>
      <div v-if="intentState.phase !== 'idle'" class="data-banner pending-review" role="status">
        <strong>{{ intentState.phase === 'submitting' ? '正在等待核对接受响应' : '有核对提交结果待确认' }}</strong><p>{{ intentState.error }}</p>
        <template v-if="intentState.intent"><p class="identifier">原任务：{{ intentState.intent.taskId }} · 原命令：{{ intentState.intent.commandId }}</p>
          <button v-if="!samePending" class="text-btn" @click="emit('selectTask', intentState.intent.taskId)">打开待确认的原任务</button>
          <template v-else-if="!record"><p>已先查询服务端记录。没有记录时，显式重送原请求可能首次创建核对；原键和原正文不变，不执行容器命令。</p><button class="refresh" :disabled="!canRetryPending" @click="retryPending">按原请求确认提交结果</button></template>
          <template v-else-if="conflict"><p>服务端已有另一条唯一核对记录。请阅读下方原正文后继续已有记录，不创建替代请求。</p><button class="refresh" :disabled="!ready || reading" @click="adoptExisting">确认查看已有记录并结束本地待确认</button></template>
        </template>
      </div>
      <template v-if="task">
        <p class="identifier">原任务 {{ task.id }} · {{ executionLabel(task.executionMode) }} · {{ task.nodeId }} / {{ task.agentInstanceId }}</p>
        <p>原任务结果：<b>{{ taskStatusLabel(task.status) }}</b>。原任务当前持有互斥：<b>{{ boolLabel(task.blocksInstance) }}</b>。这不代表实例没有另一条新任务的互斥。</p>
        <p v-if="props.readOnly" class="data-banner">全局只读模式：可查看核对记录，不能提交或续办。</p>
        <p v-else-if="!caps.canReview && !caps.canRecheck" class="data-banner">{{ caps.reason }}</p>
        <template v-if="record">
          <h3>人工核对记录 · {{ resolutionStatusLabel(record.status) }}</h3>
          <p v-if="record.status === 'APPLIED'" class="data-banner applied-review">已人工核对并解除互斥，原执行结果仍不确定。仅解除原任务的互斥；后续操作须另行确认，产生新的独立命令。</p>
          <dl class="task-facts"><div><dt>核对记录 / 技术结果码</dt><dd>{{ record.id }} / {{ record.resultCode || '暂无' }}</dd></div><div><dt>累计尝试 / 发起人</dt><dd>{{ record.attempts }} / {{ record.requestedBy }}</dd></div><div><dt>接收 / 最近更新</dt><dd>{{ timeLabel(record.createdAt) }} / {{ timeLabel(record.updatedAt) }}</dd></div><div><dt>应用时间</dt><dd>{{ record.appliedAt ? timeLabel(record.appliedAt) : '尚未应用' }}</dd></div></dl>
          <h4>已持久化的原始原因</h4><p class="review-text">{{ record.reason }}</p><h4>人工证据（纯文本）</h4><p class="review-text">{{ record.evidence }}</p>
          <template v-if="record.agentEvidence"><h4>独立 Agent 回执证据</h4><dl class="task-facts"><div><dt>Agent 原记录为</dt><dd>{{ taskStatusLabel(record.agentEvidence.taskStatus) }} / {{ record.agentEvidence.taskResultCode || '无结果码' }}</dd></div><div><dt>原命令观测 / 完成时间</dt><dd>{{ stateLabel(record.agentEvidence.taskObservedStatus) }} / {{ record.agentEvidence.taskFinishedAt ? timeLabel(record.agentEvidence.taskFinishedAt) : '未记录' }}</dd></div><div><dt>本次独立采样 / 尝试时间</dt><dd>{{ stateLabel(record.agentEvidence.observedInstanceStatus) }} / {{ timeLabel(record.agentEvidence.observedAt) }}</dd></div><div><dt>执行器判断</dt><dd>{{ processLabel(record.agentEvidence.processAssessment) }}</dd></div></dl><p>上述证据不证明 Docker daemon 已停止处理原请求，也不替代实例采集状态。</p></template>
          <p v-if="record.status === 'BLOCKED'" class="data-banner error">核对受阻，原互斥保留。修正条件后可显式按原请求重新检查；理由、证据、幂等键和命令均不变。</p>
          <button v-if="record.status === 'BLOCKED'" class="refresh" :disabled="!canRecheck" @click="recheck">按原核对请求重新检查</button>
          <h4>独立核对事件时间线</h4><p v-if="!events.length">暂无核对事件。</p><ol v-else class="task-events review-events"><li v-for="event in events" :key="event.id"><div><b>#{{ event.sequence }} {{ event.fromStatus ? resolutionStatusLabel(event.fromStatus) + ' → ' : '' }}{{ resolutionStatusLabel(event.toStatus) }}</b><time>{{ timeLabel(event.occurredAt) }}</time></div><p>{{ reviewReasonLabel(event.reason) }}</p><small>{{ event.actor }} · {{ event.reason }}</small></li></ol>
        </template>
        <template v-else-if="task.status === 'UNKNOWN'">
          <h3>确认前只读证据</h3><p>先读取原 Agent 任务、绑定、执行器及互斥条件。不会执行容器动作；证据仅供本次人工判断，后台仍会重新验证。</p>
          <button class="refresh" :disabled="!ready || reading || readingEvidence || intentState.phase === 'submitting'" @click="readEvidence">{{ readingEvidence ? '正在读取证据…' : '读取确认前证据' }}</button>
          <p v-if="evidenceError" class="data-banner error">{{ evidenceError }}；旧证据已废弃。</p>
          <template v-if="evidence"><p :class="['data-banner', evidence.canAcknowledge ? '' : 'error']">{{ reviewReasonLabel(evidence.classification) }} · 读取时间 {{ timeLabel(evidence.checkedAt) }}</p><dl class="task-facts"><div><dt>绑定一致 / 原 Agent 状态</dt><dd>{{ boolLabel(evidence.bindingMatches) }} / {{ evidence.agentTask ? taskStatusLabel(evidence.agentTask.status as Task['status']) : '无有效原记录' }}</dd></div><div><dt>worker / 已知进程仍活动</dt><dd>{{ boolLabel(evidence.workerActive) }} / {{ boolLabel(evidence.knownProcessesActive) }}</dd></div><div><dt>Agent 互斥归属</dt><dd>{{ evidence.lockDisposition === 'OWNED_BY_COMMAND' ? '属于原命令' : evidence.lockDisposition === 'NONE' ? '无互斥' : evidence.lockDisposition === 'OWNED_BY_OTHER' ? '属于其他命令' : '未验证' }}</dd></div><div><dt>执行器判断</dt><dd>{{ processLabel(evidence.processAssessment) }}</dd></div></dl></template>
          <form v-if="!record && intentState.phase === 'idle'" class="review-form" @submit.prevent="submitNew">
            <label>核对原因（最多 500 字）<textarea v-model="reason" rows="2" aria-label="核对原因" :disabled="props.readOnly || !caps.canReview" /></label>
            <label>人工证据（最多 4000 字）<textarea v-model="manualEvidence" rows="4" aria-label="人工证据" :disabled="props.readOnly || !caps.canReview" placeholder="记录检查的状态、日志、时间及判断依据；不要填写令牌或密钥。" /></label>
            <label class="review-ack"><input v-model="noReplay" type="checkbox" :disabled="props.readOnly || !caps.canReview" />我确认本次只核对并申请解除原互斥，不会重放原命令，也不代表原操作成功。</label>
            <label class="review-ack"><input v-model="residualRisk" type="checkbox" :disabled="props.readOnly || !caps.canReview" />我理解即使 CLI 已退出，Docker daemon 仍可能继续处理原请求；后续独立操作存在剩余风险。</label>
            <button class="primary" type="submit" :disabled="!canSubmit">提交人工核对（不执行容器命令）</button>
          </form>
        </template>
        <p v-else>此任务不是 UNKNOWN，无需创建人工核对。</p>
      </template>
    </div>
  </section>
</template>
