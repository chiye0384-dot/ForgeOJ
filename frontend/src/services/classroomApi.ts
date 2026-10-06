// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { jsonHeaders, requestJson, type CsrfToken } from './forgeojApi'

export interface ClassroomSummary {
  id: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED'
  version: number
  role: 'OWNER' | 'ASSISTANT' | 'MEMBER'
  memberStatus: 'ACTIVE' | 'LEFT' | 'REMOVED'
}
export interface Member {
  userId: number
  username: string
  role: 'OWNER' | 'ASSISTANT' | 'MEMBER'
  status: 'ACTIVE' | 'LEFT' | 'REMOVED'
}
export interface Transfer {
  id: string
  targetUserId: number
  fromUserId: number
  status: 'PENDING' | 'ACCEPTED' | 'WITHDRAWN'
}
export interface ClassroomDetail {
  id: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED'
  ownerId: number
  version: number
  role: 'OWNER' | 'ASSISTANT' | 'MEMBER'
  inviteEnabled: boolean
  members: Member[]
  pendingTransfer: Transfer | null
}
export function getClassrooms(page: number) {
  return requestJson<{ items: ClassroomSummary[]; total: number }>(
    `/api/v1/me/classrooms?page=${page}&size=20`,
  )
}
export function getClassroom(id: string) {
  return requestJson<ClassroomDetail>(`/api/v1/classrooms/${encodeURIComponent(id)}`)
}
export function classroomWrite<T>(
  path: string,
  body: object | undefined,
  csrf: CsrfToken,
  method = 'POST',
) {
  return requestJson<T>(`/api/v1/classrooms${path}`, {
    method,
    headers: jsonHeaders(csrf),
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
}
