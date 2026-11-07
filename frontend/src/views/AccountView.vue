<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  accountAction,
  completeRegistration,
  getSession,
  login,
  restoreSession,
  type SessionResponse,
} from '@/services/forgeojApi'
import { accountReturnPath } from '@/router/accountNavigation'

const localDemo = Boolean(document.querySelector('meta[name="forgeoj-local-demo"]'))
const router = useRouter()
const route = useRoute()
const session = ref<SessionResponse | null>(null)
const mode = ref(typeof route.params.action === 'string' ? route.params.action : 'login')
const username = ref('')
const email = ref('')
const nickname = ref('')
const password = ref('')
const currentPassword = ref('')
const message = ref('')
const error = ref('')
const busy = ref(false)
const token = ref('')
const headings: Record<string, string> = {
  login: '登录账号',
  register: '注册账号',
  waiting: '验证邮箱，完成注册',
  resend: '重发激活邮件',
  forgot: '找回密码',
  activate: '完成注册并登录',
  reset: '重设密码',
  security: '账号与安全',
  change: '修改密码',
  binding: '验证邮箱',
  bind: '确认邮箱验证',
  all: '退出所有设备',
}
const heading = computed(() => headings[mode.value] ?? '账号与安全')
const submitLabels: Record<string, string> = {
  login: '登录',
  register: '注册并发送验证邮件',
  resend: '发送激活邮件',
  forgot: '发送重置邮件',
  activate: '激活并登录此账号',
  reset: '保存新密码',
  change: '保存新密码',
  binding: '发送验证邮件',
  bind: '确认验证',
  all: '退出所有设备',
}

watch(
  () => route.path,
  () => {
    if (!session.value?.authenticated) {
      changeMode(typeof route.params.action === 'string' ? route.params.action : 'login')
    }
  },
)

async function loadSession(): Promise<void> {
  error.value = ''
  try {
    session.value = await restoreSession()
    if (
      session.value.authenticated &&
      ['login', 'register', 'forgot', 'resend', 'waiting'].includes(mode.value)
    )
      mode.value = 'security'
  } catch {
    error.value = '暂时无法读取账号状态，请点击重试。'
  }
}
async function consumeMailLink(hash: string): Promise<void> {
  if (!hash) return
  // Mail credentials stay in memory; remove them from both browser and router history.
  const fragment = new URLSearchParams(hash.slice(1))
  token.value = fragment.get('token') ?? ''
  const action = fragment.get('action')
  if (token.value && ['activate', 'reset', 'bind'].includes(action ?? '')) mode.value = action!
  window.history.replaceState(
    { ...window.history.state, current: window.location.pathname + window.location.search },
    '',
    window.location.pathname + window.location.search,
  )
  if (route.hash) await router.replace({ path: route.path, query: route.query, hash: '' })
}
watch(
  () => route.hash,
  (hash) => {
    if (hash) void consumeMailLink(hash)
  },
)
onMounted(async () => {
  await consumeMailLink(window.location.hash || route.hash)
  await loadSession()
})

