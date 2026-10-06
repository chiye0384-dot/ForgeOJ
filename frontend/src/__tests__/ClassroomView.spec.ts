// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import ClassroomView from '@/views/ClassroomView.vue'
import { ApiRequestError, restoreSession } from '@/services/forgeojApi'
import {
  classroomWrite,
  getClassroom,
  getClassrooms,
  type ClassroomDetail,
} from '@/services/classroomApi'
vi.mock('@/services/forgeojApi', async (original) => ({
  ...(await original<typeof import('@/services/forgeojApi')>()),
  restoreSession: vi.fn<typeof restoreSession>(),
}))
vi.mock('@/services/classroomApi', () => ({
  classroomWrite: vi.fn<typeof classroomWrite>(),
  getClassroom: vi.fn<typeof getClassroom>(),
  getClassrooms: vi.fn<typeof getClassrooms>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const account = { authenticated: true, user: { id: 1, username: 'owner' }, csrf }
const room: ClassroomDetail = {
  id: 'fixture-room',
  title: '原创班级',
  status: 'ACTIVE',
  ownerId: 1,
  version: 4,
  role: 'OWNER',
  inviteEnabled: true,
  members: [],
  pendingTransfer: null,
}
let app: App
let host: HTMLDivElement
function pending<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
async function settle() {
  for (let i = 0; i < 35; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(button).toBeDefined()
  button!.click()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(restoreSession).mockResolvedValue(account)
  vi.mocked(getClassrooms).mockResolvedValue({
    items: [{ ...room, memberStatus: 'ACTIVE' }],
    total: 1,
  })
  vi.mocked(getClassroom).mockResolvedValue(room)
  host = document.createElement('div')
  app = createApp(ClassroomView)
  app.mount(host)
})
afterEach(() => app.unmount())
it('uses the displayed CAS version and clears one-time invite on refresh', async () => {
  await settle()
  click('打开班级')
  await settle()
  vi.mocked(classroomWrite).mockResolvedValue({ inviteCode: 'one-time-fixture-code', version: 5 })
  click('生成或轮换邀请码')
  await settle()
  expect(classroomWrite).toHaveBeenCalledWith(
    '/fixture-room/invite',
    { expectedVersion: 4, enabled: true },
    csrf,
    'POST',
  )
  expect(host.textContent).toContain('one-time-fixture-code')
  click('刷新班级')
  await settle()
  expect(host.textContent).not.toContain('one-time-fixture-code')
})
it('does not write under an identity changed in another tab', async () => {
  await settle()
  click('打开班级')
  await settle()
  vi.mocked(restoreSession).mockResolvedValue({ ...account, user: { id: 2, username: 'other' } })
  click('生成或轮换邀请码')
  await settle()
  expect(classroomWrite).not.toHaveBeenCalled()
  expect(host.textContent).toContain('账号已变化')
  expect(host.textContent).not.toContain('生成或轮换邀请码')
})
it('does not show the previous owner invite if identity changes during its response', async () => {
  await settle()
  click('打开班级')
  await settle()
  const write = pending<{ inviteCode: string }>()
  vi.mocked(classroomWrite).mockReturnValue(write.promise)
  click('生成或轮换邀请码')
  await settle()
  vi.mocked(restoreSession).mockResolvedValue({ ...account, user: { id: 2, username: 'other' } })
  vi.mocked(getClassroom).mockRejectedValue(new ApiRequestError(404))
  write.resolve({ inviteCode: 'previous-owner-secret' })
  await settle()
  expect(host.textContent).not.toContain('previous-owner-secret')
  expect(host.textContent).not.toContain('操作成功')
})
it('keeps conflict visible without silently retrying at a newer version', async () => {
  await settle()
  click('打开班级')
  await settle()
  vi.mocked(classroomWrite).mockRejectedValue(new ApiRequestError(409))
  click('关闭邀请码')
  await settle()
  expect(classroomWrite).toHaveBeenCalledTimes(1)
  expect(host.textContent).toContain('版本已变化')
  expect(host.textContent).toContain('版本 4')
})
it('ignores an unmounted detail response', async () => {
  await settle()
  const detail = pending<ClassroomDetail>()
  vi.mocked(getClassroom).mockReturnValue(detail.promise)
  click('打开班级')
  await settle()
  app.unmount()
  detail.resolve(room)
  await settle()
  expect(host.textContent).not.toContain('生成或轮换邀请码')
})
it('requires visible confirmation and cancels it when the displayed version is refreshed', async () => {
  await settle()
  click('打开班级')
  await settle()
  click('归档班级')
  await settle()
  expect(host.textContent).toContain('确认归档')
  expect(classroomWrite).not.toHaveBeenCalled()
  click('取消操作')
  await settle()
  expect(classroomWrite).not.toHaveBeenCalled()
  click('归档班级')
  await settle()
  click('刷新班级')
  await settle()
  expect(host.textContent).not.toContain('确认归档')
  click('归档班级')
  await settle()
  vi.mocked(classroomWrite).mockResolvedValue({})
  click('确认操作')
  await settle()
  expect(classroomWrite).toHaveBeenCalledExactlyOnceWith(
    '/fixture-room/archive',
    { expectedVersion: 4 },
    csrf,
    'POST',
  )
})
