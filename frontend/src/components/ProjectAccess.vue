<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, ApiRequestError } from '../api'
import { authSession, type Project } from '../auth-session'

interface Member { userId: string; displayName: string; status: 'ACTIVE' | 'DISABLED'; role: 'ADMIN' | 'OPERATOR' | 'VIEWER'; version: number }
interface AdminUser { id: string; displayName: string; platformRole: 'ADMIN' | 'USER'; status: 'ACTIVE' | 'DISABLED'; version: number }

const props = defineProps<{ project: Project | null; readOnly: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const members = ref<Member[]>([]), users = ref<AdminUser[]>([])
const loading = ref(false), saving = ref(''), error = ref(''), notice = ref('')
const name = ref(''), newUserId = ref(''), newRole = ref<Member['role']>('VIEWER')
const canManage = computed(() => !!props.project && props.project.permissions.includes('MEMBER_MANAGE') && !props.readOnly)
const canRename = computed(() => !!props.project && props.project.permissions.includes('PROJECT_MANAGE') && !props.readOnly)
const availableUsers = computed(() => users.value.filter(user => user.status === 'ACTIVE' && !members.value.some(member => member.userId === user.id)))

function asRecord(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {} }
function readMembers(value: unknown): Member[] {
  if (!Array.isArray(value)) throw new Error('成员列表格式无效')
  return value.map(raw => { const x = asRecord(raw); if (typeof x.userId !== 'string' || typeof x.displayName !== 'string' || !['ACTIVE', 'DISABLED'].includes(String(x.status)) || !['ADMIN', 'OPERATOR', 'VIEWER'].includes(String(x.role)) || !Number.isSafeInteger(x.version)) throw new Error('成员身份格式无效'); return x as unknown as Member })
}
function readUsers(value: unknown): AdminUser[] {
  if (!Array.isArray(value)) throw new Error('用户列表格式无效')
  return value.map(raw => { const x = asRecord(raw); if (typeof x.id !== 'string' || typeof x.displayName !== 'string' || !['ACTIVE', 'DISABLED'].includes(String(x.status)) || !['ADMIN', 'USER'].includes(String(x.platformRole)) || !Number.isSafeInteger(x.version)) throw new Error('平台用户格式无效'); return x as unknown as AdminUser })
}
async function load() {
  if (!props.project) return
  loading.value = true; error.value = ''; notice.value = ''; name.value = props.project.name
  try {
    const values: [unknown, unknown | null] = await Promise.all([
      api.projectMembers(props.project.id),
      authSession.get().identity?.platformRole === 'ADMIN' ? api.adminUsers() : Promise.resolve(null)
    ])
    members.value = readMembers(values[0]); users.value = values[1] === null ? [] : readUsers(values[1])
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '成员读取失败' }
  finally { loading.value = false }
}
async function renameProject() {
  if (!canRename.value || !props.project || !name.value.trim() || saving.value) return
  saving.value = 'project'; error.value = ''; notice.value = ''
  try { await api.updateProject(props.project.id, { name: name.value.trim(), expectedVersion: props.project.permissionVersion }); notice.value = '项目名称已更新'; emit('changed') }
  catch (failure) { error.value = failure instanceof ApiRequestError ? failure.message : '项目名称更新失败' }
  finally { saving.value = '' }
}
async function changeRole(member: Member, role: Member['role']) {
  if (!canManage.value || role === member.role || saving.value) return
  saving.value = member.userId; error.value = ''; notice.value = ''
  try { await api.updateProjectMember(props.project!.id, member.userId, { role, expectedVersion: member.version }); notice.value = '成员权限已更新'; emit('changed') }
  catch (failure) { error.value = failure instanceof ApiRequestError ? failure.message : '成员权限更新失败' }
  finally { saving.value = ''; await load() }
}
async function addMember() {
  if (!canManage.value || !props.project || !newUserId.value || saving.value) return
  saving.value = newUserId.value; error.value = ''; notice.value = ''
  try { await api.updateProjectMember(props.project.id, newUserId.value, { role: newRole.value, expectedVersion: 0 }); newUserId.value = ''; notice.value = '成员已加入项目'; emit('changed') }
  catch (failure) { error.value = failure instanceof ApiRequestError ? failure.message : '成员加入失败' }
  finally { saving.value = ''; await load() }
}
async function removeMember(member: Member) {
  if (!canManage.value || saving.value) return
  if (!window.confirm(`确认移除成员“${member.displayName}”？`)) return
  saving.value = member.userId; error.value = ''; notice.value = ''
  try { await api.removeProjectMember(props.project!.id, member.userId, member.version); notice.value = '成员已移除'; emit('changed') }
  catch (failure) { error.value = failure instanceof ApiRequestError ? failure.message : '成员移除失败' }
  finally { saving.value = ''; await load() }
}
watch(() => props.project?.id, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="panel full access-panel" aria-label="项目成员与权限">
    <div class="panel-head"><div><h2>项目成员与权限</h2><p>权限变更使用版本号校验，最后一名项目管理员不能被移除或降级。</p></div><button class="refresh" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新' }}</button></div>
    <p v-if="!project" class="empty-state">请先选择项目。</p>
    <template v-else>
      <form class="access-project-form" @submit.prevent="renameProject"><label>项目名称<input v-model="name" :disabled="!canRename || saving === 'project'" maxlength="128" /></label><button class="primary" type="submit" :disabled="!canRename || saving === 'project' || !name.trim()">{{ saving === 'project' ? '保存中…' : '保存名称' }}</button><span class="identifier">项目版本 {{ project.permissionVersion }}</span></form>
      <p v-if="!canManage" class="data-banner">当前项目没有成员管理权限，下面仅显示成员清单。</p>
      <form v-if="canManage && users.length" class="access-add-form" @submit.prevent="addMember"><label>加入用户<select v-model="newUserId"><option value="">请选择平台用户</option><option v-for="user in availableUsers" :key="user.id" :value="user.id">{{ user.displayName }} · {{ user.id }}</option></select></label><label>项目角色<select v-model="newRole"><option value="VIEWER">查看者</option><option value="OPERATOR">操作员</option><option value="ADMIN">管理员</option></select></label><button class="primary" type="submit" :disabled="!newUserId || !!saving">加入项目</button></form>
      <p v-else-if="canManage && authSession.get().identity?.platformRole !== 'ADMIN'" class="data-banner">项目管理员可以调整已有成员；新增用户需要平台管理员先建立身份。</p>
      <div v-if="error" class="data-banner error" role="alert">{{ error }}</div><div v-if="notice" class="data-banner" role="status">{{ notice }}</div>
      <div v-if="!members.length && !loading" class="empty-state">暂无成员</div>
      <div v-else class="member-table"><div class="member-row member-head"><span>成员</span><span>平台状态</span><span>项目角色</span><span>版本</span><span>操作</span></div><div v-for="member in members" :key="member.userId" class="member-row"><span><strong>{{ member.displayName }}</strong><small>{{ member.userId }}</small></span><span>{{ member.status === 'ACTIVE' ? '正常' : '已禁用' }}</span><span><select :value="member.role" :disabled="!canManage || saving === member.userId" @change="changeRole(member, ($event.target as HTMLSelectElement).value as Member['role'])"><option value="ADMIN">管理员</option><option value="OPERATOR">操作员</option><option value="VIEWER">查看者</option></select></span><span>{{ member.version }}</span><span><button class="text-btn danger-text" :disabled="!canManage || saving === member.userId" @click="removeMember(member)">移除</button></span></div></div>
    </template>
  </section>
</template>
