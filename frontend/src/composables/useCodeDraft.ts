// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import { getCodeDraft, saveCodeDraft } from '@/services/learningApi'

export function useCodeDraft(slug: string, source: Ref<string>) {
  const message = ref('登录后读取草稿。')
  const conflict = ref(false)
  const ready = ref(false)
  const dirty = ref(false)
  let version = 0
  let generation = 0
  let csrf: CsrfToken | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let busy = false
  let saved = ''
  let paused = false
  function clearTimer() {
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  function reset() {
    generation += 1
    clearTimer()
    csrf = undefined
    ready.value = false
    conflict.value = false
    dirty.value = false
    busy = false
    paused = false
    message.value = '登录后读取草稿。'
  }
  async function load(token: CsrfToken) {
    reset()
    csrf = token
    const operation = generation
    message.value = '正在读取草稿……'
    try {
      const draft = await getCodeDraft(slug)
      if (operation !== generation) return
      if (draft.sourceCode !== null) source.value = draft.sourceCode
      version = draft.version
      saved = source.value
      ready.value = draft.editable
      dirty.value = false
      message.value = draft.editable
        ? '草稿已同步；停止编辑 1 秒后自动保存。'
        : '题目暂不可用，草稿仅可读取。'
    } catch {
      if (operation === generation) message.value = '草稿读取失败，本页代码保留；请重试读取。'
    }
  }
  function schedule() {
    clearTimer()
    if (ready.value && !paused && !busy && dirty.value)
      timer = setTimeout(() => {
        void save()
      }, 1000)
  }
  async function save() {
    if (!ready.value || paused || busy || !csrf || !dirty.value) return
    const operation = generation
    const snapshot = source.value
    busy = true
    clearTimer()
    message.value = '正在保存草稿……'
    try {
      const draft = await saveCodeDraft(slug, snapshot, version, csrf)
      if (operation !== generation) return
      version = draft.version
      saved = snapshot
      dirty.value = source.value !== saved
      message.value = dirty.value ? '有新编辑尚未保存。' : '草稿已保存。'
    } catch (error) {
      if (operation !== generation) return
      if (error instanceof ApiRequestError && error.status === 409) {
        paused = true
        conflict.value = true
        message.value = '草稿冲突：另一个页面已保存新版本。请选择如何处理。'
      } else {
        paused = true
        message.value = '草稿未保存，本页代码保留；可手动重试。'
      }
    } finally {
      if (operation === generation) {
        busy = false
        schedule()
      }
    }
  }
  async function useServer() {
    if (csrf) await load(csrf)
  }
  function keepLocal() {
    paused = true
    message.value = '已保留本页代码，自动保存暂停。可继续编辑或选择保存本页版本。'
  }
  async function saveLocal() {
    if (!csrf || busy) return
    const operation = generation
    busy = true
    clearTimer()
    paused = true
    try {
      const latest = await getCodeDraft(slug)
      if (operation !== generation) return
      if (!latest.editable) {
        message.value = '题目暂不可用，草稿未保存。'
        return
      }
      version = latest.version
      ready.value = true
      dirty.value = true
      paused = false
      conflict.value = false
    } catch {
      if (operation === generation) message.value = '最新版本读取失败，本页代码保留。'
    } finally {
      if (operation === generation) busy = false
    }
    if (operation === generation && !paused) await save()
  }
  async function retry() {
    if (!ready.value) {
      if (csrf) await load(csrf)
      return
    }
    paused = false
    await save()
  }
  watch(
    source,
    () => {
      if (!ready.value) return
      dirty.value = source.value !== saved
      if (dirty.value && !paused) message.value = '有新编辑尚未保存。'
      schedule()
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(reset)
  return { message, conflict, ready, dirty, load, reset, useServer, keepLocal, saveLocal, retry }
}
