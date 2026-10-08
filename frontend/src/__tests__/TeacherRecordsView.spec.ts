// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import View from '@/views/TeacherRecordsView.vue'
import { ApiRequestError, restoreSession, type SessionResponse } from '@/services/forgeojApi'
import { teacherRead, type TeacherGrades, type TeacherAttempt } from '@/services/teacherRecordApi'

vi.mock('@/services/forgeojApi', async (original) => ({
  ...(await original<typeof import('@/services/forgeojApi')>()),
  restoreSession: vi.fn<typeof restoreSession>(),
}))
vi.mock('@/services/teacherRecordApi', () => ({ teacherRead: vi.fn<typeof teacherRead>() }))
const account: SessionResponse = {
  authenticated: true,
  user: { id: 1, username: 'owner' },
  csrf: { token: 'fixture', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' },
}
const fixture: TeacherGrades = {
  assignment: {
    id: 'work',
    title: '原创作业',
    status: 'ACTIVE',
    version: 2,
    startsAt: null,
    startedAt: '2026-10-08T01:00:00Z',
    endedAt: null,
    closeReason: null,
    deadlineAt: '2026-10-09T01:00:00Z',
    acceptExistingAc: true,
    allowLate: true,
    solutionPolicy: 'AFTER_AC',
  },
  classroomTitle: '原创班级',
  classroomStatus: 'ACTIVE',
  page: 1,
  size: 20,
  total: 1,
  items: [
    {
      userId: 2,
      username: 'student',
      memberStatus: 'LEFT',
      role: 'MEMBER',
      completed: 1,
      problems: [
        {
          ordinal: 1,
          slug: 'sum',
          title: '原创题',
          judgeVersionId: 1,
          state: 'ON_TIME_AC',
          attempts: 2,
          firstAcAt: '2026-10-08T01:02:00Z',
        },
      ],
    },
  ],
}
const attempt: TeacherAttempt = {
  submissionId: 'formal',
  userId: 2,
  ordinal: 1,
  problemSlug: 'sum',
  judgeVersionId: 1,
  language: 'JAVA_21',
  processingStatus: 'FINISHED',
  verdict: 'AC',
  acceptedAt: '2026-10-08T01:01:00Z',
  finishedAt: '2026-10-08T01:02:00Z',
}
const sentinel = '<script>student original source</script>'
let app: App | undefined, host: HTMLDivElement, props: { id: string; assignmentId: string }
async function settle() {
  for (let i = 0; i < 30; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string) {
  const found = [...host.querySelectorAll('button')].find(
    (b) => b.getAttribute('aria-label') === label || b.textContent?.trim() === label,
  )
  if (!found) throw new Error(`Missing button ${label}`)
  return found
}
async function open() {
  app = createApp({ render: () => h(View, props) })
  app.component('RouterLink', { props: ['to'], template: '<a :href="to"><slot /></a>' })
  app.mount(host)
  await settle()
}
async function select() {
  button('查看 student 第1题提交').click()
  await settle()
}
async function source() {
  await select()
  button('查看源码 formal').click()
  await settle()
}
beforeEach(() => {
  vi.resetAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  props = reactive({ id: 'room', assignmentId: 'work' })
  vi.mocked(restoreSession).mockResolvedValue(structuredClone(account))
  vi.mocked(teacherRead).mockImplementation(async (_room, _id, path) => {
    if (path.startsWith('/grades')) return structuredClone(fixture)
    if (path.startsWith('/participants')) return { items: [attempt], page: 1, size: 20, total: 1 }
    return { submission: attempt, sourceCode: sentinel, sourceSha256: 'hash' }
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  vi.restoreAllMocks()
})
it('shows historical grades, scoped attempts and escaped readonly source after explicit selection', async () => {
  await open()
  expect(host.textContent).toContain('已退出')
  expect(host.textContent).toContain('完成 1/1')
  expect(host.textContent).not.toContain(sentinel)
  await source()
  expect(host.querySelector('pre')?.textContent).toBe(sentinel)
  expect(host.querySelector('script')).toBeNull()
  expect(host.querySelector('textarea')).toBeNull()
  expect(teacherRead).toHaveBeenCalledWith('room', 'work', '/submissions/formal')
  button('收起源码').click()
  await settle()
  expect(host.textContent).not.toContain(sentinel)
})
it('clears source and grades immediately when a subsequent role check is forbidden', async () => {
  await open()
  await source()
  vi.mocked(teacherRead).mockRejectedValueOnce(new ApiRequestError(403))
  button('刷新教学记录').click()
  await nextTick()
  expect(host.textContent).not.toContain(sentinel)
  await settle()
  expect(host.textContent).toContain('当前角色不能查看教学记录')
  expect(host.textContent).not.toContain('原创题')
})
it('discards late source response when the assignment route changes', async () => {
  await open()
  await select()
  let resolve!: (value: unknown) => void
  vi.mocked(teacherRead).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  button('查看源码 formal').click()
  await settle()
  props.assignmentId = 'second'
  await settle()
  resolve({ submission: attempt, sourceCode: sentinel, sourceSha256: 'hash' })
  await settle()
  expect(host.textContent).not.toContain(sentinel)
  expect(teacherRead).toHaveBeenCalledWith('room', 'second', expect.stringContaining('/grades'))
})
it('discards source when identity changes after the response', async () => {
  await open()
  await select()
  vi.mocked(restoreSession)
    .mockResolvedValueOnce(account)
    .mockResolvedValueOnce({ ...account, user: { id: 9, username: 'other' } })
  button('查看源码 formal').click()
  await settle()
  expect(host.textContent).not.toContain(sentinel)
  expect(host.textContent).toContain('身份已变化')
  expect(host.textContent).not.toContain('原创题')
})
it('does not request grades for anonymous users and shows an empty attempts explanation', async () => {
  vi.mocked(restoreSession).mockResolvedValueOnce({ ...account, authenticated: false, user: null })
  await open()
  expect(teacherRead).not.toHaveBeenCalled()
  expect(host.textContent).toContain('请先登录')
  button('刷新教学记录').click()
  await settle()
  vi.mocked(teacherRead).mockResolvedValueOnce({ items: [], page: 1, size: 20, total: 0 })
  await select()
  expect(host.textContent).toContain('此前完成的私人历史代码不在此列')
  expect(host.textContent).not.toContain('正式提交源码')
})
