<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  adminAction,
  adminRequest,
  restoreAdmin,
  AdminRequestError,
  type AdminSession,
  type AdminAccount,
  type AdminEvent,
  type AdminPage,
  type AdminRole,
} from '@/services/adminApi'

const props = withDefaults(defineProps<{ section?: string }>(), { section: 'home' })
const session = ref<AdminSession | null>(null)
const accounts = ref<AdminAccount[]>([]),
  events = ref<AdminEvent[]>([])
const page = ref(1),
  total = ref(0),
  busy = ref(false),
  message = ref(''),
  error = ref('')
const username = ref(''),
  password = ref(''),
  currentPassword = ref(''),
  newPassword = ref('')
const role = ref<AdminRole>('CONTENT_REVIEWER'),
  reason = ref(''),
  eventAction = ref(''),
  actorId = ref(''),
  targetId = ref(''),
  from = ref(''),
  to = ref('')
const createRequestId = ref(crypto.randomUUID())
let revision = 0,
  active = true,
  controller: AbortController | undefined
const roles: Record<AdminRole, string> = {
  CONTENT_REVIEWER: '内容审核员',
  OPS_ADMIN: '运维管理员',
  SUPER_ADMIN: '超级管理员',
}
function clear(): void {
  accounts.value = []
  events.value = []
  total.value = 0
}
function failed(failure: unknown): void {
  clear()
  const status = failure instanceof AdminRequestError ? failure.status : 0
  if ([401, 403].includes(status)) {
    session.value = session.value
      ? { authenticated: false, admin: null, csrf: session.value.csrf }
      : null
    password.value = ''
    currentPassword.value = ''
    newPassword.value = ''
    reason.value = ''
  }
  error.value =
    status === 401
      ? '登录已失效，请重新登录。'
      : status === 403
        ? '当前账号没有此权限，或需要先修改初始密码。'
        : status === 409
          ? '账号状态已变化，或操作会停用最后一个超级管理员。请刷新后重试。'
          : status === 429
            ? '操作过于频繁，请稍后重试。'
            : status === 400
              ? '请检查输入格式和时间范围。'
              : '暂时无法完成操作，请刷新后重试。'
}
async function load(): Promise<void> {
  const stamp = ++revision
  controller?.abort()
  controller = new AbortController()
  clear()
  error.value = ''
  busy.value = true
  try {
    const current = await restoreAdmin(controller.signal)
    if (!active || stamp !== revision) return
    if (!current.authenticated || current.admin?.id !== session.value?.admin?.id) {
      password.value = ''
      currentPassword.value = ''
      newPassword.value = ''
      reason.value = ''
    }
    session.value = current
    if (
      !current.authenticated ||
      current.admin?.mustChangePassword ||
      current.admin?.role !== 'SUPER_ADMIN'
    )
      return
    if (props.section === 'accounts') {
      const result = await adminRequest<AdminPage<AdminAccount>>(
        `/accounts?page=${page.value}&size=20`,
        { signal: controller.signal },
      )
      if (!active || stamp !== revision) return
      accounts.value = result.items
      total.value = result.total
    } else if (props.section === 'audit') {
      const query = new URLSearchParams({ page: String(page.value), size: '20' })
      for (const [key, value] of [
        ['action', eventAction.value],
        ['actorId', actorId.value],
        ['targetId', targetId.value],
        ['from', from.value],
        ['to', to.value],
      ])
        if (value) query.set(key!, value!)
      const result = await adminRequest<AdminPage<AdminEvent>>(`/audit-events?${query}`, {
        signal: controller.signal,
      })
      if (!active || stamp !== revision) return
      events.value = result.items
      total.value = result.total
    }
  } catch (failure) {
    if (
      active &&
      stamp === revision &&
      !(failure instanceof DOMException && failure.name === 'AbortError')
    )
      failed(failure)
  } finally {
    if (active && stamp === revision) busy.value = false
  }
}
async function act(path: string, body: unknown, method = 'POST'): Promise<void> {
  if (!session.value || busy.value) return
  const stamp = ++revision
  busy.value = true
  error.value = ''
  message.value = ''
  clear()
  try {
    const result = await adminAction<AdminSession | void>(path, body, session.value, method)
    if (!active || stamp !== revision) return
    if (path === '/auth/login') {
      session.value = result as AdminSession
      message.value = '登录成功。'
    } else if (path.startsWith('/auth/')) {
      session.value = null
      message.value = '操作成功，请重新登录。'
    } else {
      message.value = '操作成功。'
      if (path === '/accounts') createRequestId.value = crypto.randomUUID()
    }
    password.value = ''
    currentPassword.value = ''
    newPassword.value = ''
    await load()
  } catch (failure) {
    if (active && stamp === revision) failed(failure)
  } finally {
    password.value = ''
    currentPassword.value = ''
    newPassword.value = ''
    if (active && stamp === revision) busy.value = false
  }
}
function login(): Promise<void> {
  return act('/auth/login', { username: username.value, password: password.value })
}
function change(): Promise<void> {
  return act('/auth/password/change', {
    currentPassword: currentPassword.value,
    password: newPassword.value,
  })
}
function create(): Promise<void> {
  return act('/accounts', {
    clientRequestId: createRequestId.value,
    username: username.value,
    role: role.value,
    password: password.value,
    reason: reason.value,
  })
}
async function mutate(account: AdminAccount, action: string): Promise<void> {
  if (!reason.value.trim()) {
    error.value = '请填写操作原因。'
    return
  }
  if (
    !window.confirm(
      `确认对 ${account.username} 执行${action === 'disable' ? '停用' : action === 'restore' ? '恢复' : action === 'role' ? '角色调整' : '密码重置'}？该账号旧会话会失效。`,
    )
  )
    return
  await act(
    `/accounts/${account.id}/${action}`,
    {
      expectedVersion: account.version,
      reason: reason.value,
      role: role.value,
      password: password.value,
    },
    action === 'role' ? 'PUT' : 'POST',
  )
}
function move(delta: number): void {
  page.value += delta
  void load()
}
function focus(): void {
  void load()
}
function filter(): void {
  page.value = 1
  void load()
}
onMounted(() => {
  window.addEventListener('focus', focus)
  void load()
})
onUnmounted(() => {
  active = false
  revision++
  controller?.abort()
  clear()
  window.removeEventListener('focus', focus)
})
</script>

