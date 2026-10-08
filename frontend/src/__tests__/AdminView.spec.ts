// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from '@/views/AdminView.vue'
import {
  restoreAdmin,
  adminRequest,
  adminAction,
  AdminRequestError,
  type AdminSession,
} from '@/services/adminApi'
vi.mock('@/services/adminApi', async (original) => ({
  ...(await original<typeof import('@/services/adminApi')>()),
  restoreAdmin: vi.fn<typeof restoreAdmin>(),
  adminRequest: vi.fn<typeof adminRequest>(),
  adminAction: vi.fn<typeof adminAction>(),
}))
const ready: AdminSession = {
  authenticated: true,
  admin: { id: 1, username: 'super_fixture', role: 'SUPER_ADMIN', mustChangePassword: false },
  csrf: { headerName: 'X-ADMIN-CSRF-TOKEN', parameterName: '_admin_csrf', token: 'fixture' },
}
const rows = {
  items: [
    {
      id: 2,
      username: 'private_admin_name',
      role: 'OPS_ADMIN',
      status: 'ACTIVE',
      mustChangePassword: false,
      version: 3,
    },
  ],
  page: 1,
  size: 20,
  total: 1,
}
let app: App | undefined, host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 40; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount(section = 'accounts') {
  app = createApp(View, { section })
  app.mount(host)
}
function fill(name: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(`input[name="${name}"]`)!
  expect(input).not.toBeNull()
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}
function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
  expect(button).toBeDefined()
  button.click()
}
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(restoreAdmin).mockResolvedValue(structuredClone(ready))
  vi.mocked(adminRequest).mockResolvedValue(rows)
  vi.stubGlobal(
    'confirm',
    vi.fn(() => true),
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  vi.unstubAllGlobals()
})
it('forces first password change before listing any account or audit data', async () => {
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...ready,
    admin: { ...ready.admin!, mustChangePassword: true },
  })
  mount()
  await settle()
  expect(host.textContent).toContain('请先修改初始或重置密码')
  expect(adminRequest).not.toHaveBeenCalled()
  expect(host.querySelector('[data-testid="admin-create"]')).toBeNull()
  fill('currentPassword', 'public-current')
  fill('newPassword', 'public-replacement')
  vi.mocked(adminAction).mockResolvedValue(undefined)
  vi.mocked(restoreAdmin).mockResolvedValue({ ...ready, authenticated: false, admin: null })
  host
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
  expect(adminAction).toHaveBeenCalledWith(
    '/auth/password/change',
    { currentPassword: 'public-current', password: 'public-replacement' },
    expect.objectContaining({ csrf: ready.csrf }),
    'POST',
  )
  expect(host.querySelector('[data-testid="admin-login"]')).not.toBeNull()
  expect(host.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('')
})
it('does not request privileged data for either limited role on a directly visited URL', async () => {
  for (const role of ['CONTENT_REVIEWER', 'OPS_ADMIN'] as const) {
    vi.mocked(restoreAdmin).mockResolvedValue({ ...ready, admin: { ...ready.admin!, role } })
    mount()
    await settle()
    expect(host.textContent).not.toContain('private_admin_name')
    expect(host.querySelector('table')).toBeNull()
    app!.unmount()
    app = undefined
  }
  expect(adminRequest).not.toHaveBeenCalled()
})
it('sends one role, current version and explicit reason after confirmation, then clears passwords on denial', async () => {
  mount()
  await settle()
  fill('reason', 'fixture reason')
  fill('initialPassword', 'public-secret-fixture')
  vi.mocked(adminAction).mockRejectedValue(new AdminRequestError(403))
  click('重置密码')
  await settle()
  expect(confirm).toHaveBeenCalledOnce()
  expect(adminAction).toHaveBeenCalledWith(
    '/accounts/2/password/reset',
    expect.objectContaining({
      expectedVersion: 3,
      reason: 'fixture reason',
      password: 'public-secret-fixture',
    }),
    ready,
    'POST',
  )
  expect(host.textContent).not.toContain('private_admin_name')
  expect(host.textContent).toContain('当前账号没有此权限')
  expect(host.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('')
})
it('drops a late account response when focus discovers a revoked session', async () => {
  let deliver!: (v: typeof rows) => void
  vi.mocked(adminRequest).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        deliver = resolve
      }),
  )
  mount()
  await settle()
  vi.mocked(restoreAdmin).mockResolvedValue({ ...ready, authenticated: false, admin: null })
  window.dispatchEvent(new Event('focus'))
  await settle()
  deliver(rows)
  await settle()
  expect(host.textContent).not.toContain('private_admin_name')
  expect(host.querySelector('[data-testid="admin-login"]')).not.toBeNull()
})
it('clears visible data and ignores further data after an authorization failure during reload', async () => {
  mount()
  await settle()
  expect(host.textContent).toContain('private_admin_name')
  vi.mocked(adminRequest).mockRejectedValue(new AdminRequestError(401))
  click('刷新')
  await settle()
  expect(host.textContent).not.toContain('private_admin_name')
  expect(host.textContent).toContain('登录已失效')
})
it('renders audit reasons as text, preventing stored HTML from executing', async () => {
  vi.mocked(adminRequest).mockResolvedValue({
    items: [
      {
        id: 'event',
        occurredAt: '2026-10-08T01:00:00',
        action: 'ADMIN_CREATE',
        actorAdminId: 1,
        targetId: '2',
        outcome: 'SUCCESS',
        reason: '<img src=x onerror=alert(1)>',
        correlationId: 'request-id',
        beforeState: null,
        afterState: 'ACTIVE',
      },
    ],
    page: 1,
    total: 1,
    size: 20,
  })
  mount('audit')
  await settle()
  expect(host.textContent).toContain('<img src=x onerror=alert(1)>')
  expect(host.querySelector('img')).toBeNull()
  expect(adminRequest).toHaveBeenCalledWith('/audit-events?page=1&size=20', expect.anything())
})
