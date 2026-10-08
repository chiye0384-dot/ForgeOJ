// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Panel from '@/components/ProblemFeedback.vue'
import { requestJson, ApiRequestError } from '@/services/forgeojApi'
vi.mock('@/services/forgeojApi', async (original) => ({
  ...(await original<typeof import('@/services/forgeojApi')>()),
  requestJson: vi.fn<typeof requestJson>(),
}))
const recordedRequests = vi.mocked(requestJson)
const props = reactive({
  slug: 'public-fixture',
  userId: 1,
  csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' },
})
const own = {
  id: 'report-one',
  category: 'OTHER',
  body: '本人反馈私密正文',
  caseStatus: 'OPEN',
  resolution: null,
}
let app: App | undefined, host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 40; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount() {
  app = createApp({ render: () => h(Panel, props) })
  app.mount(host)
}
function click(text: string) {
  const b = [...host.querySelectorAll('button')].find((x) => x.textContent?.trim() === text)
  expect(b).toBeDefined()
  b!.click()
}
beforeEach(() => {
  vi.clearAllMocks()
  props.userId = 1
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(requestJson).mockResolvedValue({ items: [own], page: 1, total: 1 })
})
afterEach(() => {
  app?.unmount()
  host.remove()
  app = undefined
})
it('discards a former user response after identity changes', async () => {
  let resolve: (value: unknown) => void = () => {}
  vi.mocked(requestJson)
    .mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    .mockResolvedValue({ items: [], page: 1, total: 0 })
  mount()
  await settle()
  props.userId = 2
  await settle()
  resolve({ items: [own], page: 1, total: 1 })
  await settle()
  expect(host.textContent).not.toContain(own.body)
})
it('replays the same report request after uncertain POST failure', async () => {
  mount()
  await settle()
  const input = host.querySelector('textarea')!
  input.value = '新的问题'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await settle()
  vi.mocked(requestJson)
    .mockRejectedValueOnce(new ApiRequestError(503))
    .mockResolvedValueOnce(own)
    .mockResolvedValue({ items: [own], page: 1, total: 1 })
  host
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
  expect(host.textContent).toContain('可重试原请求')
  host
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
  const calls = recordedRequests.mock.calls.filter((c) => c[1]?.method === 'POST')
  expect(calls).toHaveLength(2)
  expect(calls[0]![1]?.body).toEqual(calls[1]![1]?.body)
  expect(input.value).toBe('')
})
it('clears private history and text after session rejection', async () => {
  mount()
  await settle()
  expect(host.textContent).toContain(own.body)
  const input = host.querySelector('textarea')!
  input.value = '未提交私密正文'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await settle()
  vi.mocked(requestJson).mockRejectedValue(new ApiRequestError(401))
  click('刷新本人反馈')
  await settle()
  expect(host.textContent).not.toContain(own.body)
  expect(input.value).toBe('')
})
