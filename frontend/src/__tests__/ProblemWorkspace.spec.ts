import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

import AppView from '../App.vue'
import JudgeWorkspaceView from '../views/JudgeWorkspaceView.vue'

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

const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'workspace-csrf' }
function session(authenticated = true): unknown {
  return { authenticated, user: authenticated ? { id: 1, username: 'learner' } : null, csrf }
}

function problem(slug: string): unknown {
  return {
    slug,
    title: slug === 'sum-two-integers' ? 'A + B' : '两数较大值',
    statement: '读取两个整数，输出较大值。',
    inputDescription: '两个整数。',
    outputDescription: '较大的整数。',
    publicSamples: [{ input: '1 2', output: '2' }],
    judgeVersion: 1,
    resourceLimits: { timeLimitMs: 1000, memoryLimitMb: 128, outputLimitBytes: 65536 },
    hiddenTest: 'workspace-hidden-must-not-render',
  }
}

const created = {
  submissionId: 'selected-submission',
  processingStatus: 'QUEUED',
  statusVersion: 0,
}
function status(processingStatus = 'RUNNING', statusVersion = 1): unknown {
  return {
    ...created,
    processingStatus,
    statusVersion,
    verdict: processingStatus === 'FINISHED' ? 'AC' : null,
    diagnosticMessage: null,
  }
}

function submit(host: HTMLDivElement): void {
  host
    .querySelector('[data-testid="submission-form"]')
    ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
}

let app: App | undefined
let sockets: Array<{ close: ReturnType<typeof vi.fn<() => void>> }>

beforeEach(() => {
  vi.useFakeTimers()
  sockets = []
  vi.stubGlobal(
    'WebSocket',
    class {
      onmessage = null
      onopen = null
      onclose = null
      onerror = null
      close = vi.fn<() => void>()
      constructor() {
        sockets.push(this)
      }
    },
  )
  vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')
})

