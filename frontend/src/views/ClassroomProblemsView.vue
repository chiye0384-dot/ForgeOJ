<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ApiRequestError,
  restoreSession,
  getSubmission,
  type SessionResponse,
  type SubmissionCreatedResponse,
  type SubmissionStatusResponse,
} from '@/services/forgeojApi'
import { getClassroom, type ClassroomDetail } from '@/services/classroomApi'
import { authoredList, type AuthoredSummary, type AuthoredDetail } from '@/services/contentApi'
import { listValidations, type ContentValidation } from '@/services/validationApi'
import { readSelfTest, type SelfTestRun, type SelfTestDetail } from '@/services/selfTestApi'
import {
  privateRead,
  privateWrite,
  type PrivateProblem,
  type PrivateDetail,
  type PrivateMaintenance,
  type PrivateSolution,
} from '@/services/classroomProblemApi'

const props = defineProps<{ id: string }>()
const session = ref<SessionResponse | null>(null),
  room = ref<ClassroomDetail | null>(null)
const problems = ref<PrivateProblem[]>([]),
  detail = ref<PrivateDetail | null>(null)
const maintenance = ref<PrivateMaintenance | null>(null),
  solution = ref<PrivateSolution | null>(null)
const drafts = ref<AuthoredSummary[]>([]),
  validations = ref<ContentValidation[]>([])
const draftId = ref(''),
  jobId = ref(''),
  policy = ref<'AFTER_AC' | 'IMMEDIATE'>('AFTER_AC')
const source = ref('public class Main {\n  public static void main(String[] args) {\n  }\n}\n'),
  input = ref('')
const submission = ref<SubmissionStatusResponse | null>(null),
  selfTest = ref<SelfTestDetail | null>(null)
const message = ref(''),
  busy = ref(false),
  page = ref(1),
  total = ref(0),
  draftPage = ref(1),
  draftTotal = ref(0)
const confirmation = ref<{ text: string; run: () => void } | null>(null)
const teaching = computed(() => room.value && ['OWNER', 'ASSISTANT'].includes(room.value.role))
const writable = computed(() => room.value?.status === 'ACTIVE' && !busy.value)
let generation = 0,
  disposed = false
