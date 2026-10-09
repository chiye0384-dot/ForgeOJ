// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
export type ExecutionKind = 'FORMAL' | 'VALIDATE' | 'OUTPUT_PREVIEW' | 'SELF_TEST'
export interface OperationsTask {
  kind: ExecutionKind
  id: string
  ownerId: number
  submissionId: string | null
  snapshotId: string | null
  status: string
  version: number
  attemptCount: number
  maxAttempts: number
  failureCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  nextAttemptAt: string | null
  leaseExpired: boolean
  expiresAt: string | null
  executionRecoveryUsed: boolean
}
export interface OperationsAttempt {
  id: string
  number: number
  status: string
  failureCode: string | null
  startedAt: string
  heartbeatAt: string
  leaseExpiresAt: string
  finishedAt: string | null
}
export interface OperationsEvent {
  id: string
  type: string
  sequence: number
  publishAttempts: number
  errorCode: string | null
  nextAttemptAt: string
  lastAttemptAt: string | null
  failedAt: string | null
  publishedAt: string | null
  deliveryRecoveryUsed: boolean
}
export interface RecoveryReceipt {
  id: string
  scope: 'EXECUTION' | 'DELIVERY'
  kind: ExecutionKind
  taskId: string
  targetId: string
  eventId: string
  version: number
  createdAt: string
}
export interface OperationsRecovery {
  id: string
  scope: 'EXECUTION' | 'DELIVERY'
  eventId: string
  previousStatus: string
  previousVersion: number
  previousAttempts: number
  previousMaxAttempts: number
  previousFailureCode: string | null
  previousFinishedAt: string | null
  resultingVersion: number
  createdAt: string
}
