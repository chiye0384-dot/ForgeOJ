<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  AdminRequestError,
  adminRequest,
  adminHeaders,
  restoreAdmin,
  type AdminSession,
  type AdminPage,
} from '@/services/adminApi'
interface SearchStatus {
  enabled: boolean
  version: number
  publicEpoch: number
  readableEpoch: number
  pendingEvents: number
  deadLetters: number
  failedPublications: number
  blockedDeadLetters: number
  failedDeadPublications: number
  activeRebuild: string | null
}
interface Rebuild {
  id: string
  status: string
  attempts: number
  errorCode: string | null
  reason: string
  createdAt: string
  finishedAt: string | null
}
const session = ref<AdminSession | null>(null),
  status = ref<SearchStatus | null>(null),
  jobs = ref<Rebuild[]>([])
const page = ref(1),
  total = ref(0),
  reason = ref(''),
  error = ref(''),
  busy = ref(false),
  receipt = ref<Rebuild | null>(null)
const pending = ref<{
  actor: number
  body: { expectedVersion: number; clientRequestId: string; reason: string }
} | null>(null)
const allowed = computed(
  () =>
    session.value?.authenticated &&
    !session.value.admin?.mustChangePassword &&
    ['OPS_ADMIN', 'SUPER_ADMIN'].includes(session.value.admin?.role ?? ''),
)
let active = true,
  revision = 0,
  controller: AbortController | undefined
