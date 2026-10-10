// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import View from '@/views/AdminSearchView.vue'
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
  admin: { id: 1, username: 'search_fixture', role: 'OPS_ADMIN', mustChangePassword: false },
  csrf: { headerName: 'X-ADMIN-CSRF-TOKEN', parameterName: '_admin_csrf', token: 'fixture' },
}
const status = {
  enabled: true,
  version: 7,
  publicEpoch: 8,
  readableEpoch: 6,
  pendingEvents: 2,
  deadLetters: 1,
  failedPublications: 1,
  blockedDeadLetters: 1,
  failedDeadPublications: 1,
  activeRebuild: null,
}
const job = {
  id: 'fixture-rebuild',
  status: 'SUCCEEDED',
  attempts: 1,
  errorCode: null,
  reason: '<script>private source</script>',
  createdAt: '2026-10-10',
  finishedAt: '2026-10-10',
  statementText: 'UNDECLARED_PRIVATE_BODY',
  leaseToken: 'UNDECLARED_LEASE',
}
let app: App | undefined, host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 50; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/search', component: View },
      { path: '/admin/login', component: { template: '<div />' } },
      { path: '/admin/operations', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/search')
  await router.isReady()
  app = createApp(View)
  app.use(router)
  app.mount(host)
  await settle()
}
function click(label: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)
  expect(button).toBeDefined()
  button!.click()
}
function reason(text: string) {
  const input = host.querySelector('textarea')!
  input.value = text
  input.dispatchEvent(new Event('input'))
  return settle()
}
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(restoreAdmin).mockResolvedValue(identity)
  vi.mocked(adminRequest).mockImplementation(async (path) =>
    path === '/search/status' ? status : { items: [job], page: 1, size: 20, total: 1 },
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  vi.restoreAllMocks()
})
it('shows whitelisted search state and escapes reasons without rendering undeclared fields', async () => {
  await mount()
  expect(host.textContent).toContain('8 / 6')
  expect(host.textContent).toContain('fixture-rebuild')
  expect(host.textContent).toContain(job.reason)
  expect(host.querySelector('script')).toBeNull()
  expect(host.textContent).not.toContain('UNDECLARED_PRIVATE_BODY')
  expect(host.textContent).not.toContain('UNDECLARED_LEASE')
})
it('denies reviewer and first-password accounts before reading search metadata', async () => {
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...identity,
    admin: { ...identity.admin!, role: 'CONTENT_REVIEWER' },
  })
  await mount()
  expect(host.textContent).toContain('没有搜索运维权限')
  expect(vi.mocked(adminRequest)).not.toHaveBeenCalled()
  expect(host.querySelector('table')).toBeNull()
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...identity,
    admin: { ...identity.admin!, mustChangePassword: true },
  })
  click('刷新状态')
  await settle()
  expect(vi.mocked(adminRequest)).not.toHaveBeenCalled()
})
it('uses CSRF, the expected version and the same request id after an uncertain response', async () => {
  const attempts: RequestInit[] = []
  vi.mocked(adminRequest).mockImplementation(async (path, init) => {
    if (init?.method === 'POST') {
      attempts.push(init)
      if (attempts.length === 1) throw new AdminRequestError(503)
      return { ...job, status: 'QUEUED' }
    }
    return path === '/search/status' ? status : { items: [job], page: 1, size: 20, total: 1 }
  })
  await mount()
  await reason('修复本测试索引')
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(attempts).toHaveLength(1)
  click('刷新状态')
  await settle()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(attempts).toHaveLength(2)
  expect(attempts[1]!.body).toBe(attempts[0]!.body)
  expect(attempts[0]!.headers).toEqual({
    'Content-Type': 'application/json',
    'X-ADMIN-CSRF-TOKEN': 'fixture',
  })
  expect(JSON.parse(String(attempts[0]!.body))).toMatchObject({
    expectedVersion: 7,
    reason: '修复本测试索引',
  })
  expect(host.textContent).toContain('QUEUED')
})
it('clears visible metadata and pending requests when permission is revoked', async () => {
  await mount()
  expect(host.querySelector('table')).not.toBeNull()
  vi.mocked(adminRequest).mockRejectedValue(new AdminRequestError(403))
  click('刷新状态')
  await settle()
  expect(host.querySelector('table')).toBeNull()
  expect(host.querySelector('textarea')).toBeNull()
  expect(host.textContent).not.toContain('fixture-rebuild')
  expect(host.textContent).toContain('没有搜索运维权限')
})
