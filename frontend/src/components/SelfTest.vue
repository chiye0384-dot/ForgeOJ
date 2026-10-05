<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import {
  createSelfTest,
  readSelfTest,
  listSelfTests,
  cancelSelfTest,
  terminalSelfTest,
  validSelfTest,
  type SelfTestRequest,
  type SelfTestRun,
  type SelfTestDetail,
  type SelfTestPage,
} from '@/services/selfTestApi'

const props = defineProps<{ slug: string; sourceCode: string; userId: number; csrf: CsrfToken }>()
const input = ref('')
const active = ref<SelfTestRun | null>(null)
const detail = ref<SelfTestDetail | null>(null)
const history = ref<SelfTestPage | null>(null)
const busy = ref(false)
const error = ref('')
const pending = ref<SelfTestRequest | null>(null)
const paused = ref(false)
let generation = 0
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
let polls = 0
let historySequence = 0
function current(op: number): boolean {
  return !disposed && op === generation
}
function stop(): void {
  clearTimeout(timer)
  timer = undefined
}
function manualRefresh(): void {
  polls = 0
  void refresh()
}
function checked(run: SelfTestRun): void {
  if (!validSelfTest(run, props.slug)) throw new Error('自测响应无效，请刷新。')
}
function message(cause: unknown): string {
  if (cause instanceof ApiRequestError) {
    if (cause.status === 429) return '当前等待队列已满，请稍后重试。'
    if (cause.status === 404 || cause.status === 410) return '自测记录已过期或不可访问。'
    if (cause.status === 409) return '自测状态已变化，请刷新。'
  }
  return cause instanceof Error ? cause.message : '自测请求失败，请重试。'
}
async function historyLoad(page = 1): Promise<void> {
  const op = generation
  const sequence = ++historySequence
  try {
    const value = await listSelfTests(props.slug, page)
    if (!current(op) || sequence !== historySequence) return
    if (
      !value ||
      value.page !== page ||
      value.size !== 10 ||
      !Number.isSafeInteger(value.total) ||
      value.total < 0 ||
      !Array.isArray(value.items) ||
      value.items.length > 10 ||
      value.items.some((item) => !validSelfTest(item, props.slug))
    )
      throw new Error('自测记录响应无效。')
    history.value = value
  } catch (cause) {
    if (current(op) && sequence === historySequence) error.value = message(cause)
  }
}
function schedule(op: number, id: string): void {
  if (!current(op) || !active.value || terminalSelfTest(active.value)) return
  if (polls >= 120) {
    paused.value = true
    return
  }
  timer = setTimeout(() => {
    polls++
    void refresh(op, id)
  }, 1000)
}
async function refresh(op = generation, id = active.value?.runId): Promise<void> {
  if (!id || !current(op)) return
  stop()
  try {
    const value = await readSelfTest(id)
    if (!current(op) || active.value?.runId !== id) return
    checked(value.run)
    if (
      value.run.runId !== id ||
      typeof value.sourceCode !== 'string' ||
      typeof value.input !== 'string' ||
      (value.run.executionResult === 'SUCCESS'
        ? typeof value.output !== 'string'
        : value.output !== null)
    )
      throw new Error('自测快照无效。')
    if (value.run.statusVersion < active.value.statusVersion)
      throw new Error('自测状态已过期，请刷新。')
    active.value = value.run
    detail.value = value
    error.value = ''
    paused.value = false
    if (terminalSelfTest(value.run)) void historyLoad()
    else schedule(op, id)
  } catch (cause) {
    if (current(op) && active.value?.runId === id) {
      error.value = message(cause)
      paused.value = true
      if (cause instanceof ApiRequestError && (cause.status === 404 || cause.status === 410)) {
        detail.value = null
        active.value = null
      }
    }
  }
}
async function run(retry = false): Promise<void> {
  if (busy.value) return
  stop()
  const op = ++generation
  polls = 0
  busy.value = true
  error.value = ''
  paused.value = false
  active.value = null
  detail.value = null
  if (!retry || !pending.value)
    pending.value = {
      requestId: crypto.randomUUID(),
      language: 'JAVA_21',
      sourceCode: props.sourceCode,
      input: input.value,
    }
  const captured = pending.value
  try {
    const value = await createSelfTest(props.slug, captured, props.csrf)
    if (!current(op)) return
    checked(value)
    active.value = value
    pending.value = null
    await refresh(op, value.runId)
  } catch (cause) {
    if (current(op)) {
      error.value = message(cause)
      // Only network/server ambiguity keeps the exact original request available for replay.
      if (cause instanceof ApiRequestError && cause.status < 500) pending.value = null
    }
  } finally {
    if (current(op)) busy.value = false
  }
}
async function select(value: SelfTestRun): Promise<void> {
  if (busy.value || pending.value) return
  stop()
  generation++
  polls = 0
  checked(value)
  active.value = value
  detail.value = null
  error.value = ''
  paused.value = false
  await refresh(generation, value.runId)
}
async function cancel(): Promise<void> {
  if (!active.value || busy.value) return
  const op = generation,
    id = active.value.runId
  busy.value = true
  stop()
  try {
    const value = await cancelSelfTest(id, props.csrf)
    if (current(op) && active.value?.runId === id) {
      checked(value)
      active.value = value
      await refresh(op, id)
    }
  } catch (cause) {
    if (current(op)) {
      error.value = message(cause)
      paused.value = true
    }
  } finally {
    if (current(op)) busy.value = false
  }
}
watch(
  () => [props.slug, props.userId],
  () => {
    stop()
    generation++
    input.value = ''
    active.value = null
    detail.value = null
    history.value = null
    pending.value = null
    busy.value = false
    error.value = ''
    paused.value = false
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  generation++
  stop()
  pending.value = null
  detail.value = null
})
</script>

