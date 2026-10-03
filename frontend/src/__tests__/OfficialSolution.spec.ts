/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import OfficialSolution from '@/components/OfficialSolution.vue'
import type { SolutionAccess } from '@/services/solutionApi'
import { confirmSolution, readSolution } from '@/services/solutionApi'
import { ApiRequestError } from '@/services/forgeojApi'

vi.mock('@/services/solutionApi', async (original) => ({
  ...(await original<typeof import('@/services/solutionApi')>()),
  readSolution: vi.fn<typeof readSolution>(),
  confirmSolution: vi.fn<typeof confirmSolution>(),
}))
const read = vi.mocked(readSolution)
const confirm = vi.mocked(confirmSolution)
const locked: SolutionAccess = { judgeVersion: 1, access: 'LOCKED', solution: null }
const unlocked: SolutionAccess = {
  judgeVersion: 1,
  access: 'EARLY_VIEW',
  solution: {
    idea: '<img src=x onerror=alert(1)>',
    language: 'JAVA_21',
    sourceCode: 'private solution text',
  },
}
let app: App | undefined
let host: HTMLDivElement
function mount() {
  const props = reactive({
    slug: 'sum-two-integers',
    judgeVersion: 1,
    userId: 1,
    csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
    refreshKey: 0,
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(OfficialSolution, props) })
  app.mount(host)
  return props
}
async function settle() {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  expect(button).toBeDefined()
  button!.click()
  await settle()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.resetAllMocks()
})
describe('official solution', () => {
  it('reads lazily and cancel never writes; confirmation is explicit and text stays inert', async () => {
    read.mockResolvedValue(locked)
    confirm.mockResolvedValue(unlocked)
    mount()
    expect(read).not.toHaveBeenCalled()
    await click('查看官方题解')
    expect(host.textContent).not.toContain('private solution text')
    await click('提前查看')
    expect(host.textContent).toContain('可能影响独立思考')
    expect(confirm).not.toHaveBeenCalled()
    await click('取消')
    expect(confirm).not.toHaveBeenCalled()
    await click('提前查看')
    await click('确认提前查看当前版本')
    expect(confirm).toHaveBeenCalledExactlyOnceWith(
      'sum-two-integers',
      1,
      expect.objectContaining({ token: 'csrf' }),
    )
    expect(host.textContent).toContain('private solution text')
    expect(host.querySelector('img')).toBeNull()
  })
  it('actual terminal notice only rereads server; client cannot unlock', async () => {
    read.mockResolvedValueOnce(locked).mockResolvedValueOnce({ ...unlocked, access: 'AC' })
    const props = mount()
    await click('查看官方题解')
    props.refreshKey = 2
    await settle()
    expect(read).toHaveBeenCalledTimes(2)
    expect(confirm).not.toHaveBeenCalled()
    expect(host.textContent).toContain('已通过当前版本')
  })
  it('409 preserves need for a new decision and never auto retries confirmation', async () => {
    read.mockResolvedValue(locked)
    confirm.mockRejectedValue(new ApiRequestError(409))
    mount()
    await click('查看官方题解')
    await click('提前查看')
    await click('确认提前查看当前版本')
    expect(host.textContent).toContain('版本已更新')
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(host.textContent).not.toContain('private solution text')
  })
  it('does not lose a terminal refresh arriving during a pending read', async () => {
    let resolve!: (value: SolutionAccess) => void
    read
      .mockReturnValueOnce(
        new Promise((r) => {
          resolve = r
        }),
      )
      .mockResolvedValueOnce({ ...unlocked, access: 'AC' })
    const props = mount()
    await click('查看官方题解')
    props.refreshKey = 2
    await settle()
    resolve(locked)
    await settle()
    expect(read).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('已通过当前版本')
  })
  it('unmount and identity/version changes discard late private content', async () => {
    let resolve!: (v: SolutionAccess) => void
    read.mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    const props = mount()
    await click('查看官方题解')
    props.userId = 2
    await settle()
    resolve(unlocked)
    await settle()
    expect(host.textContent).not.toContain('private solution text')
    read.mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    await click('查看官方题解')
    app!.unmount()
    app = undefined
    resolve(unlocked)
    await settle()
    expect(host.textContent).toBe('')
  })
  it('unavailable and malformed locked payload never render undeclared code', async () => {
    read
      .mockResolvedValueOnce({ ...locked, access: 'UNAVAILABLE' })
      .mockResolvedValueOnce({ ...unlocked, access: 'LOCKED' })
    mount()
    await click('查看官方题解')
    expect(host.textContent).toContain('暂未提供')
    await click('重新读取题解状态')
    expect(host.textContent).not.toContain('private solution text')
    expect(host.textContent).toContain('版本不一致')
  })
})
