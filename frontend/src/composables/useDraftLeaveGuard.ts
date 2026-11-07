// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { onBeforeUnmount, onMounted } from 'vue'
import type { useCodeDraft } from './useCodeDraft'

export function useDraftLeaveGuard(draft: ReturnType<typeof useCodeDraft>) {
  function beforeUnload(event: BeforeUnloadEvent) {
    if (!draft.dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  async function prepareToLeave(): Promise<boolean> {
    if (!draft.dirty.value) return true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const saved = await Promise.race([
        draft.flush(),
        new Promise<false>((resolve) => {
          timer = setTimeout(() => resolve(false), 5000)
        }),
      ])
      if (saved) return true
      return window.confirm(
        '代码尚未保存。确定离开并放弃本页未保存的修改吗？取消可留在本页继续保存。',
      )
    } finally {
      clearTimeout(timer)
    }
  }

  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
  return prepareToLeave
}
