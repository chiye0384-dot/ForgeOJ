<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import {
  createSubmission,
  getProblem,
  getSession,
  restoreSession,
  accountAction,
  login,
  ApiRequestError,
  type ProblemResponse,
  type SessionResponse,
  type SubmissionStatusResponse,
} from '@/services/forgeojApi'
import { monitorSubmission } from '@/services/submissionMonitor'
import { useCodeDraft } from '@/composables/useCodeDraft'
import OfficialSolution from '@/components/OfficialSolution.vue'
import SelfTest from '@/components/SelfTest.vue'
import ProblemFeedback from '@/components/ProblemFeedback.vue'

const props = withDefaults(defineProps<{ slug?: string }>(), { slug: 'sum-two-integers' })

const username = ref('')
const password = ref('')
const initialSourceCode =
  props.slug === 'sum-two-integers'
    ? `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        long a = scanner.nextLong();
        long b = scanner.nextLong();
        System.out.println(a + b);
    }
}`
    : `public class Main {
    public static void main(String[] args) {
        // 在这里编写解答。
    }
}`
const sourceCode = ref(initialSourceCode)
const draft = useCodeDraft(props.slug, sourceCode)

const session = ref<SessionResponse | null>(null)
const problem = ref<ProblemResponse | null>(null)
const submission = ref<SubmissionStatusResponse | null>(null)
const loading = ref(true)
const signingIn = ref(false)
const submitting = ref(false)
const loggingOut = ref(false)
const errorMessage = ref('')
let stopMonitor: (() => void) | undefined
let disposed = false
let generation = 0

const authenticated = computed(() => session.value?.authenticated === true)
function stopMonitoring(): void {
  stopMonitor?.()
  stopMonitor = undefined
}

function toMessage(error: unknown): string {
  if (error instanceof ApiRequestError && error.status === 503)
    return '服务暂时不可用，请稍后重试。'
  return error instanceof Error ? error.message : '发生未知错误'
}

function isCurrent(operation: number): boolean {
  return !disposed && operation === generation
}

async function loadProblem(operation: number): Promise<void> {
  const loaded = await getProblem(props.slug).catch((error: unknown) => {
    if (error instanceof ApiRequestError && error.status === 404)
      throw new Error('题目不存在或暂时不可用，请返回题库选择其他题目。')
    throw error
  })
  if (!isCurrent(operation)) return
  if (loaded.slug !== props.slug) throw new Error('题目读取失败，请重新读取。')
  problem.value = loaded
  if (session.value?.authenticated) await draft.load(session.value.csrf)
}

async function initialize(): Promise<void> {
  const operation = ++generation
  stopMonitoring()
  draft.reset()
  problem.value = null
  submission.value = null
  loading.value = true
  errorMessage.value = ''
  try {
    const nextSession = await restoreSession()
    if (!isCurrent(operation)) return
    if (session.value?.user?.id !== nextSession.user?.id) sourceCode.value = initialSourceCode
    session.value = nextSession
    if (nextSession.authenticated) {
      await loadProblem(operation)
    }
  } catch (error) {
    if (isCurrent(operation)) errorMessage.value = toMessage(error)
  } finally {
    if (isCurrent(operation)) loading.value = false
  }
}

async function handleLogin(): Promise<void> {
  if (!session.value || signingIn.value) {
    return
  }

  signingIn.value = true
  const operation = ++generation
  errorMessage.value = ''
  try {
    const nextSession = await login(username.value, password.value, session.value.csrf)
    if (!isCurrent(operation)) return
    session.value = nextSession
    password.value = ''
    sourceCode.value = initialSourceCode
    await loadProblem(operation)
  } catch (error) {
    if (isCurrent(operation)) errorMessage.value = toMessage(error)
  } finally {
    if (isCurrent(operation)) signingIn.value = false
  }
}

async function handleLogout(): Promise<void> {
  if (!session.value || loggingOut.value) return
  const operation = ++generation
  const csrf = session.value.csrf
  loggingOut.value = true
  loading.value = true
  submitting.value = false
  errorMessage.value = ''
  stopMonitoring()
  problem.value = null
  submission.value = null
  draft.reset()
  sourceCode.value = initialSourceCode
  username.value = ''
  password.value = ''
  try {
    await accountAction('logout', {}, csrf)
    if (!isCurrent(operation)) return
    const nextSession = await getSession()
    if (!isCurrent(operation)) return
    session.value = nextSession
    if (nextSession.authenticated) await loadProblem(operation)
  } catch (error) {
    if (isCurrent(operation)) errorMessage.value = `退出未完成：${toMessage(error)}`
  } finally {
    if (isCurrent(operation)) {
      loading.value = false
      loggingOut.value = false
    }
  }
}

