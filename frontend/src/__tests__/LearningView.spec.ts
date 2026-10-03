// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import LearningView from '@/views/LearningView.vue'
import { restoreSession, ApiRequestError } from '@/services/forgeojApi'
import {
  getLists,
  getList,
  getHistory,
  mutateList,
  type ListDetail,
  type ListSummary,
  type Page,
} from '@/services/learningApi'
vi.mock('@/services/forgeojApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/forgeojApi')>()),
  restoreSession: vi.fn<typeof restoreSession>(),
}))
vi.mock('@/services/learningApi', () => ({
  getLists: vi.fn<typeof getLists>(),
  getList: vi.fn<typeof getList>(),
  getHistory: vi.fn<typeof getHistory>(),
  mutateList: vi.fn<typeof mutateList>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const summary: ListSummary = {
  id: 'fixture-list',
  title: '本人练习',
  version: 1,
  entryCount: 1,
  availableCount: 1,
  completedCount: 0,
  unavailableCount: 0,
}
const detail: ListDetail = { list: summary, items: [], page: 1, size: 20, total: 0 }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
async function settle() {
  for (let i = 0; i < 30; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
let app: App
let host: HTMLDivElement
function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(button).toBeDefined()
  button!.click()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(restoreSession).mockResolvedValue({
    authenticated: true,
    user: { id: 1, username: 'learner' },
    csrf,
  })
  vi.mocked(getLists).mockResolvedValue({ items: [summary], page: 1, size: 20, total: 1 })
  vi.mocked(getList).mockResolvedValue(detail)
  vi.mocked(getHistory).mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
  host = document.createElement('div')
  app = createApp(LearningView)
  app.mount(host)
})
afterEach(() => {
  app.unmount()
})
describe('learning records UI', () => {
  it('creates a private list with the authenticated CSRF and server-generated ID', async () => {
    await settle()
    const input = host.querySelector('input')!
    input.value = '新题单'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(mutateList).mockResolvedValue(summary)
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    expect(mutateList).toHaveBeenCalledWith('', '', 'POST', { title: '新题单' }, csrf)
    expect(getList).toHaveBeenCalledWith('fixture-list', false, true, 1)
    expect(host.textContent).toContain('题单已更新。')
  })
  it('refreshes on a list conflict without silently repeating the failed mutation', async () => {
    await settle()
    click('本人练习')
    await settle()
    vi.mocked(mutateList).mockRejectedValueOnce(new ApiRequestError(409))
    const form = host.querySelector('.detail form')!
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    expect(mutateList).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('已重新读取，请再次选择')
    expect(getList).toHaveBeenCalledTimes(2)
  })
  it('renders unavailable placeholders without a problem link and reads official personal progress separately', async () => {
    await settle()
    click('官方题单')
    await settle()
    vi.mocked(getList).mockResolvedValueOnce({
      ...detail,
      list: { ...summary, availableCount: 0, unavailableCount: 1 },
      items: [{ itemId: 'unavailable', position: 1, available: false }],
      total: 1,
    })
    click('本人练习')
    await settle()
    expect(getList).toHaveBeenLastCalledWith('fixture-list', true, true, 1)
    expect(host.textContent).toContain('题目暂不可用')
    expect(host.querySelector('.detail ol a')).toBeNull()
    expect(host.textContent).toContain('当前判题版本进度：0 / 0')
  })
  it('ignores late history after switching to official lists', async () => {
    await settle()
    const pending = deferred<Page<never>>()
    vi.mocked(getHistory).mockReturnValueOnce(pending.promise)
    click('提交历史')
    await settle()
    click('官方题单')
    await settle()
    pending.resolve({ items: [], page: 1, size: 20, total: 999 })
    await settle()
    expect(host.textContent).not.toContain('50')
    expect(host.textContent).toContain('本人练习')
    expect(host.textContent).not.toContain('暂无本人提交记录')
  })
})
