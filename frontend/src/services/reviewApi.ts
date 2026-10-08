// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { jsonHeaders, requestJson, type CsrfToken } from './forgeojApi'
import type { AuthoredContent } from './contentApi'
export interface ContentReview {
  reviewId: string
  draftId: string
  draftVersion: number
  reviewNo: number
  validationJobId: string
  status: 'PENDING' | 'WITHDRAWN' | 'APPROVED' | 'REJECTED'
  version: number
  decisionReason?: string
  publishedSlug?: string
}
export interface ReviewDetail {
  review: ContentReview
  content: AuthoredContent
  testCount: number
}
function checked(value: ContentReview, draft: string) {
  if (
    !value ||
    value.draftId !== draft ||
    typeof value.reviewId !== 'string' ||
    typeof value.validationJobId !== 'string' ||
    !Number.isSafeInteger(value.draftVersion) ||
    value.draftVersion < 1 ||
    !Number.isSafeInteger(value.reviewNo) ||
    value.reviewNo < 1 ||
    !(
      (value.status === 'PENDING' && value.version === 0) ||
      (['WITHDRAWN', 'APPROVED', 'REJECTED'].includes(value.status) && value.version === 1)
    )
  )
    throw new Error('送审状态格式错误。')
  return value
}
function path(draft: string) {
  return `/api/v1/me/authored-problems/${encodeURIComponent(draft)}/reviews`
}
export async function submitReview(
  draft: string,
  expectedVersion: number,
  validationJobId: string,
  requestId: string,
  csrf: CsrfToken,
) {
  return checked(
    await requestJson<ContentReview>(path(draft), {
      method: 'POST',
      headers: jsonHeaders(csrf),
      body: JSON.stringify({ expectedVersion, validationJobId, requestId }),
    }),
    draft,
  )
}
export async function withdrawReview(draft: string, review: ContentReview, csrf: CsrfToken) {
  return checked(
    await requestJson<ContentReview>(
      `${path(draft)}/${encodeURIComponent(review.reviewId)}/withdraw`,
      {
        method: 'POST',
        headers: jsonHeaders(csrf),
        body: JSON.stringify({
          expectedVersion: review.draftVersion,
          expectedReviewVersion: review.version,
        }),
      },
    ),
    draft,
  )
}
export async function listReviews(draft: string, page: number) {
  const value = await requestJson<{
    items: ContentReview[]
    page: number
    size: number
    total: number
  }>(`${path(draft)}?page=${page}&size=20`)
  if (
    !value ||
    !Array.isArray(value.items) ||
    value.items.length > 20 ||
    value.page !== page ||
    value.size !== 20 ||
    !Number.isSafeInteger(value.total) ||
    value.total < 0
  )
    throw new Error('送审历史格式错误。')
  return { ...value, items: value.items.map((v) => checked(v, draft)) }
}
export async function readReview(draft: string, review: string) {
  const value = await requestJson<ReviewDetail>(`${path(draft)}/${encodeURIComponent(review)}`)
  checked(value.review, draft)
  if (
    value.review.reviewId !== review ||
    !value.content?.metadata ||
    !Number.isSafeInteger(value.testCount) ||
    value.testCount < 1 ||
    value.testCount > 100
  )
    throw new Error('送审快照格式错误。')
  return value
}
