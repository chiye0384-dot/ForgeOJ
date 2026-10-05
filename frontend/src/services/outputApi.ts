// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { jsonHeaders, requestJson, type CsrfToken } from './forgeojApi'

export interface OutputPreview {
  jobId: string
  draftId: string
  draftVersion: number
  processingStatus: 'QUEUED' | 'RUNNING' | 'FINISHED' | 'SYSTEM_ERROR'
  statusVersion: number
  referenceResult: string | null
  stale: boolean
  acceptedVersion: number | null
}
export interface OutputCase {
  sequence: number
  input: string
  previousOutput: string
  generatedOutput: string | null
}
export interface OutputDetail {
  preview: OutputPreview
  cases: OutputCase[]
}
function path(draft: string) {
  return `/api/v1/me/authored-problems/${encodeURIComponent(draft)}/output-previews`
}
function checked(value: OutputPreview, draft: string) {
  if (
    !value ||
    value.draftId !== draft ||
    typeof value.jobId !== 'string' ||
    !Number.isSafeInteger(value.draftVersion) ||
    value.draftVersion < 1 ||
    !Number.isSafeInteger(value.statusVersion) ||
    value.statusVersion < 0 ||
    !['QUEUED', 'RUNNING', 'FINISHED', 'SYSTEM_ERROR'].includes(value.processingStatus) ||
    typeof value.stale !== 'boolean' ||
    !(
      value.acceptedVersion === null ||
      (Number.isSafeInteger(value.acceptedVersion) &&
        value.acceptedVersion === value.draftVersion + 1)
    ) ||
    !(
      value.referenceResult === null ||
      [
        'ACCEPTED',
        'COMPILE_ERROR',
        'RUNTIME_ERROR',
        'TIME_LIMIT_EXCEEDED',
        'OUTPUT_LIMIT_EXCEEDED',
        'MEMORY_LIMIT_EXCEEDED',
        'SECURITY_VIOLATION',
      ].includes(value.referenceResult)
    ) ||
    (value.processingStatus === 'FINISHED') !== (value.referenceResult !== null)
  )
    throw new Error('输出预览状态格式错误。')
  return value
}
export async function createOutput(
  draft: string,
  expectedVersion: number,
  requestId: string,
  csrf: CsrfToken,
) {
  return checked(
    await requestJson<OutputPreview>(path(draft), {
      method: 'POST',
      headers: jsonHeaders(csrf),
      body: JSON.stringify({ expectedVersion, requestId }),
    }),
    draft,
  )
}
export async function readOutput(draft: string, job: string) {
  const value = await requestJson<OutputDetail>(`${path(draft)}/${encodeURIComponent(job)}`)
  checked(value.preview, draft)
  const success =
    value.preview.processingStatus === 'FINISHED' && value.preview.referenceResult === 'ACCEPTED'
  if (
    value.preview.jobId !== job ||
    !Array.isArray(value.cases) ||
    value.cases.length < 1 ||
    value.cases.length > 100
  )
    throw new Error('输出预览数据格式错误。')
  let total = 0
  const bytes = (s: string) => new TextEncoder().encode(s).length
  value.cases.forEach((c, i) => {
    if (
      c.sequence !== i + 1 ||
      typeof c.input !== 'string' ||
      typeof c.previousOutput !== 'string' ||
      (success ? typeof c.generatedOutput !== 'string' : c.generatedOutput !== null) ||
      bytes(c.input) > 1048576 ||
      bytes(c.previousOutput) > 1048576 ||
      (c.generatedOutput !== null && bytes(c.generatedOutput) > 1048576)
    )
      throw new Error('输出预览数据格式错误。')
    total += bytes(c.input) + bytes(c.generatedOutput ?? c.previousOutput)
  })
  if (total > 16 * 1024 * 1024) throw new Error('输出预览数据过大。')
  return value
}
export async function listOutputs(draft: string, page: number) {
  const value = await requestJson<{
    items: OutputPreview[]
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
    throw new Error('输出预览历史格式错误。')
  return { ...value, items: value.items.map((v) => checked(v, draft)) }
}
export async function acceptOutput(
  draft: string,
  job: string,
  expectedVersion: number,
  csrf: CsrfToken,
) {
  const value = await requestJson<{
    jobId: string
    draftId: string
    expectedVersion: number
    appliedVersion: number
  }>(`${path(draft)}/${encodeURIComponent(job)}/accept`, {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify({ expectedVersion }),
  })
  if (
    value.jobId !== job ||
    value.draftId !== draft ||
    value.expectedVersion !== expectedVersion ||
    value.appliedVersion !== expectedVersion + 1
  )
    throw new Error('输出确认结果格式错误。')
  return value
}
