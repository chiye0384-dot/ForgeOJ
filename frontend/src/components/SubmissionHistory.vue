<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import {
  getHistory,
  getSubmissionSource,
  type HistoryItem,
  type SubmissionSource,
} from '@/services/learningApi'
import { getSubmission, type SubmissionStatusResponse } from '@/services/forgeojApi'
import { judgeLabel, judgeAdvice, judgeTime } from '@/services/judgePresentation'
const props = defineProps<{
  slug?: string
  userId: number
  active: boolean
  revision?: number
  canLoad?: boolean
}>()
const emit = defineEmits<{ load: [source: SubmissionSource] }>()
const items = ref<HistoryItem[]>([])
const page = ref(1),
  total = ref(0),
  loading = ref(false),
  reading = ref(false)
const source = ref<SubmissionSource | null>(null),
  status = ref<SubmissionStatusResponse | null>(null)
const error = ref('')
let generation = 0,
  selection = 0,
  disposed = false
async function loadPage(next = page.value) {
  const op = ++generation
  selection++
  reading.value = false
  loading.value = true
  source.value = null
  status.value = null
  error.value = ''
  try {
    const result = await getHistory(next, props.slug)
    if (disposed || op !== generation) return
    if (
      result.page !== next ||
      result.size !== 20 ||
      !Array.isArray(result.items) ||
      !Number.isSafeInteger(result.total)
    )
      throw new Error('提交记录响应无效。')
    items.value = result.items
    total.value = result.total
    page.value = next
  } catch {
    if (!disposed && op === generation) error.value = '提交历史暂时无法读取，请重试。'
  } finally {
    if (!disposed && op === generation) loading.value = false
  }
}
async function inspect(id: string) {
  const op = generation,
    seq = ++selection
  reading.value = true
  source.value = null
  status.value = null
  error.value = ''
  try {
    const [code, latest] = await Promise.all([getSubmissionSource(id), getSubmission(id)])
    if (disposed || op !== generation || seq !== selection) return
    if (props.slug && code.problemSlug !== props.slug) throw new Error('记录不属于当前题目。')
    source.value = code
    status.value = latest
  } catch {
    if (!disposed && op === generation && seq === selection)
      error.value = '这条提交无法读取，可能已失去访问权限。'
  } finally {
    if (!disposed && op === generation && seq === selection) reading.value = false
  }
}
watch(
  () => [props.slug, props.userId],
  () => {
    generation++
    selection++
    reading.value = false
    items.value = []
    source.value = null
    status.value = null
    page.value = 1
    if (props.active) void loadPage(1)
  },
)
watch(
  () => [props.active, props.revision],
  () => {
    if (props.active) void loadPage()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  generation++
  selection++
})
</script>
<template>
  <section class="submission-history">
    <h3>提交历史</h3>
    <p>这里保存正式提交；自测记录请到“自定义自测”查看。</p>
    <button type="button" :disabled="loading" @click="loadPage()">刷新提交历史</button>
    <p v-if="loading" role="status">正在读取……</p>
    <p v-else-if="!items.length">暂无本人提交记录。</p>
    <ol>
      <li v-for="item in items" :key="item.submissionId">
        <button type="button" :disabled="reading || loading" @click="inspect(item.submissionId)">
          {{ judgeLabel(item.processingStatus, item.verdict) }} · {{ judgeTime(item.createdAt) }} ·
          版本 {{ item.judgeVersion }}
        </button>
        <p v-if="item.judgeDataWarning" role="alert">{{ item.judgeDataWarning }}</p>
      </li>
    </ol>
    <nav aria-label="提交历史分页">
      <button type="button" :disabled="loading || page <= 1" @click="loadPage(page - 1)">
        上一页记录
      </button>
      <span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span>
      <button type="button" :disabled="loading || page * 20 >= total" @click="loadPage(page + 1)">
        下一页记录
      </button>
    </nav>
    <p v-if="reading" role="status">正在读取本次提交的代码……</p>
    <article v-if="source && status" class="history-detail">
      <h4>本次提交的代码</h4>
      <strong>{{ judgeLabel(status.processingStatus, status.verdict) }}</strong>
      <p>{{ judgeAdvice(status.processingStatus, status.verdict) }}</p>
      <p v-if="status.judgeDataWarning" role="alert">{{ status.judgeDataWarning }}</p>
      <pre v-if="status.diagnosticMessage">{{ status.diagnosticMessage }}</pre>
      <pre data-testid="history-source">{{ source.sourceCode }}</pre>
      <button
        v-if="canLoad && source.problemSlug === slug"
        type="button"
        @click="emit('load', source)"
      >
        载入这份历史代码
      </button>
      <details>
        <summary>提交技术信息</summary>
        <p>
          {{ source.submissionId }} · {{ status.processingStatus }} {{ status.verdict }} ·
          状态版本：{{ status.statusVersion }}
        </p>
      </details>
    </article>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
button {
  margin: 0.3rem 0.4rem 0.3rem 0;
  padding: 0.55rem;
  cursor: pointer;
}
ol {
  padding-left: 1.5rem;
}
pre {
  padding: 0.8rem;
  background: #f1f5f9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.history-detail {
  margin-top: 1rem;
  border-top: 1px solid #d8e0eb;
}
nav {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: wrap;
}
[role='alert'] {
  color: #a52832;
}
</style>
