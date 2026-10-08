<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ApiRequestError,
  restoreSession,
  getSubmission,
  getProblems,
  type SessionResponse,
  type SubmissionCreatedResponse,
  type SubmissionStatusResponse,
} from '@/services/forgeojApi'
import { privateRead, type PrivateProblem } from '@/services/classroomProblemApi'
import { readSelfTest, type SelfTestRun, type SelfTestDetail } from '@/services/selfTestApi'
import {
  assignmentRead,
  assignmentWrite,
  type AssignmentPage,
  type AssignmentDetail,
  type AssignmentDefinition,
  type AssignmentItem,
  type AssignmentSolution,
} from '@/services/assignmentApi'
const props = defineProps<{ id: string }>()
const session = ref<SessionResponse | null>(null),
  list = ref<AssignmentPage | null>(null),
  detail = ref<AssignmentDetail | null>(null)
const page = ref(1),
  busy = ref(false),
  message = ref(''),
  item = ref<AssignmentItem | null>(null)
const source = ref(''),
  input = ref(''),
  submission = ref<SubmissionStatusResponse | null>(null),
  selfTest = ref<SelfTestDetail | null>(null),
  solution = ref<AssignmentSolution | null>(null)
const form = ref<AssignmentDefinition>(empty()),
  deadline = ref(''),
  starts = ref(''),
  reason = ref(''),
  memberId = ref(''),
  editing = ref(false)
const choicePage = ref(1),
  choiceKind = ref('PUBLIC'),
  choices = ref<{ slug: string; title: string }[]>([]),
  choiceTotal = ref(0)
const confirm = ref<{ text: string; run: () => Promise<void> } | null>(null)
const teaching = computed(() => !!list.value?.teaching),
  writable = computed(() => !busy.value && list.value?.classroomStatus === 'ACTIVE')
const runnable = computed(
  () =>
    writable.value &&
    detail.value?.member &&
    detail.value.participating &&
    detail.value.assignment.status === 'ACTIVE' &&
    !!item.value?.metadata,
)
const completed = computed(
  () =>
    detail.value?.problems.filter(
      (p) => p.grade && ['PRECOMPLETED', 'ON_TIME_AC', 'LATE_AC'].includes(p.grade.state),
    ).length ?? 0,
)
const statusNames: Record<string, string> = {
  DRAFT: '草稿',
  SCHEDULED: '待开始',
  ACTIVE: '进行中',
  ENDED: '已结束',
  CANCELLED: '已取消',
  STOPPED: '已停止',
}
const gradeNames: Record<string, string> = {
  NOT_STARTED: '未开始',
  ATTEMPTING: '尝试中',
  PRECOMPLETED: '此前已完成',
  ON_TIME_AC: '按时 AC',
  LATE_AC: '迟交 AC',
}
let generation = 0,
  disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined
