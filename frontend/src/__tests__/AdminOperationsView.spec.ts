// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from '@/views/AdminOperationsView.vue'
import {
  restoreAdmin,
  adminRequest,
  AdminRequestError,
  type AdminSession,
} from '@/services/adminApi'
vi.mock('@/services/adminApi', async (original) => ({
  ...(await original<typeof import('@/services/adminApi')>()),
  restoreAdmin: vi.fn<typeof restoreAdmin>(),
  adminRequest: vi.fn<typeof adminRequest>(),
}))
const identity: AdminSession = {
  authenticated: true,
  admin: { id: 1, username: 'ops_fixture', role: 'OPS_ADMIN', mustChangePassword: false },
  csrf: { headerName: 'X-ADMIN-CSRF-TOKEN', parameterName: '_admin_csrf', token: 'fixture' },
}
const task = {
  kind: 'FORMAL',
  id: 'task-fixture',
  ownerId: 2,
  status: 'DEAD_LETTER',
  version: 6,
  submissionId: 'submission-fixture',
  snapshotId: null,
  attemptCount: 3,
  maxAttempts: 3,
  failureCode: 'PLATFORM_FAILURE',
  createdAt: '2026-10-09 08:00:00',
  startedAt: null,
  finishedAt: '2026-10-09 08:01:00',
  nextAttemptAt: null,
  leaseExpired: false,
  expiresAt: null,
  executionRecoveryUsed: false,
  sourceCode: 'PRIVATE_UNDECLARED_SOURCE',
  payload: 'PRIVATE_UNDECLARED_PAYLOAD',
}
const page = { items: [task], page: 1, size: 20, total: 1 }
let app: App | undefined, host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 40; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount() {
  app = createApp(View)
  app.mount(host)
}
function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(button).toBeDefined()
  button!.click()
}
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(restoreAdmin).mockResolvedValue(identity)
  vi.mocked(adminRequest).mockImplementation(async (path) =>
    path.endsWith('/FORMAL/task-fixture')
      ? task
      : path.includes('/attempts?') || path.includes('/events?') || path.includes('/recoveries?')
        ? { items: [], page: 1, size: 20, total: 0 }
        : page,
  )
})
afterEach(() => {
  app?.unmount()
  host.remove()
  app = undefined
})
it('rejects reviewer and initial-password sessions before loading operational metadata', async () => {
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...identity,
    admin: { ...identity.admin!, role: 'CONTENT_REVIEWER' },
  })
  mount()
  await settle()
  expect(adminRequest).not.toHaveBeenCalled()
  expect(host.textContent).toContain('运维管理员或超级管理员')
  app!.unmount()
  app = undefined
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...identity,
    admin: { ...identity.admin!, mustChangePassword: true },
  })
  mount()
  await settle()
  expect(adminRequest).not.toHaveBeenCalled()
})
it('loads exact bound metadata and never renders undeclared source or message payload', async () => {
  mount()
  await settle()
  expect(host.textContent).toContain('DEAD_LETTER')
  click('查看元数据')
  await settle()
  expect(adminRequest).toHaveBeenCalledWith(
    '/operations/tasks/FORMAL/task-fixture',
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  )
  expect(host.textContent).toContain('submission-fixture')
  expect(host.textContent).not.toContain('PRIVATE_UNDECLARED')
})
it('uses exact detail lookup to inspect a finished task after recovery', async () => {
  mount()
  await settle()
  const select = host.querySelector('select') as HTMLSelectElement
  select.value = 'FORMAL'
  select.dispatchEvent(new Event('change'))
  const input = host.querySelector('input[maxlength="36"]') as HTMLInputElement
  input.value = 'task-fixture'
  input.dispatchEvent(new Event('input'))
  vi.mocked(adminRequest).mockResolvedValue({
    ...task,
    status: 'FINISHED',
    executionRecoveryUsed: true,
  })
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(adminRequest).toHaveBeenLastCalledWith(
    '/operations/tasks/FORMAL/task-fixture',
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  )
  expect(host.textContent).toContain('FINISHED')
})
it('requires a reason and explicit confirmation, retaining the same request after response loss', async () => {
  mount()
  await settle()
  click('查看元数据')
  await settle()
  const button = [...host.querySelectorAll('button')].find(
    (b) => b.textContent?.trim() === '追加一次执行',
  )!
  expect(button.disabled).toBe(true)
  const input = host.querySelector('input[maxlength="500"]') as HTMLInputElement
  input.value = 'infrastructure fixed'
  input.dispatchEvent(new Event('input'))
  const confirmation = host.querySelector('input[type="checkbox"]') as HTMLInputElement
  confirmation.checked = true
  confirmation.dispatchEvent(new Event('change'))
  await settle()
  let original = ''
  vi.mocked(adminRequest).mockImplementation(async (_path, init) => {
    original = String(init?.body)
    throw new TypeError('response lost')
  })
  click('追加一次执行')
  await settle()
  expect(JSON.parse(original)).toMatchObject({ expectedVersion: 6, reason: 'infrastructure fixed' })
  vi.mocked(adminRequest).mockImplementation(async (_path, init) => {
    expect(init?.body).toBe(original)
    expect(init?.headers).toMatchObject({ 'X-ADMIN-CSRF-TOKEN': 'fixture' })
    return {
      id: 'receipt-fixture',
      scope: 'EXECUTION',
      eventId: 'event-fixture',
      kind: 'FORMAL',
      taskId: 'task-fixture',
    }
  })
  click('重发原请求，获取处理凭证')
  await settle()
  expect(host.textContent).toContain('receipt-fixture')
  expect(host.querySelector('[data-testid="operations-detail"]')).toBeNull()
  vi.mocked(restoreAdmin).mockResolvedValue({ ...identity, authenticated: false, admin: null })
  click('刷新')
  await settle()
  expect(host.textContent).not.toContain('receipt-fixture')
})
it('clears list and detail when the server rejects a current role change', async () => {
  mount()
  await settle()
  click('查看元数据')
  await settle()
  vi.mocked(adminRequest).mockRejectedValue(new AdminRequestError(403))
  click('刷新')
  await settle()
  expect(host.textContent).toContain('当前账号没有运维权限')
  expect(host.textContent).not.toContain('task-fixture')
  expect(host.querySelector('[data-testid="operations-detail"]')).toBeNull()
})
it('discards a late response after component unmount', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(adminRequest).mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  mount()
  await settle()
  app!.unmount()
  app = undefined
  resolve(page)
  await settle()
  expect(host.textContent).not.toContain('task-fixture')
})
