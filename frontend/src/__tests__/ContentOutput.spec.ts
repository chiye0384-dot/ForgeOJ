// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { createApp, h, nextTick, reactive, type Component } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ContentOutput from '@/components/ContentOutput.vue'
import { ApiRequestError } from '@/services/forgeojApi'
import type { OutputDetail, OutputPreview } from '@/services/outputApi'
import type { createOutput, readOutput, listOutputs, acceptOutput } from '@/services/outputApi'
import type { authoredDetail, authoredTests } from '@/services/contentApi'
const mocks = vi.hoisted(() => ({
  create: vi.fn<typeof createOutput>(),
  read: vi.fn<typeof readOutput>(),
  list: vi.fn<typeof listOutputs>(),
  accept: vi.fn<typeof acceptOutput>(),
  draft: vi.fn<typeof authoredDetail>(),
  tests: vi.fn<typeof authoredTests>(),
}))
vi.mock('@/services/outputApi', () => ({
  createOutput: mocks.create,
  readOutput: mocks.read,
  listOutputs: mocks.list,
  acceptOutput: mocks.accept,
}))
vi.mock('@/services/contentApi', () => ({
  authoredDetail: mocks.draft,
  authoredTests: mocks.tests,
}))
const preview: OutputPreview = {
  jobId: 'job',
  draftId: 'draft',
  draftVersion: 3,
  processingStatus: 'FINISHED',
  statusVersion: 2,
  referenceResult: 'ACCEPTED',
  stale: false,
  acceptedVersion: null,
}
const detail: OutputDetail = {
  preview,
  cases: [{ sequence: 1, input: '1 2\n', previousOutput: 'wrong\n', generatedOutput: '3\n' }],
}
const props = {
  draftId: 'draft',
  version: 3,
  status: 'DRAFT',
  userId: 1,
  csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' },
  editingBusy: false,
}
// Native Vue/DOM harness, matching the existing suite; no added test dependency.
function mount(component: Component, options: { props: typeof props }) {
  const local = reactive({ ...options.props })
  const host = document.createElement('div')
  document.body.appendChild(host)
  const events: Record<string, unknown[][]> = {}
  function record(name: string, args: unknown[]) {
    ;(events[name] ??= []).push(args)
  }
  const app = createApp(() =>
    h(component, {
      ...local,
      onApplied: (...args: unknown[]) => record('applied', args),
      onConflict: (...args: unknown[]) => record('conflict', args),
    }),
  )
  app.mount(host)
  return {
    text: () => host.textContent ?? '',
    findAll: (selector: string) =>
      [...host.querySelectorAll<HTMLButtonElement>(selector)].map((el) => ({
        text: () => el.textContent?.trim(),
        attributes: (name: string) => (el.hasAttribute(name) ? el.getAttribute(name) : undefined),
        trigger: async (_name: string) => {
          el.click()
          await nextTick()
        },
      })),
    find: (selector: string) => ({ exists: () => host.querySelector(selector) !== null }),
    setProps: async (value: Partial<typeof props>) => {
      Object.assign(local, value)
      await nextTick()
    },
    emitted: (name: string) => events[name],
    unmount: () => {
      app.unmount()
      host.remove()
    },
  }
}
async function flushPromises() {
  for (let i = 0; i < 16; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(w: ReturnType<typeof mount>, name: string) {
  const b = w.findAll('button').find((b) => b.text() === name)
  if (!b) throw new Error(name)
  return b
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.create.mockResolvedValue(preview)
  mocks.read.mockResolvedValue(detail)
  mocks.list.mockResolvedValue({ items: [preview], page: 1, size: 20, total: 1 })
  mocks.accept.mockResolvedValue({
    jobId: 'job',
    draftId: 'draft',
    expectedVersion: 3,
    appliedVersion: 4,
  })
  mocks.draft.mockResolvedValue({
    draft: { id: 'draft', title: 'original', version: 4, status: 'DRAFT', testCount: 1 },
    content: {
      metadata: {
        title: 'original',
        statement: 'local',
        inputDescription: 'two integers',
        outputDescription: 'sum',
        samples: [],
        originType: 'ORIGINAL',
        sourceUrl: '',
        licenseStatement: 'original',
        timeLimitMs: 2000,
        memoryLimitMb: 256,
        outputLimitBytes: 1048576,
      },
      referenceCode: 'private reference',
      solutionIdea: 'private idea',
      solutionCode: 'private solution',
    },
  })
  mocks.tests.mockResolvedValue([{ sequence: 1, input: '1 2\n', expectedOutput: '3\n' }])
})
describe('reference output confirmation', () => {
  it('shows generated and previous output and writes only after explicit confirmation', async () => {
    const w = mount(ContentOutput, { props })
    await button(w, '生成当前已保存输入的输出预览').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('wrong')
    expect(w.text()).toContain('3')
    expect(mocks.accept).not.toHaveBeenCalled()
    await button(w, '确认使用这组生成输出').trigger('click')
    await flushPromises()
    expect(mocks.accept).toHaveBeenCalledWith('draft', 'job', 3, props.csrf)
    expect(w.emitted('applied')?.[0]?.[0]).toMatchObject({ version: 4 })
    w.unmount()
  })
  it('cannot confirm queued, failed, stale or archived output', async () => {
    mocks.read.mockResolvedValue({
      preview: { ...preview, processingStatus: 'FINISHED', referenceResult: 'RUNTIME_ERROR' },
      cases: [{ ...detail.cases[0]!, generatedOutput: null }],
    })
    const w = mount(ContentOutput, { props })
    await button(w, '生成当前已保存输入的输出预览').trigger('click')
    await flushPromises()
    expect(button(w, '确认使用这组生成输出').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('无可确认输出')
    mocks.read.mockResolvedValue({ ...detail, preview: { ...preview, stale: true } })
    await button(w, '刷新输出预览').trigger('click')
    await flushPromises()
    expect(button(w, '确认使用这组生成输出').attributes('disabled')).toBeDefined()
    await w.setProps({ status: 'ARCHIVED' })
    expect(button(w, '生成当前已保存输入的输出预览').attributes('disabled')).toBeDefined()
    expect(mocks.accept).not.toHaveBeenCalled()
    w.unmount()
  })
  it('reuses creation request after both uncertain POST and failed follow-up GET', async () => {
    mocks.create.mockRejectedValueOnce(new Error('network uncertain'))
    mocks.read.mockRejectedValueOnce(new Error('GET failed'))
    const w = mount(ContentOutput, { props })
    for (let i = 0; i < 3; i++) {
      await button(w, '生成当前已保存输入的输出预览').trigger('click')
      await flushPromises()
    }
    expect(mocks.create.mock.calls.map((c) => c[2])).toEqual([
      mocks.create.mock.calls[0]?.[2],
      mocks.create.mock.calls[0]?.[2],
      mocks.create.mock.calls[0]?.[2],
    ])
    expect(w.text()).toContain('生成输出')
    w.unmount()
  })
  it('drops late private output when identity changes', async () => {
    let resolve!: (value: OutputDetail) => void
    mocks.read.mockImplementation(
      () =>
        new Promise<OutputDetail>((r) => {
          resolve = r
        }),
    )
    const w = mount(ContentOutput, { props })
    await button(w, '生成当前已保存输入的输出预览').trigger('click')
    await flushPromises()
    await w.setProps({ userId: 2, draftId: 'other' })
    resolve({ ...detail, cases: [{ ...detail.cases[0]!, generatedOutput: 'PRIVATE_LATE_OUTPUT' }] })
    await flushPromises()
    expect(w.text()).not.toContain('PRIVATE_LATE_OUTPUT')
    expect(w.find('[data-testid="output-preview-result"]').exists()).toBe(false)
    w.unmount()
  })
  it('keeps preview after acceptance uncertainty and never auto-overwrites a conflict', async () => {
    const w = mount(ContentOutput, { props })
    await button(w, '生成当前已保存输入的输出预览').trigger('click')
    await flushPromises()
    mocks.accept.mockRejectedValueOnce(new Error('uncertain'))
    await button(w, '确认使用这组生成输出').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('wrong')
    mocks.accept.mockRejectedValueOnce(new ApiRequestError(409))
    await button(w, '确认使用这组生成输出').trigger('click')
    await flushPromises()
    expect(w.emitted('conflict')).toHaveLength(1)
    expect(w.emitted('applied')).toBeUndefined()
    expect(mocks.accept).toHaveBeenCalledTimes(2)
    w.unmount()
  })
})