function clear() {
  status.value = null
  jobs.value = []
  total.value = 0
  receipt.value = null
}
function failed(failure: unknown) {
  clear()
  const code = failure instanceof AdminRequestError ? failure.status : 0
  if ([401, 403].includes(code)) {
    session.value = null
    pending.value = null
  }
  error.value =
    code === 401
      ? '后台登录已失效，请重新登录。'
      : code === 403
        ? '当前账号没有搜索运维权限，或需要先修改初始密码。'
        : code === 409
          ? '状态或版本已变化，请刷新后核对。'
          : '搜索运维暂不可用，请重试。'
}
async function load() {
  controller?.abort()
  controller = new AbortController()
  const stamp = ++revision
  const signal = controller.signal
  busy.value = true
  error.value = ''
  clear()
  try {
    const current = await restoreAdmin(signal)
    if (!active || stamp !== revision) return
    session.value = current
    if (!allowed.value) throw new AdminRequestError(current.authenticated ? 403 : 401)
    if (pending.value && pending.value.actor !== current.admin?.id) pending.value = null
    const [state, history] = await Promise.all([
      adminRequest<SearchStatus>('/search/status', { signal }),
      adminRequest<AdminPage<Rebuild>>(`/search/rebuilds?page=${page.value}&size=20`, { signal }),
    ])
    if (!active || stamp !== revision) return
    status.value = state
    jobs.value = history.items
    total.value = history.total
  } catch (failure) {
    if (active && stamp === revision) failed(failure)
  } finally {
    if (active && stamp === revision) busy.value = false
  }
}
async function rebuild() {
  if (busy.value || !allowed.value || !session.value?.admin || !status.value) return
  if (!pending.value) {
    if (!reason.value.trim() || !status.value.enabled || status.value.activeRebuild) return
    pending.value = {
      actor: session.value.admin.id,
      body: {
        expectedVersion: status.value.version,
        clientRequestId: crypto.randomUUID(),
        reason: reason.value.trim(),
      },
    }
  }
  const request = pending.value
  const stamp = ++revision
  busy.value = true
  error.value = ''
  try {
    const current = await restoreAdmin()
    if (!active || stamp !== revision) return
    session.value = current
    if (!allowed.value || current.admin?.id !== request.actor) throw new AdminRequestError(403)
    const result = await adminRequest<Rebuild>('/search/rebuilds', {
      method: 'POST',
      headers: adminHeaders(current),
      body: JSON.stringify(request.body),
    })
    if (!active || stamp !== revision) return
    receipt.value = result
    pending.value = null
    status.value = null
    jobs.value = []
  } catch (failure) {
    if (active && stamp === revision) {
      if (failure instanceof AdminRequestError && [400, 409].includes(failure.status))
        pending.value = null
      failed(failure)
    }
  } finally {
    if (active && stamp === revision) busy.value = false
  }
}
function changePage(next: number) {
  page.value = next
  void load()
}
function focus() {
  if (!busy.value) void load()
}
onMounted(() => {
  void load()
  window.addEventListener('focus', focus)
})
onUnmounted(() => {
  active = false
  revision++
  controller?.abort()
  clear()
  pending.value = null
  session.value = null
  window.removeEventListener('focus', focus)
})
</script>
<template>
  <main class="search-admin">
    <h1>公共题搜索运维</h1>
    <p>
      <RouterLink to="/admin/login">后台账号</RouterLink> ·
      <RouterLink to="/admin/operations">任务运维</RouterLink>
    </p>
    <p>
      重建公开题目的搜索索引，并为本次覆盖的补发失败死信安排一次有限补发。原失败记录保留。
      请输入原因，提交后刷新查看作业进度。
    </p>
    <button type="button" :disabled="busy" @click="load">刷新状态</button>
    <p v-if="busy" role="status">正在读取或提交…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="allowed">
      <dl v-if="status">
        <dt>搜索开关</dt>
        <dd>{{ status.enabled ? '已启用' : '未启用，按标题搜索' }}</dd>
        <dt>公开数据版本 / 搜索可读版本</dt>
        <dd>{{ status.publicEpoch }} / {{ status.readableEpoch }}</dd>
        <dt>待发布事件 / 累计死信</dt>
        <dd>{{ status.pendingEvents }} / {{ status.deadLetters }}</dd>
        <dt>发布失败 / 等待死信确认 / 死信补发失败</dt>
        <dd>
          {{ status.failedPublications }} / {{ status.blockedDeadLetters }} /
          {{ status.failedDeadPublications }}
        </dd>
        <dt>进行中的重建</dt>
        <dd>{{ status.activeRebuild ?? '无' }}</dd>
      </dl>
      <form @submit.prevent="rebuild">
        <label
          >重建原因
          <textarea v-model="reason" maxlength="500" :disabled="busy || !!pending" required />
        </label>
        <button
          type="submit"
          :disabled="
            busy ||
            !status ||
            (!pending && (!reason.trim() || !status.enabled || !!status.activeRebuild))
          "
        >
          {{ pending ? '重试原请求' : '提交重建' }}
        </button>
      </form>
      <p v-if="receipt" role="status">
        作业 {{ receipt.id }}：{{ receipt.status }}。刷新查看后续状态。
      </p>
      <table v-if="jobs.length">
        <caption>
          重建记录
        </caption>
        <thead>
          <tr>
            <th>作业</th>
            <th>状态</th>
            <th>尝试次数</th>
            <th>原因</th>
            <th>错误码</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in jobs" :key="job.id">
            <td>{{ job.id }}</td>
            <td>{{ job.status }}</td>
            <td>{{ job.attempts }}</td>
            <td>{{ job.reason }}</td>
            <td>{{ job.errorCode ?? '无' }}</td>
          </tr>
        </tbody>
      </table>
      <nav aria-label="重建记录分页">
        <button :disabled="busy || page <= 1" @click="changePage(page - 1)">上一页</button> 第
        {{ page }} 页，共 {{ total }} 项
        <button :disabled="busy || page * 20 >= total" @click="changePage(page + 1)">下一页</button>
      </nav>
    </template>
  </main>
</template>
<style scoped>
.search-admin {
  max-width: 1100px;
  margin: 2rem auto;
  padding: 1rem;
}
label {
  display: grid;
  gap: 0.5rem;
  margin: 1rem 0;
}
textarea {
  min-height: 5rem;
  max-width: 35rem;
}
table {
  width: 100%;
  margin: 1rem 0;
  border-collapse: collapse;
}
td,
th {
  padding: 0.6rem;
  border: 1px solid #d8e0eb;
  overflow-wrap: anywhere;
  text-align: left;
}
dt {
  font-weight: 600;
}
dd {
  margin-bottom: 0.75rem;
}
</style>
