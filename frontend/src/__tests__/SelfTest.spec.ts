/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import SelfTest from '@/components/SelfTest.vue'
import {
  createSelfTest,
  readSelfTest,
  listSelfTests,
  cancelSelfTest,
  validSelfTest,
  type SelfTestRun,
  type SelfTestDetail,
} from '@/services/selfTestApi'
import { ApiRequestError } from '@/services/forgeojApi'

vi.mock('@/services/selfTestApi', async (original) => ({
  ...(await original<typeof import('@/services/selfTestApi')>()),
  createSelfTest: vi.fn<typeof createSelfTest>(),
  readSelfTest: vi.fn<typeof readSelfTest>(),
  listSelfTests: vi.fn<typeof listSelfTests>(),
  cancelSelfTest: vi.fn<typeof cancelSelfTest>(),
}))
const create = vi.mocked(createSelfTest),
  read = vi.mocked(readSelfTest),
  list = vi.mocked(listSelfTests),
  cancel = vi.mocked(cancelSelfTest)
const queued: SelfTestRun = {
  runId: '37fc39db-9e08-4d7f-a912-4810cfa81db1',
  problemSlug: 'sum-two-integers',
  judgeVersion: 1,
  processingStatus: 'QUEUED',
  statusVersion: 0,
  executionResult: null,
  expiresAt: null,
}
const success: SelfTestRun = {
  ...queued,
  processingStatus: 'FINISHED',
  statusVersion: 2,
  executionResult: 'SUCCESS',
  expiresAt: '2026-10-06 00:00:00',
}
const snapshot: SelfTestDetail = {
  run: success,
  sourceCode: 'public class Main { /* frozen */ }',
  input: '1 2\n',
  output: '<img src=x onerror=alert(1)>\n',
}
let app: App | undefined, host: HTMLDivElement
function mount() {
  const props = reactive({
    slug: queued.problemSlug,
    sourceCode: snapshot.sourceCode,
    userId: 1,
    csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(SelfTest, props) })
  app.mount(host)
  return props
}
async function settle() {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function click(label: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)
  expect(button).toBeDefined()
  button!.click()
  await settle()
}
async function input(text: string) {
  const field = host.querySelector<HTMLTextAreaElement>('#self-test-input')!
  field.value = text
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await settle()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.useRealTimers()
  vi.resetAllMocks()
})
describe('independent self-test', () => {
  it('freezes click input/code, renders text inertly and never claims formal AC', async () => {
    let resolve!: (value: SelfTestRun) => void
    create.mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    read.mockResolvedValue(snapshot)
    list.mockResolvedValue({ items: [success], page: 1, size: 10, total: 1 })
    const props = mount()
    await input('1 2\n')
    await click('运行自测')
    props.sourceCode = 'later local code'
    await input('9 9\n')
    resolve(queued)
    await settle()
    expect(create).toHaveBeenCalledWith(
      queued.problemSlug,
      expect.objectContaining({
        sourceCode: snapshot.sourceCode,
        input: '1 2\n',
        language: 'JAVA_21',
      }),
      expect.anything(),
    )
    expect(host.textContent).toContain('SUCCESS')
    expect(host.textContent).toContain(snapshot.sourceCode)
    expect(host.querySelector('img')).toBeNull()
    expect(host.textContent).toContain('不计为通过题目')
  })
  it('ambiguous failure retries exact UUID and payload despite local edits', async () => {
    create.mockRejectedValueOnce(new ApiRequestError(503)).mockResolvedValueOnce(queued)
    read.mockResolvedValue(snapshot)
    list.mockResolvedValue({ items: [], page: 1, size: 10, total: 0 })
    const props = mount()
    await input('1 2\n')
    await click('运行自测')
    const original = create.mock.calls[0]![1]
    props.sourceCode = 'later'
    await input('new input')
    await click('重试原自测请求')
    expect(create.mock.calls[1]![1]).toEqual(original)
  })
  it('identity change discards late create/detail and unmount stops polling', async () => {
    vi.useFakeTimers()
    let resolve!: (value: SelfTestDetail) => void
    create.mockResolvedValue(queued)
    read.mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    const props = mount()
    await click('运行自测')
    props.userId = 2
    await settle()
    resolve(snapshot)
    await settle()
    expect(host.textContent).not.toContain('inert')
    expect(host.querySelector('[data-testid=self-test-result]')).toBeNull()
    app?.unmount()
    app = undefined
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenCalledTimes(1)
  })
  it('queued cancellation uses independent ID and terminal cancels polling', async () => {
    vi.useFakeTimers()
    create.mockResolvedValue(queued)
    read.mockResolvedValueOnce({ ...snapshot, run: queued, output: null }).mockResolvedValueOnce({
      ...snapshot,
      run: { ...queued, processingStatus: 'CANCELLED', statusVersion: 1 },
      output: null,
    })
    cancel.mockResolvedValue({ ...queued, processingStatus: 'CANCELLED', statusVersion: 1 })
    list.mockResolvedValue({ items: [], page: 1, size: 10, total: 0 })
    mount()
    await click('运行自测')
    await click('取消排队自测')
    expect(cancel).toHaveBeenCalledWith(queued.runId, expect.objectContaining({ token: 'csrf' }))
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenCalledTimes(2)
  })
  it('bounded polling pauses and manual refresh resumes; expiry exposes no private record', async () => {
    vi.useFakeTimers()
    create.mockResolvedValue(queued)
    read.mockResolvedValue({ ...snapshot, run: queued, output: null })
    mount()
    await click('运行自测')
    for (let i = 0; i < 120; i++) await vi.advanceTimersByTimeAsync(1000)
    await settle()
    expect(host.textContent).toContain('自动刷新已暂停')
    const count = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenCalledTimes(count)
    read.mockRejectedValue(new ApiRequestError(404))
    await click('刷新自测结果')
    expect(host.textContent).toContain('记录已过期')
  })
  it('short history is owner endpoint and explicit selection reads frozen data', async () => {
    list.mockResolvedValue({ items: [success], page: 1, size: 10, total: 1 })
    read.mockResolvedValue(snapshot)
    mount()
    await click('刷新短期自测记录')
    expect(list).toHaveBeenCalledWith(queued.problemSlug, 1)
    await click(`FINISHED · SUCCESS · ${queued.runId.slice(0, 8)}`)
    expect(read).toHaveBeenCalledWith(queued.runId)
    expect(host.textContent).toContain('frozen')
  })
  it('server quota rejection allows a new request and never auto retries', async () => {
    create.mockRejectedValue(new ApiRequestError(429))
    mount()
    await click('运行自测')
    expect(host.textContent).toContain('队列已满')
    expect(host.textContent).not.toContain('重试原自测请求')
    expect(create).toHaveBeenCalledTimes(1)
  })
  it('rejects AC and malformed identities as self-test outcomes', () => {
    expect(validSelfTest(success, queued.problemSlug)).toBe(true)
    expect(validSelfTest({ ...success, executionResult: 'ACCEPTED' }, queued.problemSlug)).toBe(
      false,
    )
    expect(validSelfTest({ ...success, problemSlug: 'another' }, queued.problemSlug)).toBe(false)
    expect(validSelfTest({ ...success, statusVersion: -1 }, queued.problemSlug)).toBe(false)
  })
})
