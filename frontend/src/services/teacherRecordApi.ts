// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { assignmentRead, type AssignmentSummary } from '@/services/assignmentApi'

export interface TeacherGrade {
  ordinal: number
  slug: string
  title: string
  judgeVersionId: number
  state: string
  attempts: number
  firstAcAt: string | null
}
export interface TeacherParticipant {
  userId: number
  username: string
  memberStatus: string
  role: string
  completed: number
  problems: TeacherGrade[]
}
export interface TeacherGrades {
  assignment: AssignmentSummary
  classroomTitle: string
  classroomStatus: string
  items: TeacherParticipant[]
  page: number
  size: number
  total: number
}
export interface TeacherAttempt {
  submissionId: string
  userId: number
  ordinal: number
  problemSlug: string
  judgeVersionId: number
  language: string
  processingStatus: string
  verdict: string | null
  acceptedAt: string
  finishedAt: string | null
}
export interface TeacherAttempts {
  items: TeacherAttempt[]
  page: number
  size: number
  total: number
}
export interface TeacherSource {
  submission: TeacherAttempt
  sourceCode: string
  sourceSha256: string
}
export function teacherRead<T>(room: string, assignment: string, suffix: string) {
  return assignmentRead<T>(room, assignment, `/teaching${suffix}`)
}
