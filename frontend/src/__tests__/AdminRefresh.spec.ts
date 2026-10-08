// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest'
import { adminAction, refreshAdmin, restoreAdmin } from '@/services/adminApi'
const csrf = {
  headerName: 'X-ADMIN-CSRF-TOKEN',
  parameterName: '_admin_csrf',
  token: 'admin-only-csrf',
}
const session = (authenticated: boolean) => ({
  authenticated,
  admin: authenticated
    ? { id: 1, username: 'fixture', role: 'SUPER_ADMIN' as const, mustChangePassword: false }
    : null,
  csrf,
})
const response = (authenticated: boolean) =>
  new Response(JSON.stringify(session(authenticated)), {
    headers: { 'Content-Type': 'application/json' },
  })
function locks() {
  const request = vi.fn<(_name: string, perform: () => Promise<unknown>) => Promise<unknown>>(
    async (_name, perform) => perform(),
  )
  vi.stubGlobal('navigator', { locks: { request } })
  return request
}
afterEach(() => vi.unstubAllGlobals())
it('coordinates only the admin refresh domain and retries once with the admin CSRF', async () => {
  const lock = locks(),
    answers = [
      new Response(null, { status: 401 }),
      response(false),
      response(true),
      new Response(null, { status: 204 }),
    ]
  const fetch = vi.fn<typeof globalThis.fetch>(async () => answers.shift()!)
  vi.stubGlobal('fetch', fetch)
  await adminAction('/accounts/2/disable', { expectedVersion: 3, reason: 'fixture' }, session(true))
  expect(lock).toHaveBeenCalledWith('forgeoj-admin-refresh', expect.any(Function))
  expect(fetch).toHaveBeenCalledTimes(4)
  expect(fetch).toHaveBeenNthCalledWith(
    3,
    '/api/v1/admin/auth/refresh',
    expect.objectContaining({
      headers: expect.objectContaining({ 'X-ADMIN-CSRF-TOKEN': csrf.token }),
    }),
  )
  expect(
    fetch.mock.calls.every((call) => String((call as unknown[])[0]).startsWith('/api/v1/admin/')),
  ).toBe(true)
})
it('shares a concurrent refresh and notices a different tab has already renewed', async () => {
  locks()
  const fetch = vi.fn<typeof globalThis.fetch>(async () => response(true))
  vi.stubGlobal('fetch', fetch)
  const first = refreshAdmin(),
    second = refreshAdmin()
  expect(first).toBe(second)
  expect((await first).authenticated).toBe(true)
  await second
  expect(fetch).toHaveBeenCalledOnce()
})
it('returns to login without racing refresh cookies when Web Locks is unavailable', async () => {
  vi.stubGlobal('navigator', {})
  const fetch = vi.fn<typeof globalThis.fetch>(async () => response(false))
  vi.stubGlobal('fetch', fetch)
  expect((await restoreAdmin()).authenticated).toBe(false)
  expect(fetch).toHaveBeenCalledOnce()
})
it('does not automatically retry a failed durable write', async () => {
  locks()
  const fetch = vi.fn<typeof globalThis.fetch>(async () => new Response(null, { status: 503 }))
  vi.stubGlobal('fetch', fetch)
  await expect(
    adminAction('/accounts', { username: 'fixture' }, session(true)),
  ).rejects.toMatchObject({ status: 503 })
  expect(fetch).toHaveBeenCalledOnce()
})
