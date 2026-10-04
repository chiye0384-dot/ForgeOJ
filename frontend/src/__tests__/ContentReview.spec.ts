// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ContentReviewPanel from '@/components/ContentReview.vue'
import { ApiRequestError } from '@/services/forgeojApi'
import { authoredDetail, type AuthoredContent } from '@/services/contentApi'
import { listValidations, type ContentValidation } from '@/services/validationApi'
import {
  submitReview,
  withdrawReview,
  listReviews,
  readReview,
  type ContentReview,
} from '@/services/reviewApi'
vi.mock('@/services/reviewApi', () => ({
  submitReview: vi.fn<typeof submitReview>(),
  withdrawReview: vi.fn<typeof withdrawReview>(),
  listReviews: vi.fn<typeof listReviews>(),
  readReview: vi.fn<typeof readReview>(),
}))
vi.mock('@/services/validationApi', () => ({ listValidations: vi.fn<typeof listValidations>() }))
vi.mock('@/services/contentApi', () => ({ authoredDetail: vi.fn<typeof authoredDetail>() }))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const review = (): ContentReview => ({
  reviewId: 'review-one',
  draftId: 'draft-one',
  draftVersion: 3,
  reviewNo: 1,
  validationJobId: 'passed-one',
  status: 'PENDING',
  version: 0,
})
const validation = (): ContentValidation => ({
  jobId: 'passed-one',
  draftId: 'draft-one',
  draftVersion: 3,
  processingStatus: 'FINISHED',
  statusVersion: 2,
  validationStatus: 'PASSED',
  referenceResult: 'ACCEPTED',
  solutionResult: 'ACCEPTED',
  stale: false,
})
const content: AuthoredContent = {
  metadata: {
    title: 'Frozen original title',
    statement: 'Frozen original statement',
    inputDescription: 'Input',
    outputDescription: 'Output',
    samples: [],
    originType: 'ORIGINAL',
    sourceUrl: '',
    licenseStatement: 'original fixture',
    timeLimitMs: 2000,
    memoryLimitMb: 256,
    outputLimitBytes: 1048576,
  },
  referenceCode: 'private original reference',
  solutionIdea: 'original idea',
  solutionCode: 'private independent solution',
}
let app: App, host: HTMLDivElement
const workflow = vi.fn<(summary: unknown) => void>()
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
  for (let i = 0; i < 16; i++) {
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
  vi.mocked(listValidations).mockResolvedValue({
    items: [validation()],
    page: 1,
    size: 20,
    total: 1,
  })
  vi.mocked(listReviews).mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
  vi.mocked(submitReview).mockResolvedValue(review())
  vi.mocked(authoredDetail).mockResolvedValue({
    draft: { id: 'draft-one', title: 'Original', version: 3, status: 'UNDER_REVIEW', testCount: 1 },
    content,
  })
  app = createApp(() => h(ContentReviewPanel, { ...props, onWorkflow: workflow }))
  app.mount(host)
})
afterEach(() => app.unmount())
describe('immutable author review lifecycle', () => {
  it('only sends current nonstale double-pass saved content and applies authoritative workflow state', async () => {
    vi.mocked(listValidations).mockResolvedValue({
      items: [
        validation(),
        { ...validation(), jobId: 'stale', stale: true },
        {
          ...validation(),
          jobId: 'failed',
          validationStatus: 'FAILED',
          solutionResult: 'WRONG_ANSWER',
        },
      ],
      page: 1,
      size: 20,
      total: 3,
    })
    click('载入通过验证与送审历史')
    await settle()
    expect(host.querySelectorAll('select option')).toHaveLength(2)
    click('送审当前已保存版本')
    await settle()
    expect(submitReview).toHaveBeenCalledWith(
      'draft-one',
      3,
      'passed-one',
      expect.any(String),
      csrf,
    )
    expect(workflow).toHaveBeenCalledWith(expect.objectContaining({ status: 'UNDER_REVIEW' }))
    props.status = 'UNDER_REVIEW'
    await settle()
    click('送审当前已保存版本')
    expect(submitReview).toHaveBeenCalledTimes(1)
  })
  it('retains request ID across uncertain POST and follow-up GET failures without auto retrying conflict', async () => {
    click('载入通过验证与送审历史')
    await settle()
    vi.mocked(submitReview).mockRejectedValueOnce(new Error('network uncertain'))
    click('送审当前已保存版本')
    await settle()
    vi.mocked(authoredDetail).mockRejectedValueOnce(new Error('follow-up unavailable'))
    click('送审当前已保存版本')
    await settle()
    click('送审当前已保存版本')
    await settle()
    const calls = vi.mocked(submitReview).mock.calls
    expect(calls[1]?.[3]).toBe(calls[0]?.[3])
    expect(calls[2]?.[3]).toBe(calls[0]?.[3])
    vi.mocked(submitReview).mockRejectedValueOnce(new ApiRequestError(409))
    click('送审当前已保存版本')
    await settle()
    expect(host.textContent).toContain('本地编辑已保留')
    expect(submitReview).toHaveBeenCalledTimes(4)
  })
  it('drops late private history on identity change', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof listReviews>>) => void
    vi.mocked(listReviews).mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    click('载入通过验证与送审历史')
    props.userId = 2
    props.draftId = 'draft-two'
    await settle()
    resolve({ items: [review()], page: 1, size: 20, total: 1 })
    await settle()
    expect(host.textContent).not.toContain('第 1 次送审')
    expect(host.querySelectorAll('select option')).toHaveLength(1)
  })
  it('keeps withdrawn immutable snapshots readable in archived history', async () => {
    props.status = 'ARCHIVED'
    await settle()
    const withdrawn = { ...review(), status: 'WITHDRAWN' as const, version: 1 }
    vi.mocked(listReviews).mockResolvedValue({ items: [withdrawn], page: 1, size: 20, total: 1 })
    vi.mocked(readReview).mockResolvedValue({ review: withdrawn, content, testCount: 1 })
    click('载入通过验证与送审历史')
    await settle()
    click('查看第 1 次冻结快照')
    await settle()
    expect(host.querySelector('[data-testid="review-snapshot"]')?.textContent).toContain(
      'Frozen original statement',
    )
    click('送审当前已保存版本')
    expect(submitReview).not.toHaveBeenCalled()
    expect(withdrawReview).not.toHaveBeenCalled()
  })
  it('withdraws a particular review and rereads draft state instead of assuming no newer pending review', async () => {
    props.status = 'UNDER_REVIEW'
    await settle()
    vi.mocked(listReviews).mockResolvedValue({ items: [review()], page: 1, size: 20, total: 1 })
    vi.mocked(withdrawReview).mockResolvedValue({ ...review(), status: 'WITHDRAWN', version: 1 })
    click('载入通过验证与送审历史')
    await settle()
    click('撤回第 1 次送审')
    await settle()
    expect(withdrawReview).toHaveBeenCalledWith('draft-one', review(), csrf)
    expect(workflow).toHaveBeenCalledWith(expect.objectContaining({ status: 'UNDER_REVIEW' }))
  })
})
