<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import {
  authoredDetail,
  authoredTests,
  type AuthoredSummary,
  type AuthoredTest,
} from '@/services/contentApi'
import {
  createOutput,
  readOutput,
  listOutputs,
  acceptOutput,
  type OutputPreview,
  type OutputDetail,
} from '@/services/outputApi'
const props = defineProps<{
  draftId: string
  version: number
  status: string
  userId: number
  csrf: CsrfToken
  editingBusy: boolean
}>()
const emit = defineEmits<{
  busy: [value: boolean]
  conflict: []
  applied: [summary: AuthoredSummary, tests: AuthoredTest[]]
}>()
const busy = ref(false),
  message = ref(''),
  detail = ref<OutputDetail | null>(null),
  history = ref<OutputPreview[]>([]),
  page = ref(1),
  total = ref(0)
let generation = 0,
  disposed = false
let pending: { id: string; version: number } | null = null
const canAccept = computed(
  () =>
    detail.value &&
    !busy.value &&
    !props.editingBusy &&
    props.status === 'DRAFT' &&
    !detail.value.preview.stale &&
    detail.value.preview.draftVersion === props.version &&
    detail.value.preview.processingStatus === 'FINISHED' &&
    detail.value.preview.referenceResult === 'ACCEPTED' &&
    detail.value.preview.acceptedVersion === null,
)
function current(g: number) {
  return !disposed && generation === g
}
function failure(e: unknown) {
  if (e instanceof ApiRequestError && e.status === 409) {
    emit('conflict')
    return '版本或状态已变化，请保留本地内容并显式重新载入草稿。'
  }
  if (e instanceof ApiRequestError && e.status === 429) return '排队额度已满，请稍后重试。'
  if (e instanceof ApiRequestError && e.status === 401) return '登录已失效，请重新登录。'
  return e instanceof Error ? e.message : '操作失败，请重试。'
}
async function operate(action: (g: number) => Promise<void>) {
  if (busy.value || props.editingBusy) return
  const g = ++generation
  busy.value = true
  emit('busy', true)
  message.value = ''
  try {
    await action(g)
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) {
      busy.value = false
      emit('busy', false)
    }
  }
}
async function generate() {
  if (props.status !== 'DRAFT') return
  await operate(async (g) => {
    pending ??= { id: crypto.randomUUID(), version: props.version }
    const job = await createOutput(props.draftId, pending.version, pending.id, props.csrf)
    if (!current(g)) return
    const result = await readOutput(props.draftId, job.jobId)
    if (!current(g)) return
    detail.value = result
    pending = null
    message.value = '预览已创建；不会自动修改测试答案。'
  })
}
async function inspect(job: string) {
  await operate(async (g) => {
    const result = await readOutput(props.draftId, job)
    if (current(g)) detail.value = result
  })
}
async function load() {
  await operate(async (g) => {
    const result = await listOutputs(props.draftId, page.value)
    if (current(g)) {
      history.value = result.items
      total.value = result.total
    }
  })
}
async function confirm() {
  if (!canAccept.value || !detail.value) return
  const job = detail.value.preview.jobId,
    version = detail.value.preview.draftVersion
  await operate(async (g) => {
    await acceptOutput(props.draftId, job, version, props.csrf)
    if (!current(g)) return
    const [saved, tests] = await Promise.all([
      authoredDetail(props.draftId),
      authoredTests(props.draftId),
    ])
    if (!current(g)) return
    emit('applied', saved.draft, tests)
    message.value = '已确认替换全部测试答案；请对新版本重新执行双程序验证。'
  })
}
function movePage(delta: number) {
  page.value += delta
  void load()
}
watch(
  () => [props.draftId, props.userId, props.version, props.status],
  () => {
    generation++
    busy.value = false
    emit('busy', false)
    pending = null
    detail.value = null
    history.value = []
    page.value = 1
    total.value = 0
    message.value = ''
  },
)
onBeforeUnmount(() => {
  disposed = true
  generation++
  emit('busy', false)
})
</script>

<template>
  <section aria-label="参考输出预览" data-testid="content-output">
    <h4>参考输出预览</h4>
    <p>仅运行当前已保存的参考程序和测试输入；忽略原答案，不执行题解，不产生验证通过或正式成绩。</p>
    <button :disabled="busy || editingBusy || status !== 'DRAFT'" @click="generate">
      生成当前已保存输入的输出预览
    </button>
    <button :disabled="busy || editingBusy" @click="load">查看输出预览历史</button>
    <ul>
      <li v-for="v in history" :key="v.jobId">
        版本 {{ v.draftVersion }} · {{ v.processingStatus }} · {{ v.referenceResult ?? '等待执行' }}
        <button :disabled="busy || editingBusy" @click="inspect(v.jobId)">
          查看输出 {{ v.jobId }}
        </button>
      </li>
    </ul>
    <button :disabled="busy || editingBusy || page <= 1" @click="movePage(-1)">上一页预览</button>
    <button :disabled="busy || editingBusy || page * 20 >= total" @click="movePage(1)">
      下一页预览
    </button>
    <article v-if="detail" data-testid="output-preview-result" :data-job-id="detail.preview.jobId">
      <p>
        冻结版本 {{ detail.preview.draftVersion }} · {{ detail.preview.processingStatus }} ·
        {{ detail.preview.referenceResult ?? '等待执行' }}
      </p>
      <p v-if="detail.preview.stale || detail.preview.draftVersion !== version">
        此预览已失效，只能查看历史。
      </p>
      <p v-if="detail.preview.acceptedVersion">
        已确认为版本 {{ detail.preview.acceptedVersion }}，不会再次覆盖测试。
      </p>
      <button :disabled="busy || editingBusy" @click="inspect(detail.preview.jobId)">
        刷新输出预览
      </button>
      <div v-for="c in detail.cases" :key="c.sequence">
        <h5>测试 {{ c.sequence }}</h5>
        <p>冻结输入</p>
        <pre>{{ c.input }}</pre>
        <p>原正确输出</p>
        <pre>{{ c.previousOutput }}</pre>
        <p>生成输出</p>
        <pre>{{ c.generatedOutput ?? '无可确认输出' }}</pre>
      </div>
      <p>确认将替换已保存的全部测试答案及本页测试列表，并使旧验证失效；未保存的题面和代码保留。</p>
      <button :disabled="!canAccept" @click="confirm">确认使用这组生成输出</button>
    </article>
    <p role="status">{{ message }}</p>
  </section>
</template>