let publishRequest: { signature: string; id: string } | null = null
let submitRequest: { signature: string; id: string } | null = null
let selfTestRequest: { signature: string; id: string } | null = null
function current(g: number) {
  return !disposed && generation === g
}
function clearPrivate() {
  room.value = null
  problems.value = []
  detail.value = null
  maintenance.value = null
  solution.value = null
  drafts.value = []
  validations.value = []
  draftId.value = ''
  jobId.value = ''
  submission.value = null
  selfTest.value = null
  source.value = ''
  input.value = ''
  confirmation.value = null
}
function error(e: unknown) {
  if (e instanceof ApiRequestError)
    return (
      (
        {
          401: '登录已失效。',
          403: '当前角色不能执行此操作。',
          404: '班级或题目不可访问。',
          409: '版本、验证或班级状态已变化，请刷新后重试。',
          429: '排队额度已满，请等待已有任务完成。',
          503: '服务暂时不可用。',
        } as Record<number, string>
      )[e.status] ?? '请求失败。'
    )
  return e instanceof Error ? e.message : '操作失败。'
}
async function sameIdentity(g: number, id: number) {
  const account = await restoreSession()
  if (!current(g)) return null
  if (!account.authenticated || account.user?.id !== id) {
    clearPrivate()
    session.value = account
    publishRequest = submitRequest = selfTestRequest = null
    throw new Error('身份已变化，请重新打开班级。')
  }
  session.value = account
  return account
}
async function load() {
  const g = ++generation
  busy.value = true
  clearPrivate()
  message.value = ''
  try {
    const account = await restoreSession()
    if (!current(g)) return
    if (session.value?.user?.id !== account.user?.id)
      publishRequest = submitRequest = selfTestRequest = null
    session.value = account
    if (!account.authenticated || !account.user) return
    const [r, list] = await Promise.all([
      getClassroom(props.id),
      privateRead<{ items: PrivateProblem[]; total: number }>(
        props.id,
        '',
        `?page=${page.value}&size=20`,
      ),
    ])
    if (!(await sameIdentity(g, account.user.id))) return
    room.value = r
    problems.value = list.items
    total.value = list.total
    if (['OWNER', 'ASSISTANT'].includes(r.role)) {
      const authored = await authoredList(draftPage.value)
      if (!(await sameIdentity(g, account.user.id))) return
      drafts.value = authored.items
      draftTotal.value = authored.total
    }
  } catch (e) {
    if (current(g)) {
      clearPrivate()
      message.value = error(e)
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
async function read<T>(action: () => Promise<T>, apply: (v: T) => void) {
  const g = ++generation,
    id = session.value?.user?.id
  confirmation.value = null
  busy.value = true
  message.value = ''
  try {
    if (!id || !(await sameIdentity(g, id))) return
    const value = await action()
    if (!(await sameIdentity(g, id))) return
    apply(value)
  } catch (e) {
    if (current(g)) {
      clearPrivate()
      message.value = error(e)
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
function open(p: PrivateProblem) {
  detail.value = null
  maintenance.value = null
  solution.value = null
  submission.value = null
  selfTest.value = null
  source.value = ''
  input.value = ''
  void read(
    () => privateRead<PrivateDetail>(props.id, p.slug),
    (value) => {
      detail.value = value
      source.value = 'public class Main {\n  public static void main(String[] args) {\n  }\n}\n'
    },
  )
}
function pickDraft() {
  jobId.value = ''
  validations.value = []
  if (!draftId.value) return
  const id = draftId.value
  void read(
    () => listValidations(id, 1),
    (value) => {
      validations.value = value.items.filter(
        (v) =>
          v.validationStatus === 'PASSED' &&
          !v.stale &&
          v.draftVersion === drafts.value.find((d) => d.id === id)?.version,
      )
    },
  )
}
async function write<T>(slug: string, suffix: string, body: object, apply: (v: T) => void) {
  const g = ++generation,
    id = session.value?.user?.id
  confirmation.value = null
  busy.value = true
  message.value = ''
  try {
    if (!id) throw new Error('请先登录。')
    const account = await sameIdentity(g, id)
    if (!account) return
    const value = await privateWrite<T>(props.id, slug, suffix, body, account.csrf)
    if (!(await sameIdentity(g, id))) return
    apply(value)
  } catch (e) {
    if (current(g)) {
      clearPrivate()
      message.value = error(e)
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
function publish() {
  const draft = drafts.value.find((d) => d.id === draftId.value),
    job = validations.value.find((j) => j.jobId === jobId.value)
  if (!draft || !job) return
  const signature = JSON.stringify([props.id, draft.id, draft.version, job.jobId, policy.value])
  if (publishRequest?.signature !== signature)
    publishRequest = { signature, id: crypto.randomUUID() }
  const body = {
    draftId: draft.id,
    draftVersion: draft.version,
    validationJobId: job.jobId,
    clientRequestId: publishRequest.id,
    solutionPolicy: policy.value,
  }
  confirmation.value = {
    text: '发布后题目归班级所有。修订需重新验证并发布新题，旧判题依据保留。',
    run: () => {
      void write<PrivateProblem>('', '', body, (value) => {
        publishRequest = null
        problems.value.unshift(value)
        total.value++
        message.value = '班级私有题已发布。'
      })
    },
  }
}
function viewSolution() {
  if (detail.value)
    void read(
      () => privateRead<PrivateSolution>(props.id, detail.value!.problem.slug, '/solution'),
      (v) => {
        solution.value = v
      },
    )
}
function viewMaintenance() {
  if (detail.value)
    void read(
      () => privateRead<PrivateMaintenance>(props.id, detail.value!.problem.slug, '/maintenance'),
      (v) => {
        maintenance.value = v
      },
    )
}
function earlyView() {
  if (!detail.value) return
  const p = detail.value.problem
  confirmation.value = {
    text: '提前查看题解可能影响独立思考。仍要查看吗？仅记录在自己的学习记录中，不影响继续提交。',
    run: () => {
      void write<PrivateSolution>(
        p.slug,
        '/solution/early-view',
        { expectedVersion: p.version, confirmEarlyView: true },
        (v) => {
          solution.value = v
        },
      )
    },
  }
}
function archive() {
  if (!detail.value) return
  const p = detail.value.problem
  confirmation.value = {
    text: '归档后停止新提交，保留题目和历史判题依据。确认归档？',
    run: () => {
      void write<void>(p.slug, '/archive', { expectedVersion: p.version }, () => {
        p.status = 'ARCHIVED'
        p.version++
        problems.value = problems.value.map((item) => (item.slug === p.slug ? { ...p } : item))
      })
    },
  }
}
function copy() {
  if (detail.value)
    void write<AuthoredDetail>(detail.value.problem.slug, '/copy', {}, (v) => {
      message.value = `已复制到自己的作者草稿：${v.draft.id}。请到“创作”修改并重新验证。`
    })
}
function submit() {
  if (!detail.value) return
  const p = detail.value.problem,
    code = source.value,
    signature = JSON.stringify([p.slug, code])
  if (submitRequest?.signature !== signature) submitRequest = { signature, id: crypto.randomUUID() }
  void write<SubmissionCreatedResponse>(
    p.slug,
    '/submissions',
    { clientRequestId: submitRequest.id, language: 'JAVA_21', sourceCode: code },
    (v) => {
      submission.value = { ...v, verdict: null, diagnosticMessage: null }
      submitRequest = null
      message.value = '正式提交已排队；点击刷新结果查看进度。'
    },
  )
}
function run() {
  if (!detail.value) return
  const p = detail.value.problem,
    code = source.value,
    stdin = input.value,
    signature = JSON.stringify([p.slug, code, stdin])
  if (selfTestRequest?.signature !== signature)
    selfTestRequest = { signature, id: crypto.randomUUID() }
  void write<SelfTestRun>(
    p.slug,
    '/self-tests',
    { requestId: selfTestRequest.id, language: 'JAVA_21', sourceCode: code, input: stdin },
    (v) => {
      selfTest.value = { run: v, sourceCode: code, input: stdin, output: null }
      selfTestRequest = null
      message.value = '自测已排队，不计 AC；点击刷新自测查看输出。'
    },
  )
}
function refreshSubmission() {
  if (submission.value)
    void read(
      () => getSubmission(submission.value!.submissionId),
      (v) => {
        submission.value = v
      },
    )
}
function refreshSelfTest() {
  if (selfTest.value)
    void read(
      () => readSelfTest(selfTest.value!.run.runId),
      (v) => {
        selfTest.value = v
      },
    )
}
function turnPage(direction: number, authored: boolean) {
  if (authored) draftPage.value += direction
  else page.value += direction
  void load()
}
watch(
  () => props.id,
  () => {
    page.value = draftPage.value = 1
    publishRequest = submitRequest = selfTestRequest = null
    void load()
  },
)
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  generation++
  clearPrivate()
})
</script>

<template>
  <section class="private-problems">
    <h2>班级私有题</h2>
    <p>
      <a href="/classrooms">返回我的班级</a> ·
      <button :disabled="busy" @click="load">刷新班级题</button>
    </p>
    <p v-if="message" role="status">{{ message }}</p>
    <div v-if="confirmation" role="alertdialog" aria-label="操作确认">
      <p>{{ confirmation.text }}</p>
      <button @click="confirmation = null">取消</button
      ><button @click="confirmation.run()">再次确认</button>
    </div>
    <template v-if="room">
      <h3>{{ room.title }} · {{ room.status === 'ACTIVE' ? '运行中' : '已归档，只读' }}</h3>
      <form v-if="teaching && room.status === 'ACTIVE'" @submit.prevent="publish">
        <h3>从自己的已验证草稿发布</h3>
        <p>先到<a href="/authoring">创作</a>准备题面、测试、参考和独立题解，并完成双程序验证。</p>
        <label
          >作者草稿<select v-model="draftId" :disabled="busy" @change="pickDraft">
            <option value="">请选择</option>
            <option
              v-for="d in drafts.filter((item) => item.status === 'DRAFT')"
              :key="d.id"
              :value="d.id"
            >
              {{ d.title }} · v{{ d.version }}
            </option>
          </select></label
        >
        <button type="button" :disabled="busy || draftPage === 1" @click="turnPage(-1, true)">
          上一页草稿</button
        ><button
          type="button"
          :disabled="busy || draftPage * 20 >= draftTotal"
          @click="turnPage(1, true)"
        >
          下一页草稿
        </button>
        <label
          >通过的验证<select v-model="jobId" :disabled="busy">
            <option value="">请选择</option>
            <option v-for="v in validations" :key="v.jobId" :value="v.jobId">
              v{{ v.draftVersion }} · 参考与题解通过 · {{ v.jobId }}
            </option>
          </select></label
        >
        <label
          >自由练习题解<select v-model="policy" :disabled="busy">
            <option value="AFTER_AC">AC 后开放；未 AC 可再次确认查看</option>
            <option value="IMMEDIATE">立即开放</option>
          </select></label
        >
        <button :disabled="!writable || !jobId">发布班级私有题</button>
      </form>
      <ul>
        <li v-for="p in problems" :key="p.slug">
          {{ p.title }} · {{ p.status === 'ACTIVE' ? '可练习' : '已归档' }}
          <button :disabled="busy" @click="open(p)">打开题目</button>
        </li>
      </ul>
      <button :disabled="busy || page === 1" @click="turnPage(-1, false)">上一页题目</button
      ><button :disabled="busy || page * 20 >= total" @click="turnPage(1, false)">
        下一页题目
      </button>
      <article v-if="detail">
        <h3>{{ detail.metadata.title }}</h3>
        <p class="text">{{ detail.metadata.statement }}</p>
        <h4>输入</h4>
        <p class="text">{{ detail.metadata.inputDescription }}</p>
        <h4>输出</h4>
        <p class="text">{{ detail.metadata.outputDescription }}</p>
        <pre v-for="(sample, i) in detail.metadata.samples" :key="i">
输入：{{ sample.input }}输出：{{ sample.output }}</pre
        >
        <p>
          Java 21 · {{ detail.metadata.timeLimitMs }} ms · {{ detail.metadata.memoryLimitMb }} MiB ·
          {{ detail.metadata.licenseStatement }}
        </p>
        <template v-if="detail.problem.status === 'ACTIVE' && room.status === 'ACTIVE'">
          <label
            >Java 源码<textarea
              v-model="source"
              :disabled="busy"
              rows="12"
              spellcheck="false"
            /></label
          ><label>自测输入<textarea v-model="input" :disabled="busy" rows="3" /></label>
          <button :disabled="busy" @click="run">运行自测</button
          ><button :disabled="busy" @click="submit">正式提交</button>
        </template>
        <div v-if="submission">
          <p>正式结果：{{ submission.processingStatus }} {{ submission.verdict }}</p>
          <pre v-if="submission.diagnosticMessage">{{ submission.diagnosticMessage }}</pre>
          <button :disabled="busy" @click="refreshSubmission">刷新结果</button>
        </div>
        <div v-if="selfTest">
          <p>自测：{{ selfTest.run.processingStatus }} {{ selfTest.run.executionResult }}</p>
          <pre v-if="selfTest.output !== null">{{ selfTest.output }}</pre>
          <button :disabled="busy" @click="refreshSelfTest">刷新自测</button>
        </div>
        <button :disabled="busy" @click="viewSolution">查看官方题解</button>
        <div v-if="solution?.access === 'LOCKED'">
          <p>AC 后自动开放；也可以经提醒再次确认后提前查看。</p>
          <button :disabled="!writable || detail.problem.status !== 'ACTIVE'" @click="earlyView">
            提前查看题解
          </button>
        </div>
        <div v-else-if="solution">
          <p>题解访问：{{ solution.access }}</p>
          <p class="text">{{ solution.idea }}</p>
          <pre>{{ solution.sourceCode }}</pre>
        </div>
        <template v-if="teaching"
          ><button :disabled="busy" @click="viewMaintenance">查看教学维护数据</button
          ><button :disabled="!writable" @click="copy">复制为修订草稿</button
          ><button :disabled="!writable || detail.problem.status !== 'ACTIVE'" @click="archive">
            归档题目
          </button></template
        >
        <div v-if="maintenance">
          <h4>仅教学维护可见</h4>
          <p>隐藏测试：{{ maintenance.testCount }} 条</p>
          <h4>私有参考程序</h4>
          <pre>{{ maintenance.referenceCode }}</pre>
          <h4>独立题解</h4>
          <p>{{ maintenance.solutionIdea }}</p>
          <pre>{{ maintenance.solutionCode }}</pre>
        </div>
      </article>
    </template>
  </section>
</template>
<style scoped>
.private-problems {
  max-width: 1000px;
  margin: auto;
  padding: 1.5rem;
}
label {
  display: block;
  margin: 0.8rem 0;
}
textarea {
  display: block;
  width: 100%;
  font-family: monospace;
}
button {
  margin: 0.3rem;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 1rem;
  background: #f3f5f7;
  color: #17202a;
}
.text {
  white-space: pre-wrap;
}
[role='alertdialog'] {
  border: 2px solid #b26a00;
  padding: 1rem;
}
</style>
