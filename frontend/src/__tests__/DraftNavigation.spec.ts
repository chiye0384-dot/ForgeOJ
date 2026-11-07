// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, ref, type App, type Ref } from 'vue'
import {
  createMemoryHistory,
  createRouter,
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  RouterView,
  type Router,
} from 'vue-router'
import { useCodeDraft } from '@/composables/useCodeDraft'
import { useDraftLeaveGuard } from '@/composables/useDraftLeaveGuard'
import { getCodeDraft, saveCodeDraft } from '@/services/learningApi'

vi.mock('@/services/learningApi', () => ({
  getCodeDraft: vi.fn<typeof getCodeDraft>(),
  saveCodeDraft: vi.fn<typeof saveCodeDraft>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
let app: App
let router: Router
let source: Ref<string>
let draft: ReturnType<typeof useCodeDraft>

beforeEach(async () => {
  vi.useFakeTimers()
  vi.mocked(getCodeDraft).mockResolvedValue({
    language: 'JAVA_21',
    sourceCode: null,
    version: 0,
    updatedAt: null,
    editable: true,
  })
  vi.mocked(saveCodeDraft).mockImplementation(async (_slug, text, version) => ({
    language: 'JAVA_21',
    sourceCode: text,
    version: version + 1,
    updatedAt: null,
    editable: true,
  }))
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  const workspace = {
    setup() {
      source = ref('template')
      draft = useCodeDraft('fixture', source)
      const leave = useDraftLeaveGuard(draft)
      onBeforeRouteLeave(leave)
      onBeforeRouteUpdate(leave)
      return () => h('div')
    },
  }
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/problem/:slug', component: workspace },
      { path: '/library', component: { render: () => h('div') } },
    ],
  })
  await router.push('/problem/one')
  app = createApp({ render: () => h(RouterView) })
  app.use(router).mount(document.createElement('div'))
  await draft.load(csrf)
})
afterEach(() => {
  app.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

it('saves before leaving and before switching params on the same problem route', async () => {
  source.value = 'last edit'
  await router.push('/problem/two')
  expect(saveCodeDraft).toHaveBeenCalledWith('fixture', 'last edit', 0, csrf)
  expect(router.currentRoute.value.path).toBe('/problem/two')
  source.value = 'another edit'
  await router.push('/library')
  expect(saveCodeDraft).toHaveBeenLastCalledWith('fixture', 'another edit', 1, csrf)
  expect(window.confirm).not.toHaveBeenCalled()
})

it('keeps failed edits on the page until the user explicitly discards them', async () => {
  vi.mocked(saveCodeDraft).mockRejectedValue(new Error('offline'))
  source.value = 'keep this code'
  await router.push('/library')
  expect(router.currentRoute.value.path).toBe('/problem/one')
  expect(source.value).toBe('keep this code')
  expect(window.confirm).toHaveBeenCalledOnce()
  vi.mocked(window.confirm).mockReturnValue(true)
  await router.push('/library')
  expect(router.currentRoute.value.path).toBe('/library')
})

it('warns about refresh while dirty and removes the listener on unmount', async () => {
  source.value = 'not saved'
  const refresh = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(refresh)
  expect(refresh.defaultPrevented).toBe(true)
  await router.push('/library')
  const afterLeave = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(afterLeave)
  expect(afterLeave.defaultPrevented).toBe(false)
})

it('offers to stay after a stalled save instead of hanging navigation indefinitely', async () => {
  vi.mocked(saveCodeDraft).mockReturnValue(new Promise(() => {}))
  source.value = 'keep stalled edits'
  const navigating = router.push('/library')
  await vi.advanceTimersByTimeAsync(5000)
  await navigating
  expect(router.currentRoute.value.path).toBe('/problem/one')
  expect(source.value).toBe('keep stalled edits')
  expect(window.confirm).toHaveBeenCalledOnce()
})
