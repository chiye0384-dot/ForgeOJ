<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  accountAction,
  getSession,
  restoreSession,
  type SessionResponse,
} from '@/services/forgeojApi'

const session = ref<SessionResponse | null>(null)
const mode = ref('register')
const username = ref('')
const email = ref('')
const nickname = ref('')
const password = ref('')
const currentPassword = ref('')
const message = ref('')
const error = ref('')
const busy = ref(false)
let token = ''

onMounted(async () => {
  // Consume the address fragment into memory, then remove it before any requests.
  const fragment = new URLSearchParams(window.location.hash.slice(1))
  token = fragment.get('token') ?? ''
  const action = fragment.get('action')
  if (token && ['activate', 'reset', 'bind'].includes(action ?? '')) mode.value = action!
  window.history.replaceState(null, '', window.location.pathname)
  try {
    session.value = await restoreSession()
  } catch {
    error.value = '暂时无法读取账号状态，请刷新重试。'
  }
})

function changeMode(next: string): void {
  mode.value = next
  password.value = ''
  currentPassword.value = ''
  message.value = ''
  error.value = ''
}

async function submit(): Promise<void> {
  if (!session.value || busy.value) return
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    const actions: Record<string, [string, unknown]> = {
      register: [
        'register',
        {
          username: username.value,
          email: email.value,
          password: password.value,
          nickname: nickname.value,
        },
      ],
      resend: ['email-verification/request', { email: email.value }],
      forgot: ['password-reset/request', { email: email.value }],
      activate: ['email-verification/confirm', { token }],
      reset: ['password-reset/confirm', { token, password: password.value }],
      change: [
        'password/change',
        { currentPassword: currentPassword.value, password: password.value },
      ],
      binding: ['email-binding/request', { password: currentPassword.value, email: email.value }],
      bind: ['email-binding/confirm', { token }],
      all: ['logout-all', {}],
    }
    const action = actions[mode.value]
    if (!action) return
    await accountAction(action[0], action[1], session.value.csrf)
    password.value = ''
    currentPassword.value = ''
    message.value = ['register', 'resend', 'forgot', 'binding'].includes(mode.value)
      ? '如果信息符合条件，邮件将发送。请检查邮箱；开发环境请打开本地邮件模拟器。'
      : '操作成功。激活、改密或重置后请重新登录。'
    if (['activate', 'reset', 'bind'].includes(mode.value)) token = ''
    session.value = await getSession()
  } catch (failure) {
    const status = failure instanceof Error && 'status' in failure ? failure.status : 0
    error.value =
      status === 400
        ? '信息格式不正确，或链接已过期、已使用。'
        : status === 401
          ? '身份验证失败，请检查密码或重新登录。'
          : status === 429
            ? '操作过于频繁，请稍后重试。'
            : '操作未完成，请刷新页面后重试。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="account">
    <a href="/">← 返回判题</a>
    <h2>账号与安全</h2>
    <nav aria-label="账号功能">
      <button type="button" @click="changeMode('register')">注册</button>
      <button type="button" @click="changeMode('resend')">重发激活邮件</button>
      <button type="button" @click="changeMode('forgot')">找回密码</button>
      <template v-if="session?.authenticated">
        <button type="button" @click="changeMode('change')">修改密码</button>
        <button type="button" @click="changeMode('binding')">验证邮箱</button>
        <button type="button" @click="changeMode('all')">全部退出</button>
      </template>
    </nav>
    <p v-if="session?.authenticated">当前账号：{{ session.user?.username }}</p>
    <form data-testid="account-form" @submit.prevent="submit">
      <label v-if="mode === 'register'"
        >用户名（3–32 位字母、数字、下划线）
        <input
          v-model="username"
          name="username"
          pattern="[A-Za-z0-9_]{3,32}"
          required
          autocomplete="username"
        />
      </label>
      <label v-if="['register', 'resend', 'forgot', 'binding'].includes(mode)"
        >邮箱
        <input
          v-model.trim="email"
          name="email"
          type="email"
          maxlength="254"
          required
          autocomplete="email"
        />
      </label>
      <label v-if="mode === 'register'"
        >昵称（可重复）
        <input v-model="nickname" name="nickname" maxlength="64" />
      </label>
      <label v-if="['change', 'binding'].includes(mode)"
        >当前密码
        <input
          v-model="currentPassword"
          name="currentPassword"
          type="password"
          required
          autocomplete="current-password"
        />
      </label>
      <label v-if="['register', 'reset', 'change'].includes(mode)"
        >新密码（至少 12 字符，最多 72 UTF-8 字节）
        <input
          v-model="password"
          name="password"
          type="password"
          minlength="12"
          required
          autocomplete="new-password"
        />
      </label>
      <p v-if="mode === 'all'">这会关闭所有浏览器和设备的登录会话，需要重新登录。</p>
      <p v-if="mode === 'activate'">确认激活此邮件链接对应的账号。</p>
      <p v-if="mode === 'bind'">确认验证邮箱。需要登录申请此链接的账号。</p>
      <p v-if="['change', 'reset'].includes(mode)">密码更新成功后，所有旧登录会话都会失效。</p>
      <button
        :aria-busy="busy"
        :disabled="busy || !session || (['activate', 'reset', 'bind'].includes(mode) && !token)"
      >
        {{ busy ? '处理中……' : '确认操作' }}
      </button>
    </form>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.account {
  max-width: 620px;
  margin: 2rem auto;
  padding: 2rem;
  background: white;
  border: 1px solid #d8e0eb;
  border-radius: 16px;
}
nav {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin: 1rem 0;
}
form,
label {
  display: grid;
  gap: 0.6rem;
}
form {
  gap: 1rem;
}
input {
  padding: 0.7rem;
  border: 1px solid #b8c4d4;
  border-radius: 8px;
}
button {
  cursor: pointer;
  padding: 0.65rem;
  background: #176b87;
  color: white;
  border: 0;
  border-radius: 8px;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
button[aria-busy='true'] {
  cursor: wait;
}
[role='alert'] {
  color: #8a2424;
}
</style>