afterEach(() => {
  app?.unmount()
  app = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function mountWorkspace(slug = 'larger-of-two-integers'): Promise<HTMLDivElement> {
  const host = document.createElement('div')
  app = createApp(JudgeWorkspaceView, { slug })
  app.mount(host)
  await settleVue()
  return host
}

describe('selected problem workspace lifecycle', () => {
  it('loads and submits the selected slug with a neutral template and polls its returned result', async () => {
    let reads = 0
    const fetchMock = vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/v1/auth/session') return jsonResponse(session())
      if (path === '/api/v1/problems/larger-of-two-integers')
        return jsonResponse(problem('larger-of-two-integers'))
      if (path === '/api/v1/problems/larger-of-two-integers/submissions')
        return jsonResponse(created, 202)
      if (path === '/api/v1/submissions/selected-submission')
        return jsonResponse(++reads === 1 ? status() : status('FINISHED', 2))
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const host = await mountWorkspace()
    expect(host.textContent).toContain('两数较大值')
    expect(host.textContent).not.toContain('workspace-hidden-must-not-render')
    const source = host.querySelector('[data-testid="source-code"]') as HTMLTextAreaElement
    expect(source.value).not.toContain('System.out.println(a + b)')
    expect(source.value).toContain('public class Main')
    const answer =
      'public class Main { public static void main(String[] args) { System.out.println(2); } }'
    source.value = answer
    source.dispatchEvent(new Event('input', { bubbles: true }))
    submit(host)
    await settleVue()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/problems/larger-of-two-integers/submissions',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-CSRF-TOKEN': 'workspace-csrf',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        }),
        body: JSON.stringify({ language: 'JAVA_21', sourceCode: answer }),
      }),
    )
    expect(host.textContent).toContain('RUNNING')
    await vi.advanceTimersByTimeAsync(1000)
    await settleVue()
    expect(host.textContent).toContain('AC')
    expect(sockets[0]?.close).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores a submission response arriving after logout and never starts its monitor', async () => {
    const pendingSubmission = deferred<Response>()
    let authenticated = true
    const fetchMock = vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/v1/auth/session') return jsonResponse(session(authenticated))
      if (path === '/api/v1/auth/logout') {
        authenticated = false
        return new Response(null, { status: 204 })
      }
      if (path.endsWith('/submissions')) return pendingSubmission.promise
      if (path === '/api/v1/problems/larger-of-two-integers')
        return jsonResponse(problem('larger-of-two-integers'))
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const host = await mountWorkspace()
    submit(host)
    await settleVue()
    ;(host.querySelector('[data-testid="logout"]') as HTMLButtonElement).click()
    await settleVue()
    expect(host.textContent).toContain('登录后开始判题')
    pendingSubmission.resolve(jsonResponse(created, 202))
    await settleVue()
    expect(host.textContent).toContain('登录后开始判题')
    expect(host.textContent).not.toContain('selected-submission')
    expect(host.querySelector('[data-testid="submission-form"]')).toBeNull()
    expect(sockets).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(0)
    expect(
      fetchMock.mock.calls.some(([path]) => String(path).startsWith('/api/v1/submissions/')),
    ).toBe(false)
  })

  it('stops the active socket and polling on logout and ignores an in-flight result read', async () => {
    const lateRead = deferred<Response>()
    let reads = 0
    let authenticated = true
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
        const path = String(input)
        if (path === '/api/v1/auth/session') return jsonResponse(session(authenticated))
        if (path === '/api/v1/auth/logout') {
          authenticated = false
          return new Response(null, { status: 204 })
        }
        if (path.endsWith('/submissions')) return jsonResponse(created, 202)
        if (path === '/api/v1/submissions/selected-submission')
          return ++reads === 1 ? jsonResponse(status()) : lateRead.promise
        return jsonResponse(problem('larger-of-two-integers'))
      }),
    )
    const host = await mountWorkspace()
    submit(host)
    await settleVue()
    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(1000)
    await settleVue()
    ;(host.querySelector('[data-testid="logout"]') as HTMLButtonElement).click()
    await settleVue()
    expect(sockets[0]?.close).toHaveBeenCalledOnce()
    lateRead.resolve(jsonResponse(status('FINISHED', 2)))
    await settleVue()
    expect(host.textContent).toContain('登录后开始判题')
    expect(host.textContent).not.toContain('selected-submission')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('remounts a different selected problem and closes the previous monitor', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
        const path = String(input)
        if (path === '/api/v1/auth/session') return jsonResponse(session())
        if (path.endsWith('/submissions')) return jsonResponse(created, 202)
        if (path === '/api/v1/submissions/selected-submission') return jsonResponse(status())
        const slug = path.slice('/api/v1/problems/'.length)
        return jsonResponse(problem(slug))
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: JudgeWorkspaceView },
        {
          path: '/problems/:slug',
          name: 'problem-workspace',
          component: JudgeWorkspaceView,
          props: true,
        },
        { path: '/problems', component: { template: '<div />' } },
        { path: '/account', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const host = document.createElement('div')
    app = createApp(AppView).use(router)
    app.mount(host)
    await settleVue()
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).toContain(
      'System.out.println(a + b)',
    )
    submit(host)
    await settleVue()
    expect(host.textContent).toContain('selected-submission')
    await router.push('/problems/larger-of-two-integers')
    await settleVue()
    expect(sockets[0]?.close).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
    expect(host.textContent).toContain('两数较大值')
    expect(host.textContent).not.toContain('selected-submission')
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).not.toContain(
      'System.out.println(a + b)',
    )
  })

  it('shows an unavailable selected problem without a submission form', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>(async (input: RequestInfo | URL) =>
        String(input) === '/api/v1/auth/session' ? jsonResponse(session()) : jsonResponse({}, 404),
      ),
    )
    const host = await mountWorkspace('archived-problem')
    expect(host.textContent).toContain('题目不存在或暂时不可用')
    expect(host.querySelector('[data-testid="submission-form"]')).toBeNull()
    expect(host.textContent).not.toContain('A + B')
    expect(host.textContent).not.toContain('两数较大值')
    expect(sockets).toHaveLength(0)
  })

  it('offers another logout and a safe reload when logout fails after clearing the current result', async () => {
    let logoutAttempts = 0
    let authenticated = true
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
        const path = String(input)
        if (path === '/api/v1/auth/session') return jsonResponse(session(authenticated))
        if (path === '/api/v1/auth/logout') {
          logoutAttempts += 1
          if (logoutAttempts === 1) return jsonResponse({}, 503)
          authenticated = false
          return new Response(null, { status: 204 })
        }
        return jsonResponse(problem('larger-of-two-integers'))
      }),
    )
    const host = await mountWorkspace()
    ;(host.querySelector('[data-testid="logout"]') as HTMLButtonElement).click()
    await settleVue()
    expect(host.textContent).toContain('退出未完成')
    expect(host.textContent).toContain('重新读取')
    expect(host.querySelector('[data-testid="submission-form"]')).toBeNull()
    const retry = host.querySelector('[data-testid="logout"]') as HTMLButtonElement
    expect(retry.disabled).toBe(false)
    retry.click()
    await settleVue()
    expect(logoutAttempts).toBe(2)
    expect(host.textContent).toContain('登录后开始判题')
  })

  it('clears the previous user’s unsaved code before a different user logs in on the same page', async () => {
    let currentUser: { id: number; username: string } | null = { id: 1, username: 'learnerA' }
    vi.stubGlobal(
      'fetch',
      vi.fn<FetchMock>(async (input: RequestInfo | URL) => {
        const path = String(input)
        if (path === '/api/v1/auth/session')
          return jsonResponse({ authenticated: currentUser !== null, user: currentUser, csrf })
        if (path === '/api/v1/auth/logout') {
          currentUser = null
          return new Response(null, { status: 204 })
        }
        if (path === '/api/v1/auth/login') {
          currentUser = { id: 2, username: 'learnerB' }
          return jsonResponse({ authenticated: true, user: currentUser, csrf })
        }
        return jsonResponse(problem('larger-of-two-integers'))
      }),
    )
    const host = await mountWorkspace()
    const previousSource = host.querySelector('textarea') as HTMLTextAreaElement
    previousSource.value = 'private-draft-from-user-A'
    previousSource.dispatchEvent(new Event('input', { bubbles: true }))
    ;(host.querySelector('[data-testid="logout"]') as HTMLButtonElement).click()
    await settleVue()
    const username = host.querySelector('[data-testid="username"]') as HTMLInputElement
    const password = host.querySelector('[data-testid="password"]') as HTMLInputElement
    username.value = 'learnerB'
    username.dispatchEvent(new Event('input', { bubbles: true }))
    password.value = 'test-user-B-password'
    password.dispatchEvent(new Event('input', { bubbles: true }))
    host
      .querySelector('[data-testid="login-form"]')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settleVue()
    expect(host.textContent).toContain('learnerB')
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).toContain(
      'public class Main',
    )
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).not.toContain(
      'private-draft-from-user-A',
    )
    expect(host.textContent).not.toContain('private-draft-from-user-A')
  })
})
