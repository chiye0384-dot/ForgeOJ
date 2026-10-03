// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { jsonHeaders, requestJson, type CsrfToken, type PublicSample } from './forgeojApi'
export interface ContentMetadata {
  title: string
  statement: string
  inputDescription: string
  outputDescription: string
  samples: PublicSample[]
  originType: 'ORIGINAL' | 'ADAPTED'
  sourceUrl: string
  licenseStatement: string
  timeLimitMs: number
  memoryLimitMb: number
  outputLimitBytes: number
}
export interface AuthoredContent {
  metadata: ContentMetadata
  referenceCode: string
  solutionIdea: string
  solutionCode: string
}
export interface AuthoredSummary {
  id: string
  title: string
  version: number
  status: 'DRAFT' | 'ARCHIVED'
  testCount: number
}
export interface AuthoredDetail {
  draft: AuthoredSummary
  content: AuthoredContent
}
export interface AuthoredTest {
  sequence: number
  input: string
  expectedOutput: string
}
const root = '/api/v1/me/authored-problems'
export function authoredList(page: number) {
  return requestJson<{ items: AuthoredSummary[]; total: number }>(`${root}?page=${page}&size=20`)
}
export function authoredDetail(id: string) {
  return requestJson<AuthoredDetail>(`${root}/${encodeURIComponent(id)}`)
}
export function authoredTests(id: string) {
  return requestJson<AuthoredTest[]>(`${root}/${encodeURIComponent(id)}/tests`)
}
export function authoredWrite(
  id: string,
  suffix: string,
  method: string,
  body: unknown,
  csrf: CsrfToken,
) {
  return requestJson<AuthoredDetail>(`${root}${id ? '/' + encodeURIComponent(id) : ''}${suffix}`, {
    method,
    headers: jsonHeaders(csrf),
    body: JSON.stringify(body),
  })
}
export function authoredZip(id: string, expectedVersion: number, file: File, csrf: CsrfToken) {
  return requestJson<AuthoredDetail>(
    `${root}/${encodeURIComponent(id)}/tests/zip?expectedVersion=${expectedVersion}`,
    {
      method: 'PUT',
      headers: { ...jsonHeaders(csrf), 'Content-Type': 'application/zip' },
      body: file,
    },
  )
}