<template>
  <section class="admin-page">
    <h2>管理后台</h2>
    <nav aria-label="后台导航">
      <a href="/admin">管理首页</a>
      <template v-if="session?.admin?.role === 'SUPER_ADMIN' && !session.admin.mustChangePassword">
        <a href="/admin/accounts">管理员账号</a><a href="/admin/audit">操作审计</a>
      </template>
      <button type="button" :disabled="busy" @click="load">刷新</button>
    </nav>
    <p role="status">{{ message }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <form
      v-if="session && !session.authenticated"
      data-testid="admin-login"
      @submit.prevent="login"
    >
      <p>使用独立管理员账号登录。</p>
      <label
        >用户名<input
          v-model="username"
          name="username"
          autocomplete="username"
          required
          maxlength="32"
      /></label>
      <label
        >密码<input
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
      /></label>
      <button :disabled="busy">登录</button>
    </form>
    <template v-if="session?.authenticated && session.admin">
      <p>当前账号：{{ session.admin.username }} · {{ roles[session.admin.role] }}</p>
      <nav
        v-if="
          !session.admin.mustChangePassword &&
          ['OPS_ADMIN', 'SUPER_ADMIN'].includes(session.admin.role)
        "
      >
        <RouterLink to="/admin/operations">任务运维</RouterLink>
      </nav>
      <nav
        v-if="
          !session.admin.mustChangePassword &&
          ['CONTENT_REVIEWER', 'SUPER_ADMIN'].includes(session.admin.role)
        "
      >
        <RouterLink to="/admin/reviews">公共题审核</RouterLink> ·
        <RouterLink to="/admin/problems">公共题维护</RouterLink> ·
        <RouterLink to="/admin/feedback">题目反馈</RouterLink>
      </nav>
      <p v-if="session.admin.mustChangePassword">请先修改初始或重置密码，完成后重新登录。</p>
      <form data-testid="admin-password" @submit.prevent="change">
        <label
          >当前密码<input
            v-model="currentPassword"
            name="currentPassword"
            type="password"
            autocomplete="current-password"
            required
        /></label>
        <label
          >新密码<input
            v-model="newPassword"
            name="newPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            required
        /></label>
        <button :disabled="busy">修改密码</button>
      </form>
      <button type="button" :disabled="busy" @click="act('/auth/logout', {})">退出当前会话</button>
      <button type="button" :disabled="busy" @click="act('/auth/logout-all', {})">
        退出全部会话
      </button>
      <template v-if="!session.admin.mustChangePassword">
        <p v-if="session.admin.role !== 'SUPER_ADMIN'">
          账号已就绪。管理员账号和审计记录由超级管理员管理。
        </p>
        <template v-if="session.admin.role === 'SUPER_ADMIN' && section === 'accounts'">
          <h3>管理员账号</h3>
          <form data-testid="admin-create" @submit.prevent="create">
            <label
              >新账号用户名<input
                v-model="username"
                name="newUsername"
                pattern="[A-Za-z0-9_]{3,32}"
                required
            /></label>
            <label
              >角色<select v-model="role" name="role">
                <option v-for="(label, value) in roles" :key="value" :value="value">
                  {{ label }}
                </option>
              </select></label
            >
            <label
              >初始或重置密码<input
                v-model="password"
                name="initialPassword"
                type="password"
                autocomplete="new-password"
                minlength="12"
                required
            /></label>
            <label>操作原因<input v-model="reason" name="reason" maxlength="500" required /></label>
            <button :disabled="busy">创建管理员</button>
          </form>
          <p>
            修改现有账号时，使用上方所选角色、重置密码和操作原因。新建或重置后首次登录必须改密。
          </p>
          <table>
            <thead>
              <tr>
                <th>账号</th>
                <th>角色</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="account in accounts" :key="account.id">
                <td>{{ account.username }}</td>
                <td>{{ roles[account.role] }}</td>
                <td>
                  {{ account.status === 'ACTIVE' ? '有效' : '已停用'
                  }}{{ account.mustChangePassword ? '，需改密' : '' }}
                </td>
                <td>
                  <button type="button" :disabled="busy" @click="mutate(account, 'role')">
                    调整角色
                  </button>
                  <button
                    type="button"
                    :disabled="busy"
                    @click="mutate(account, account.status === 'ACTIVE' ? 'disable' : 'restore')"
                  >
                    {{ account.status === 'ACTIVE' ? '停用' : '恢复' }}
                  </button>
                  <button
                    type="button"
                    :disabled="busy || account.id === session.admin.id"
                    @click="mutate(account, 'password/reset')"
                  >
                    重置密码
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
        <template v-if="session.admin.role === 'SUPER_ADMIN' && section === 'audit'">
          <h3>操作审计</h3>
          <form @submit.prevent="filter">
            <label>动作<input v-model="eventAction" name="action" /></label
            ><label>管理员 ID<input v-model="actorId" type="number" min="1" /></label
            ><label>目标账号 ID<input v-model="targetId" type="number" min="1" /></label
            ><label>开始时间（UTC）<input v-model="from" type="datetime-local" /></label
            ><label>结束时间（UTC）<input v-model="to" type="datetime-local" /></label
            ><button :disabled="busy">筛选</button>
          </form>
          <table>
            <thead>
              <tr>
                <th>时间（UTC）</th>
                <th>动作</th>
                <th>执行者</th>
                <th>目标</th>
                <th>结果与原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in events" :key="event.id">
                <td>{{ event.occurredAt }}</td>
                <td>{{ event.action }}</td>
                <td>{{ event.actorAdminId ?? event.actorType }}</td>
                <td>{{ event.targetId }}</td>
                <td>
                  {{ event.outcome }} · {{ event.reason }}
                  <details>
                    <summary>状态与关联记录</summary>
                    <p>{{ event.beforeState }}</p>
                    <p>{{ event.afterState }}</p>
                    <p>{{ event.correlationId }}</p>
                  </details>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
        <div v-if="session.admin.role === 'SUPER_ADMIN' && ['accounts', 'audit'].includes(section)">
          <p>第 {{ page }} 页，共 {{ total }} 条</p>
          <button type="button" :disabled="busy || page <= 1" @click="move(-1)">上一页</button
          ><button type="button" :disabled="busy || page * 20 >= total" @click="move(1)">
            下一页
          </button>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.admin-page {
  padding: 1.5rem;
}
nav,
form {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  margin: 1rem 0;
  align-items: end;
}
label {
  display: grid;
  gap: 0.3rem;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  padding: 0.6rem;
  border-bottom: 1px solid #dce3ec;
  text-align: left;
}
td button {
  margin: 0.2rem;
}
[role='alert'] {
  color: #a32222;
}
</style>
