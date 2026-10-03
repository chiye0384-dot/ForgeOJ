import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'

import JudgeWorkspaceView from '../views/JudgeWorkspaceView.vue'

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

function typeInto(element: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('M0 judge workspace', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('navigator', {
      userAgent: navigator.userAgent,
      locks: { request: async (_name: string, perform: () => Promise<unknown>) => perform() },
    })
    vi.stubGlobal(
      'WebSocket',
      class {
        onmessage = null
        onopen = null
        onclose = null
        onerror = null
        close = vi.fn<() => void>()
      },
    )
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(
      '11111111-1111-4111-8111-111111111111',
    )
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('logs in, loads the problem, submits Java 21 code, and polls to a terminal result', async () => {
    const responses = [
      jsonResponse({
        authenticated: false,
        user: null,
        csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-before-login' },
      }),
      jsonResponse({
        authenticated: true,
        user: { id: 1, username: 'learner' },
        csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-after-login' },
      }),
      jsonResponse({
        slug: 'sum-two-integers',
        title: 'A + B',
        statement: '读取两个整数并输出它们的和。',
        inputDescription: '两个整数。',
        outputDescription: '两个整数之和。',
        publicSamples: [{ input: '1 2', output: '3' }],
        judgeVersion: 1,
        resourceLimits: { timeLimitMs: 1000, memoryLimitMb: 128, outputLimitBytes: 65536 },
      }),
      jsonResponse(
        {
          submissionId: 'submission-1',
          processingStatus: 'QUEUED',
          statusVersion: 0,
        },
        202,
      ),
      jsonResponse({
        submissionId: 'submission-1',
        processingStatus: 'RUNNING',
        statusVersion: 1,
        verdict: null,
        diagnosticMessage: null,
        hiddenInput: 'must-never-be-rendered',
      }),
      jsonResponse({
        submissionId: 'submission-1',
        processingStatus: 'FINISHED',
        statusVersion: 2,
        verdict: 'AC',
        diagnosticMessage: null,
        hiddenInput: 'must-never-be-rendered',
      }),
    ]
    responses.splice(
      1,
      0,
      jsonResponse({
        authenticated: false,
        user: null,
        csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-before-login' },
      }),
      jsonResponse({}, 401),
    )
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async (input, init) => {
        if (String(input).endsWith('/draft')) {
          const body = init?.body ? (JSON.parse(String(init.body)) as { sourceCode: string }) : null
          return jsonResponse({
            language: 'JAVA_21',
            sourceCode: body?.sourceCode ?? null,
            version: body ? 1 : 0,
            updatedAt: null,
            editable: true,
          })
        }
        const response = responses.shift()
        if (!response) {
          throw new Error('Unexpected fetch call')
        }
        return response
      },
    )
    vi.stubGlobal('fetch', fetchMock)

    const host = document.createElement('div')
    const app = createApp(JudgeWorkspaceView)
    app.mount(host)
    await settleVue()

    expect(host.textContent).toContain('登录后开始判题')

    typeInto(host.querySelector('[data-testid="username"]') as HTMLInputElement, 'learner')
    typeInto(host.querySelector('[data-testid="password"]') as HTMLInputElement, 'password')
    host
      .querySelector('[data-testid="login-form"]')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settleVue()

    expect(host.textContent).toContain('A + B')
    expect(host.textContent).toContain('读取两个整数并输出它们的和。')

    const source = host.querySelector('[data-testid="source-code"]') as HTMLTextAreaElement
    typeInto(source, 'public class Main { public static void main(String[] args) {} }')
    host
      .querySelector('[data-testid="submission-form"]')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settleVue()

    expect(host.textContent).toContain('RUNNING')

    await vi.advanceTimersByTimeAsync(1000)
    await settleVue()

    expect(host.textContent).toContain('AC')
    expect(host.textContent).toContain('状态版本：2')
    expect(host.textContent).not.toContain('must-never-be-rendered')
    expect(fetchMock).toHaveBeenCalledTimes(10)
    expect(vi.getTimerCount()).toBe(0)

    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      '/api/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-before-login' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      7,
      '/api/v1/problems/sum-two-integers/submissions',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-CSRF-TOKEN': 'csrf-after-login',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        }),
        body: JSON.stringify({
          language: 'JAVA_21',
          sourceCode: 'public class Main { public static void main(String[] args) {} }',
        }),
      }),
    )

    app.unmount()
  })
})
