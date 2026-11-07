import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import siteRouter from '../router'
import { requireAccount } from '../router/accountNavigation'
afterEach(() => vi.unstubAllGlobals())
describe('ordinary account navigation', () => {
  it('unifies protected entries and preserves public and admin routes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(
        async () =>
          new Response(JSON.stringify({ authenticated: false, user: null, csrf: {} }), {
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: siteRouter.options.routes,
    })
    router.beforeEach(requireAccount)
    for (const path of [
      '/learning',
      '/authoring',
      '/classrooms',
      '/classrooms/1/problems',
      '/classrooms/1/assignments',
      '/classrooms/1/assignments/2/records',
      '/problems/sum-two-integers',
    ]) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe('/account')
      expect(router.currentRoute.value.query.redirect).toBe(path)
    }
    for (const path of ['/problems', '/account', '/admin/login']) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(path)
    }
  })
  it('allows authenticated navigation', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof globalThis.fetch>(
        async () =>
          new Response(
            JSON.stringify({ authenticated: true, user: { id: 1, username: 'learner' }, csrf: {} }),
            { headers: { 'Content-Type': 'application/json' } },
          ),
      ),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: siteRouter.options.routes,
    })
    router.beforeEach(requireAccount)
    await router.push('/learning')
    expect(router.currentRoute.value.path).toBe('/learning')
  })
})
