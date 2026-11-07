import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import JudgeWorkspaceView from '@/views/JudgeWorkspaceView.vue'
import SubmissionHistory from '@/components/SubmissionHistory.vue'
import { readEditorPreferences, saveEditorPreferences } from '@/services/editorPreferences'

let app: App | undefined
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const response = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
async function settle() {
  for (let i = 0; i < 30; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mountWorkspace(host: HTMLElement) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: JudgeWorkspaceView }],
  })
  await router.push('/')
  app = createApp(JudgeWorkspaceView).use(router)
  app.mount(host)
  await settle()
}
beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  vi.stubGlobal(
    'WebSocket',
    class {
      close() {}
    },
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
})
function fixtures(path: string) {
  if (path.endsWith('/session'))
    return response({ authenticated: true, user: { id: 2, username: 'fixture' }, csrf })
  if (path.endsWith('/draft'))
    return response({
      language: 'JAVA_21',
      sourceCode: null,
      version: 0,
      editable: true,
      updatedAt: null,
    })
  if (path === '/api/v1/problems/sum-two-integers')
    return response({
      slug: 'sum-two-integers',
      title: '整数相加',
      judgeVersion: 1,
      statement: 'fixture',
      inputDescription: 'two',
      outputDescription: 'sum',
      publicSamples: [],
      resourceLimits: { timeLimitMs: 2000, memoryLimitMb: 256, outputLimitBytes: 1024 },
    })
  return response({
    submissionId: 'original',
    processingStatus: 'FINISHED',
    statusVersion: 2,
    verdict: 'AC',
    diagnosticMessage: null,
  })
}
it('retries the same frozen formal request after a lost response even if code changes', async () => {
  const posts: RequestInit[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/submissions') && init?.method === 'POST') {
        posts.push(init)
        if (posts.length === 1) throw new TypeError('lost response after server accepted')
        return response(
          { submissionId: 'original', processingStatus: 'QUEUED', statusVersion: 0 },
          202,
        )
      }
      return fixtures(path)
    }),
  )
  const host = document.createElement('div')
  await mountWorkspace(host)
  const code = host.querySelector('[data-testid="source-code"]') as HTMLTextAreaElement
  code.value = 'original frozen source'
  code.dispatchEvent(new Event('input'))
  await settle()
  host
    .querySelector('form[data-testid="submission-form"]')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(host.textContent).toContain('尚未确认服务器')
  code.value = 'later edit'
  code.dispatchEvent(new Event('input'))
  await settle()
  host
    .querySelector('form[data-testid="submission-form"]')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(posts).toHaveLength(1)
  ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '重试原提交')!.click()
  await settle()
  expect(posts).toHaveLength(2)
  expect(posts[1]!.body).toBe(posts[0]!.body)
  expect((posts[1]!.headers as Record<string, string>)['Idempotency-Key']).toBe(
    (posts[0]!.headers as Record<string, string>)['Idempotency-Key'],
  )
  expect(JSON.parse(String(posts[1]!.body)).sourceCode).toBe('original frozen source')
  expect(code.value).toBe('later edit')
  expect(host.textContent).toContain('已通过')
})
it('does not offer uncertain retry after an explicit validation rejection', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
      String(input).endsWith('/submissions') && init?.method === 'POST'
        ? response({}, 400)
        : fixtures(String(input)),
    ),
  )
  const host = document.createElement('div')
  await mountWorkspace(host)
  host
    .querySelector('form[data-testid="submission-form"]')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(host.textContent).not.toContain('重试原提交')
})
it('reads history only on activation and keeps source read-only until an explicit load action', async () => {
  const onLoad = vi.fn<(value: unknown) => void>()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.includes('/me/submissions?'))
        return response({
          items: [
            {
              submissionId: 'original',
              createdAt: '2026-10-30T08:00:00',
              processingStatus: 'FINISHED',
              verdict: 'AC',
              judgeVersion: 1,
            },
          ],
          page: 1,
          size: 20,
          total: 1,
        })
      if (path.endsWith('/source'))
        return response({
          submissionId: 'original',
          sourceCode: 'historical source',
          language: 'JAVA_21',
          judgeVersion: 1,
          createdAt: 'fixture',
          problemSlug: 'sum-two-integers',
        })
      return fixtures(path)
    }),
  )
  const host = document.createElement('div')
  app = createApp(SubmissionHistory, {
    slug: 'sum-two-integers',
    userId: 2,
    active: true,
    canLoad: true,
    onLoad,
  })
  app.mount(host)
  await settle()
  ;[...host.querySelectorAll('li button')][0]!.dispatchEvent(new MouseEvent('click'))
  await settle()
  expect(host.querySelector('[data-testid="history-source"]')?.textContent).toBe(
    'historical source',
  )
  expect(onLoad).not.toHaveBeenCalled()
  ;[...host.querySelectorAll('button')]
    .find((b) => b.textContent?.trim() === '载入这份历史代码')!
    .click()
  await settle()
  expect(onLoad).toHaveBeenCalledWith(expect.objectContaining({ sourceCode: 'historical source' }))
})
it('persists only bounded editor preferences, never source or identity', () => {
  saveEditorPreferences({ share: 42, wrapped: false, fontSize: 18 })
  expect(readEditorPreferences()).toEqual({ share: 42, wrapped: false, fontSize: 18 })
  const value = localStorage.getItem('forgeoj.editor-preferences.v1')!
  expect(Object.keys(JSON.parse(value)).sort()).toEqual(['fontSize', 'share', 'wrapped'])
  localStorage.setItem(
    'forgeoj.editor-preferences.v1',
    '{"share":999,"fontSize":200,"wrapped":"yes"}',
  )
  expect(readEditorPreferences()).toEqual({ share: 38, wrapped: true, fontSize: 14 })
})
