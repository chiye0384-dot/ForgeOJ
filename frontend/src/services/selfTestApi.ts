/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
import { jsonHeaders, requestJson, type CsrfToken } from './forgeojApi'

export interface SelfTestRequest {
  requestId: string
  language: 'JAVA_21'
  sourceCode: string
  input: string
}
export interface SelfTestRun {
  runId: string
  problemSlug: string
  judgeVersion: number
  processingStatus: 'QUEUED' | 'RUNNING' | 'FINISHED' | 'CANCELLED' | 'SYSTEM_ERROR'
  statusVersion: number
  executionResult: string | null
  expiresAt: string | null
}
export interface SelfTestDetail {
  run: SelfTestRun
  sourceCode: string
  input: string
  output: string | null
}
export interface SelfTestPage {
  items: SelfTestRun[]
  page: number
  size: number
  total: number
}
export function createSelfTest(
  slug: string,
  body: SelfTestRequest,
  csrf: CsrfToken,
): Promise<SelfTestRun> {
  return requestJson(`/api/v1/problems/${encodeURIComponent(slug)}/self-tests`, {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify(body),
  })
}
export function readSelfTest(id: string): Promise<SelfTestDetail> {
  return requestJson(`/api/v1/self-tests/${encodeURIComponent(id)}`)
}
export function listSelfTests(slug: string, page: number): Promise<SelfTestPage> {
  return requestJson(
    `/api/v1/me/self-tests?problemSlug=${encodeURIComponent(slug)}&page=${page}&size=10`,
  )
}
export function cancelSelfTest(id: string, csrf: CsrfToken): Promise<SelfTestRun> {
  return requestJson(`/api/v1/self-tests/${encodeURIComponent(id)}/cancel`, {
    method: 'POST',
    headers: jsonHeaders(csrf),
  })
}
export function terminalSelfTest(run: SelfTestRun): boolean {
  return ['FINISHED', 'CANCELLED', 'SYSTEM_ERROR'].includes(run.processingStatus)
}
export function validSelfTest(run: SelfTestRun, slug: string): boolean {
  return (
    !!run &&
    /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(run.runId) &&
    run.problemSlug === slug &&
    Number.isSafeInteger(run.judgeVersion) &&
    run.judgeVersion > 0 &&
    Number.isSafeInteger(run.statusVersion) &&
    run.statusVersion >= 0 &&
    ['QUEUED', 'RUNNING', 'FINISHED', 'CANCELLED', 'SYSTEM_ERROR'].includes(run.processingStatus) &&
    (run.processingStatus === 'FINISHED'
      ? [
          'SUCCESS',
          'COMPILE_ERROR',
          'RUNTIME_ERROR',
          'TIME_LIMIT_EXCEEDED',
          'OUTPUT_LIMIT_EXCEEDED',
          'MEMORY_LIMIT_EXCEEDED',
          'SECURITY_VIOLATION',
        ].includes(run.executionResult ?? '')
      : run.executionResult === null)
  )
}
