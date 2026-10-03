<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import {
  confirmSolution,
  readSolution,
  validSolution,
  type SolutionAccess,
} from '@/services/solutionApi'

const props = defineProps<{
  slug: string
  judgeVersion: number
  userId: number
  csrf: CsrfToken
  refreshKey: number
}>()
const state = ref<SolutionAccess | null>(null)
const opened = ref(false)
const confirming = ref(false)
const busy = ref(false)
const error = ref('')
let generation = 0
let disposed = false
let refreshPending = false
async function load(early = false): Promise<void> {
  if (busy.value) return
  const operation = ++generation
  busy.value = true
  opened.value = true
  confirming.value = false
  error.value = ''
  state.value = null
  try {
    const value = early
      ? await confirmSolution(props.slug, props.judgeVersion, props.csrf)
      : await readSolution(props.slug)
    if (disposed || operation !== generation) return
    if (!validSolution(value, props.judgeVersion))
      throw new Error('题解版本不一致，请重新载入题目。')
    state.value = value
  } catch (cause) {
    if (disposed || operation !== generation) return
    error.value =
      cause instanceof ApiRequestError && cause.status === 409
        ? '题目版本已更新，请重新载入题目后再决定是否查看。'
        : cause instanceof Error
          ? cause.message
          : '题解读取失败，请重试。'
  } finally {
    if (!disposed && operation === generation) {
      busy.value = false
      if (refreshPending) {
        refreshPending = false
        void load()
      }
    }
  }
}
watch(
  () => [props.slug, props.judgeVersion, props.userId],
  () => {
    generation += 1
    state.value = null
    error.value = ''
    opened.value = false
    confirming.value = false
    busy.value = false
    refreshPending = false
  },
  { flush: 'sync' },
)
watch(
  () => props.refreshKey,
  () => {
    if (!opened.value) return
    if (busy.value) refreshPending = true
    else void load()
  },
)
onBeforeUnmount(() => {
  disposed = true
  generation += 1
  state.value = null
})
</script>

<template>
  <section class="official-solution" aria-live="polite">
    <h3>官方题解</h3>
    <button type="button" :disabled="busy" @click="load()">
      {{ busy ? '正在读取……' : opened ? '重新读取题解状态' : '查看官方题解' }}
    </button>
    <p v-if="state?.access === 'UNAVAILABLE'">当前版本题解暂未提供。</p>
    <template v-if="state?.access === 'LOCKED'">
      <p>通过当前版本后自动解锁，也可以选择提前查看。</p>
      <button v-if="!confirming" type="button" @click="confirming = true">提前查看</button>
      <div v-else role="alert">
        <p>提前查看可能影响独立思考。确认后仅在你的个人学习记录中保存，不影响继续提交。</p>
        <button type="button" @click="load(true)">确认提前查看当前版本</button>
        <button type="button" @click="confirming = false">取消</button>
      </div>
    </template>
    <template v-if="state?.solution">
      <p>
        {{ state.access === 'AC' ? '已通过当前版本，题解已解锁。' : '你已确认提前查看当前版本。' }}
      </p>
      <h4>核心思路</h4>
      <p class="idea">{{ state.solution.idea }}</p>
      <h4>Java 21 代码</h4>
      <pre>{{ state.solution.sourceCode }}</pre>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.official-solution {
  margin-top: 1.5rem;
  border-top: 1px solid #d8e0eb;
  padding-top: 1rem;
}
button {
  margin: 0.25rem 0.5rem 0.25rem 0;
  padding: 0.6rem;
  cursor: pointer;
}
pre {
  overflow: auto;
  white-space: pre;
  background: #f1f5f9;
  padding: 1rem;
}
.idea {
  white-space: pre-wrap;
}
</style>