async function handleSubmit(): Promise<void> {
  if (!session.value?.authenticated || !problem.value || submitting.value || loggingOut.value) {
    return
  }

  stopMonitoring()
  const operation = ++generation
  submitting.value = true
  submission.value = null
  errorMessage.value = ''
  try {
    const created = await createSubmission(
      problem.value.slug,
      sourceCode.value,
      session.value.csrf,
      crypto.randomUUID(),
    )
    if (!isCurrent(operation)) return
    submission.value = {
      ...created,
      verdict: null,
      diagnosticMessage: null,
    }
    stopMonitor = monitorSubmission(
      submission.value,
      (latest) => {
        if (!isCurrent(operation)) return
        submission.value = latest
        errorMessage.value = ''
      },
      (error) => {
        if (isCurrent(operation)) errorMessage.value = toMessage(error)
      },
    )
  } catch (error) {
    if (isCurrent(operation)) errorMessage.value = toMessage(error)
  } finally {
    if (isCurrent(operation)) submitting.value = false
  }
}

onMounted(() => {
  void initialize()
})
onBeforeUnmount(() => {
  disposed = true
  generation += 1
  stopMonitoring()
})
</script>

<template>
  <section class="workspace" aria-live="polite">
    <p class="workspace-navigation"><a href="/problems">返回题库</a></p>
    <p v-if="loading" class="notice">正在读取登录状态……</p>

    <div v-else-if="!authenticated" class="card login-card">
      <p class="eyebrow">ForgeOJ 账号</p>
      <h2>登录后开始判题</h2>
      <p class="muted">使用 ForgeOJ 账号开始练习所选题目。</p>

      <form data-testid="login-form" class="form-stack" @submit.prevent="handleLogin">
        <label>
          用户名或邮箱
          <input
            v-model.trim="username"
            data-testid="username"
            name="username"
            autocomplete="username"
            required
          />
        </label>
        <label>
          密码
          <input
            v-model="password"
            data-testid="password"
            name="password"
            type="password"
            autocomplete="current-password"
            required
          />
        </label>
        <button type="submit" :disabled="signingIn">
          {{ signingIn ? '登录中……' : '登录' }}
        </button>
      </form>
      <p><a href="/account">注册、激活或找回密码</a></p>
    </div>

    <div v-else-if="problem" class="judge-grid">
      <article class="card problem-card">
        <div class="problem-heading">
          <div>
            <p class="eyebrow">题目 · 版本 {{ problem.judgeVersion }}</p>
            <h2>{{ problem.title }}</h2>
          </div>
          <div>
            <span class="user-chip">{{ session?.user?.username }}</span>
            <p>
              <a href="/account">账号设置</a> ·
              <button type="button" data-testid="logout" @click="handleLogout">退出</button>
            </p>
          </div>
        </div>

        <p>{{ problem.statement }}</p>
        <h3>输入</h3>
        <p>{{ problem.inputDescription }}</p>
        <h3>输出</h3>
        <p>{{ problem.outputDescription }}</p>

        <section v-for="(sample, index) in problem.publicSamples" :key="index" class="sample">
          <h3>公开样例 {{ index + 1 }}</h3>
          <div class="sample-grid">
            <div>
              <strong>输入</strong>
              <pre>{{ sample.input }}</pre>
            </div>
            <div>
              <strong>输出</strong>
              <pre>{{ sample.output }}</pre>
            </div>
          </div>
        </section>

        <p class="limits">
          {{ problem.resourceLimits.timeLimitMs }} ms ·
          {{ problem.resourceLimits.memoryLimitMb }} MB · 输出上限
          {{ problem.resourceLimits.outputLimitBytes }} bytes
        </p>
        <section v-if="problem.attribution">
          <h3>题目来源与许可</h3>
          <p>作者：{{ problem.attribution.authorName }} · {{ problem.attribution.originType }}</p>
          <p v-if="problem.attribution.sourceUrl">来源：{{ problem.attribution.sourceUrl }}</p>
          <p>许可：{{ problem.attribution.licenseStatement }}</p>
          <p v-if="problem.attribution.correctionOfSlug">
            修正自 {{ problem.attribution.correctionOfSlug }}，旧题完成记录不代表本题已完成。
          </p>
        </section>
        <OfficialSolution
          v-if="session?.user"
          :slug="problem.slug"
          :judge-version="problem.judgeVersion"
          :user-id="session.user.id"
          :csrf="session.csrf"
          :refresh-key="submission?.processingStatus === 'FINISHED' ? submission.statusVersion : 0"
        />
      </article>

      <section class="card submission-card">
        <div class="problem-heading">
          <div>
            <p class="eyebrow">Java 21</p>
            <h2>提交代码</h2>
          </div>
          <span class="language-chip">JAVA_21</span>
        </div>

        <form data-testid="submission-form" class="form-stack" @submit.prevent="handleSubmit">
          <p class="muted" data-testid="draft-status">{{ draft.message.value }}</p>
          <div v-if="draft.conflict.value" class="draft-conflict" role="alert">
            <button type="button" @click="draft.useServer">载入服务端草稿</button>
            <button type="button" @click="draft.keepLocal">保留本页代码</button>
            <button type="button" @click="draft.saveLocal">保存本页版本</button>
          </div>
          <button v-else type="button" @click="draft.retry">重试草稿读取或保存</button>
          <label>
            Main.java
            <textarea
              v-model="sourceCode"
              data-testid="source-code"
              name="sourceCode"
              spellcheck="false"
              required
            />
          </label>
          <button type="submit" :disabled="submitting || sourceCode.trim().length === 0">
            {{ submitting ? '提交中……' : '提交并判题' }}
          </button>
        </form>
        <SelfTest
          v-if="session?.user && problem"
          :slug="problem.slug"
          :source-code="sourceCode"
          :user-id="session.user.id"
          :csrf="session.csrf"
        />
        <p>
          <a :href="`/learning?tab=history&problemSlug=${encodeURIComponent(props.slug)}`"
            >查看本人提交历史</a
          >
          · <a href="/learning">管理题单与进度</a>
        </p>

        <section v-if="submission" class="result-card">
          <p class="eyebrow">提交 {{ submission.submissionId }}</p>
          <div class="result-row">
            <strong class="status">{{ submission.processingStatus }}</strong>
            <strong v-if="submission.verdict" class="verdict">{{ submission.verdict }}</strong>
          </div>
          <p>状态版本：{{ submission.statusVersion }}</p>
          <p v-if="submission.judgeDataWarning" role="alert">{{ submission.judgeDataWarning }}</p>
          <p v-if="submission.diagnosticMessage" class="diagnostic">
            {{ submission.diagnosticMessage }}
          </p>
        </section>
        <ProblemFeedback
          v-if="session?.user"
          :slug="problem.slug"
          :user-id="session.user.id"
          :csrf="session.csrf"
        />
      </section>
    </div>

    <div v-else-if="!loading" class="card unavailable-card">
      <p>当前题目暂时无法读取。</p>
      <button type="button" @click="initialize">重新读取</button>
      <button type="button" data-testid="logout" :disabled="loggingOut" @click="handleLogout">
        退出账号
      </button>
    </div>

    <p v-if="errorMessage" role="alert" class="error">{{ errorMessage }}</p>
  </section>