const requests = new Map<string, { signature: string; id: string }>()
function empty(): AssignmentDefinition {
  return {
    title: '',
    description: '',
    deadlineAt: '',
    acceptExistingAc: false,
    allowLate: true,
    solutionPolicy: 'AFTER_AC',
    problemSlugs: [],
  }
}
function key(kind: string, body: object) {
  const signature = props.id + JSON.stringify(body),
    old = requests.get(kind)
  if (old?.signature === signature) return old.id
  const id = crypto.randomUUID()
  requests.set(kind, { signature, id })
  return id
}
function current(g: number) {
  return !disposed && g === generation
}
function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}
function clear() {
  stop()
  list.value = detail.value = null
  item.value = null
  submission.value = null
  selfTest.value = null
  solution.value = null
  source.value = input.value = ''
  confirm.value = null
  choices.value = []
  form.value = empty()
  editing.value = false
  deadline.value = starts.value = reason.value = memberId.value = ''
}
function error(e: unknown) {
  if (e instanceof ApiRequestError)
    return (
      (
        {
          400: '请检查输入和时间。',
          401: '登录已失效。',
          403: '当前角色不能执行此操作。',
          404: '作业或题目不可访问。',
          409: '作业版本或状态已变化。请刷新后重新查看，再决定是否操作。',
          429: '排队额度已满，请等待已有任务完成。',
          503: '服务暂时不可用。',
        } as Record<number, string>
      )[e.status] ?? '请求失败。'
    )
  return e instanceof Error ? e.message : '操作失败。'
}
async function identity(g: number, user: number) {
  const account = await restoreSession()
  if (!current(g)) return null
  if (!account.authenticated || account.user?.id !== user) {
    clear()
    requests.clear()
    session.value = account
    throw new Error('身份已变化，请重新打开作业。')
  }
  session.value = account
  return account
}
async function task(run: (g: number, account: SessionResponse) => Promise<void>) {
  if (busy.value) return
  const g = ++generation
  busy.value = true
  message.value = ''
  stop()
  try {
    const account = await restoreSession()
    if (!current(g)) return
    if (session.value?.user?.id !== account.user?.id) {
      const changed = session.value?.authenticated === true
      clear()
      requests.clear()
      session.value = account
      if (changed) throw new Error('身份已变化，请重新打开作业。')
    }
    session.value = account
    if (!account.authenticated || !account.user) {
      clear()
      return
    }
    await run(g, account)
  } catch (e) {
    if (current(g)) {
      message.value = error(e)
      if (e instanceof ApiRequestError && [401, 403, 404].includes(e.status)) clear()
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
async function load(selected = '') {
  await task(async (g, account) => {
    clear()
    const rows = await assignmentRead<AssignmentPage>(props.id, '', `?page=${page.value}&size=20`)
    if (!(await identity(g, account.user!.id))) return
    list.value = rows
    if (selected) {
      const d = await assignmentRead<AssignmentDetail>(props.id, selected)
      if (!(await identity(g, account.user!.id))) return
      detail.value = d
    }
  })
}
async function open(id: string) {
  await task(async (g, account) => {
    detail.value = null
    item.value = null
    solution.value = null
    submission.value = null
    selfTest.value = null
    source.value = input.value = ''
    editing.value = false
    confirm.value = null
    deadline.value = starts.value = reason.value = memberId.value = ''
    const d = await assignmentRead<AssignmentDetail>(props.id, id)
    if (!(await identity(g, account.user!.id))) return
    detail.value = d
  })
}
function localTime(value: string) {
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
function instant(value: string) {
  const date = new Date(value)
  if (!value || !Number.isFinite(date.getTime())) throw new Error('请选择有效时间。')
  return date.toISOString()
}
function display(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
function turn(value: number) {
  page.value = value
  void load()
}
function turnChoices(value: number) {
  choicePage.value = value
  void choicesLoad()
}
function resetChoices() {
  choicePage.value = 1
  choices.value = []
}
async function choicesLoad() {
  await task(async (g, account) => {
    const rows =
      choiceKind.value === 'PUBLIC'
        ? await getProblems({ page: choicePage.value, size: 20 })
        : await privateRead<{ items: PrivateProblem[]; total: number }>(
            props.id,
            '',
            `?page=${choicePage.value}&size=20`,
          )
    if (!(await identity(g, account.user!.id))) return
    choices.value = rows.items.filter((p) => !('status' in p) || p.status === 'ACTIVE')
    choiceTotal.value = rows.total
  })
}
function startEdit(create = false) {
  confirm.value = null
  if (create) {
    detail.value = null
    form.value = empty()
    deadline.value = localTime(new Date(Date.now() + 86400000).toISOString())
  } else if (detail.value) {
    const d = detail.value
    form.value = {
      title: d.assignment.title,
      description: d.description,
      deadlineAt: d.assignment.deadlineAt,
      acceptExistingAc: d.assignment.acceptExistingAc,
      allowLate: d.assignment.allowLate,
      solutionPolicy: d.assignment.solutionPolicy,
      problemSlugs: d.problems.map((p) => p.slug!).filter(Boolean),
    }
    deadline.value = localTime(d.assignment.deadlineAt)
  }
  item.value = null
  editing.value = true
  choices.value = []
  choicePage.value = 1
}
async function write(
  suffix: string,
  body: object,
  method = 'POST',
  id = detail.value?.assignment.id ?? '',
) {
  await task(async (g, account) => {
    const result = await assignmentWrite<AssignmentDetail>(
      props.id,
      id,
      suffix,
      body,
      account.csrf,
      method,
    )
    if (!(await identity(g, account.user!.id))) return
    if (!id && !suffix) requests.delete('create')
    if (suffix === '/copy') requests.delete('copy')
    detail.value = result
    item.value = null
    solution.value = null
    editing.value = false
    confirm.value = null
    message.value = '操作已保存。'
    const rows = await assignmentRead<AssignmentPage>(props.id, '', `?page=${page.value}&size=20`)
    if (!(await identity(g, account.user!.id))) return
    list.value = rows
  })
}
async function save() {
  try {
    const original = detail.value?.assignment.deadlineAt
    const definition = {
      ...form.value,
      deadlineAt:
        original && deadline.value === localTime(original) ? original : instant(deadline.value),
    }
    if (detail.value)
      await write('', { expectedVersion: detail.value.assignment.version, definition }, 'PUT')
    else await write('', { clientRequestId: key('create', definition), definition }, 'POST', '')
  } catch (e) {
    message.value = error(e)
  }
}
function publish() {
  const d = detail.value
  if (!d) return
  try {
    const startsAt = starts.value ? instant(starts.value) : null
    confirm.value = {
      text: startsAt
        ? `将在 ${display(startsAt)} 开始，并在实际开始时固定参与成员和题目版本。确认定时发布？`
        : '立即开始后将固定参与成员、题目版本、迟交和题解策略。确认发布？',
      run: () =>
        write(
          '/publish',
          { expectedVersion: d.assignment.version, startsAt },
          'POST',
          d.assignment.id,
        ),
    }
  } catch (e) {
    message.value = error(e)
  }
}
function cancel() {
  const d = detail.value
  if (!d || !reason.value.trim()) return
  const cause = reason.value
  confirm.value = {
    text: '取消后保留全部记录，不能继续提交。确认取消作业？',
    run: () =>
      write(
        '/cancel',
        { expectedVersion: d.assignment.version, reason: cause },
        'POST',
        d.assignment.id,
      ),
  }
}
async function copy() {
  try {
    const body = { deadlineAt: instant(deadline.value) }
    await write('/copy', {
      ...body,
      clientRequestId: key('copy', { ...body, id: detail.value?.assignment.id }),
    })
  } catch (e) {
    message.value = error(e)
  }
}
function choose(p: AssignmentItem) {
  stop()
  ++generation
  item.value = p
  solution.value = null
  submission.value = null
  selfTest.value = null
  source.value = 'public class Main {\n  public static void main(String[] args) {\n  }\n}\n'
  input.value = ''
}
async function viewSolution() {
  const d = detail.value,
    p = item.value
  if (!d || !p?.slug) return
  await task(async (g, account) => {
    solution.value = null
    const result = await assignmentRead<AssignmentSolution>(
      props.id,
      d.assignment.id,
      `/problems/${encodeURIComponent(p.slug!)}/solution`,
    )
    if (!(await identity(g, account.user!.id))) return
    solution.value = result
  })
}
async function execute(kind: 'submissions' | 'self-tests') {
  const d = detail.value,
    p = item.value
  if (!d || !p?.slug || !runnable.value) return
  await task(async (g, account) => {
    const body = {
        language: 'JAVA_21',
        sourceCode: source.value,
        ...(kind === 'self-tests' ? { input: input.value } : {}),
      },
      request = key(kind, { id: d.assignment.id, slug: p.slug, ...body })
    const result = await assignmentWrite<SubmissionCreatedResponse | SelfTestRun>(
      props.id,
      d.assignment.id,
      `/problems/${encodeURIComponent(p.slug!)}/${kind}`,
      { ...body, [kind === 'self-tests' ? 'requestId' : 'clientRequestId']: request },
      account.csrf,
    )
    if (!(await identity(g, account.user!.id))) return
    await poll(g, account.user!.id, d.assignment.id, kind, result)
  })
}
async function poll(
  g: number,
  user: number,
  assignment: string,
  kind: 'submissions' | 'self-tests',
  created: SubmissionCreatedResponse | SelfTestRun,
) {
  try {
    if (!current(g)) return
    const id = 'submissionId' in created ? created.submissionId : created.runId
    const result = kind === 'submissions' ? await getSubmission(id) : await readSelfTest(id)
    if (!(await identity(g, user))) return
    const status = 'run' in result ? result.run : result
    if (kind === 'submissions') submission.value = result as SubmissionStatusResponse
    else selfTest.value = result as SelfTestDetail
    if (['QUEUED', 'RUNNING'].includes(status.processingStatus)) {
      timer = setTimeout(() => void poll(g, user, assignment, kind, created), 1500)
      return
    }
    if (kind === 'submissions') {
      const d = await assignmentRead<AssignmentDetail>(props.id, assignment)
      if (!(await identity(g, user))) return
      detail.value = d
      if (item.value) item.value = d.problems.find((p) => p.ordinal === item.value!.ordinal) ?? null
    }
    requests.delete(kind)
  } catch (e) {
    if (current(g)) {
      message.value = error(e)
      if (e instanceof ApiRequestError && [401, 403, 404].includes(e.status)) clear()
    }
  }
}
onMounted(() => void load())
watch(
  () => props.id,
  () => {
    ++generation
    busy.value = false
    requests.clear()
    clear()
    page.value = 1
    void load()
  },
)
onBeforeUnmount(() => {
  disposed = true
  ++generation
  stop()
  clear()
})
</script>

<template>
  <section class="assignments">
    <h2>{{ list?.classroomTitle ?? '班级' }} · 作业</h2>
    <p><RouterLink to="/classrooms">返回班级</RouterLink></p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="!session?.authenticated">请先登录账号。</p>
    <template v-else>
      <p v-if="list && !list.member">
        已离开班级：可查看本人历史完成摘要和提交结果；重新成为有效成员后恢复原作业关系。
      </p>
      <button :disabled="busy" @click="load(detail?.assignment.id)">刷新作业</button>
      <button v-if="teaching" :disabled="!writable" @click="startEdit(true)">新建作业</button>
      <ul>
        <li v-for="a in list?.items" :key="a.id">
          <button :disabled="busy" @click="open(a.id)">{{ a.title }}</button> ·
          {{ statusNames[a.status] }} · 截止 {{ display(a.deadlineAt) }}
        </li>
      </ul>
      <button :disabled="busy || page <= 1" @click="turn(page - 1)">上一页</button>
      <span>第 {{ page }} 页，共 {{ list?.total ?? 0 }} 份作业</span>
      <button :disabled="busy || page * 20 >= (list?.total ?? 0)" @click="turn(page + 1)">
        下一页
      </button>
      <form v-if="editing && teaching" @submit.prevent="save">
        <h3>{{ detail ? '编辑作业' : '新建作业草稿' }}</h3>
        <label
          >作业名称<input v-model="form.title" maxlength="100" required :disabled="busy"
        /></label>
        <label
          >作业说明<textarea v-model="form.description" maxlength="10000" :disabled="busy" />
        </label>
        <label
          >截止时间（本地时间）<input
            v-model="deadline"
            type="datetime-local"
            required
            :disabled="busy"
        /></label>
        <fieldset :disabled="busy || !!detail?.assignment.startedAt">
          <label
            ><input v-model="form.acceptExistingAc" type="checkbox" />接受当前判题版本的本人已有
            AC</label
          >
          <label><input v-model="form.allowLate" type="checkbox" />截止后允许迟交</label>
          <label
            >题解开放<select v-model="form.solutionPolicy">
              <option value="AFTER_AC">本作业 AC 后</option>
              <option value="AFTER_DEADLINE">截止或结束后</option>
              <option value="IMMEDIATE">立即开放</option>
            </select></label
          >
          <p>所选题目 {{ form.problemSlugs.length }}/20（按选择顺序）</p>
          <ol>
            <li v-for="slug in form.problemSlugs" :key="slug">
              {{ slug
              }}<button
                type="button"
                @click="form.problemSlugs = form.problemSlugs.filter((s) => s !== slug)"
              >
                移除
              </button>
            </li>
          </ol>
          <select v-model="choiceKind" aria-label="题库范围" @change="resetChoices">
            <option value="PUBLIC">公共题库</option>
            <option value="CLASSROOM">本班私有题</option>
          </select>
          <button type="button" @click="choicesLoad">加载题目</button>
          <ul>
            <li v-for="p in choices" :key="p.slug">
              {{ p.title
              }}<button
                type="button"
                :disabled="form.problemSlugs.includes(p.slug) || form.problemSlugs.length >= 20"
                @click="form.problemSlugs.push(p.slug)"
              >
                选择 {{ p.title }}
              </button>
            </li>
          </ul>
          <button type="button" :disabled="choicePage <= 1" @click="turnChoices(choicePage - 1)">
            上一组题目
          </button>
          <button
            type="button"
            :disabled="choicePage * 20 >= choiceTotal"
            @click="turnChoices(choicePage + 1)"
          >
            下一组题目
          </button>
        </fieldset>
        <p v-if="detail?.assignment.startedAt">
          开始后只允许修改说明、名称和延长截止时间；题目及策略已固定。
        </p>
        <button :disabled="!writable || !form.title.trim() || !form.problemSlugs.length">
          保存作业
        </button>
        <button type="button" :disabled="busy" @click="editing = false">取消编辑</button>
      </form>
      <article v-if="detail && !editing">
        <h3>{{ detail.assignment.title }}</h3>
        <RouterLink
          v-if="detail.teaching"
          :to="`/classrooms/${id}/assignments/${detail.assignment.id}/records`"
          >查看全员成绩与正式提交</RouterLink
        >
        <p>
          {{ statusNames[detail.assignment.status] }} · 版本 {{ detail.assignment.version }} · 截止
          {{ display(detail.assignment.deadlineAt) }}
        </p>
        <p>
          计划开始 {{ display(detail.assignment.startsAt) }} · 实际开始
          {{ display(detail.assignment.startedAt) }}
        </p>
        <p v-if="detail.assignment.closeReason">结束原因：{{ detail.assignment.closeReason }}</p>
        <p class="preserve">{{ detail.description }}</p>
        <p v-if="detail.participating">
          本人完成 {{ completed }}/{{ detail.problems.length }}
          题。按服务器接受提交的时间判断按时，最终必须 AC；自测不计次数和完成。
        </p>
        <p v-if="detail.participating">
          本作业正式提交的记录和代码可由本班负责人、助教查看；自测和私人练习代码不进入教学记录。
        </p>
        <table>
          <thead>
            <tr>
              <th>题目</th>
              <th>本人状态</th>
              <th>尝试次数</th>
              <th>首次 AC</th>
              <th>提交结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in detail.problems" :key="p.ordinal">
              <td>
                <button v-if="p.metadata" :disabled="busy" @click="choose(p)">
                  {{ p.metadata.title }}</button
                ><span v-else>第 {{ p.ordinal }} 题</span>
              </td>
              <td>{{ p.grade ? gradeNames[p.grade.state] : '未纳入本人参与关系' }}</td>
              <td>{{ p.grade?.attempts ?? '—' }}</td>
              <td>{{ display(p.grade?.firstAcAt ?? null) }}</td>
              <td>
                <button
                  v-if="p.grade?.completionSubmissionId"
                  :disabled="busy"
                  @click="
                    task(async (g, account) => {
                      const result = await getSubmission(p.grade!.completionSubmissionId!)
                      if (await identity(g, account.user!.id)) submission = result
                    })
                  "
                >
                  本人完成提交
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <template v-if="detail.teaching">
          <button
            :disabled="!writable || ['CANCELLED', 'STOPPED'].includes(detail.assignment.status)"
            @click="startEdit()"
          >
            编辑或延期
          </button>
          <template v-if="['DRAFT', 'SCHEDULED'].includes(detail.assignment.status)"
            ><label
              >定时开始（留空立即）<input
                v-model="starts"
                type="datetime-local"
                :disabled="busy" /></label
            ><button :disabled="!writable" @click="publish">发布作业</button></template
          >
          <template v-if="['DRAFT', 'SCHEDULED', 'ACTIVE'].includes(detail.assignment.status)"
            ><label>取消原因<input v-model="reason" maxlength="500" :disabled="busy" /></label
            ><button :disabled="!writable || !reason.trim()" @click="cancel">
              取消作业
            </button></template
          >
          <template v-if="detail.assignment.status === 'ACTIVE'"
            ><label
              >补加参与成员<select v-model="memberId" :disabled="busy">
                <option value="">请选择</option>
                <option
                  v-for="m in detail.eligibleMembers.filter((m) => m.status === 'ACTIVE')"
                  :key="m.userId"
                  :value="String(m.userId)"
                >
                  {{ m.username }}
                </option>
              </select></label
            ><button
              :disabled="!writable || !memberId"
              @click="
                write('/participants', {
                  expectedVersion: detail.assignment.version,
                  userId: Number(memberId),
                })
              "
            >
              补加成员
            </button></template
          >
          <label
            >复制后的新截止时间<input
              v-model="deadline"
              type="datetime-local"
              :disabled="busy" /></label
          ><button :disabled="!writable || !deadline" @click="copy">复制为新草稿</button>
        </template>
      </article>
      <aside v-if="confirm" role="dialog" aria-label="确认作业操作">
        <p>{{ confirm.text }}</p>
        <button :disabled="busy" @click="confirm.run">确认操作</button
        ><button :disabled="busy" @click="confirm = null">返回</button>
      </aside>
      <article v-if="item?.metadata">
        <h3>{{ item.metadata.title }}</h3>
        <p class="preserve">{{ item.metadata.statement }}</p>
        <h4>输入</h4>
        <p class="preserve">{{ item.metadata.inputDescription }}</p>
        <h4>输出</h4>
        <p class="preserve">{{ item.metadata.outputDescription }}</p>
        <div v-for="(s, i) in item.metadata.samples" :key="i">
          <h4>样例 {{ i + 1 }}</h4>
          <pre>{{ s.input }}</pre>
          <pre>{{ s.output }}</pre>
        </div>
        <label>Java 21 代码<textarea v-model="source" rows="12" :disabled="!runnable" /></label>
        <button :disabled="!runnable || !source.trim()" @click="execute('submissions')">
          提交作业
        </button>
        <label>自测输入<textarea v-model="input" rows="3" :disabled="!runnable" /></label
        ><button :disabled="!runnable || !source.trim()" @click="execute('self-tests')">
          运行作业自测
        </button>
        <button :disabled="busy" @click="viewSolution">查看作业题解</button>
        <template v-if="solution"
          ><p v-if="solution.access === 'LOCKED'">
            当前作业策略尚未开放题解。自由练习的提前确认不能绕过作业策略。
          </p>
          <p v-else-if="solution.access === 'UNAVAILABLE'">题解尚不可用。</p>
          <template v-else
            ><p class="preserve">{{ solution.idea }}</p>
            <pre>{{ solution.sourceCode }}</pre>
          </template></template
        >
      </article>
      <p v-if="submission" role="status">
        本人提交：{{ submission.processingStatus }} {{ submission.verdict ?? '' }}
      </p>
      <template v-if="selfTest"
        ><p>自测：{{ selfTest.run.processingStatus }} {{ selfTest.run.executionResult }}</p>
        <pre>{{ selfTest.output }}</pre>
      </template>
    </template>
  </section>
</template>
<style scoped>
.assignments {
  max-width: 1100px;
  margin: 1.5rem auto;
  padding: 1.2rem;
  background: white;
  border-radius: 12px;
}
label {
  display: block;
  margin: 0.7rem 0;
}
input,
select,
textarea {
  padding: 0.4rem;
}
textarea {
  display: block;
  width: 95%;
}
button {
  margin: 0.25rem;
  padding: 0.4rem 0.7rem;
}
table {
  width: 100%;
  border-collapse: collapse;
}
td,
th {
  text-align: left;
  padding: 0.6rem;
  border-bottom: 1px solid #dce3ec;
}
pre,
.preserve {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
aside {
  border: 2px solid #5265a3;
  padding: 1rem;
  margin: 1rem 0;
}
</style>
