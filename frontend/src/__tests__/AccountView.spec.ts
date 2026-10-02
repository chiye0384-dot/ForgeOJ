import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import AccountView from '../views/AccountView.vue'

const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture-csrf' }
async function settle(): Promise<void> {
  for (let i = 0; i < 30; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function type(host: HTMLElement, name: string, value: string): void {
  const input = host.querySelector(`[name="${name}"]`) as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}
afterEach(() => {
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})
describe('account pages', () => {
  it('submits registration through CSRF and shows a generic mail response', async () => {
    const fetch = vi.fn<(path: RequestInfo | URL) => Promise<Response>>(
      async (path: RequestInfo | URL) =>
        new Response(
          JSON.stringify(
            String(path).endsWith('/session')
              ? { authenticated: true, user: { id: 1, username: 'existing' }, csrf }
              : { message: 'generic' },
          ),
          {
            status: String(path).endsWith('/register') ? 202 : 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'),
      app = createApp(AccountView)
    app.mount(host)
    await settle()
    type(host, 'username', 'NewLearner')
    type(host, 'email', 'new@example.test')
    type(host, 'password', 'account-fixture-password')
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/register',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'fixture-csrf' }),
      }),
    )
    expect(host.textContent).toContain('如果信息符合条件')
    expect((host.querySelector('[name="password"]') as HTMLInputElement).value).toBe('')
    app.unmount()
  })
  it('removes mail token from address and sends it only on confirmation', async () => {
    window.history.replaceState(null, '', '/account#action=activate&token=fixture-mail-secret')
    const fetch = vi.fn<(path: RequestInfo | URL) => Promise<Response>>(
      async (path: RequestInfo | URL) =>
        String(path).endsWith('/confirm')
          ? new Response(null, { status: 204 })
          : new Response(
              JSON.stringify({ authenticated: true, user: { id: 1, username: 'existing' }, csrf }),
              { headers: { 'Content-Type': 'application/json' } },
            ),
    )
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'),
      app = createApp(AccountView)
    app.mount(host)
    await settle()
    expect(window.location.hash).toBe('')
    expect(host.textContent).not.toContain('fixture-mail-secret')
    expect(fetch).toHaveBeenCalledTimes(1)
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/email-verification/confirm',
      expect.objectContaining({ body: JSON.stringify({ token: 'fixture-mail-secret' }) }),
    )
    expect(host.textContent).toContain('操作成功')
    expect((host.querySelector('form button') as HTMLButtonElement).disabled).toBe(true)
    app.unmount()
  })
})