</template>

<style scoped>
.workspace {
  width: min(1180px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 2rem 0 4rem;
}

.judge-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.9fr);
  gap: 1.25rem;
  align-items: start;
}

.card {
  padding: 1.5rem;
  border: 1px solid #d8e0eb;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 12px 30px rgb(31 49 77 / 8%);
}

.login-card {
  max-width: 420px;
  margin: 3rem auto;
}

.eyebrow {
  margin: 0 0 0.35rem;
  color: #176b87;
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

h2 {
  margin: 0 0 1rem;
}

h3 {
  margin-top: 1.5rem;
  margin-bottom: 0.45rem;
}

.muted,
.limits,
.notice {
  color: #5d6878;
}

.workspace-navigation a {
  color: #15576d;
}

.unavailable-card button + button {
  margin-left: 0.75rem;
}

.form-stack {
  display: grid;
  gap: 1rem;
}

label {
  display: grid;
  gap: 0.45rem;
  font-weight: 650;
}

input,
textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #b8c4d4;
  border-radius: 8px;
  padding: 0.72rem 0.8rem;
  color: #152033;
  background: #fbfcfe;
  font: inherit;
}

textarea {
  min-height: 370px;
  resize: vertical;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 0.88rem;
  line-height: 1.55;
}

input:focus,
textarea:focus {
  outline: 3px solid rgb(36 143 175 / 18%);
  border-color: #248faf;
}

button {
  border: 0;
  border-radius: 9px;
  padding: 0.8rem 1.1rem;
  color: white;
  background: #176b87;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

button:disabled {
  cursor: wait;
  opacity: 0.6;
}

.problem-heading,
.result-row {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: start;
}

.user-chip,
.language-chip {
  padding: 0.35rem 0.65rem;
  border-radius: 999px;
  color: #15576d;
  background: #e7f5f8;
  font-size: 0.8rem;
  font-weight: 700;
}

.sample-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

pre {
  min-height: 1.5rem;
  margin: 0.35rem 0 0;
  padding: 0.75rem;
  overflow: auto;
  border-radius: 8px;
  background: #f1f5f9;
}

.limits {
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid #e3e8ef;
  font-size: 0.9rem;
}

.result-card {
  margin-top: 1.25rem;
  padding: 1rem;
  border-radius: 10px;
  background: #f1f8fa;
}

.status,
.verdict {
  font-size: 1.2rem;
}

.verdict {
  color: #137449;
}

.diagnostic,
.error {
  white-space: pre-wrap;
}

.error {
  max-width: 760px;
  margin: 1rem auto;
  padding: 0.9rem 1rem;
  border: 1px solid #f1b7b7;
  border-radius: 8px;
  color: #8a2424;
  background: #fff2f2;
}

@media (max-width: 850px) {
  .judge-grid,
  .sample-grid {
    grid-template-columns: 1fr;
  }
}
</style>
