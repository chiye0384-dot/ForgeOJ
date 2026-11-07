<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { matchedRouteKey, onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink } from 'vue-router'

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
import { useDraftLeaveGuard } from '@/composables/useDraftLeaveGuard'
import OfficialSolution from '@/components/OfficialSolution.vue'
import SelfTest from '@/components/SelfTest.vue'
import ProblemFeedback from '@/components/ProblemFeedback.vue'
import CodeEditor from '@/components/CodeEditor.vue'
import WorkspaceSplit from '@/components/WorkspaceSplit.vue'
import SubmissionHistory from '@/components/SubmissionHistory.vue'
import type { SubmissionSource } from '@/services/learningApi'
import { judgeLabel, judgeAdvice } from '@/services/judgePresentation'

const props = withDefaults(defineProps<{ slug?: string }>(), { slug: 'sum-two-integers' })

const tabs = [
  { id: 'description', label: '题目描述' },
  { id: 'solution', label: '官方题解' },
  { id: 'self-test', label: '自定义自测' },
  { id: 'feedback', label: '题目反馈' },
  { id: 'history', label: '提交历史' },
]
const activeTab = ref('description')
const visitedTabs = ref(['description'])
const editorExpanded = ref(false)
function selectTab(id: string) {
  if (!visitedTabs.value.includes(id)) visitedTabs.value.push(id)
  activeTab.value = id
}
function openSelfTest() {
  editorExpanded.value = false
  selectTab('self-test')
}
async function tabKey(event: KeyboardEvent) {
  const index = tabs.findIndex((tab) => tab.id === activeTab.value)
  const next =
    event.key === 'ArrowRight'
      ? (index + 1) % tabs.length
      : event.key === 'ArrowLeft'
        ? (index + tabs.length - 1) % tabs.length
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? tabs.length - 1
            : -1
  if (next < 0) return
  event.preventDefault()
  selectTab(tabs[next]!.id)
  await nextTick()
  document.getElementById('workspace-tab-' + activeTab.value)?.focus()
}

const username = ref('')
const password = ref('')
const initialSourceCode = `public class Main {
    public static void main(String[] args) {
        // 在这里编写解答。
    }
}`
const sourceCode = ref(initialSourceCode)
const draft = useCodeDraft(props.slug, sourceCode)
const prepareToLeave = useDraftLeaveGuard(draft)
if (inject(matchedRouteKey, undefined)?.value) {
  onBeforeRouteLeave(prepareToLeavePage)
  onBeforeRouteUpdate(prepareToLeavePage)
}

