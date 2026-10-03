/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
import { jsonHeaders, requestJson, type CsrfToken } from './forgeojApi'

export interface SolutionAccess {
  judgeVersion: number
  access: 'UNAVAILABLE' | 'LOCKED' | 'AC' | 'EARLY_VIEW'
  solution: { idea: string; language: 'JAVA_21'; sourceCode: string } | null
}

export async function readSolution(slug: string): Promise<SolutionAccess> {
  return requestJson(`/api/v1/me/problems/${encodeURIComponent(slug)}/solution`)
}
export async function confirmSolution(
  slug: string,
  judgeVersion: number,
  csrf: CsrfToken,
): Promise<SolutionAccess> {
  return requestJson(`/api/v1/me/problems/${encodeURIComponent(slug)}/solution/early-view`, {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify({ judgeVersion, confirmEarlyView: true }),
  })
}
export function validSolution(value: SolutionAccess, expectedVersion: number): boolean {
  if (!value || value.judgeVersion !== expectedVersion) return false
  if (value.access === 'UNAVAILABLE' || value.access === 'LOCKED') return value.solution === null
  if (value.access !== 'AC' && value.access !== 'EARLY_VIEW') return false
  return (
    value.solution?.language === 'JAVA_21' &&
    typeof value.solution.idea === 'string' &&
    typeof value.solution.sourceCode === 'string'
  )
}
