<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiRequestError, restoreSession, type SessionResponse } from '@/services/forgeojApi'
import {
  teacherRead,
  type TeacherGrades,
  type TeacherAttempts,
  type TeacherSource,
} from '@/services/teacherRecordApi'

const props = defineProps<{ id: string; assignmentId: string }>()
const session = ref<SessionResponse | null>(null)
const grades = ref<TeacherGrades | null>(null)
const attempts = ref<TeacherAttempts | null>(null)
const source = ref<TeacherSource | null>(null)
const selected = ref<{ userId: number; username: string; ordinal: number; title: string } | null>(
  null,
)
const page = ref(1),
  attemptPage = ref(1),
  busy = ref(false),
  message = ref('')
let generation = 0,
  disposed = false
const gradeNames: Record<string, string> = {
  NOT_STARTED: '未开始',
  ATTEMPTING: '尝试中',
  ON_TIME_AC: '按时 AC',
  LATE_AC: '迟交 AC',
  PRECOMPLETED: '此前已完成',
}
const memberNames: Record<string, string> = {
  ACTIVE: '有效成员',
  LEFT: '已退出',
  REMOVED: '已移出',
}
function current(g: number) {
  return !disposed && g === generation
}
function clear() {
  grades.value = null
  attempts.value = null
  source.value = null
  selected.value = null
}
function error(e: unknown) {
  if (e instanceof ApiRequestError)
    return (
      (
        {
          400: '请检查分页参数。',
          401: '登录已失效。',
          403: '当前角色不能查看教学记录。',
          404: '作业或记录不可访问。',
          503: '服务暂不可用，请重试。',
        } as Record<number, string>
      )[e.status] ?? '读取失败。'
    )
  return e instanceof Error ? e.message : '读取失败。'
}
async function checked<T>(suffix: string, g: number, user: number) {
  const result = await teacherRead<T>(props.id, props.assignmentId, suffix)
  if (!current(g)) return null
  const fresh = await restoreSession()
  if (!current(g)) return null
  if (!fresh.authenticated || fresh.user?.id !== user) {
    clear()
    session.value = fresh
    throw new Error('身份已变化，请重新打开教学记录。')
  }
  session.value = fresh
  return result
}
async function task(run: (g: number, user: number) => Promise<void>) {
  const g = ++generation
  busy.value = true
  message.value = ''
  source.value = null
  try {
    const fresh = await restoreSession()
    if (!current(g)) return
    const changed = session.value?.authenticated && session.value.user?.id !== fresh.user?.id
    session.value = fresh
    if (changed) {
      clear()
      throw new Error('身份已变化，请重新打开教学记录。')
    }
    if (!fresh.authenticated || !fresh.user) {
      clear()
      return
    }
    await run(g, fresh.user.id)
  } catch (e) {
    if (current(g)) {
      message.value = error(e)
      if (e instanceof ApiRequestError && [401, 403, 404].includes(e.status)) clear()
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
async function load() {
  clear()
  await task(async (g, user) => {
    const rows = await checked<TeacherGrades>(`/grades?page=${page.value}&size=20`, g, user)
    if (rows) grades.value = rows
  })
}
async function member(userId: number, username: string, ordinal: number, title: string) {
  selected.value = { userId, username, ordinal, title }
  attemptPage.value = 1
  attempts.value = null
  source.value = null
  await loadAttempts()
}
async function loadAttempts() {
  const target = selected.value
  if (!target) return
  attempts.value = null
  source.value = null
  await task(async (g, user) => {
    const rows = await checked<TeacherAttempts>(
      `/participants/${target.userId}/problems/${target.ordinal}/attempts?page=${attemptPage.value}&size=20`,
      g,
      user,
    )
    if (rows) attempts.value = rows
  })
}
async function code(id: string) {
  await task(async (g, user) => {
    const result = await checked<TeacherSource>(`/submissions/${encodeURIComponent(id)}`, g, user)
    if (result) source.value = result
  })
}
async function turn(delta: number) {
  page.value += delta
  await load()
}
async function turnAttempts(delta: number) {
  attemptPage.value += delta
  await loadAttempts()
}
function closeSource() {
  generation++
  source.value = null
  busy.value = false
}
function display(value: string | null) {
  return value ? new Date(value).toLocaleString() : '—'
}
watch(
  () => [props.id, props.assignmentId],
  () => {
    generation++
    clear()
    page.value = 1
    attemptPage.value = 1
    void load()
  },
)
onMounted(() => void load())
onBeforeUnmount(() => {
  disposed = true
  generation++
  clear()
})
</script>

<template>
  <main class="teacher-records">
    <h2>作业教学记录</h2>
    <RouterLink :to="`/classrooms/${id}/assignments`">返回班级作业</RouterLink>
    <p>仅负责人和助教可查看本班作业正式提交；私人练习、自测和预完成历史代码不开放。</p>
    <p v-if="message" role="alert">{{ message }}</p>
    <p v-if="session && !session.authenticated">请先登录。</p>
    <button :disabled="busy" @click="load">刷新教学记录</button>
    <section v-if="grades">
      <h3>{{ grades.classroomTitle }} · {{ grades.assignment.title }}</h3>
      <p>
        {{ grades.classroomStatus === 'ARCHIVED' ? '班级已归档，历史只读' : '班级作业记录' }} · 截止
        {{ display(grades.assignment.deadlineAt) }}
      </p>
      <p>保留原参与成员，标注当前成员状态。自测不计次数；此前完成不产生作业内首次 AC。</p>
      <p v-if="!grades.total">作业尚无参与成员。</p>
      <article v-for="person in grades.items" :key="person.userId">
        <h4>
          {{ person.username }} · {{ memberNames[person.memberStatus] }} · 完成
          {{ person.completed }}/{{ person.problems.length }}
        </h4>
        <table>
          <thead>
            <tr>
              <th>题目</th>
              <th>状态</th>
              <th>正式尝试次数</th>
              <th>作业内首次 AC</th>
              <th>正式提交</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="problem in person.problems" :key="problem.ordinal">
              <td>{{ problem.ordinal }}. {{ problem.title }}</td>
              <td>{{ gradeNames[problem.state] }}</td>
              <td>{{ problem.attempts }}</td>
              <td>{{ display(problem.firstAcAt) }}</td>
              <td>
                <button
                  :disabled="busy"
                  :aria-label="`查看 ${person.username} 第${problem.ordinal}题提交`"
                  @click="member(person.userId, person.username, problem.ordinal, problem.title)"
                >
                  查看提交
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </article>
      <nav aria-label="成员分页">
        <button :disabled="busy || page <= 1" @click="turn(-1)">上一页成员</button
        ><span>第 {{ page }} 页 · {{ grades.total }} 人</span
        ><button :disabled="busy || page * 20 >= grades.total" @click="turn(1)">下一页成员</button>
      </nav>
    </section>
    <section v-if="selected && attempts">
      <h3>{{ selected.username }} · {{ selected.title }} · 正式提交</h3>
      <p v-if="!attempts.total">该题暂无作业内正式尝试；此前完成的私人历史代码不在此列。</p>
      <table v-else>
        <thead>
          <tr>
            <th>提交</th>
            <th>接受时间</th>
            <th>状态 / 结果</th>
            <th>完成时间</th>
            <th>源码</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="attempt in attempts.items" :key="attempt.submissionId">
            <td>{{ attempt.submissionId }}</td>
            <td>{{ display(attempt.acceptedAt) }}</td>
            <td>{{ attempt.processingStatus }} / {{ attempt.verdict ?? '待判定' }}</td>
            <td>{{ display(attempt.finishedAt) }}</td>
            <td>
              <button
                :disabled="busy"
                :aria-label="`查看源码 ${attempt.submissionId}`"
                @click="code(attempt.submissionId)"
              >
                查看源码
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <nav aria-label="提交分页">
        <button :disabled="busy || attemptPage <= 1" @click="turnAttempts(-1)">上一页提交</button
        ><span>第 {{ attemptPage }} 页 · {{ attempts.total }} 次</span
        ><button :disabled="busy || attemptPage * 20 >= attempts.total" @click="turnAttempts(1)">
          下一页提交
        </button>
      </nav>
    </section>
    <section v-if="source" aria-label="只读源码">
      <h3>正式提交源码</h3>
      <p>
        {{ source.submission.submissionId }} · 第 {{ source.submission.ordinal }} 题 ·
        {{ source.submission.language }} · 判题版本 {{ source.submission.judgeVersionId }}
      </p>
      <button @click="closeSource">收起源码</button>
      <pre>{{ source.sourceCode }}</pre>
    </section>
  </main>
</template>

<style scoped>
.teacher-records {
  max-width: 1180px;
  margin: 24px auto;
  padding: 24px;
  background: white;
  border-radius: 12px;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  padding: 10px;
  text-align: left;
  border-bottom: 1px solid #ddd;
  overflow-wrap: anywhere;
}
nav {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 16px 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f3f6fa;
  padding: 16px;
}
button {
  cursor: pointer;
}
</style>
