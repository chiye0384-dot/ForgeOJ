// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { jsonHeaders, requestJson, type CsrfToken } from './forgeojApi'
import type { AuthoredDetail } from './contentApi'
export interface PublishedProblem {
  id: number
  slug: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED'
  version: number
  dataInvalid: boolean
}
const root = '/api/v1/me/public-problems'
export function publishedList(page: number) {
  return requestJson<{ items: PublishedProblem[]; total: number }>(`${root}?page=${page}&size=20`)
}
export function copyPublicRevision(slug: string, body: unknown, csrf: CsrfToken) {
  return requestJson<AuthoredDetail>(`${root}/${encodeURIComponent(slug)}/revisions`, {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify(body),
  })
}