const session = ref<SessionResponse | null>(null)
const problem = ref<ProblemResponse | null>(null)
const submission = ref<SubmissionStatusResponse | null>(null)
const loading = ref(true)
const signingIn = ref(false)
const submitting = ref(false)
const loggingOut = ref(false)
const errorMessage = ref('')
const pendingSubmission = ref<{
  requestId: string
  source: string
  slug: string
  userId: number
} | null>(null)
async function prepareToLeavePage() {
  if (
    pendingSubmission.value &&
    !window.confirm(
      '这次提交是否成功尚未确认。离开后将无法在本页重试原请求，请先到提交历史核对。仍要离开吗？',
    )
  )
    return false
  return prepareToLeave()
}
function protectPending(event: BeforeUnloadEvent) {
  if (pendingSubmission.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
function allowNewSubmission() {
  if (
    window.confirm(
      '原提交可能已经被服务器接收。请先检查提交历史；继续新提交可能增加一条记录。确定不再追查原请求吗？',
    )
  )
    pendingSubmission.value = null
}
async function loadHistoricalSource(value: SubmissionSource) {
  const operation = generation
  if (value.problemSlug !== props.slug || draft.conflict.value) {
    errorMessage.value = '请先处理当前草稿冲突，再载入历史代码。'
    return
  }
  if (!window.confirm('将用这份历史代码替换编辑区内容。当前修改会先尝试保存，确认载入吗？')) return
  if (draft.dirty.value && !(await draft.flush())) {
    errorMessage.value = '当前修改尚未保存，请先重试保存后再载入历史代码。'
    return
  }
  if (!isCurrent(operation) || value.problemSlug !== props.slug || draft.conflict.value) return
  sourceCode.value = value.sourceCode
}
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
  pendingSubmission.value = null
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
  if (!(await prepareToLeavePage())) return
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
  pendingSubmission.value = null
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

async function handleSubmit(retry = false): Promise<void> {
  if (!session.value?.authenticated || !problem.value || submitting.value || loggingOut.value) {
    return
  }
  if (pendingSubmission.value && !retry) return
  if (!pendingSubmission.value)
    pendingSubmission.value = {
      requestId: crypto.randomUUID(),
      source: sourceCode.value,
      slug: problem.value.slug,
      userId: session.value.user!.id,
    }
  const captured = pendingSubmission.value
  if (captured.userId !== session.value.user?.id || captured.slug !== props.slug) {
    pendingSubmission.value = null
    return
  }

  stopMonitoring()
  const operation = ++generation
  submitting.value = true
  submission.value = null
  errorMessage.value = ''
  try {
    const created = await createSubmission(
      captured.slug,
      captured.source,
      session.value.csrf,
      captured.requestId,
    )
    if (!isCurrent(operation)) return
    if (
      !created.submissionId ||
      !Number.isSafeInteger(created.statusVersion) ||
      !['QUEUED', 'RUNNING', 'RETRYING', 'FINISHED', 'CANCELLED', 'SYSTEM_ERROR'].includes(
        created.processingStatus,
      )
    )
      throw new Error('提交回执无效。')
    pendingSubmission.value = null
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
    if (isCurrent(operation)) {
      if (error instanceof ApiRequestError && error.status < 500 && error.status !== 408)
        pendingSubmission.value = null
      errorMessage.value = pendingSubmission.value
        ? '尚未确认服务器是否收到这次提交。请重试原提交，或先查看左侧提交历史。'
        : toMessage(error)
    }
  } finally {
    if (isCurrent(operation)) submitting.value = false
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', protectPending)
  void initialize()
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', protectPending)
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

    <template v-else-if="problem">
      <header class="workspace-toolbar">
        <div class="workspace-title">
          <h2>{{ problem.title }}</h2>
          <span class="version-chip">版本 {{ problem.judgeVersion }}</span>
        </div>
        <nav aria-label="做题操作">
          <RouterLink :to="`/learning?tab=history&problemSlug=${encodeURIComponent(props.slug)}`"
            >提交记录</RouterLink
          >
          <span class="user-chip">{{ session?.user?.username }}</span>
          <RouterLink to="/account">账号设置</RouterLink>
          <button type="button" class="text-button" data-testid="logout" @click="handleLogout">
            退出
          </button>
        </nav>
      </header>
      <WorkspaceSplit :expanded="editorExpanded">
        <template #left>
          <section class="pane problem-pane" aria-label="题目与练习工具">
            <div class="panel-tabs" role="tablist" aria-label="题目内容" @keydown="tabKey">
              <button
                v-for="tab in tabs"
                :id="'workspace-tab-' + tab.id"
                :key="tab.id"
                type="button"
                role="tab"
                :aria-selected="activeTab === tab.id"
                :aria-controls="'workspace-panel-' + tab.id"
                :tabindex="activeTab === tab.id ? 0 : -1"
                @click="selectTab(tab.id)"
              >
                {{ tab.label }}
              </button>
            </div>
            <div class="panel-content">
              <section
                v-show="activeTab === 'description'"
                id="workspace-panel-description"
                role="tabpanel"
                aria-labelledby="workspace-tab-description"
                tabindex="0"
              >
                <h3>题目描述</h3>
                <p class="notice">
                  本平台使用标准输入输出：从 System.in 读入，用 System.out 输出。请提交完整的 public
                  class Main 程序；力扣中只有 Solution 方法的代码需要补齐 main 入口。
                </p>
                <p class="statement">{{ problem.statement }}</p>
                <h3>输入</h3>
                <p>{{ problem.inputDescription }}</p>
                <h3>输出</h3>
                <p>{{ problem.outputDescription }}</p>
                <section
                  v-for="(sample, index) in problem.publicSamples"
                  :key="index"
                  class="sample"
                >
                  <h3>示例 {{ index + 1 }}</h3>
                  <strong>输入</strong>
                  <pre>{{ sample.input }}</pre>
                  <strong>输出</strong>
                  <pre>{{ sample.output }}</pre>
                </section>
                <p class="limits">
                  {{ problem.resourceLimits.timeLimitMs }} ms ·
                  {{ problem.resourceLimits.memoryLimitMb }} MB · 输出上限
                  {{ problem.resourceLimits.outputLimitBytes }} bytes
                </p>
                <section v-if="problem.attribution">
                  <h3>题目来源与许可</h3>
                  <p>
                    作者：{{ problem.attribution.authorName }} ·
                    {{ problem.attribution.originType }}
                  </p>
                  <p v-if="problem.attribution.sourceUrl">
                    来源：{{ problem.attribution.sourceUrl }}
                  </p>
                  <p>许可：{{ problem.attribution.licenseStatement }}</p>
                  <p v-if="problem.attribution.correctionOfSlug">
                    修正自
                    {{ problem.attribution.correctionOfSlug }}，旧题完成记录不代表本题已完成。
                  </p>
                </section>
              </section>
              <section
                v-show="activeTab === 'solution'"
                id="workspace-panel-solution"
                role="tabpanel"
                aria-labelledby="workspace-tab-solution"
                tabindex="0"
              >
                <OfficialSolution
                  v-if="session?.user && visitedTabs.includes('solution')"
                  :active="activeTab === 'solution'"
                  :slug="problem.slug"
                  :judge-version="problem.judgeVersion"
                  :user-id="session.user.id"
                  :csrf="session.csrf"
                  :refresh-key="
                    submission?.processingStatus === 'FINISHED' ? submission.statusVersion : 0
                  "
                />
              </section>
              <section
                v-show="activeTab === 'self-test'"
                id="workspace-panel-self-test"
                role="tabpanel"
                aria-labelledby="workspace-tab-self-test"
                tabindex="0"
              >
                <SelfTest
                  v-if="session?.user && visitedTabs.includes('self-test')"
                  :slug="problem.slug"
                  :source-code="sourceCode"
                  :user-id="session.user.id"
                  :csrf="session.csrf"
                />
              </section>
              <section
                v-show="activeTab === 'feedback'"
                id="workspace-panel-feedback"
                role="tabpanel"
                aria-labelledby="workspace-tab-feedback"
                tabindex="0"
              >
                <ProblemFeedback
                  v-if="session?.user && visitedTabs.includes('feedback')"
                  :slug="problem.slug"
                  :user-id="session.user.id"
                  :csrf="session.csrf"
                />
              </section>
              <section
                v-show="activeTab === 'history'"
                id="workspace-panel-history"
                role="tabpanel"
                aria-labelledby="workspace-tab-history"
                tabindex="0"
              >
                <SubmissionHistory
                  v-if="session?.user && visitedTabs.includes('history')"
                  :key="session.user.id"
                  :slug="problem.slug"
                  :user-id="session.user.id"
                  :active="activeTab === 'history'"
                  :revision="submission?.statusVersion"
                  can-load
                  @load="loadHistoricalSource"
                />
              </section>
            </div>
          </section>
        </template>
        <template #right>
          <section class="pane code-pane" aria-label="代码编辑与判题">
            <header class="code-heading">
              <h3>代码编辑</h3>
              <button
                type="button"
                class="text-button"
                :aria-pressed="editorExpanded"
                @click="editorExpanded = !editorExpanded"
              >
                {{ editorExpanded ? '恢复左右布局' : '展开代码区域' }}
              </button>
            </header>
            <form
              data-testid="submission-form"
              class="submission-form"
              @submit.prevent="handleSubmit()"
            >
              <div class="draft-row">
                <span class="muted" data-testid="draft-status">{{ draft.message.value }}</span
                ><button
                  v-if="!draft.conflict.value"
                  type="button"
                  class="text-button"
                  @click="draft.retry"
                >
                  重试保存
                </button>
              </div>
              <div v-if="draft.conflict.value" class="draft-conflict" role="alert">
                <button type="button" @click="draft.useServer">载入服务端草稿</button
                ><button type="button" @click="draft.keepLocal">保留本页代码</button
                ><button type="button" @click="draft.saveLocal">保存本页版本</button>
              </div>
              <CodeEditor v-model="sourceCode" />
              <div v-if="pendingSubmission && !submitting" class="pending-request" role="alert">
                <p>提交回执尚未确认。重试会使用当时的代码，不会提交后来在编辑区的修改。</p>
                <button type="button" @click="handleSubmit(true)">重试原提交</button>
                <button type="button" @click="selectTab('history')">核对提交历史</button>
                <button type="button" @click="allowNewSubmission">允许新提交</button>
              </div>
              <div class="submit-actions">
                <button type="button" class="secondary-button" @click="openSelfTest">
                  自定义自测</button
                ><button
                  type="submit"
                  :disabled="submitting || !!pendingSubmission || sourceCode.trim().length === 0"
                >
                  {{ submitting ? '提交中……' : '提交并判题' }}
                </button>
              </div>
            </form>
            <section v-if="submission" class="result-card" aria-label="本次判题结果">
              <div class="result-row">
                <strong class="status">{{
                  judgeLabel(submission.processingStatus, submission.verdict)
                }}</strong>
              </div>
              <p>{{ judgeAdvice(submission.processingStatus, submission.verdict) }}</p>
              <details>
                <summary>提交技术信息</summary>
                <p class="submission-id">
                  提交 {{ submission.submissionId }} · 状态版本：{{ submission.statusVersion }} ·
                  {{ submission.processingStatus }} {{ submission.verdict }}
                </p>
              </details>
              <p v-if="submission.judgeDataWarning" role="alert">
                {{ submission.judgeDataWarning }}
              </p>
              <p v-if="submission.diagnosticMessage" class="diagnostic">
                {{ submission.diagnosticMessage }}
              </p>
            </section>
          </section>
        </template>
      </WorkspaceSplit>
    </template>

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
  width: calc(100% - 2rem);
  margin: 0 auto;
  padding: 0.6rem 0 1rem;
}
.workspace-navigation {
  margin: 0 0 0.5rem;
  font-size: 0.85rem;
}
a {
  color: #15576d;
}
.workspace-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.6rem 1rem;
  margin-bottom: 0.75rem;
}
.workspace-title,
.workspace-toolbar nav {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.65rem;
}
.workspace-title h2 {
  margin: 0;
  font-size: 1.2rem;
}
.workspace-toolbar nav {
  font-size: 0.85rem;
}
.user-chip,
.version-chip {
  color: #516176;
  font-size: 0.8rem;
}
.pane {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  height: 100%;
  min-width: 0;
  min-height: 0;
  border: 1px solid #d8e0eb;
  border-radius: 12px;
  background: white;
  overflow: hidden;
}
.panel-tabs {
  display: flex;
  flex-wrap: wrap;
  flex-shrink: 0;
  gap: 0.1rem;
  border-bottom: 1px solid #dce3ec;
  padding: 0.3rem 0.4rem 0;
  background: #f8fafc;
}
.panel-tabs button {
  padding: 0.8rem 0.6rem;
  font-size: 0.85rem;
  border-radius: 0;
  border-bottom: 3px solid transparent;
  color: #516176;
  background: transparent;
}
.panel-tabs button[aria-selected='true'] {
  color: #176b87;
  border-color: #176b87;
}
.panel-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overflow-wrap: anywhere;
  padding: 1.15rem;
  scrollbar-gutter: stable;
}
h3 {
  margin: 1.2rem 0 0.5rem;
  font-size: 1rem;
}
.panel-content h3:first-child {
  margin-top: 0;
}
p {
  line-height: 1.65;
}
.statement {
  white-space: pre-wrap;
}
.sample {
  margin: 1.1rem 0;
}
pre {
  margin: 0.45rem 0 0.8rem;
  padding: 0.75rem;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  border-radius: 8px;
  background: #f1f5f9;
}
.limits {
  border-top: 1px solid #e3e8ef;
  padding-top: 1rem;
  color: #637083;
  font-size: 0.85rem;
}
.code-pane {
  padding: 0.7rem;
}
.code-heading,
.draft-row,
.submit-actions,
.result-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.5rem;
  flex-shrink: 0;
}
.code-heading h3 {
  margin: 0;
}
.submission-form {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  gap: 0.5rem;
}
.draft-row {
  min-height: 1.5rem;
  font-size: 0.75rem;
}
.submit-actions {
  justify-content: flex-end;
}
.muted {
  color: #637083;
}
button {
  border: 0;
  border-radius: 7px;
  padding: 0.6rem 0.9rem;
  color: white;
  background: #176b87;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
  cursor: wait;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid #248faf;
  outline-offset: 2px;
}
.text-button {
  padding: 0.35rem 0.5rem;
  color: #15576d;
  background: transparent;
  font-size: 0.8rem;
}
.secondary-button {
  background: #eaf3f7;
  color: #15576d;
}
.draft-conflict {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.result-card {
  flex-shrink: 0;
  max-height: 7rem;
  overflow: auto;
  margin-top: 0.6rem;
  padding: 0.65rem;
  border-radius: 8px;
  background: #edf7f3;
}
.result-card p {
  margin: 0.3rem 0 0;
}
.status,
.verdict {
  font-size: 1rem;
}
.verdict {
  color: #137449;
}
.submission-id {
  color: #637083;
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}
.diagnostic,
.error {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.card {
  padding: 1.5rem;
  border: 1px solid #d8e0eb;
  border-radius: 12px;
  background: white;
}
.login-card {
  max-width: 420px;
  margin: 3rem auto;
}
.form-stack,
.form-stack label {
  display: grid;
  gap: 0.7rem;
}
.form-stack input {
  padding: 0.7rem;
  border: 1px solid #b8c4d4;
  border-radius: 8px;
  font: inherit;
}
.error {
  margin: 0.7rem 0;
  padding: 0.7rem;
  color: #8a2424;
  background: #fff2f2;
  border-radius: 8px;
}
@media (max-width: 899px) {
  .workspace {
    width: calc(100% - 1rem);
  }
  .workspace-toolbar nav {
    gap: 0.5rem;
  }
}
</style>
