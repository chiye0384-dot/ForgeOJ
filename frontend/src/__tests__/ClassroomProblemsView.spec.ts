// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from '@/views/ClassroomProblemsView.vue'
import { restoreSession, type SessionResponse } from '@/services/forgeojApi'
import { getClassroom } from '@/services/classroomApi'
import { authoredList } from '@/services/contentApi'
import { listValidations } from '@/services/validationApi'
import { privateRead, privateWrite, type PrivateDetail } from '@/services/classroomProblemApi'
vi.mock('@/services/forgeojApi', async (original) => ({
  ...(await original<typeof import('@/services/forgeojApi')>()),
  restoreSession: vi.fn<typeof restoreSession>(),
}))
vi.mock('@/services/classroomApi', () => ({ getClassroom: vi.fn<typeof getClassroom>() }))
vi.mock('@/services/contentApi', () => ({ authoredList: vi.fn<typeof authoredList>() }))
vi.mock('@/services/validationApi', () => ({ listValidations: vi.fn<typeof listValidations>() }))
vi.mock('@/services/classroomProblemApi', () => ({
  privateRead: vi.fn<typeof privateRead>(),
  privateWrite: vi.fn<typeof privateWrite>(),
}))
const account: SessionResponse = {
  authenticated: true,
  user: { id: 1, username: 'owner' },
  csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' },
}
const problem = {
  slug: 'class-fixture',
  title: '原创私有题',
  status: 'ACTIVE' as const,
  version: 1,
  createdBy: 1,
  solutionPolicy: 'AFTER_AC' as const,
}
const detail: PrivateDetail = {
  problem: { ...problem },
  metadata: {
    title: problem.title,
    statement: '成员题面',
    inputDescription: '整数',
    outputDescription: '结果',
    samples: [],
    originType: 'ORIGINAL',
    sourceUrl: '',
    licenseStatement: '原创',
    timeLimitMs: 2000,
    memoryLimitMb: 256,
    outputLimitBytes: 1024,
  },
}
let app: App, host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 40; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function click(text: string) {
  const b = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(b).toBeDefined()
  b!.click()
}
function mount() {
  app = createApp(View, { id: 'room-fixture' })
  app.mount(host)
}
beforeEach(() => {
  vi.resetAllMocks()
  host = document.createElement('div')
  vi.mocked(restoreSession).mockResolvedValue(account)
  vi.mocked(getClassroom).mockResolvedValue({
    id: 'room-fixture',
    title: '原创班级',
    status: 'ACTIVE',
    role: 'OWNER',
    ownerId: 1,
    version: 1,
    members: [],
    inviteEnabled: false,
    pendingTransfer: null,
  })
  vi.mocked(authoredList).mockResolvedValue({
    items: [{ id: 'draft', title: '作者题', version: 3, status: 'DRAFT', testCount: 1 }],
    total: 1,
  })
  vi.mocked(privateRead).mockImplementation(async (_room, slug, suffix) => {
    if (!slug) return { items: [problem], total: 1 }
    if (suffix === '/solution') return { access: 'LOCKED', idea: null, sourceCode: null }
    return structuredClone(detail)
  })
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
it('members see learning only and never receive maintenance data on opening', async () => {
  vi.mocked(getClassroom).mockResolvedValue({
    id: 'room-fixture',
    title: '原创班级',
    status: 'ACTIVE',
    role: 'MEMBER',
    ownerId: 2,
    version: 1,
    members: [],
    inviteEnabled: false,
    pendingTransfer: null,
  })
  mount()
  await settle()
  click('打开题目')
  await settle()
  expect(host.textContent).toContain('成员题面')
  expect(host.textContent).not.toContain('查看教学维护数据')
  expect(authoredList).not.toHaveBeenCalled()
  expect(privateRead).not.toHaveBeenCalledWith('room-fixture', 'class-fixture', '/maintenance')
})
it('early viewing sends nothing before second confirmation or after cancellation', async () => {
  mount()
  await settle()
  click('打开题目')
  await settle()
  click('查看官方题解')
  await settle()
  click('提前查看题解')
  await settle()
  expect(host.textContent).toContain('可能影响独立思考')
  expect(privateWrite).not.toHaveBeenCalled()
  click('取消')
  await settle()
  expect(privateWrite).not.toHaveBeenCalled()
  click('提前查看题解')
  await settle()
  vi.mocked(privateWrite).mockResolvedValue({
    access: 'EARLY_VIEW',
    idea: '独立思路',
    sourceCode: '独立题解源码',
  })
  click('再次确认')
  await settle()
  expect(privateWrite).toHaveBeenCalledWith(
    'room-fixture',
    'class-fixture',
    '/solution/early-view',
    { expectedVersion: 1, confirmEarlyView: true },
    account.csrf,
  )
  expect(host.textContent).toContain('独立题解源码')
})
it('changed identity discards private response and prevents pending confirmation write', async () => {
  mount()
  await settle()
  click('打开题目')
  await settle()
  click('查看官方题解')
  await settle()
  click('提前查看题解')
  await settle()
  vi.mocked(restoreSession).mockResolvedValue({ ...account, user: { id: 2, username: 'other' } })
  click('再次确认')
  await settle()
  expect(privateWrite).not.toHaveBeenCalled()
  expect(host.textContent).not.toContain('成员题面')
  expect(host.textContent).toContain('身份已变化')
})
it('late private response cannot repopulate a page after refresh', async () => {
  mount()
  await settle()
  let resolve!: (v: unknown) => void
  vi.mocked(privateRead).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  click('打开题目')
  await settle()
  const refresh = [...host.querySelectorAll('button')].find((b) => b.textContent === '刷新班级题')!
  refresh.disabled = false
  refresh.click()
  await settle()
  resolve(structuredClone(detail))
  await settle()
  expect(host.textContent).not.toContain('成员题面')
})
it('publication binds the selected draft version and dual validation and requires confirmation', async () => {
  vi.mocked(listValidations).mockResolvedValue({
    items: [
      {
        jobId: 'passed',
        draftId: 'draft',
        draftVersion: 3,
        processingStatus: 'FINISHED',
        statusVersion: 1,
        validationStatus: 'PASSED',
        referenceResult: 'ACCEPTED',
        solutionResult: 'ACCEPTED',
        stale: false,
      },
    ],
    page: 1,
    size: 20,
    total: 1,
  })
  mount()
  await settle()
  const selects = host.querySelectorAll('select')
  selects[0]!.value = 'draft'
  selects[0]!.dispatchEvent(new Event('change'))
  await settle()
  selects[1]!.value = 'passed'
  selects[1]!.dispatchEvent(new Event('change'))
  await settle()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(privateWrite).not.toHaveBeenCalled()
  vi.mocked(privateWrite).mockResolvedValue(problem)
  click('再次确认')
  await settle()
  expect(privateWrite).toHaveBeenCalledWith(
    'room-fixture',
    '',
    '',
    expect.objectContaining({
      draftId: 'draft',
      draftVersion: 3,
      validationJobId: 'passed',
      solutionPolicy: 'AFTER_AC',
      clientRequestId: expect.any(String),
    }),
    account.csrf,
  )
})
it('archive cancellation keeps the published problem active', async () => {
  mount()
  await settle()
  click('打开题目')
  await settle()
  click('归档题目')
  await settle()
  click('取消')
  await settle()
  expect(privateWrite).not.toHaveBeenCalled()
  expect(host.textContent).toContain('正式提交')
})
it('archived classroom hides publication and submission but permits historical learning reads', async () => {
  vi.mocked(getClassroom).mockResolvedValue({
    id: 'room-fixture',
    title: '原创班级',
    status: 'ARCHIVED',
    role: 'OWNER',
    ownerId: 1,
    version: 1,
    members: [],
    inviteEnabled: false,
    pendingTransfer: null,
  })
  mount()
  await settle()
  click('打开题目')
  await settle()
  expect(host.textContent).toContain('成员题面')
  expect(host.textContent).not.toContain('正式提交')
  expect(host.textContent).not.toContain('发布班级私有题')
})
