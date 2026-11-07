import { afterEach, expect, it } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import CodeEditor from '@/components/CodeEditor.vue'

let app: App
function mount(text: string) {
  const source = ref(text)
  const host = document.createElement('div')
  app = createApp({
    render: () =>
      h(CodeEditor, {
        modelValue: source.value,
        'onUpdate:modelValue': (value: string) => {
          source.value = value
        },
      }),
  })
  app.mount(host)
  return { source, host, area: host.querySelector('textarea')! }
}
afterEach(() => app.unmount())

it('inserts indentation at the caret and preserves the rest of the program', async () => {
  const { source, area } = mount('ab')
  area.setSelectionRange(1, 1)
  area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', cancelable: true }))
  await nextTick()
  expect(source.value).toBe('a    b')
  expect(area.selectionStart).toBe(5)
  const escape = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, cancelable: true })
  area.dispatchEvent(escape)
  expect(escape.defaultPrevented).toBe(false)
})

it('continues indentation after Enter without interfering with IME composition', async () => {
  const { source, area } = mount('    if (true) {')
  area.setSelectionRange(source.value.length, source.value.length)
  area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
  await nextTick()
  expect(source.value).toBe('    if (true) {\n        ')
  const composing = new KeyboardEvent('keydown', {
    key: 'Enter',
    isComposing: true,
    cancelable: true,
  })
  area.dispatchEvent(composing)
  expect(composing.defaultPrevented).toBe(false)
})

it('renders pasted markup only as text and keeps line numbers in sync', async () => {
  const { source, host } = mount('public class Main {\n// <img src=x onerror=alert(1)>\n}')
  expect(host.querySelector('img')).toBeNull()
  expect(host.querySelector('.highlight')?.textContent).toContain('<img src=x onerror=alert(1)>')
  expect(host.querySelector('.gutter')?.textContent).toBe('1\n2\n3')
  source.value += '\n'
  await nextTick()
  expect(host.querySelector('.gutter')?.textContent).toBe('1\n2\n3\n4')
})

it('handles an unfinished block comment while the user is typing', () => {
  const unfinished = '/* unfinished ' + '/*x'.repeat(5000)
  const { host } = mount(unfinished)
  expect(host.querySelector('.token-comment')?.textContent).toBe(unfinished)
})

it('wraps long lines by default and toggles wrapping without changing source or selection', async () => {
  const text = 'String value = "' + 'long-code-'.repeat(30) + '";'
  const { source, host, area } = mount(text)
  expect(area.getAttribute('wrap')).toBe('soft')
  area.setSelectionRange(12, 20)
  const toggle = host.querySelector('input[type="checkbox"]') as HTMLInputElement
  toggle.checked = false
  toggle.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  expect(area.getAttribute('wrap')).toBe('off')
  expect(area.selectionStart).toBe(12)
  expect(area.selectionEnd).toBe(20)
  expect(source.value).toBe(text)
  toggle.checked = true
  toggle.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  expect(area.getAttribute('wrap')).toBe('soft')
  expect(source.value).toBe(text)
})
