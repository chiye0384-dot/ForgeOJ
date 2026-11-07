import { afterEach, expect, it } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import WorkspaceSplit from '../components/WorkspaceSplit.vue'
let app: App
let host: HTMLDivElement
afterEach(() => app.unmount())
function mount() {
  host = document.createElement('div')
  const expanded = ref(false)
  app = createApp({
    render: () =>
      h(
        WorkspaceSplit,
        { expanded: expanded.value },
        {
          left: () => h('input', { value: 'unsent input' }),
          right: () => h('textarea', 'unsaved code'),
        },
      ),
  })
  app.mount(host)
  return { expanded, separator: host.querySelector('[role="separator"]') as HTMLElement }
}
it('supports keyboard sizing with announced limits and a reset without remounting editor contents', async () => {
  const { expanded, separator } = mount()
  const editor = host.querySelector('textarea')
  separator.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', cancelable: true }))
  await nextTick()
  expect(separator.getAttribute('aria-valuenow')).toBe('36')
  separator.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', cancelable: true }))
  await nextTick()
  expect(separator.getAttribute('aria-valuenow')).toBe(separator.getAttribute('aria-valuemin'))
  separator.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', cancelable: true }))
  await nextTick()
  expect(separator.getAttribute('aria-valuenow')).toBe(separator.getAttribute('aria-valuemax'))
  separator.dispatchEvent(new MouseEvent('dblclick'))
  await nextTick()
  expect(separator.getAttribute('aria-valuenow')).toBe('38')
  expanded.value = true
  await nextTick()
  expect((host.querySelector('.left-pane') as HTMLElement).style.display).toBe('none')
  expanded.value = false
  await nextTick()
  expect(host.querySelector('textarea')).toBe(editor)
  expect((host.querySelector('input') as HTMLInputElement).value).toBe('unsent input')
})
it('changes width only during a pointer drag and stops when the pointer is released', async () => {
  const { separator } = mount()
  const surface = host.querySelector('.split-workspace') as HTMLElement
  surface.getBoundingClientRect = () => new DOMRect(10, 0, 1200, 800)
  separator.dispatchEvent(
    new PointerEvent('pointerdown', { button: 0, pointerId: 1, clientX: 466 }),
  )
  separator.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientX: 610 }))
  await nextTick()
  expect(separator.getAttribute('aria-valuenow')).toBe('50')
  separator.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1 }))
  separator.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientX: 800 }))
  await nextTick()
  expect(separator.getAttribute('aria-valuenow')).toBe('50')
})
