// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { requestJson, jsonHeaders, type CsrfToken } from './forgeojApi'
import type { ContentMetadata } from './contentApi'
import type { Member } from './classroomApi'
export interface AssignmentDefinition {
  title: string
  description: string
  deadlineAt: string
  acceptExistingAc: boolean
  allowLate: boolean
  solutionPolicy: 'IMMEDIATE' | 'AFTER_AC' | 'AFTER_DEADLINE'
  problemSlugs: string[]
}
export interface AssignmentSummary extends Omit<
  AssignmentDefinition,
  'description' | 'problemSlugs'
> {
  id: string
  status: 'DRAFT' | 'SCHEDULED' | 'ACTIVE' | 'ENDED' | 'CANCELLED' | 'STOPPED'
  version: number
  startsAt: string | null
  startedAt: string | null
  endedAt: string | null
  closeReason: string | null
}
export interface AssignmentGrade {
  state: 'NOT_STARTED' | 'ATTEMPTING' | 'PRECOMPLETED' | 'ON_TIME_AC' | 'LATE_AC'
  attempts: number
  completionSubmissionId: string | null
  firstAcSubmissionId: string | null
  firstAcAt: string | null
}
export interface AssignmentItem {
  ordinal: number
  slug: string | null
  metadata: (Omit<ContentMetadata, 'originType'> & { originType: string }) | null
  judgeVersionId: number
  grade: AssignmentGrade | null
}
export interface AssignmentDetail {
  assignment: AssignmentSummary
  description: string
  member: boolean
  teaching: boolean
  participating: boolean
  problems: AssignmentItem[]
  eligibleMembers: Member[]
}
export interface AssignmentPage {
  items: AssignmentSummary[]
  page: number
  size: number
  total: number
  classroomTitle: string
  classroomStatus: string
  member: boolean
  teaching: boolean
}
export interface AssignmentSolution {
  access: string
  idea: string | null
  sourceCode: string | null
}
export function assignmentPath(room: string, id = '', suffix = '') {
  return `/api/v1/classrooms/${encodeURIComponent(room)}/assignments${id ? '/' + encodeURIComponent(id) : ''}${suffix}`
}
export function assignmentRead<T>(room: string, id = '', suffix = '') {
  return requestJson<T>(assignmentPath(room, id, suffix))
}
export function assignmentWrite<T>(
  room: string,
  id: string,
  suffix: string,
  body: object,
  csrf: CsrfToken,
  method = 'POST',
) {
  return requestJson<T>(assignmentPath(room, id, suffix), {
    method,
    headers: jsonHeaders(csrf),
    body: JSON.stringify(body),
  })
}