<template>
  <section class="self-test" aria-live="polite">
    <h3>自定义输入自测</h3>
    <p>
      运行当前编辑器的代码。自测成功不计为通过题目，也不解锁题解。完成后的代码、输入和输出保留 24
      小时。
    </p>
    <label for="self-test-input">自定义输入（可以为空）</label>
    <textarea id="self-test-input" v-model="input" rows="4" data-testid="self-test-input" />
    <button type="button" :disabled="busy || !!pending" @click="run()">
      {{ busy ? '正在处理……' : '运行自测' }}
    </button>
    <template v-if="pending">
      <p>尚未确认原请求结果。重试会使用点击时冻结的代码与输入。</p>
      <button type="button" :disabled="busy" @click="run(true)">重试原自测请求</button>
      <button type="button" :disabled="busy" @click="pending = null">放弃重试，允许新请求</button>
    </template>
    <div v-if="active" data-testid="self-test-result">
      <p>自测状态：{{ active.processingStatus }} · {{ active.executionResult ?? '等待结果' }}</p>
      <p v-if="active.executionResult === 'SUCCESS'">运行成功，仅表示这组自定义输入正常执行。</p>
      <button type="button" :disabled="busy" @click="manualRefresh">刷新自测结果</button>
      <button
        v-if="active.processingStatus === 'QUEUED'"
        type="button"
        :disabled="busy"
        @click="cancel()"
      >
        取消排队自测
      </button>
      <p v-if="paused">自动刷新已暂停，可以手动刷新继续读取。</p>
      <template v-if="detail">
        <h4>本次冻结输入</h4>
        <pre>{{ detail.input }}</pre>
        <h4 v-if="detail.output !== null">实际标准输出</h4>
        <pre v-if="detail.output !== null" data-testid="self-test-output">{{ detail.output }}</pre>
        <details>
          <summary>本次冻结代码</summary>
          <pre>{{ detail.sourceCode }}</pre>
        </details>
      </template>
    </div>
    <button type="button" :disabled="busy || !!pending" @click="historyLoad()">
      刷新短期自测记录
    </button>
    <ul v-if="history">
      <li v-for="item in history.items" :key="item.runId">
        <button type="button" :disabled="busy || !!pending" @click="select(item)">
          {{ item.processingStatus }} · {{ item.executionResult ?? '等待结果' }} ·
          {{ item.runId.slice(0, 8) }}
        </button>
      </li>
    </ul>
    <template v-if="history">
      <button
        type="button"
        :disabled="busy || history.page <= 1"
        @click="historyLoad(history.page - 1)"
      >
        上一页自测
      </button>
      <button
        type="button"
        :disabled="busy || history.page * history.size >= history.total"
        @click="historyLoad(history.page + 1)"
      >
        下一页自测
      </button>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.self-test {
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid #d8e0eb;
}
textarea {
  display: block;
  width: 100%;
  font-family: monospace;
}
button {
  margin: 0.3rem 0.5rem 0.3rem 0;
  padding: 0.6rem;
  cursor: pointer;
}
pre {
  overflow: auto;
  white-space: pre;
  background: #f1f5f9;
  padding: 1rem;
}
</style>
