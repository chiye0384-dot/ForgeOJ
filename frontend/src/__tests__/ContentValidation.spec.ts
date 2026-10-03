// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ContentValidation from '@/components/ContentValidation.vue'
import { ApiRequestError } from '@/services/forgeojApi'
import {
  createValidation,
  readValidation,
  listValidations,
  type ContentValidation as Result,
} from '@/services/validationApi'
vi.mock('@/services/validationApi', () => ({
  createValidation: vi.fn<typeof createValidation>(),
  readValidation: vi.fn<typeof readValidation>(),
  listValidations: vi.fn<typeof listValidations>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const queued = (): Result => ({
  jobId: 'job-one',
  draftId: 'draft-one',
  draftVersion: 3,
  processingStatus: 'QUEUED',
  statusVersion: 0,
  validationStatus: null,
  referenceResult: null,
  solutionResult: null,
  stale: false,
})
const passed = (): Result => ({
  ...queued(),
  processingStatus: 'FINISHED',
  statusVersion: 2,
  validationStatus: 'PASSED',
  referenceResult: 'ACCEPTED',
  solutionResult: 'ACCEPTED',
})
let app: App, host: HTMLDivElement
let props: ReturnType<typeof properties>
function properties() {
  return reactive({
    draftId: 'draft-one',
    version: 3,
    status: 'DRAFT',
    userId: 1,
    csrf,
    editingBusy: false,
  })
}
async function settle() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function click(name: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === name)
  expect(button).toBeDefined()
  button!.click()
}
beforeEach(() => {
  vi.resetAllMocks()
  props = properties()
  host = document.createElement('div')
  vi.mocked(createValidation).mockResolvedValue(queued())
  vi.mocked(readValidation).mockResolvedValue(passed())
  app = createApp(() => h(ContentValidation, props))
  app.mount(host)
})
afterEach(() => {
  app.unmount()
})
describe('author immutable content validation', () => {
  it('submits the saved version with CSRF and reports two authoritative results', async () => {
    click('验证当前已保存版本')
    await settle()
    expect(createValidation).toHaveBeenCalledWith('draft-one', 3, expect.any(String), csrf)
    expect(host.textContent).toContain('冻结草稿版本 3')
    expect(host.textContent).toContain('排队中')
    expect(host.querySelector('[data-testid="validation-result"]')?.textContent).not.toContain(
      '验证通过',
    )
    click('刷新验证结果')
    await settle()
    expect(host.textContent).toContain('验证通过')
    expect(host.textContent).toContain('参考程序：通过全部测试；独立题解：通过全部测试')
    props.version = 4
    await settle()
    expect(host.textContent).toContain('此结果属于旧版本')
  })
  it('does not replace failed independent solution with successful reference result', async () => {
    vi.mocked(createValidation).mockResolvedValue({
      ...passed(),
      validationStatus: 'FAILED',
      solutionResult: 'WRONG_ANSWER',
    })
    click('验证当前已保存版本')
    await settle()
    expect(host.textContent).toContain('验证未通过')
    expect(host.textContent).toContain('独立题解：答案错误')
    expect(host.textContent).not.toContain('· 验证通过')
  })
  it('reuses an uncertain POST request ID and never automatically retries a version conflict', async () => {
    vi.mocked(createValidation).mockRejectedValueOnce(new Error('network interrupted'))
    click('验证当前已保存版本')
    await settle()
    click('验证当前已保存版本')
    await settle()
    const calls = vi.mocked(createValidation).mock.calls
    expect(calls[1]?.[2]).toBe(calls[0]?.[2])
    vi.mocked(createValidation).mockRejectedValueOnce(new ApiRequestError(409))
    click('验证当前已保存版本')
    await settle()
    expect(host.textContent).toContain('载入服务端版本')
    expect(createValidation).toHaveBeenCalledTimes(3)
  })
  it('drops late private results when identity changes and cancels further polling', async () => {
    let resolve!: (value: Result) => void
    vi.mocked(createValidation).mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    click('验证当前已保存版本')
    props.userId = 2
    props.draftId = 'draft-two'
    await settle()
    resolve(passed())
    await settle()
    expect(host.textContent).not.toContain('冻结草稿版本')
    expect(readValidation).not.toHaveBeenCalled()
  })
  it('keeps archived history readable and rejects new execution from archived editor', async () => {
    props.status = 'ARCHIVED'
    await settle()
    click('验证当前已保存版本')
    expect(createValidation).not.toHaveBeenCalled()
    vi.mocked(listValidations).mockResolvedValue({ items: [passed()], page: 1, size: 20, total: 1 })
    click('查看验证记录')
    await settle()
    expect(listValidations).toHaveBeenCalledWith('draft-one', 1)
    expect(host.textContent).toContain('版本 3 · 验证通过')
  })
})
