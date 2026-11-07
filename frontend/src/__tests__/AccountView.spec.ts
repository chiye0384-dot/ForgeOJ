import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import AccountView from '../views/AccountView.vue'
import { accountReturnPath } from '../router/accountNavigation'
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture-csrf' }
const session = (authenticated = false) => ({
  authenticated,
  user: authenticated ? { id: 1, username: 'learner' } : null,
  csrf,
})
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const apps: App[] = []
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
async function click(host: HTMLElement, text: string): Promise<void> {
  Array.from(host.querySelectorAll('button'))
    .find((b) => b.textContent?.trim() === text)!
    .click()
  await settle()
}
async function mount(path = '/account') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/account/:action(register|forgot|resend)?', component: AccountView },
      { path: '/problems', component: { template: '<div />' } },
      { path: '/problems/:slug', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const host = document.createElement('div'),
    app = createApp(AccountView)
  document.body.append(host)
  apps.push(app)
  app.use(router)
  app.mount(host)
  await settle()
  return { host, router }
}
afterEach(() => {
  apps.splice(0).forEach((app) => app.unmount())
  document.body.replaceChildren()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})
describe('account entry', () => {
  it('defaults to login with only identifier and password, then returns to the requested page', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(async (path) =>
      json(session(String(path).endsWith('/login'))),
    )
    vi.stubGlobal('fetch', fetch)
    const { host, router } = await mount('/account?redirect=/problems/sum-two-integers')
    expect(host.querySelector('h2')?.textContent).toBe('登录账号')
    expect(host.querySelector('[name="email"]')).toBeNull()
    expect(host.querySelector('[name="nickname"]')).toBeNull()
    expect(host.querySelector('[name="password"]')?.getAttribute('autocomplete')).toBe(
      'current-password',
    )
    type(host, 'username', 'learner')
    type(host, 'password', 'existing-fixture-password')
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/login',
      expect.objectContaining({
        body: JSON.stringify({ username: 'learner', password: 'existing-fixture-password' }),
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': csrf.token }),
      }),
    )
    expect(router.currentRoute.value.path).toBe('/problems/sum-two-integers')
  })
  it('opens registration explicitly with optional nickname and explains verification before auto login', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(async (path) =>
      String(path).endsWith('/register') ? json({ message: 'generic' }, 202) : json(session()),
    )
    vi.stubGlobal('fetch', fetch)
    const { host, router } = await mount()
    await click(host, '注册账号')
    expect(host.querySelector('h2')?.textContent).toBe('注册账号')
    expect(host.querySelector('[name="nickname"]')?.hasAttribute('required')).toBe(false)
    type(host, 'username', 'NewLearner')
    type(host, 'email', 'new@example.test')
    type(host, 'password', 'account-fixture-password')
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/register',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(host.textContent).toContain('确认激活后会自动登录')
    expect(router.currentRoute.value.path).toBe('/account/register')
    await router.push('/account')
    await settle()
    expect(host.querySelector('h2')?.textContent).toBe('登录账号')
    expect(host.querySelector('[name="email"]')).toBeNull()
    expect((host.querySelector('[name="password"]') as HTMLInputElement).value).toBe('')
  })
  it('removes the mail token before requests and automatically logs in after activation', async () => {
    window.history.replaceState(
      null,
      '',
      '/account?redirect=/problems/sum-two-integers#action=activate&token=fixture-mail-secret',
    )
    const fetch = vi.fn<typeof globalThis.fetch>(async (path) =>
      json(session(String(path).endsWith('/complete'))),
    )
    vi.stubGlobal('fetch', fetch)
    const { host, router } = await mount('/account?redirect=/problems/sum-two-integers')
    expect(host.querySelector('h2')?.textContent).toBe('完成注册并登录')
    expect(window.location.hash).toBe('')
    expect(JSON.stringify(window.history.state)).not.toContain('fixture-mail-secret')
    expect(window.location.search).toContain('redirect=')
    expect(host.textContent).not.toContain('fixture-mail-secret')
    expect(fetch).toHaveBeenCalledTimes(1)
    await click(host, '激活并登录此账号')
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/registration/complete',
      expect.objectContaining({ body: JSON.stringify({ token: 'fixture-mail-secret' }) }),
    )
    expect(router.currentRoute.value.path).toBe('/problems/sum-two-integers')
  })
  it('recognizes an email link opened into an already mounted account page', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(async (path) =>
      json(session(String(path).endsWith('/complete'))),
    )
    vi.stubGlobal('fetch', fetch)
    const { host, router } = await mount()
    await router.push('/account#action=activate&token=existing-page-fixture')
    await settle()
    expect(host.querySelector('h2')?.textContent).toBe('完成注册并登录')
    expect(router.currentRoute.value.hash).toBe('')
    expect(fetch).toHaveBeenCalledTimes(1)
    await click(host, '激活并登录此账号')
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/registration/complete',
      expect.objectContaining({ body: JSON.stringify({ token: 'existing-page-fixture' }) }),
    )
    expect(router.currentRoute.value.path).toBe('/problems')
  })
  it('does not navigate or claim login when activation is invalid', async () => {
    window.history.replaceState(null, '', '/account#action=activate&token=expired-fixture')
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(async (path) =>
        String(path).endsWith('/complete') ? new Response(null, { status: 400 }) : json(session()),
      ),
    )
    const { host, router } = await mount()
    await click(host, '激活并登录此账号')
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('链接已过期')
    expect(router.currentRoute.value.path).toBe('/account')
    expect(host.textContent).not.toContain('登录成功')
  })
  it('keeps failed credentials on login with an actionable error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(async (path) =>
        String(path).endsWith('/login') ? new Response(null, { status: 401 }) : json(session()),
      ),
    )
    const { host, router } = await mount()
    type(host, 'username', 'learner')
    type(host, 'password', 'wrong')
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('账号或密码不正确')
    expect(router.currentRoute.value.path).toBe('/account')
  })
  it('explains fixture login and simulated email only when the demo gateway identifies itself', async () => {
    const marker = document.createElement('meta')
    marker.name = 'forgeoj-local-demo'
    document.body.append(marker)
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(async () => json(session())),
    )
    const { host } = await mount()
    expect(host.textContent).toContain('forgeoj-dev-only')
    await click(host, '注册账号')
    expect(host.textContent).toContain('不会发送到真实邮箱')
  })
  it('shows settings for a restored signed-in session', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(async () => json(session(true))),
    )
    const { host } = await mount()
    expect(host.querySelector('h2')?.textContent).toBe('账号与安全')
    expect(host.querySelector('form')).toBeNull()
    expect(host.textContent).toContain('退出当前账号')
    expect(host.textContent).not.toContain('注册账号')
  })
  it('accepts return paths only within known ordinary site routes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(async () => json(session())),
    )
    const { router } = await mount()
    for (const value of [
      'https://evil.test',
      '//evil.test',
      '/\\evil.test',
      '/account',
      '/admin/login',
      '/unknown',
    ])
      expect(accountReturnPath(router, value)).toBe('/problems')
    expect(accountReturnPath(router, '/problems/sum-two-integers')).toBe(
      '/problems/sum-two-integers',
    )
  })
})
