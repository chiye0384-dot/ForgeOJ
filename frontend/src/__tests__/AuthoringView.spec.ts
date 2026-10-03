// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import AuthoringView from '@/views/AuthoringView.vue'
import { restoreSession, ApiRequestError } from '@/services/forgeojApi'
import {
  authoredDetail,
  authoredList,
  authoredTests,
  authoredWrite,
  authoredZip,
  type AuthoredDetail,
} from '@/services/contentApi'
vi.mock('@/services/forgeojApi', async (original) => ({
  ...(await original<typeof import('@/services/forgeojApi')>()),
  restoreSession: vi.fn<typeof restoreSession>(),
}))
vi.mock('@/services/contentApi', () => ({
  authoredDetail: vi.fn<typeof authoredDetail>(),
  authoredList: vi.fn<typeof authoredList>(),
  authoredTests: vi.fn<typeof authoredTests>(),
  authoredWrite: vi.fn<typeof authoredWrite>(),
  authoredZip: vi.fn<typeof authoredZip>(),
}))
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fixture' }
const fresh = (): AuthoredDetail => ({
  draft: { id: 'private-fixture', title: '原创草稿', version: 1, status: 'DRAFT', testCount: 0 },
  content: {
    metadata: {
      title: '原创草稿',
      statement: '',
      inputDescription: '',
      outputDescription: '',
      samples: [],
      originType: 'ORIGINAL',
      sourceUrl: '',
      licenseStatement: '',
      timeLimitMs: 2000,
      memoryLimitMb: 256,
      outputLimitBytes: 1048576,
    },
    referenceCode: '',
    solutionIdea: '',
    solutionCode: '',
  },
})
let app: App
let host: HTMLDivElement
async function settle() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function click(name: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === name)
  expect(button).toBeDefined()
  button!.click()
}
function fill(label: string, value: string) {
  const field = [...host.querySelectorAll('label')]
    .find((l) => l.textContent?.trim().startsWith(label))
    ?.querySelector('input,textarea') as HTMLInputElement
  expect(field).toBeDefined()
  field.value = value
  field.dispatchEvent(new Event('input', { bubbles: true }))
}
async function open() {
  await settle()
  click('原创草稿')
  await settle()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(restoreSession).mockResolvedValue({
    authenticated: true,
    user: { id: 1, username: 'author' },
    csrf,
  })
  vi.mocked(authoredList).mockResolvedValue({ items: [fresh().draft], total: 1 })
  vi.mocked(authoredDetail).mockImplementation(async () => fresh())
  vi.mocked(authoredTests).mockResolvedValue([])
  host = document.createElement('div')
  app = createApp(AuthoringView)
  app.component('RouterLink', { template: '<a><slot /></a>' })
  app.mount(host)
})
afterEach(() => {
  app.unmount()
})
describe('author private content editor', () => {
  it('saves independent reference and solution snapshots with server version and CSRF', async () => {
    await open()
    fill('私有参考程序 Main.java', 'private reference')
    fill('独立题解代码 Main.java', 'independent solution')
    const saved = fresh()
    saved.draft.version = 2
    vi.mocked(authoredWrite).mockResolvedValue(saved)
    click('保存题目内容')
    await settle()
    expect(authoredWrite).toHaveBeenCalledWith(
      'private-fixture',
      '',
      'PUT',
      expect.objectContaining({
        expectedVersion: 1,
        content: expect.objectContaining({
          referenceCode: 'private reference',
          solutionCode: 'independent solution',
        }),
      }),
      csrf,
    )
    expect(host.textContent).toContain('版本 2')
    expect(host.textContent).toContain('草稿已更新。')
  })
  it('preserves unsaved content while storing tests and advances the version for a later content save', async () => {
    await open()
    fill('题面', '本页尚未保存的题面')
    fill('私有参考程序 Main.java', 'unsaved private code')
    const saved = fresh()
    saved.draft.version = 2
    vi.mocked(authoredWrite).mockResolvedValue(saved)
    click('保存全部测试')
    await settle()
    const statement = [...host.querySelectorAll('label')]
      .find((l) => l.textContent?.trim().startsWith('题面'))!
      .querySelector('textarea')!
    expect(statement.value).toBe('本页尚未保存的题面')
    click('保存题目内容')
    await settle()
    expect(vi.mocked(authoredWrite).mock.calls[1]?.[3]).toEqual(
      expect.objectContaining({
        expectedVersion: 2,
        content: expect.objectContaining({ referenceCode: 'unsaved private code' }),
      }),
    )
  })
  it('retains local edits on conflict and waits for explicit reload without another mutation', async () => {
    await open()
    fill('题面', 'local conflict code')
    vi.mocked(authoredWrite).mockRejectedValueOnce(new ApiRequestError(409))
    click('保存题目内容')
    await settle()
    expect(authoredWrite).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('本页内容已保留')
    const statement = [...host.querySelectorAll('label')]
      .find((l) => l.textContent?.trim().startsWith('题面'))!
      .querySelector('textarea')!
    expect(statement.value).toBe('local conflict code')
    const remote = fresh()
    remote.content.metadata.statement = 'remote saved'
    remote.draft.version = 3
    vi.mocked(authoredDetail).mockResolvedValue(remote)
    click('载入服务端版本')
    await settle()
    expect(statement.value).toBe('remote saved')
    expect(authoredWrite).toHaveBeenCalledTimes(1)
  })
  it('ignores a late private detail after unmount and does not fetch its tests', async () => {
    await settle()
    let resolve!: (v: AuthoredDetail) => void
    vi.mocked(authoredDetail).mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    click('原创草稿')
    app.unmount()
    resolve(fresh())
    await settle()
    expect(host.textContent).toBe('')
    expect(authoredTests).not.toHaveBeenCalled()
  })
})
