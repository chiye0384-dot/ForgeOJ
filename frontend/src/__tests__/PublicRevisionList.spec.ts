// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import PublicRevisionList from '@/components/PublicRevisionList.vue'
import { publishedList, copyPublicRevision } from '@/services/publicRevisionApi'
import { ApiRequestError } from '@/services/forgeojApi'
vi.mock('@/services/publicRevisionApi', () => ({
  publishedList: vi.fn<typeof publishedList>(),
  copyPublicRevision: vi.fn<typeof copyPublicRevision>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const problem = {
  id: 1,
  slug: 'public-fixture',
  title: '本人公开题',
  status: 'ACTIVE' as const,
  version: 1,
  dataInvalid: false,
}
let app: App, host: HTMLDivElement
const props = reactive({ userId: 1, csrf })
async function settle() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(text: string) {
  const result = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(result).toBeDefined()
  return result!
}
beforeEach(() => {
  vi.resetAllMocks()
  props.userId = 1
  vi.mocked(publishedList).mockResolvedValue({ items: [problem], total: 1 })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ setup: () => () => h(PublicRevisionList, props) })
  app.mount(host)
})
afterEach(() => {
  app.unmount()
  host.remove()
})
it('loads only on explicit action and retries the same copy request after uncertain failure', async () => {
  await settle()
  expect(publishedList).not.toHaveBeenCalled()
  button('修订已公开题').click()
  await settle()
  vi.mocked(copyPublicRevision).mockRejectedValue(new ApiRequestError(503))
  button('复制文案修订').click()
  await settle()
  button('复制文案修订').click()
  await settle()
  expect(copyPublicRevision).toHaveBeenCalledTimes(2)
  expect(vi.mocked(copyPublicRevision).mock.calls[0]).toEqual(
    vi.mocked(copyPublicRevision).mock.calls[1],
  )
  expect(vi.mocked(copyPublicRevision).mock.calls[0]![1]).toMatchObject({
    expectedVersion: 1,
    revisionKind: 'TEXT',
  })
})
it('allows only a linked new problem for invalid data and clears private list on revoked identity', async () => {
  vi.mocked(publishedList).mockResolvedValue({
    items: [{ ...problem, dataInvalid: true }],
    total: 1,
  })
  button('修订已公开题').click()
  await settle()
  expect(button('复制文案修订').disabled).toBe(true)
  vi.mocked(copyPublicRevision).mockRejectedValue(new ApiRequestError(401))
  button('复制关联新题').click()
  await settle()
  expect(host.textContent).not.toContain('本人公开题')
  expect(button('修订已公开题').disabled).toBe(true)
})
it('discards the previous identity late list response', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof publishedList>>) => void
  vi.mocked(publishedList).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  button('修订已公开题').click()
  await settle()
  props.userId = 2
  await settle()
  resolve({ items: [problem], total: 1 })
  await settle()
  expect(host.textContent).not.toContain('本人公开题')
})
