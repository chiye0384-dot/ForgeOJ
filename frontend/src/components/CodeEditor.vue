<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { readEditorPreferences, saveEditorPreferences } from '@/services/editorPreferences'

const source = defineModel<string>({ required: true })
const input = ref<HTMLTextAreaElement>()
const highlight = ref<HTMLDivElement>()
const gutter = ref<HTMLPreElement>()
const wrapped = ref(readEditorPreferences().wrapped)
const fontSize = ref(readEditorPreferences().fontSize)
watch([wrapped, fontSize], () =>
  saveEditorPreferences({ wrapped: wrapped.value, fontSize: fontSize.value }),
)
const visualRows = ref<number[]>([])
const surface = ref<HTMLElement>()
const tokens = computed(() => {
  const result: { text: string; kind: string }[] = []
  const pattern =
    /(\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$))|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|\b(public|private|protected|class|static|void|int|long|double|float|boolean|char|byte|short|new|return|if|else|for|while|do|break|continue|import|package|throws|throw|try|catch|finally|final|null|true|false|switch|case|default|extends|implements)\b|\b(\d+(?:\.\d+)?)\b/g
  let end = 0
  for (const match of source.value.matchAll(pattern)) {
    if (match.index > end)
      result.push({ text: source.value.slice(end, match.index), kind: 'plain' })
    result.push({
      text: match[0],
      kind: match[1] ? 'comment' : match[2] ? 'string' : match[3] ? 'keyword' : 'number',
    })
    end = match.index + match[0].length
  }
  result.push({ text: source.value.slice(end), kind: 'plain' })
  return result
})

const lines = computed(() => {
  const result: { text: string; kind: string }[][] = [[]]
  for (const token of tokens.value) {
    const parts = token.text.split('\n')
    parts.forEach((text, index) => {
      if (index) result.push([])
      result[result.length - 1]!.push({ text, kind: token.kind })
    })
  }
  return result
})
const numbers = computed(() =>
  lines.value
    .map(
      (_, index) =>
        String(index + 1) + '\n'.repeat(Math.max(0, (visualRows.value[index] ?? 1) - 1)),
    )
    .join('\n'),
)
let observer: ResizeObserver | undefined
function measureRows() {
  if (!highlight.value) return
  const lineHeight = Number.parseFloat(getComputedStyle(highlight.value).lineHeight) || 22
  visualRows.value = Array.from(highlight.value.children, (line) =>
    Math.max(1, Math.round(line.getBoundingClientRect().height / lineHeight)),
  )
  syncScroll()
}
watch(
  [source, wrapped, fontSize],
  async () => {
    await nextTick()
    if (wrapped.value && input.value) input.value.scrollLeft = 0
    measureRows()
  },
  { flush: 'post' },
)
onMounted(() => {
  measureRows()
  if (typeof ResizeObserver !== 'undefined' && surface.value) {
    observer = new ResizeObserver(measureRows)
    observer.observe(surface.value)
  }
})
onBeforeUnmount(() => observer?.disconnect())

function syncScroll() {
  if (!input.value) return
  if (highlight.value) {
    highlight.value.scrollTop = input.value.scrollTop
    highlight.value.scrollLeft = input.value.scrollLeft
  }
  if (gutter.value) gutter.value.scrollTop = input.value.scrollTop
}

