<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  AdminRequestError,
  adminRequest,
  adminHeaders,
  restoreAdmin,
  type AdminPage,
  type AdminSession,
} from '@/services/adminApi'
import type {
  OperationsTask,
  OperationsAttempt,
  OperationsEvent,
  RecoveryReceipt,
  OperationsRecovery,
} from '@/services/operationsApi'

const session = ref<AdminSession | null>(null)
const tasks = ref<OperationsTask[]>([])
const selected = ref<OperationsTask | null>(null)
const attempts = ref<OperationsAttempt[]>([])
const events = ref<OperationsEvent[]>([])
const recoveries = ref<OperationsRecovery[]>([]),
  recoveryPage = ref(1),
  recoveryTotal = ref(0)
const reason = ref(''),
  confirmed = ref(false),
  receipt = ref<RecoveryReceipt | null>(null)
const pending = ref<{
  path: string
  body: {
    expectedVersion: number
    clientRequestId: string
    reason: string
    expectedPublishAttempts?: number
  }
  actor: number
} | null>(null)
const canRetry = computed(
  () =>
    selected.value &&
    !selected.value.executionRecoveryUsed &&
    ['DEAD_LETTER', 'SYSTEM_ERROR'].includes(selected.value.status) &&
    selected.value.attemptCount === selected.value.maxAttempts &&
    selected.value.maxAttempts < 10 &&
    ['PLATFORM_FAILURE', 'LEASE_EXPIRED', 'ATTEMPT_LIMIT_EXHAUSTED'].includes(
      selected.value.failureCode ?? '',
    ),
)
const kind = ref(''),
  status = ref(''),
  taskId = ref(''),
  error = ref(''),
  busy = ref(false)
const page = ref(1),
  total = ref(0),
  attemptPage = ref(1),
  attemptTotal = ref(0),
  eventPage = ref(1),
  eventTotal = ref(0)
const labels: Record<string, string> = {
  FORMAL: '正式判题',
  VALIDATE: '双程序验证',
  OUTPUT_PREVIEW: '参考输出预览',
  SELF_TEST: '独立自测',
}
const allowed = computed(
  () =>
    session.value?.authenticated &&
    !session.value.admin?.mustChangePassword &&
    ['OPS_ADMIN', 'SUPER_ADMIN'].includes(session.value.admin?.role ?? ''),
)
let active = true,
  revision = 0,
  controller: AbortController | undefined