function changeMode(next: string): void {
  if (busy.value) return
  mode.value = next
  if (!session.value?.authenticated && ['login', 'register', 'forgot', 'resend'].includes(next)) {
    const path = next === 'login' ? '/account' : `/account/${next}`
    if (route.path !== path) void router.push({ path, query: route.query })
  }
  password.value = ''
  currentPassword.value = ''
  message.value = ''
  error.value = ''
}
async function enterProduct(result: SessionResponse): Promise<void> {
  if (!result.authenticated) throw new Error('Missing authenticated session')
  session.value = result
  password.value = ''
  mode.value = 'security'
  message.value = '登录成功。'
  await router.replace(accountReturnPath(router, route.query.redirect))
}
async function signOut(): Promise<void> {
  if (!session.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await accountAction('logout', {}, session.value.csrf)
    session.value = await getSession()
    mode.value = 'login'
    password.value = ''
    message.value = '已退出当前账号。'
  } catch {
    error.value = '退出未完成，请重试。'
  } finally {
    busy.value = false
  }
}
async function submit(): Promise<void> {
  if (!session.value || busy.value) return
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    if (mode.value === 'login') {
      await enterProduct(await login(username.value.trim(), password.value, session.value.csrf))
      return
    }
    if (mode.value === 'activate') {
      const result = await completeRegistration(token.value, session.value.csrf)
      token.value = ''
      await enterProduct(result)
      return
    }
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
      reset: ['password-reset/confirm', { token: token.value, password: password.value }],
      change: [
        'password/change',
        { currentPassword: currentPassword.value, password: password.value },
      ],
      binding: ['email-binding/request', { password: currentPassword.value, email: email.value }],
      bind: ['email-binding/confirm', { token: token.value }],
      all: ['logout-all', {}],
    }
    const action = actions[mode.value]
    if (!action) return
    const completed = mode.value
    await accountAction(action[0], action[1], session.value.csrf)
    password.value = ''
    currentPassword.value = ''
    if (completed === 'register') {
      mode.value = 'waiting'
      message.value =
        '如果信息符合条件，验证邮件将发送到你填写的邮箱。打开邮件链接，确认激活后会自动登录。若未收到，请检查垃圾邮件，或重发激活邮件；已注册的账号请直接登录。'
    } else if (['resend', 'forgot', 'binding'].includes(completed)) {
      message.value = '如果信息符合条件，邮件将发送。请检查邮箱和垃圾邮件。'
    } else {
      if (['reset', 'bind'].includes(completed)) token.value = ''
      session.value = await getSession()
      mode.value = session.value.authenticated ? 'security' : 'login'
      message.value =
        completed === 'bind'
          ? '邮箱验证成功。'
          : completed === 'all'
            ? '已退出所有设备，请重新登录。'
            : '密码已更新，请使用新密码登录。'
    }
  } catch (failure) {
    const status = failure instanceof Error && 'status' in failure ? failure.status : 0
    error.value =
      status === 400
        ? '信息格式不正确，或链接已过期、已使用。'
        : status === 401
          ? '账号或密码不正确，或邮箱尚未激活。请检查输入，也可重发激活邮件。'
          : status === 429
            ? '操作过于频繁，请稍后重试。'
            : '操作未完成，请稍后重试。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="account" aria-labelledby="account-heading">
    <RouterLink to="/problems">← 返回题库</RouterLink>
    <h2 id="account-heading">{{ heading }}</h2>
    <p v-if="!session && !error" role="status">正在读取账号状态……</p>
    <button v-if="!session && error" type="button" @click="loadSession">重试</button>
    <template v-if="session">
      <p v-if="session.authenticated">当前账号：{{ session.user?.username }}</p>
      <nav v-if="session.authenticated" aria-label="账号安全设置">
        <button type="button" :disabled="busy" @click="changeMode('security')">账号概览</button>
        <button type="button" :disabled="busy" @click="changeMode('change')">修改密码</button>
        <button type="button" :disabled="busy" @click="changeMode('binding')">验证邮箱</button>
        <button type="button" :disabled="busy" @click="changeMode('all')">退出所有设备</button>
        <button type="button" :disabled="busy" @click="signOut">退出当前账号</button>
      </nav>
      <p v-if="mode === 'login'" class="intro">使用已有账号登录，继续练习和查看学习记录。</p>
      <p v-if="mode === 'register'" class="intro">
        填写注册信息，确认邮箱后自动登录。昵称可以不填。
      </p>
      <form
        v-if="!['security', 'waiting'].includes(mode)"
        data-testid="account-form"
        @submit.prevent="submit"
      >
        <label v-if="mode === 'login'"
          >用户名或邮箱
          <input
            v-model="username"
            name="username"
            required
            maxlength="254"
            autocomplete="username"
            autocapitalize="none"
          />
        </label>
        <label v-if="mode === 'register'"
          >用户名（3–32 位字母、数字、下划线）
          <input
            v-model="username"
            name="username"
            pattern="[A-Za-z0-9_]{3,32}"
            required
            autocomplete="username"
            autocapitalize="none"
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
          >昵称（可选，可重复）
          <input v-model="nickname" name="nickname" maxlength="64" autocomplete="nickname" />
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
        <label v-if="mode === 'login'"
          >密码
          <input
            v-model="password"
            name="password"
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
        <p v-if="mode === 'activate'">
          确认此邮件对应的账号，完成邮箱验证后会自动登录。<span v-if="session.authenticated"
            >这将切换当前浏览器的账号。</span
          >
        </p>
        <p v-if="mode === 'all'">所有设备的登录会话都会退出。</p>
        <p v-if="mode === 'bind'">请使用申请此链接的账号确认验证邮箱。</p>
        <p v-if="['change', 'reset'].includes(mode)">更新密码后需要重新登录。</p>
        <button
          class="primary"
          :aria-busy="busy"
          :disabled="busy || (['activate', 'reset', 'bind'].includes(mode) && !token)"
        >
          {{ busy ? '处理中……' : submitLabels[mode] }}
        </button>
      </form>
      <nav v-if="!session.authenticated" aria-label="账号入口">
        <template v-if="mode === 'login'">
          <span>还没有账号？</span
          ><button type="button" :disabled="busy" @click="changeMode('register')">注册账号</button>
          <button type="button" :disabled="busy" @click="changeMode('forgot')">忘记密码</button>
          <button type="button" :disabled="busy" @click="changeMode('resend')">重发激活邮件</button>
        </template>
        <template v-else>
          <button type="button" :disabled="busy" @click="changeMode('login')">返回登录</button>
          <button
            v-if="mode === 'waiting'"
            type="button"
            :disabled="busy"
            @click="changeMode('resend')"
          >
            重发激活邮件
          </button>
        </template>
      </nav>
    </template>
    <aside v-if="localDemo && !session?.authenticated" class="demo-note" aria-label="本机演示说明">
      <strong>本机演示</strong>
      <p>可直接登录：用户名 <code>learner</code>，密码 <code>forgeoj-dev-only</code>。</p>
      <p v-if="['register', 'waiting', 'resend', 'forgot', 'reset', 'activate'].includes(mode)">
        本机的注册和找回密码邮件仅进入开发模拟器，不会发送到真实邮箱。体验功能可返回登录，使用上述演示账号。
      </p>
    </aside>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.account {
  max-width: 540px;
  margin: 2rem auto;
  padding: clamp(1rem, 4vw, 2rem);
  background: white;
  border: 1px solid #d8e0eb;
  border-radius: 16px;
}
h2 {
  margin-bottom: 0.6rem;
}
.intro {
  color: #516176;
  line-height: 1.6;
}
nav {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.65rem;
  margin: 1.25rem 0 0;
}
form,
label {
  display: grid;
  gap: 0.6rem;
}
form {
  gap: 1.1rem;
  margin-top: 1.25rem;
}
input {
  min-width: 0;
  padding: 0.75rem;
  border: 1px solid #9cabbf;
  border-radius: 8px;
}
button {
  cursor: pointer;
  padding: 0.6rem 0.75rem;
  background: #eaf3f7;
  color: #15576d;
  border: 1px solid #c5dce5;
  border-radius: 8px;
}
.demo-note {
  margin-top: 1.2rem;
  padding: 0.9rem;
  border-radius: 8px;
  background: #f0f6fa;
  color: #40546c;
  font-size: 0.9rem;
  line-height: 1.7;
}
.demo-note p {
  margin: 0.4rem 0 0;
}
.primary {
  padding: 0.8rem;
  background: #176b87;
  color: white;
  border: 0;
  font-weight: 650;
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
[role='status'] {
  line-height: 1.7;
}
</style>