async function editKey(event: KeyboardEvent) {
  const area = input.value
  if (!area || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return
  const start = area.selectionStart
  const end = area.selectionEnd
  if (event.key === 'Tab' && !event.shiftKey) {
    event.preventDefault()
    source.value = source.value.slice(0, start) + '    ' + source.value.slice(end)
    await nextTick()
    area.setSelectionRange(start + 4, start + 4)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const lines = source.value.slice(0, start).split('\n')
    const line = lines[lines.length - 1] ?? ''
    const indent = line.match(/^\s*/)?.[0] ?? ''
    const addition = '\n' + indent + (line.trimEnd().endsWith('{') ? '    ' : '')
    source.value = source.value.slice(0, start) + addition + source.value.slice(end)
    await nextTick()
    area.setSelectionRange(start + addition.length, start + addition.length)
  }
  syncScroll()
}
</script>

<template>
  <div
    class="editor-shell"
    :style="{
      '--editor-font-size': fontSize + 'px',
      '--editor-line-height': Math.round(fontSize * 1.6) + 'px',
    }"
  >
    <div class="editor-toolbar">
      <span>Main.java · Java 21</span>
      <label><input v-model="wrapped" type="checkbox" />自动换行</label>
      <label
        >字号<select v-model.number="fontSize" aria-label="代码字号">
          <option v-for="size in [12, 14, 16, 18, 20]" :key="size" :value="size">{{ size }}</option>
        </select></label
      >
    </div>
    <div class="code-editor" :class="{ wrapped }">
      <pre ref="gutter" class="gutter" aria-hidden="true">{{ numbers }}</pre>
      <div ref="surface" class="editing-surface">
        <div ref="highlight" class="highlight" aria-hidden="true">
          <div v-for="(line, index) in lines" :key="index" class="highlight-line">
            <span v-for="(token, part) in line" :key="part" :class="'token-' + token.kind">{{
              token.text
            }}</span
            ><span v-if="line.every((token) => !token.text)">​</span>
          </div>
        </div>
        <textarea
          ref="input"
          v-model="source"
          class="code-input"
          data-testid="source-code"
          name="sourceCode"
          aria-label="Main.java 代码"
          spellcheck="false"
          autocapitalize="off"
          autocomplete="off"
          :wrap="wrapped ? 'soft' : 'off'"
          required
          @scroll="syncScroll"
          @keydown="editKey"
        />
      </div>
    </div>
    <p class="editor-help">
      Tab 插入四个空格；Shift + Tab 切换焦点。使用 Main 入口和标准输入输出。
    </p>
  </div>
</template>

<style scoped>
.editor-shell {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.45rem 0.75rem;
  color: #516176;
  font-size: 0.8rem;
}
.editor-toolbar label {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  white-space: nowrap;
  cursor: pointer;
}
.editor-toolbar input {
  accent-color: #176b87;
}
.code-editor {
  display: flex;
  flex: 1;
  min-height: 220px;
  overflow: hidden;
  border: 1px solid #b8c4d4;
  border-radius: 8px;
  background: #fbfcfe;
}
.code-editor:focus-within {
  outline: 3px solid rgb(36 143 175 / 18%);
  border-color: #248faf;
}
.gutter,
.highlight,
.code-input {
  box-sizing: border-box;
  height: 100%;
  margin: 0;
  padding: 14px;
  font:
    400 var(--editor-font-size, 14px) / var(--editor-line-height, 22px) 'Cascadia Code',
    Consolas,
    monospace;
  letter-spacing: normal;
  tab-size: 4;
  white-space: pre;
}
.gutter {
  width: 3.4rem;
  flex-shrink: 0;
  overflow: hidden;
  padding-right: 0.65rem;
  padding-left: 0.3rem;
  border-right: 1px solid #e2e7ef;
  color: #7b8799;
  text-align: right;
  user-select: none;
}
.editing-surface {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.highlight {
  position: absolute;
  inset: 0;
  overflow: scroll;
  scrollbar-color: transparent transparent;
  color: #152033;
  pointer-events: none;
}
.highlight-line {
  min-height: var(--editor-line-height, 22px);
}
.code-input {
  position: relative;
  display: block;
  width: 100%;
  resize: none;
  border: 0;
  border-radius: 0;
  outline: none;
  overflow: scroll;
  background: transparent;
  color: transparent;
  caret-color: #152033;
  -webkit-text-fill-color: transparent;
}
.wrapped .highlight,
.wrapped .code-input {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  overflow-x: hidden;
}
.code-input::selection {
  background: rgb(36 143 175 / 25%);
}
.token-keyword {
  color: #7541a1;
}
.token-comment {
  color: #497c52;
}
.token-string {
  color: #a24623;
}
.token-number {
  color: #146c8b;
}
.editor-help {
  flex-shrink: 0;
  margin: 0.4rem 0 0;
  color: #637083;
  font-size: 0.75rem;
  font-weight: 400;
}
@media (forced-colors: active) {
  .highlight {
    visibility: hidden;
  }
  .code-input {
    color: CanvasText;
    -webkit-text-fill-color: CanvasText;
  }
}
</style>
