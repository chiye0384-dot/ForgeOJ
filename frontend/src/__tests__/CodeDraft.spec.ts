// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App, type Ref } from 'vue'
import { useCodeDraft } from '@/composables/useCodeDraft'
import { ApiRequestError } from '@/services/forgeojApi'
import { getCodeDraft, saveCodeDraft, type CodeDraft } from '@/services/learningApi'

vi.mock('@/services/learningApi', () => ({
  getCodeDraft: vi.fn<typeof getCodeDraft>(),
  saveCodeDraft: vi.fn<typeof saveCodeDraft>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
function state(sourceCode: string | null = null, version = 0): CodeDraft {
  return { language: 'JAVA_21', sourceCode, version, updatedAt: null, editable: true }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
async function settle() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
let app: App
let draft: ReturnType<typeof useCodeDraft>
let source: Ref<string>
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  vi.mocked(getCodeDraft).mockResolvedValue(state())
  vi.mocked(saveCodeDraft).mockImplementation(async (_slug, text, version) =>
    state(text, version + 1),
  )
  source = ref('template')
  app = createApp({
    setup() {
      draft = useCodeDraft('actual-slug', source)
      return () => h('div')
    },
  })
  app.mount(document.createElement('div'))
})
afterEach(() => {
  app.unmount()
  vi.useRealTimers()
})
describe('server drafts', () => {
  it('loads without GET writing and saves incomplete code after debounce with CAS', async () => {
    await draft.load(csrf)
    expect(saveCodeDraft).not.toHaveBeenCalled()
    source.value = 'unfinished'
    await vi.advanceTimersByTimeAsync(999)
    expect(saveCodeDraft).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(saveCodeDraft).toHaveBeenCalledWith('actual-slug', 'unfinished', 0, csrf)
    expect(draft.message.value).toBe('草稿已保存。')
    expect(draft.dirty.value).toBe(false)
  })
  it('serializes saves and never replaces newer typing with an older response', async () => {
    await draft.load(csrf)
    const pending = deferred<CodeDraft>()
    vi.mocked(saveCodeDraft).mockReturnValueOnce(pending.promise)
    source.value = 'first'
    await vi.advanceTimersByTimeAsync(1000)
    source.value = 'new typing'
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveCodeDraft).toHaveBeenCalledTimes(1)
    pending.resolve(state('first', 1))
    await settle()
    expect(source.value).toBe('new typing')
    expect(draft.dirty.value).toBe(true)
    await vi.advanceTimersByTimeAsync(1000)
    expect(saveCodeDraft).toHaveBeenLastCalledWith('actual-slug', 'new typing', 1, csrf)
  })
  it('409 pauses saves, preserves text, and explicit server load replaces it', async () => {
    await draft.load(csrf)
    vi.mocked(saveCodeDraft).mockRejectedValueOnce(new ApiRequestError(409))
    source.value = 'my page'
    await vi.advanceTimersByTimeAsync(1000)
    expect(draft.conflict.value).toBe(true)
    expect(source.value).toBe('my page')
    source.value = 'more edits'
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveCodeDraft).toHaveBeenCalledTimes(1)
    vi.mocked(getCodeDraft).mockResolvedValueOnce(state('server page', 3))
    await draft.useServer()
    expect(source.value).toBe('server page')
    expect(draft.conflict.value).toBe(false)
  })
  it('keeping local stays paused and explicit save reads latest version before CAS', async () => {
    await draft.load(csrf)
    vi.mocked(saveCodeDraft).mockRejectedValueOnce(new ApiRequestError(409))
    source.value = 'my page'
    await vi.advanceTimersByTimeAsync(1000)
    draft.keepLocal()
    source.value = 'my later text'
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveCodeDraft).toHaveBeenCalledTimes(1)
    vi.mocked(getCodeDraft).mockResolvedValueOnce(state('other page', 5))
    await draft.saveLocal()
    expect(source.value).toBe('my later text')
    expect(saveCodeDraft).toHaveBeenLastCalledWith('actual-slug', 'my later text', 5, csrf)
  })
  it('a second conflict still preserves local text and never forces a write', async () => {
    await draft.load(csrf)
    source.value = 'mine'
    vi.mocked(saveCodeDraft).mockRejectedValue(new ApiRequestError(409))
    await vi.advanceTimersByTimeAsync(1000)
    vi.mocked(getCodeDraft).mockResolvedValue(state('other', 2))
    await draft.saveLocal()
    expect(draft.conflict.value).toBe(true)
    expect(source.value).toBe('mine')
    expect(saveCodeDraft).toHaveBeenCalledTimes(2)
  })
  it('failed network save is dirty and needs manual retry', async () => {
    await draft.load(csrf)
    vi.mocked(saveCodeDraft).mockRejectedValueOnce(new Error('offline'))
    source.value = 'retained'
    await vi.advanceTimersByTimeAsync(1000)
    expect(draft.dirty.value).toBe(true)
    expect(draft.message.value).toContain('未保存')
    await vi.advanceTimersByTimeAsync(3000)
    expect(saveCodeDraft).toHaveBeenCalledTimes(1)
    await draft.retry()
    expect(source.value).toBe('retained')
    expect(draft.dirty.value).toBe(false)
  })
  it('logout or navigation rejects late draft read and save responses', async () => {
    const pending = deferred<CodeDraft>()
    vi.mocked(getCodeDraft).mockReturnValueOnce(pending.promise)
    const loading = draft.load(csrf)
    draft.reset()
    source.value = 'next user template'
    pending.resolve(state('previous user secret', 1))
    await loading
    expect(source.value).toBe('next user template')
    expect(draft.ready.value).toBe(false)
    await draft.load(csrf)
    const saving = deferred<CodeDraft>()
    vi.mocked(saveCodeDraft).mockReturnValueOnce(saving.promise)
    source.value = 'old user edits'
    await vi.advanceTimersByTimeAsync(1000)
    draft.reset()
    source.value = 'new user'
    saving.resolve(state('old user edits', 10))
    await settle()
    expect(source.value).toBe('new user')
    expect(draft.message.value).toBe('登录后读取草稿。')
  })
})
