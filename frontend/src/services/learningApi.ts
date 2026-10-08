// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { jsonHeaders, requestJson, type CsrfToken, type ProblemListItem } from './forgeojApi'

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}
export interface ListSummary {
  id: string
  title: string
  description?: string
  version: number
  entryCount: number
  availableCount: number
  completedCount?: number
  unavailableCount: number
}
export interface ListEntry {
  itemId: string
  position: number
  available: boolean
  problem?: ProblemListItem & { completed?: boolean }
}
export interface ListDetail extends Page<ListEntry> {
  list: ListSummary
}
export interface HistoryItem {
  judgeDataWarning?: string
  submissionId: string
  createdAt: string
  language: string
  processingStatus: string
  statusVersion: number
  verdict: string | null
  judgeVersion: number
  problem: { slug: string; title: string } | null
}
export interface CodeDraft {
  language: string
  sourceCode: string | null
  version: number
  updatedAt: string | null
  editable: boolean
}
export function getCodeDraft(slug: string): Promise<CodeDraft> {
  return requestJson<CodeDraft>(`/api/v1/me/problems/${encodeURIComponent(slug)}/draft`).then(
    validateDraft,
  )
}
function validateDraft(draft: CodeDraft): CodeDraft {
  if (
    draft.language !== 'JAVA_21' ||
    (draft.sourceCode !== null && typeof draft.sourceCode !== 'string') ||
    !Number.isSafeInteger(draft.version) ||
    draft.version < 0 ||
    typeof draft.editable !== 'boolean'
  )
    throw new Error('草稿响应格式错误')
  return draft
}
export function saveCodeDraft(
  slug: string,
  sourceCode: string,
  expectedVersion: number,
  csrf: CsrfToken,
): Promise<CodeDraft> {
  return requestJson<CodeDraft>(`/api/v1/me/problems/${encodeURIComponent(slug)}/draft`, {
    method: 'PUT',
    headers: jsonHeaders(csrf),
    body: JSON.stringify({ language: 'JAVA_21', sourceCode, expectedVersion }),
  }).then(validateDraft)
}
export function getLists(official: boolean, page: number): Promise<Page<ListSummary>> {
  return requestJson(
    `/api/v1/${official ? 'official-problem-lists' : 'me/problem-lists'}?page=${page}&size=20`,
  )
}
export function getList(
  id: string,
  official: boolean,
  authenticated: boolean,
  page: number,
): Promise<ListDetail> {
  const scope = official
    ? authenticated
      ? 'me/official-problem-lists'
      : 'official-problem-lists'
    : 'me/problem-lists'
  return requestJson(`/api/v1/${scope}/${encodeURIComponent(id)}?page=${page}&size=20`)
}
export function mutateList(
  id: string,
  suffix: string,
  method: string,
  body: unknown,
  csrf: CsrfToken,
): Promise<ListSummary | undefined> {
  return requestJson(`/api/v1/me/problem-lists${id ? '/' + encodeURIComponent(id) : ''}${suffix}`, {
    method,
    headers: jsonHeaders(csrf),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
}
export function getHistory(page: number, slug?: string): Promise<Page<HistoryItem>> {
  const query = new URLSearchParams({ page: String(page), size: '20' })
  if (slug) query.set('problemSlug', slug)
  return requestJson(`/api/v1/me/submissions?${query}`)
}