function clear(): void {
  tasks.value = []
  total.value = 0
  clearDetail()
}
function clearDetail(): void {
  selected.value = null
  attempts.value = []
  events.value = []
  recoveries.value = []
  recoveryTotal.value = 0
  attemptTotal.value = 0
  eventTotal.value = 0
}
function begin(): { stamp: number; signal: AbortSignal } {
  controller?.abort()
  controller = new AbortController()
  busy.value = true
  error.value = ''
  return { stamp: ++revision, signal: controller.signal }
}
function current(stamp: number): boolean {
  return active && stamp === revision
}
function failed(failure: unknown): void {
  clear()
  const code = failure instanceof AdminRequestError ? failure.status : 0
  if ([401, 403].includes(code)) {
    session.value = null
    receipt.value = null
    pending.value = null
  }
  error.value =
    code === 401
      ? '后台登录已失效，请重新登录。'
      : code === 403
        ? '当前账号没有运维权限，或需要先修改初始密码。'
        : code === 400
          ? '请检查任务类型、任务ID、状态及分页。'
          : code === 404
            ? '任务不存在，请刷新查询。'
            : code === 409
              ? '当前状态、权限、版本或恢复次数不符合条件，请刷新后核对。'
              : code === 429
                ? '操作过于频繁，请稍后重试。'
                : '暂时无法读取运维数据，请刷新后重试。'
}
async function recover(event?: OperationsEvent): Promise<void> {
  if (busy.value || pending.value) return
  if (!selected.value || !session.value?.admin || !reason.value.trim() || !confirmed.value) return
  const task = selected.value
  pending.value = {
    path: `/operations/tasks/${task.kind}/${task.id}/${event ? `events/${event.id}/recover` : 'retry'}`,
    body: {
      expectedVersion: task.version,
      clientRequestId: crypto.randomUUID(),
      reason: reason.value.trim(),
      ...(event ? { expectedPublishAttempts: event.publishAttempts } : {}),
    },
    actor: session.value.admin.id,
  }
  await resend()
}
async function resend(): Promise<void> {
  if (busy.value) return
  const request = pending.value
  if (!request) return
  const { stamp, signal } = begin()
  receipt.value = null
  try {
    const identity = await restoreAdmin(signal)
    if (!current(stamp)) return
    if (
      identity.admin?.id !== session.value?.admin?.id ||
      !identity.authenticated ||
      identity.admin?.mustChangePassword ||
      !['OPS_ADMIN', 'SUPER_ADMIN'].includes(identity.admin?.role ?? '')
    ) {
      receipt.value = null
      pending.value = null
    }
    session.value = identity
    if (!allowed.value || identity.admin?.id !== request.actor) {
      pending.value = null
      clear()
      return
    }
    const result = await adminRequest<RecoveryReceipt>(request.path, {
      method: 'POST',
      headers: adminHeaders(identity),
      body: JSON.stringify(request.body),
      signal,
    })
    if (!current(stamp)) return
    receipt.value = result
    kind.value = result.kind
    taskId.value = result.taskId
    pending.value = null
    confirmed.value = false
    reason.value = ''
    clear()
  } catch (failure) {
    if (!current(stamp) || (failure instanceof DOMException && failure.name === 'AbortError'))
      return
    const code = failure instanceof AdminRequestError ? failure.status : 0
    if (code && code !== 503) pending.value = null
    failed(failure)
    if (!code || code === 503)
      error.value = '尚未收到处理凭证。可重发原请求核对结果，不会重复增加执行次数。'
  } finally {
    if (current(stamp)) busy.value = false
  }
}
async function load(reset = false): Promise<void> {
  if (reset) page.value = 1
  const { stamp, signal } = begin()
  clear()
  try {
    const identity = await restoreAdmin(signal)
    if (!current(stamp)) return
    if (
      identity.admin?.id !== session.value?.admin?.id ||
      !identity.authenticated ||
      identity.admin?.mustChangePassword ||
      !['OPS_ADMIN', 'SUPER_ADMIN'].includes(identity.admin?.role ?? '')
    ) {
      receipt.value = null
      pending.value = null
    }
    session.value = identity
    if (!allowed.value) return
    if (kind.value && taskId.value.trim()) {
      const task = await adminRequest<OperationsTask>(
        `/operations/tasks/${kind.value}/${taskId.value.trim()}`,
        { signal },
      )
      if (!current(stamp)) return
      tasks.value = [task]
      total.value = 1
      page.value = 1
      return
    }
    const query = new URLSearchParams({ page: String(page.value), size: '20' })
    if (kind.value) query.set('kind', kind.value)
    if (status.value) query.set('status', status.value)
    if (taskId.value.trim()) query.set('id', taskId.value.trim())
    const result = await adminRequest<AdminPage<OperationsTask>>(`/operations/tasks?${query}`, {
      signal,
    })
    if (!current(stamp)) return
    tasks.value = result.items
    total.value = result.total
  } catch (failure) {
    if (current(stamp) && !(failure instanceof DOMException && failure.name === 'AbortError'))
      failed(failure)
  } finally {
    if (current(stamp)) busy.value = false
  }
}
async function detail(task: OperationsTask, reset = false): Promise<void> {
  if (reset) {
    attemptPage.value = 1
    eventPage.value = 1
    recoveryPage.value = 1
  }
  const { stamp, signal } = begin()
  clearDetail()
  try {
    const identity = await restoreAdmin(signal)
    if (!current(stamp)) return
    if (identity.admin?.id !== session.value?.admin?.id) {
      receipt.value = null
      pending.value = null
      session.value = identity
      clear()
      return
    }
    session.value = identity
    if (!allowed.value) {
      receipt.value = null
      pending.value = null
      clear()
      return
    }
    const path = `/operations/tasks/${task.kind}/${task.id}`
    const [metadata, runs, deliveries, history] = await Promise.all([
      adminRequest<OperationsTask>(path, { signal }),
      adminRequest<AdminPage<OperationsAttempt>>(
        `${path}/attempts?page=${attemptPage.value}&size=20`,
        { signal },
      ),
      adminRequest<AdminPage<OperationsEvent>>(`${path}/events?page=${eventPage.value}&size=20`, {
        signal,
      }),
      adminRequest<AdminPage<OperationsRecovery>>(
        `${path}/recoveries?page=${recoveryPage.value}&size=20`,
        { signal },
      ),
    ])
    if (!current(stamp)) return
    selected.value = metadata
    attempts.value = runs.items
    attemptTotal.value = runs.total
    events.value = deliveries.items
    eventTotal.value = deliveries.total
    recoveries.value = history.items
    recoveryTotal.value = history.total
  } catch (failure) {
    if (current(stamp) && !(failure instanceof DOMException && failure.name === 'AbortError'))
      failed(failure)
  } finally {
    if (current(stamp)) busy.value = false
  }
}
function taskPaging(step: number): void {
  page.value += step
  void load()
}
function attemptPaging(step: number): void {
  if (!selected.value) return
  const task = selected.value
  attemptPage.value += step
  void detail(task)
}
function eventPaging(step: number): void {
  if (!selected.value) return
  const task = selected.value
  eventPage.value += step
  void detail(task)
}
function recoveryPaging(step: number): void {
  if (!selected.value) return
  const task = selected.value
  recoveryPage.value += step
  void detail(task)
}
onMounted(() => {
  void load()
})
onUnmounted(() => {
  active = false
  revision++
  controller?.abort()
  clear()
})
</script>

