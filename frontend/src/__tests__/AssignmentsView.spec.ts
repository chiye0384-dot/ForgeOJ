// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from '@/views/AssignmentsView.vue'
import {
  ApiRequestError,
  restoreSession,
  getSubmission,
  getProblems,
  type SessionResponse,
} from '@/services/forgeojApi'
import {
  assignmentRead,
  assignmentWrite,
  type AssignmentDetail,
  type AssignmentPage,
} from '@/services/assignmentApi'
vi.mock('@/services/forgeojApi', async (original) => ({
  ...(await original<typeof import('@/services/forgeojApi')>()),
  restoreSession: vi.fn<typeof restoreSession>(),
  getSubmission: vi.fn<typeof getSubmission>(),
  getProblems: vi.fn<typeof getProblems>(),
}))
vi.mock('@/services/assignmentApi', () => ({
  assignmentRead: vi.fn<typeof assignmentRead>(),
  assignmentWrite: vi.fn<typeof assignmentWrite>(),
}))
const account: SessionResponse = {
  authenticated: true,
  user: { id: 2, username: 'student' },
  csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' },
}
const summary = {
  id: 'assignment-fixture',
  title: '原创作业',
  status: 'ACTIVE' as const,
  version: 2,
  startsAt: '2026-10-07T08:00:00Z',
  deadlineAt: '2026-10-08T08:00:00Z',
  startedAt: '2026-10-07T08:00:00Z',
  endedAt: null,
  closeReason: null,
  acceptExistingAc: false,
  allowLate: true,
  solutionPolicy: 'AFTER_AC' as const,
}
const fixture: AssignmentDetail = {
  assignment: summary,
  description: '作业说明',
  member: true,
  teaching: false,
  participating: true,
  eligibleMembers: [],
  problems: [
    {
      ordinal: 1,
      slug: 'class-original',
      judgeVersionId: 8,
      grade: {
        state: 'NOT_STARTED',
        attempts: 0,
        completionSubmissionId: null,
        firstAcSubmissionId: null,
        firstAcAt: null,
      },
      metadata: {
        title: '原创题',
        statement: '成员题面',
        inputDescription: '两个数',
        outputDescription: '和',
        samples: [],
        originType: 'ORIGINAL',
        sourceUrl: '',
        licenseStatement: '原创',
        timeLimitMs: 2000,
        memoryLimitMb: 256,
        outputLimitBytes: 1024,
      },
    },
  ],
}
let app: App | undefined, host: HTMLDivElement, detail: AssignmentDetail, list: AssignmentPage
async function settle() {
  for (let i = 0; i < 40; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(text: string) {
  const b = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(b).toBeDefined()
  return b!
}
function click(text: string) {
  button(text).click()
}
function mount() {
  app = createApp(View, { id: 'room-fixture' })
  app.mount(host)
}
beforeEach(() => {
  vi.resetAllMocks()
  host = document.createElement('div')
  detail = structuredClone(fixture)
  list = {
    items: [summary],
    page: 1,
    size: 20,
    total: 1,
    classroomTitle: '原创班级',
    classroomStatus: 'ACTIVE',
    member: true,
    teaching: false,
  }
  vi.mocked(restoreSession).mockResolvedValue(account)
  vi.mocked(assignmentRead).mockImplementation(
    async (_room, id, suffix) =>
      (id
        ? suffix?.endsWith('/solution')
          ? { access: 'LOCKED', idea: null, sourceCode: null }
          : structuredClone(detail)
        : structuredClone(list)) as never,
  )
  vi.mocked(assignmentWrite).mockResolvedValue(detail)
})
afterEach(() => {
  app?.unmount()
  app = undefined
  vi.useRealTimers()
})
it('submits only through assignment scope and refreshes genuine grades after terminal result', async () => {
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('原创题')
  await settle()
  vi.mocked(assignmentWrite).mockResolvedValue({
    submissionId: 'run-fixture',
    processingStatus: 'QUEUED',
    statusVersion: 0,
  })
  vi.mocked(getSubmission).mockResolvedValue({
    submissionId: 'run-fixture',
    processingStatus: 'FINISHED',
    verdict: 'AC',
    statusVersion: 2,
    diagnosticMessage: null,
  })
  detail.problems[0]!.grade = {
    state: 'ON_TIME_AC',
    attempts: 1,
    completionSubmissionId: 'run-fixture',
    firstAcSubmissionId: 'run-fixture',
    firstAcAt: '2026-10-07T08:01:00Z',
  }
  click('提交作业')
  await settle()
  expect(assignmentWrite).toHaveBeenCalledWith(
    'room-fixture',
    summary.id,
    '/problems/class-original/submissions',
    expect.objectContaining({
      language: 'JAVA_21',
      clientRequestId: expect.any(String),
      sourceCode: expect.any(String),
    }),
    account.csrf,
  )
  expect(host.textContent).toContain('按时 AC')
  expect(host.textContent).toContain('本人完成 1/1')
  expect(host.textContent).toContain('FINISHED AC')
})
it('requires explicit publish confirmation and freezes started definition fields', async () => {
  list.teaching = detail.teaching = true
  detail.assignment = { ...summary, status: 'DRAFT', startedAt: null }
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('发布作业')
  await settle()
  expect(assignmentWrite).not.toHaveBeenCalled()
  expect(host.querySelector('[role="dialog"]')).not.toBeNull()
  click('返回')
  click('发布作业')
  click('确认操作')
  await settle()
  expect(assignmentWrite).toHaveBeenCalledWith(
    'room-fixture',
    summary.id,
    '/publish',
    { expectedVersion: 2, startsAt: null },
    account.csrf,
    'POST',
  )
  detail.assignment = { ...summary }
  click('刷新作业')
  await settle()
  click('编辑或延期')
  await settle()
  expect(host.querySelector('fieldset')?.disabled).toBe(true)
  expect(host.textContent).toContain('延长截止时间')
})
it('shows own historical summary without problem editor after exit', async () => {
  list.member = detail.member = false
  detail.description = ''
  detail.problems[0]!.metadata = null
  detail.problems[0]!.slug = null
  detail.problems[0]!.grade!.state = 'PRECOMPLETED'
  mount()
  await settle()
  click('原创作业')
  await settle()
  expect(host.textContent).toContain('此前已完成')
  expect(host.textContent).toContain('已离开班级')
  expect(host.textContent).not.toContain('成员题面')
  expect(host.querySelector('textarea')).toBeNull()
  expect(host.textContent).not.toContain('提交作业')
})
it('clears sensitive content on identity change during solution fetch', async () => {
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('原创题')
  await settle()
  vi.mocked(restoreSession)
    .mockResolvedValueOnce(account)
    .mockResolvedValue({
      authenticated: true,
      user: { id: 3, username: 'other' },
      csrf: account.csrf,
    })
  vi.mocked(assignmentRead).mockResolvedValue({
    access: 'AC',
    idea: 'late-secret',
    sourceCode: 'late-private-code',
  })
  click('查看作业题解')
  await settle()
  expect(host.textContent).toContain('身份已变化')
  expect(host.textContent).not.toContain('成员题面')
  expect(host.textContent).not.toContain('late-secret')
  expect(host.querySelector('textarea')).toBeNull()
})
it('preserves local editor on CAS conflict and does not silently overwrite', async () => {
  list.teaching = detail.teaching = true
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('编辑或延期')
  await settle()
  const text = host.querySelector('textarea')!
  text.value = '本地未保存说明'
  text.dispatchEvent(new Event('input'))
  vi.mocked(assignmentWrite).mockRejectedValue(new ApiRequestError(409))
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(host.textContent).toContain('请刷新后重新查看')
  expect(host.querySelector('textarea')!.value).toBe('本地未保存说明')
  expect(assignmentWrite).toHaveBeenCalledTimes(1)
})
it('keeps solution locked without an early confirmation bypass', async () => {
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('原创题')
  await settle()
  click('查看作业题解')
  await settle()
  expect(host.textContent).toContain('尚未开放题解')
  expect(host.textContent).not.toContain('仍然查看')
  expect(assignmentWrite).not.toHaveBeenCalled()
})
it('drops a late detail response after view unmount', async () => {
  mount()
  await settle()
  let finish!: (value: unknown) => void
  vi.mocked(assignmentRead).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }) as never,
  )
  click('原创作业')
  await settle()
  app!.unmount()
  app = undefined
  finish(fixture)
  await settle()
  expect(host.textContent).toBe('')
})
it('rejects a captured submission when the session changes before the write', async () => {
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('原创题')
  await settle()
  vi.mocked(restoreSession).mockResolvedValue({
    authenticated: true,
    user: { id: 3, username: 'other' },
    csrf: account.csrf,
  })
  click('提交作业')
  await settle()
  expect(assignmentWrite).not.toHaveBeenCalled()
  expect(host.textContent).toContain('身份已变化')
  expect(host.querySelector('textarea')).toBeNull()
})
it('preserves original deadline precision when only the description changes', async () => {
  list.teaching = detail.teaching = true
  detail.assignment.deadlineAt = '2026-10-08T08:00:45.123456Z'
  mount()
  await settle()
  click('原创作业')
  await settle()
  click('编辑或延期')
  await settle()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(assignmentWrite).toHaveBeenCalledWith(
    'room-fixture',
    summary.id,
    '',
    expect.objectContaining({
      definition: expect.objectContaining({ deadlineAt: '2026-10-08T08:00:45.123456Z' }),
    }),
    account.csrf,
    'PUT',
  )
})
it('uses a new creation request for a second intentionally identical assignment', async () => {
  list.teaching = detail.teaching = true
  vi.mocked(getProblems).mockResolvedValue({
    items: [
      { slug: 'class-original', title: '原创题', difficulty: 'EASY', tags: [], judgeVersion: 1 },
    ],
    page: 1,
    size: 20,
    total: 1,
  })
  mount()
  await settle()
  for (let i = 0; i < 2; i++) {
    click('新建作业')
    await settle()
    const title = host.querySelector('form input')!
    ;(title as HTMLInputElement).value = '相同作业'
    title.dispatchEvent(new Event('input'))
    click('加载题目')
    await settle()
    click('选择 原创题')
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
  }
  const calls = vi.mocked(assignmentWrite).mock.calls
  expect(calls).toHaveLength(2)
  expect((calls[0]![3] as { clientRequestId: string }).clientRequestId).not.toBe(
    (calls[1]![3] as { clientRequestId: string }).clientRequestId,
  )
})
