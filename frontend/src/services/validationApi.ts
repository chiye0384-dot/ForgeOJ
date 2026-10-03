// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { requestJson, jsonHeaders, type CsrfToken } from './forgeojApi'
export interface ContentValidation {
  jobId: string
  draftId: string
  draftVersion: number
  processingStatus: 'QUEUED' | 'RUNNING' | 'FINISHED' | 'SYSTEM_ERROR'
  statusVersion: number
  validationStatus: 'PASSED' | 'FAILED' | null
  referenceResult: string | null
  solutionResult: string | null
  stale: boolean
}
const outcomes = new Set([
  'ACCEPTED',
  'WRONG_ANSWER',
  'COMPILE_ERROR',
  'RUNTIME_ERROR',
  'TIME_LIMIT_EXCEEDED',
  'OUTPUT_LIMIT_EXCEEDED',
  'MEMORY_LIMIT_EXCEEDED',
  'SECURITY_VIOLATION',
])
function checked(value: ContentValidation, draft: string): ContentValidation {
  if (
    !value ||
    typeof value.jobId !== 'string' ||
    value.draftId !== draft ||
    !Number.isSafeInteger(value.draftVersion) ||
    value.draftVersion < 1 ||
    !Number.isSafeInteger(value.statusVersion) ||
    value.statusVersion < 0 ||
    typeof value.stale !== 'boolean' ||
    !['QUEUED', 'RUNNING', 'FINISHED', 'SYSTEM_ERROR'].includes(value.processingStatus)
  )
    throw new Error('验证状态格式错误。')
  if (value.processingStatus === 'FINISHED') {
    if (
      !outcomes.has(value.referenceResult ?? '') ||
      !outcomes.has(value.solutionResult ?? '') ||
      value.validationStatus !==
        (value.referenceResult === 'ACCEPTED' && value.solutionResult === 'ACCEPTED'
          ? 'PASSED'
          : 'FAILED')
    )
      throw new Error('验证结果不一致。')
  } else if (
    value.validationStatus !== null ||
    value.referenceResult !== null ||
    value.solutionResult !== null
  )
    throw new Error('验证结果不一致。')
  return value
}
function path(draft: string) {
  return `/api/v1/me/authored-problems/${encodeURIComponent(draft)}/validations`
}
export async function createValidation(
  draft: string,
  expectedVersion: number,
  requestId: string,
  csrf: CsrfToken,
) {
  return checked(
    await requestJson<ContentValidation>(path(draft), {
      method: 'POST',
      headers: jsonHeaders(csrf),
      body: JSON.stringify({ expectedVersion, requestId }),
    }),
    draft,
  )
}
export async function readValidation(draft: string, job: string) {
  return checked(
    await requestJson<ContentValidation>(`${path(draft)}/${encodeURIComponent(job)}`),
    draft,
  )
}
export async function listValidations(draft: string, page: number) {
  const value = await requestJson<{
    items: ContentValidation[]
    page: number
    size: number
    total: number
  }>(`${path(draft)}?page=${page}&size=20`)
  if (
    !value ||
    !Array.isArray(value.items) ||
    value.page !== page ||
    value.size !== 20 ||
    !Number.isSafeInteger(value.total) ||
    value.total < 0 ||
    value.items.length > 20
  )
    throw new Error('验证列表格式错误。')
  return { ...value, items: value.items.map((item) => checked(item, draft)) }
}