<template>
  <section class="operations">
    <h2>任务运维</h2>
    <nav>
      <RouterLink to="/admin">管理首页</RouterLink> ·
      <RouterLink to="/admin/login">后台登录</RouterLink>
      <button :disabled="busy" @click="load()">刷新</button>
    </nav>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="receipt" role="status">
      已记录{{ receipt.scope === 'EXECUTION' ? '执行恢复' : '投递恢复' }}，凭证
      {{ receipt.id }}，事件 {{ receipt.eventId }}。请刷新查看真实执行结果。
    </p>
    <button v-if="pending && allowed" :disabled="busy" @click="resend()">
      重发原请求，获取处理凭证
    </button>
    <p v-if="!busy && !allowed">请使用运维管理员或超级管理员账号，完成初始改密后访问。</p>
    <template v-if="allowed">
      <p>当前账号：{{ session?.admin?.username }}。查询仅展示执行和投递元数据。</p>
      <form @submit.prevent="load(true)">
        <label
          >用途<select v-model="kind">
            <option value="">全部</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option>
          </select></label
        >
        <label>任务ID<input v-model="taskId" maxlength="36" /></label>
        <label
          >状态<select v-model="status">
            <option value="">全部异常</option>
            <option
              v-for="value in [
                'QUEUED',
                'RUNNING',
                'RETRYING',
                'WAITING_RETRY',
                'SYSTEM_ERROR',
                'DEAD_LETTER',
              ]"
              :key="value"
            >
              {{ value }}
            </option>
          </select></label
        >
        <button :disabled="busy">查询</button>
      </form>
      <p>异常任务 {{ total }} 条 · 第 {{ page }} 页</p>
      <div class="table-wrap">
        <table data-testid="operations-tasks">
          <thead>
            <tr>
              <th>用途 / 任务</th>
              <th>状态</th>
              <th>执行次数</th>
              <th>平台失败码</th>
              <th>创建时间（数据库时间）</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="`${task.kind}:${task.id}`">
              <td>{{ labels[task.kind] }}<br />{{ task.id }}</td>
              <td>{{ task.status }}<br />版本 {{ task.version }}</td>
              <td>{{ task.attemptCount }} / {{ task.maxAttempts }}</td>
              <td>{{ task.failureCode ?? '—' }}</td>
              <td>{{ task.createdAt }}</td>
              <td><button :disabled="busy" @click="detail(task, true)">查看元数据</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav>
        <button :disabled="busy || page <= 1" @click="taskPaging(-1)">上一页</button
        ><button :disabled="busy || page * 20 >= total" @click="taskPaging(1)">下一页</button>
      </nav>
      <article v-if="selected" data-testid="operations-detail">
        <h3>{{ labels[selected.kind] }} · {{ selected.id }}</h3>
        <p>
          状态 {{ selected.status }} · 版本 {{ selected.version }} · 执行
          {{ selected.attemptCount }}/{{ selected.maxAttempts }} · 所属用户ID {{ selected.ownerId }}
        </p>
        <p>
          正式提交 {{ selected.submissionId ?? '—' }} · 冻结快照 {{ selected.snapshotId ?? '—' }}
        </p>
        <p>
          开始 {{ selected.startedAt ?? '—' }} · 完成 {{ selected.finishedAt ?? '—' }} · 下次尝试
          {{ selected.nextAttemptAt ?? '—' }} · 租约已过期 {{ selected.leaseExpired ? '是' : '否' }}
        </p>
        <p v-if="selected.expiresAt">自测内容到期：{{ selected.expiresAt }}</p>
        <p>
          人工执行恢复：{{
            selected.executionRecoveryUsed ? '已使用' : '尚未使用'
          }}（每任务最多一次）。资格由服务器复核。
        </p>
        <label
          >恢复理由<input v-model="reason" maxlength="500" :disabled="busy || !!pending"
        /></label>
        <label
          ><input
            v-model="confirmed"
            type="checkbox"
            :disabled="busy || !!pending"
          />已核对任务与失败原因，确认恢复。原代码、测试和提交时间保留。</label
        >
        <button
          v-if="canRetry"
          :disabled="busy || !!pending || !confirmed || !reason.trim()"
          @click="recover()"
        >
          追加一次执行
        </button>
        <h4>执行尝试（{{ attemptTotal }}）</h4>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>序号 / ID</th>
                <th>状态 / 失败码</th>
                <th>开始 / 完成</th>
                <th>心跳 / 租约截止</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="run in attempts" :key="run.id">
                <td>{{ run.number }} · {{ run.id }}</td>
                <td>{{ run.status }} · {{ run.failureCode ?? '—' }}</td>
                <td>{{ run.startedAt }}<br />{{ run.finishedAt ?? '—' }}</td>
                <td>{{ run.heartbeatAt }}<br />{{ run.leaseExpiresAt }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav>
          <button :disabled="busy || attemptPage <= 1" @click="attemptPaging(-1)">尝试上一页</button
          ><button :disabled="busy || attemptPage * 20 >= attemptTotal" @click="attemptPaging(1)">
            尝试下一页
          </button>
        </nav>
        <h4>投递与死信事件（{{ eventTotal }}）</h4>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>类型 / ID</th>
                <th>序号 / 投递次数</th>
                <th>失败码</th>
                <th>失败 / 发布</th>
                <th>恢复</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in events" :key="event.id">
                <td>{{ event.type }}<br />{{ event.id }}</td>
                <td>{{ event.sequence }} / {{ event.publishAttempts }}</td>
                <td>{{ event.errorCode ?? '—' }}</td>
                <td>{{ event.failedAt ?? '—' }}<br />{{ event.publishedAt ?? '—' }}</td>
                <td>
                  <button
                    v-if="event.failedAt && !event.publishedAt && !event.deliveryRecoveryUsed"
                    :disabled="busy || !!pending || !confirmed || !reason.trim()"
                    @click="recover(event)"
                  >
                    恢复此事件投递</button
                  ><span v-if="event.deliveryRecoveryUsed">人工投递恢复已使用</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav>
          <button :disabled="busy || eventPage <= 1" @click="eventPaging(-1)">事件上一页</button
          ><button :disabled="busy || eventPage * 20 >= eventTotal" @click="eventPaging(1)">
            事件下一页
          </button>
        </nav>
        <h4>人工恢复记录（{{ recoveryTotal }}）</h4>
        <p v-for="item in recoveries" :key="item.id">
          凭证 {{ item.id }} · {{ item.scope }} · 原状态 {{ item.previousStatus }} / 版本
          {{ item.previousVersion }} · 原次数 {{ item.previousAttempts }}/{{
            item.previousMaxAttempts
          }}
          · 原失败 {{ item.previousFailureCode ?? '—' }} / {{ item.previousFinishedAt ?? '—' }} ·
          恢复时间 {{ item.createdAt }} · 事件 {{ item.eventId }}
        </p>
        <nav>
          <button :disabled="busy || recoveryPage <= 1" @click="recoveryPaging(-1)">
            恢复记录上一页</button
          ><button
            :disabled="busy || recoveryPage * 20 >= recoveryTotal"
            @click="recoveryPaging(1)"
          >
            恢复记录下一页
          </button>
        </nav>
      </article>
    </template>
  </section>
</template>

<style scoped>
.operations {
  max-width: 1200px;
  margin: auto;
  padding: 1rem;
}
form,
nav {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin: 1rem 0;
  align-items: center;
}
label {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
table {
  width: 100%;
  border-collapse: collapse;
}
td,
th {
  padding: 0.5rem;
  border: 1px solid #cad4df;
  text-align: left;
  overflow-wrap: anywhere;
}
.table-wrap {
  overflow-x: auto;
}
article {
  margin-top: 1.5rem;
  padding: 1rem;
  border: 1px solid #cad4df;
  overflow-wrap: anywhere;
}
[role='alert'] {
  color: #9b2226;
}
</style>
