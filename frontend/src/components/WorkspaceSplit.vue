<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { readEditorPreferences, saveEditorPreferences } from '@/services/editorPreferences'

defineProps<{ expanded: boolean }>()
const surface = ref<HTMLElement>()
const width = ref(1200)
const share = ref(readEditorPreferences().share)
const resizing = ref(false)
const minimum = computed(() => Math.max(22, (280 / width.value) * 100))
const maximum = computed(() =>
  Math.max(minimum.value, Math.min(65, 100 - (412 / width.value) * 100)),
)
let observer: ResizeObserver | undefined
function setShare(value: number) {
  share.value = Math.max(minimum.value, Math.min(maximum.value, value))
  saveEditorPreferences({ share: share.value })
}
function start(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  resizing.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}
function move(event: PointerEvent) {
  if (!resizing.value || !surface.value) return
  const box = surface.value.getBoundingClientRect()
  if (box.width) setShare(((event.clientX - box.left) / box.width) * 100)
}
function stop() {
  resizing.value = false
}
function key(event: KeyboardEvent) {
  const values: Record<string, number> = {
    ArrowLeft: share.value - 2,
    ArrowRight: share.value + 2,
    Home: minimum.value,
    End: maximum.value,
  }
  if (event.key in values) {
    event.preventDefault()
    setShare(values[event.key]!)
  }
}
onMounted(() => {
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(() => {
    if (!window.matchMedia('(min-width: 900px)').matches) return
    width.value = surface.value?.clientWidth || 1200
    setShare(share.value)
  })
  if (surface.value) observer.observe(surface.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div
    ref="surface"
    class="split-workspace"
    :class="{ expanded, resizing }"
    :style="{ '--problem-width': `${share}%` }"
  >
    <div v-show="!expanded" class="left-pane"><slot name="left" /></div>
    <div
      v-show="!expanded"
      class="splitter"
      role="separator"
      aria-label="调整题目与代码区域宽度"
      aria-orientation="vertical"
      :aria-valuemin="Math.round(minimum)"
      :aria-valuemax="Math.round(maximum)"
      :aria-valuenow="Math.round(share)"
      tabindex="0"
      title="左右拖动调整宽度，双击恢复；也可使用左右方向键"
      @pointerdown="start"
      @pointermove="move"
      @pointerup="stop"
      @pointercancel="stop"
      @lostpointercapture="stop"
      @keydown="key"
      @dblclick="setShare(38)"
    >
      <span aria-hidden="true">⋮</span>
    </div>
    <div class="right-pane"><slot name="right" /></div>
  </div>
</template>

<style scoped>
.split-workspace {
  display: grid;
  grid-template-columns: minmax(280px, var(--problem-width)) 12px minmax(0, 1fr);
  height: calc(100dvh - 10rem);
  min-height: 500px;
}
.left-pane,
.right-pane {
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.splitter {
  display: grid;
  place-items: center;
  cursor: col-resize;
  touch-action: none;
  color: #718198;
  border-radius: 6px;
}
.splitter:hover,
.splitter:focus-visible,
.resizing .splitter {
  background: #d9edf5;
  outline: 2px solid #248faf;
  outline-offset: -3px;
}
.resizing {
  user-select: none;
}
.expanded {
  grid-template-columns: minmax(0, 1fr);
}
@media (max-width: 899px) {
  .split-workspace {
    display: flex;
    flex-direction: column;
    gap: 1rem;
    height: auto;
    min-height: 0;
  }
  .splitter {
    display: none;
  }
  .left-pane {
    height: clamp(280px, 65dvh, 520px);
    max-height: none;
    min-height: 0;
  }
  .right-pane {
    height: max(560px, 80dvh);
  }
}
</style>
