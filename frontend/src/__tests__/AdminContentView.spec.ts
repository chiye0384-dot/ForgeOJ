// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from '@/views/AdminContentView.vue'
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
  admin: { id: 1, username: 'review_fixture', role: 'CONTENT_REVIEWER', mustChangePassword: false },
  csrf: { headerName: 'X-ADMIN-CSRF-TOKEN', parameterName: '_admin_csrf', token: 'fixture' },
}
const row = { id: 'review-one', title: '原创待审核', status: 'PENDING', version: 0 }
const queue = { items: [row], page: 1, size: 20, total: 1 }
const detail = {
  review: row,
  metadata: {
    title: row.title,
    statement: '冻结题面',
    inputDescription: '输入',
    outputDescription: '输出',
    samples: [],
    originType: 'ORIGINAL',
    sourceUrl: '',
    licenseStatement: '原创许可',
    timeLimitMs: 2000,
    memoryLimitMb: 256,
  },
  solutionIdea: '独立思路',
  solutionCode: '独立代码',
  testCount: 1,
  validation: {
    processingStatus: 'FINISHED',
    validationStatus: 'PASSED',
    referenceResult: 'ACCEPTED',
    solutionResult: 'ACCEPTED',
  },
}
let app: App | undefined, host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 40; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount() {
  app = createApp(View, { section: 'reviews' })
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
  vi.mocked(restoreAdmin).mockResolvedValue(ready)
  vi.mocked(adminRequest).mockImplementation(async (path) =>
    path.endsWith('/reference')
      ? { reviewId: row.id, sourceCode: '敏感参考程序' }
      : path === `/content-reviews/${row.id}`
        ? detail
        : queue,
  )
})
afterEach(() => {
  app?.unmount()
  host.remove()
  app = undefined
})
it('denies OPS and forced-password accounts before requesting content', async () => {
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...ready,
    admin: { ...ready.admin!, role: 'OPS_ADMIN' },
  })
  mount()
  await settle()
  expect(adminRequest).not.toHaveBeenCalled()
  expect(host.textContent).toContain('需要已完成初始改密')
  app!.unmount()
  app = undefined
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...ready,
    admin: { ...ready.admin!, mustChangePassword: true },
  })
  mount()
  await settle()
  expect(adminRequest).not.toHaveBeenCalled()
})
it('requires explicit case-bound reference read and clears it after demotion', async () => {
  mount()
  await settle()
  expect(host.textContent).not.toContain('独立代码')
  click('原创待审核 · PENDING')
  await settle()
  expect(host.textContent).toContain('冻结题面')
  expect(host.textContent).not.toContain('敏感参考程序')
  expect(adminRequest).not.toHaveBeenCalledWith('/content-reviews/review-one/reference')
  click('显式查看本案参考程序（留审计）')
  await settle()
  expect(host.textContent).toContain('敏感参考程序')
  vi.mocked(restoreAdmin).mockResolvedValue({
    ...ready,
    admin: { ...ready.admin!, role: 'OPS_ADMIN' },
  })
  click('刷新列表')
  await settle()
  expect(host.textContent).not.toContain('敏感参考程序')
  expect(host.textContent).not.toContain('冻结题面')
})
it('keeps the same decision request after uncertain failure and clears context after success', async () => {
  mount()
  await settle()
  click('原创待审核 · PENDING')
  await settle()
  const reason = host.querySelector('textarea')!
  reason.value = '审核通过'
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  await settle()
  vi.mocked(adminAction)
    .mockRejectedValueOnce(new AdminRequestError(503))
    .mockResolvedValueOnce(row)
  click('批准并发布')
  await settle()
  expect(host.textContent).toContain('请求未完成')
  click('批准并发布')
  await settle()
  expect(adminAction).toHaveBeenCalledTimes(2)
  expect(vi.mocked(adminAction).mock.calls[0]![1]).toEqual(vi.mocked(adminAction).mock.calls[1]![1])
  expect(host.textContent).not.toContain('冻结题面')
  expect(host.textContent).toContain('处置已保存')
})
it('clears frozen source and reason when the server rejects current permissions', async () => {
  mount()
  await settle()
  click('原创待审核 · PENDING')
  await settle()
  click('显式查看本案参考程序（留审计）')
  await settle()
  const reason = host.querySelector('textarea')!
  reason.value = '处置说明'
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  await settle()
  vi.mocked(adminAction).mockRejectedValue(new AdminRequestError(403))
  click('驳回')
  await settle()
  expect(host.textContent).not.toContain('敏感参考程序')
  expect(host.textContent).not.toContain('独立代码')
  expect(host.querySelector('textarea')).toBeNull()
})
it('disables approval until the latest recheck finishes with both programs accepted', async () => {
  vi.mocked(adminRequest).mockImplementation(async (path) =>
    path === '/content-reviews/review-one'
      ? {
          ...detail,
          validation: {
            ...detail.validation,
            processingStatus: 'RUNNING',
            validationStatus: 'PENDING',
          },
        }
      : queue,
  )
  mount()
  await settle()
  click('原创待审核 · PENDING')
  await settle()
  const reason = host.querySelector('textarea')!
  reason.value = '等待最新重验'
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  await settle()
  const approval = [...host.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === '批准并发布',
  )!
  expect(approval.disabled).toBe(true)
  approval.click()
  await settle()
  expect(adminAction).not.toHaveBeenCalled()
})
