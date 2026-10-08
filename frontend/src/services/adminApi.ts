// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
export type AdminRole = 'CONTENT_REVIEWER' | 'OPS_ADMIN' | 'SUPER_ADMIN'
export interface AdminSession {
  authenticated: boolean
  admin: { id: number; username: string; role: AdminRole; mustChangePassword: boolean } | null
  csrf: { headerName: string; parameterName: string; token: string }
}
export interface AdminAccount {
  id: number
  username: string
  role: AdminRole
  status: 'ACTIVE' | 'DISABLED'
  mustChangePassword: boolean
  version: number
}
export interface AdminEvent {
  id: string
  occurredAt: string
  actorType: string
  actorAdminId: number | null
  action: string
  targetType: string
  targetId: string | null
  outcome: string
  reason: string
  beforeState: string | null
  afterState: string | null
  correlationId: string
}
export interface AdminPage<T> {
  items: T[]
  page: number
  size: number
  total: number
}
export class AdminRequestError extends Error {
  constructor(public readonly status: number) {
    super(`Admin request failed: ${status}`)
  }
}
const base = '/api/v1/admin'
async function raw<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(base + path, { credentials: 'same-origin', ...init })
  if (!response.ok) throw new AdminRequestError(response.status)
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>)
}
export function adminSession(signal?: AbortSignal): Promise<AdminSession> {
  return raw('/auth/session', { signal })
}
export function adminHeaders(session: AdminSession): Record<string, string> {
  return { 'Content-Type': 'application/json', [session.csrf.headerName]: session.csrf.token }
}
let refreshing: Promise<AdminSession> | undefined
export function refreshAdmin(): Promise<AdminSession> {
  if (refreshing) return refreshing
  if (!navigator.locks) return Promise.reject(new AdminRequestError(401))
  refreshing = navigator.locks
    .request('forgeoj-admin-refresh', async () => {
      const current = await adminSession()
      if (current.authenticated) return current
      return raw<AdminSession>('/auth/refresh', {
        method: 'POST',
        headers: adminHeaders(current),
        body: '{}',
      })
    })
    .finally(() => {
      refreshing = undefined
    })
  return refreshing
}
export async function restoreAdmin(signal?: AbortSignal): Promise<AdminSession> {
  const current = await adminSession(signal)
  if (current.authenticated || !navigator.locks) return current
  try {
    return await refreshAdmin()
  } catch (failure) {
    if (failure instanceof AdminRequestError && failure.status === 401) return current
    throw failure
  }
}
export async function adminRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  try {
    return await raw<T>(path, init)
  } catch (failure) {
    if (
      !(failure instanceof AdminRequestError) ||
      failure.status !== 401 ||
      path.startsWith('/auth/')
    )
      throw failure
    const session = await refreshAdmin()
    if (!session.authenticated) throw new AdminRequestError(401)
    return raw<T>(path, init)
  }
}
export function adminAction<T>(
  path: string,
  body: unknown,
  session: AdminSession,
  method = 'POST',
): Promise<T> {
  return adminRequest(path, { method, headers: adminHeaders(session), body: JSON.stringify(body) })
}
