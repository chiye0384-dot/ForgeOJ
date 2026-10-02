import { afterEach, describe, expect, it, vi } from 'vitest'
import { accountAction, refreshSession, restoreSession } from '../services/forgeojApi'

const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'refresh-fixture-csrf' }
const session = (authenticated: boolean): Response =>
  new Response(
    JSON.stringify({
      authenticated,
      user: authenticated ? { id: 1, username: 'learner' } : null,
      csrf,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  )
function locks(): void {
  vi.stubGlobal('navigator', {
    locks: { request: async (_name: string, perform: () => Promise<unknown>) => perform() },
  })
}
afterEach(() => vi.unstubAllGlobals())

describe('account refresh coordination', () => {
  it('refreshes once before retrying an expired protected account write with unchanged CSRF', async () => {
    locks()
    const responses = [
      new Response(null, { status: 401 }),
      session(false),
      session(true),
      new Response(null, { status: 204 }),
    ]
    const fetch = vi.fn<() => Promise<Response>>(async () => responses.shift()!)
    vi.stubGlobal('fetch', fetch)
    await accountAction('password/change', { currentPassword: 'old', password: 'new' }, csrf)
    expect(fetch.mock.calls).toHaveLength(4)
    expect(fetch).toHaveBeenNthCalledWith(
      3,
      '/api/v1/auth/refresh',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': csrf.token }),
      }),
    )
    expect(fetch).toHaveBeenNthCalledWith(
      4,
      '/api/v1/auth/password/change',
      expect.objectContaining({
        body: JSON.stringify({ currentPassword: 'old', password: 'new' }),
      }),
    )
  })
  it('shares an in-flight refresh and observes another tab already refreshed without rotating again', async () => {
    locks()
    const fetch = vi.fn<() => Promise<Response>>(async () => session(true))
    vi.stubGlobal('fetch', fetch)
    const first = refreshSession(),
      second = refreshSession()
    expect(first).toBe(second)
    expect((await first).authenticated).toBe(true)
    await second
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/session', { credentials: 'same-origin' })
  })
  it('requires login rather than racing shared rotating credentials when Web Locks is unavailable', async () => {
    vi.stubGlobal('navigator', {})
    const fetch = vi.fn<() => Promise<Response>>(async () => session(false))
    vi.stubGlobal('fetch', fetch)
    expect((await restoreSession()).authenticated).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
