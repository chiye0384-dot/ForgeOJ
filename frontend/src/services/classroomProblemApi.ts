// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { requestJson, jsonHeaders, type CsrfToken } from './forgeojApi'
import type { ContentMetadata } from './contentApi'
export interface PrivateProblem {
  slug: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED'
  version: number
  createdBy: number
  solutionPolicy: 'IMMEDIATE' | 'AFTER_AC'
}
export interface PrivateDetail {
  problem: PrivateProblem
  metadata: ContentMetadata
}
export interface PrivateMaintenance {
  detail: PrivateDetail
  referenceCode: string
  solutionIdea: string
  solutionCode: string
  testCount: number
}
export interface PrivateSolution {
  access: 'LOCKED' | 'IMMEDIATE' | 'AC' | 'EARLY_VIEW'
  idea: string | null
  sourceCode: string | null
}
export function privatePath(room: string, slug = '', suffix = '') {
  return `/api/v1/classrooms/${encodeURIComponent(room)}/problems${slug ? '/' + encodeURIComponent(slug) : ''}${suffix}`
}
export function privateRead<T>(room: string, slug = '', suffix = '') {
  return requestJson<T>(privatePath(room, slug, suffix))
}
export function privateWrite<T>(
  room: string,
  slug: string,
  suffix: string,
  body: object,
  csrf: CsrfToken,
) {
  return requestJson<T>(privatePath(room, slug, suffix), {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify(body),
  })
}
