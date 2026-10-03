import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'

import ProblemLibraryView from '../views/ProblemLibraryView.vue'

type FetchMock = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function settleVue(): Promise<void> {
  for (let iteration = 0; iteration < 30; iteration += 1) {
    await Promise.resolve()
    await nextTick()
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}

function list(title: string, page = 1, size = 20, total = 1): unknown {
  return {
    items: [
      {
        slug: 'larger-of-two-integers',
        title,
        difficulty: 'EASY',
        tags: ['基础'],
        judgeVersion: 1,
        hiddenInput: 'library-hidden-must-not-render',
        referenceSource: 'library-reference-must-not-render',
      },
    ],
    page,
    size,
    total,
  }
}

let app: App | undefined
async function mountLibrary(path: string): Promise<{ host: HTMLDivElement; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/problems', name: 'problem-library', component: ProblemLibraryView },
      { path: '/problems/:slug', name: 'problem-workspace', component: { template: '<div />' } },
      { path: '/account', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const host = document.createElement('div')
  app = createApp({ render: () => h(RouterView) })
  app.use(router)
  app.mount(host)
  await settleVue()
  return { host, router }
}

afterEach(() => {
  app?.unmount()
  app = undefined
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('public problem library', () => {
  it('restores URL filters anonymously, preserves them while paging, and resets a search to page one', async () => {
    const fetchMock = vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname === '/api/v1/problem-tags') return jsonResponse({ tags: ['基础', '数学'] })
      if (url.pathname !== '/api/v1/problems') throw new Error('Unexpected non-library request')
      return jsonResponse(
        list(
          '两数较大值',
          Number(url.searchParams.get('page')),
          Number(url.searchParams.get('size')),
          4,
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const { host, router } = await mountLibrary(
      '/problems?keyword=两数&difficulty=EASY&tag=基础&page=2&size=1',
    )

    expect((host.querySelector('[data-testid="problem-keyword"]') as HTMLInputElement).value).toBe(
      '两数',
    )
    expect(
      (host.querySelector('[data-testid="problem-difficulty"]') as HTMLSelectElement).value,
    ).toBe('EASY')
    expect((host.querySelector('[data-testid="problem-tag"]') as HTMLSelectElement).value).toBe(
      '基础',
    )
    expect(host.textContent).toContain('第 2 / 4 页')
    expect(host.querySelector('.problem-item a')?.getAttribute('href')).toBe(
      '/problems/larger-of-two-integers',
    )
    expect(host.textContent).not.toContain('library-hidden-must-not-render')
    expect(host.textContent).not.toContain('library-reference-must-not-render')
    ;(host.querySelector('[data-testid="next-page"]') as HTMLButtonElement).click()
    await settleVue()
    expect(router.currentRoute.value.query).toEqual({
      page: '3',
      size: '1',
      keyword: '两数',
      difficulty: 'EASY',
      tag: '基础',
    })
    const keyword = host.querySelector('[data-testid="problem-keyword"]') as HTMLInputElement
    keyword.value = ' A + B %_ '
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    host
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settleVue()
    expect(router.currentRoute.value.query).toEqual({
      page: '1',
      size: '1',
      keyword: 'A + B %_',
      difficulty: 'EASY',
      tag: '基础',
    })
    const lastRequest = new URL(
      String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1]?.[0]),
      'http://localhost',
    )
    expect(lastRequest.searchParams.get('keyword')).toBe('A + B %_')
    expect(lastRequest.searchParams.get('tag')).toBe('基础')
    expect(lastRequest.searchParams.get('page')).toBe('1')

    router.back()
    await settleVue()
    expect(router.currentRoute.value.query.page).toBe('3')
    expect(keyword.value).toBe('两数')
    expect(host.textContent).toContain('第 3 / 4 页')
    expect(fetchMock.mock.calls.every(([path]) => !String(path).includes('/auth/'))).toBe(true)
  })

  it('keeps the latest result when an older response arrives after it', async () => {
    const first = deferred<Response>()
    const second = deferred<Response>()
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>((input: RequestInfo | URL) => {
        if (String(input) === '/api/v1/problem-tags')
          return Promise.resolve(jsonResponse({ tags: [] }))
        return String(input).includes('keyword=new') ? second.promise : first.promise
      }),
    )
    const { host, router } = await mountLibrary('/problems')
    await router.push('/problems?keyword=new')
    await settleVue()
    second.resolve(jsonResponse(list('最新题目')))
    await settleVue()
    expect(host.textContent).toContain('最新题目')
    first.resolve(jsonResponse(list('旧结果')))
    await settleVue()
    expect(host.textContent).toContain('最新题目')
    expect(host.textContent).not.toContain('旧结果')
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.querySelector('[aria-busy="false"]')).not.toBeNull()
  })

  it('does not clear current loading or expose an old error while the newer request is pending', async () => {
    const first = deferred<Response>()
    const second = deferred<Response>()
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>((input: RequestInfo | URL) => {
        if (String(input) === '/api/v1/problem-tags')
          return Promise.resolve(jsonResponse({ tags: [] }))
        return String(input).includes('page=2') ? second.promise : first.promise
      }),
    )
    const { host, router } = await mountLibrary('/problems')
    await router.push('/problems?page=2')
    await settleVue()
    first.resolve(jsonResponse({}, 503))
    await settleVue()
    expect(host.textContent).toContain('正在读取题库')
    expect(host.querySelector('[aria-busy="true"]')).not.toBeNull()
    expect(host.querySelector('[role="alert"]')).toBeNull()
    second.resolve(jsonResponse(list('第二页题目', 2, 20, 21)))
    await settleVue()
    expect(host.textContent).toContain('第二页题目')
    expect(host.textContent).toContain('第 2 / 2 页')
  })

  it('shows a tag-read failure while keeping the independent public list available', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>(async (input: RequestInfo | URL) =>
        String(input) === '/api/v1/problem-tags'
          ? jsonResponse({}, 503)
          : jsonResponse(list('仍可读取的题目')),
      ),
    )
    const { host } = await mountLibrary('/problems?tag=暂缺标签')
    expect(host.textContent).toContain('标签暂时无法读取')
    expect(host.textContent).toContain('仍可读取的题目')
    expect((host.querySelector('[data-testid="problem-tag"]') as HTMLSelectElement).value).toBe(
      '暂缺标签',
    )
  })
})
