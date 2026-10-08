<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import { authoredDetail, type AuthoredSummary } from '@/services/contentApi'
import { listValidations, type ContentValidation } from '@/services/validationApi'
import {
  listReviews,
  readReview,
  submitReview,
  withdrawReview,
  type ContentReview,
  type ReviewDetail,
} from '@/services/reviewApi'
const props = defineProps<{
  draftId: string
  version: number
  status: string
  userId: number
  csrf: CsrfToken
  editingBusy: boolean
}>()
const emit = defineEmits<{
  workflow: [summary: AuthoredSummary]
  conflict: []
  busy: [value: boolean]
}>()
const busy = ref(false),
  message = ref(''),
  validations = ref<ContentValidation[]>([]),
  reviews = ref<ContentReview[]>([])
const validationPage = ref(1),
  validationTotal = ref(0),
  page = ref(1),
  total = ref(0),
  selectedJob = ref('')
const frozen = ref<ReviewDetail | null>(null)
const eligible = computed(() =>
  validations.value.filter(
    (v) =>
      v.draftVersion === props.version &&
      !v.stale &&
      v.validationStatus === 'PASSED' &&
      v.referenceResult === 'ACCEPTED' &&
      v.solutionResult === 'ACCEPTED',
  ),
)
let generation = 0,
  disposed = false
let pending: { draft: string; version: number; job: string; request: string } | null = null
function current(g: number) {
  return !disposed && g === generation
}
function failure(e: unknown) {
  if (e instanceof ApiRequestError && e.status === 409) {
    emit('conflict')
    return '版本或送审状态已变化，请载入服务端版本。本地编辑已保留。'
  }
  if (e instanceof ApiRequestError && e.status === 401) return '登录已失效，请重新登录。'
  return e instanceof Error ? e.message : '送审服务暂时不可用。'
}
async function load(reviewPage = page.value, passedPage = validationPage.value) {
  if (busy.value || props.editingBusy) return
  const g = ++generation
  busy.value = true
  message.value = ''
  try {
    const [history, verified] = await Promise.all([
      listReviews(props.draftId, reviewPage),
      listValidations(props.draftId, passedPage),
    ])
    if (!current(g)) return
    reviews.value = history.items
    page.value = history.page
    total.value = history.total
    validations.value = verified.items
    validationPage.value = verified.page
    validationTotal.value = verified.total
    if (!eligible.value.some((v) => v.jobId === selectedJob.value))
      selectedJob.value = eligible.value[0]?.jobId ?? ''
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
async function mutate(review?: ContentReview) {
  if (
    busy.value ||
    props.editingBusy ||
    (!review &&
      (props.status !== 'DRAFT' || !eligible.value.some((v) => v.jobId === selectedJob.value)))
  )
    return
  const g = ++generation,
    draft = props.draftId,
    version = props.version
  busy.value = true
  message.value = ''
  try {
    if (review) await withdrawReview(draft, review, props.csrf)
    else {
      if (
        !pending ||
        pending.draft !== draft ||
        pending.version !== version ||
        pending.job !== selectedJob.value
      )
        pending = { draft, version, job: selectedJob.value, request: crypto.randomUUID() }
      await submitReview(draft, version, pending.job, pending.request, props.csrf)
    }
    if (!current(g)) return
    const [author, history] = await Promise.all([authoredDetail(draft), listReviews(draft, 1)])
    if (!current(g)) return
    pending = null
    reviews.value = history.items
    page.value = 1
    total.value = history.total
    frozen.value = null
    message.value = review
      ? '已撤回该送审版本，原快照保留。'
      : '已提交已保存的冻结版本；审核期间请先撤回再修改。'
    emit('workflow', author.draft)
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
async function inspect(review: ContentReview) {
  if (busy.value || props.editingBusy) return
  const g = ++generation
  busy.value = true
  message.value = ''
  try {
    const detail = await readReview(props.draftId, review.reviewId)
    if (current(g)) frozen.value = detail
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
watch(busy, (value) => emit('busy', value))
watch(
  () => [props.userId, props.draftId, props.version],
  () => {
    generation++
    busy.value = false
    pending = null
    validations.value = []
    reviews.value = []
    frozen.value = null
    selectedJob.value = ''
    page.value = 1
    total.value = 0
    validationPage.value = 1
    validationTotal.value = 0
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
  <section aria-label="不可变送审" data-testid="content-review">
    <h4>送审与撤回</h4>
    <p>
      只送审当前已保存、双程序验证通过的版本；本页未保存内容不会提交。审核期间须先撤回再修改，历史快照永久保留。
    </p>
    <button :disabled="busy || editingBusy" @click="load()">载入通过验证与送审历史</button>
    <label
      >选择当前版本通过记录
      <select v-model="selectedJob" :disabled="busy || editingBusy || status !== 'DRAFT'">
        <option value="">请选择验证记录</option>
        <option v-for="v in eligible" :key="v.jobId" :value="v.jobId">
          版本 {{ v.draftVersion }} · {{ v.jobId }}
        </option>
      </select></label
    >
    <button :disabled="busy || editingBusy || status !== 'DRAFT' || !selectedJob" @click="mutate()">
      送审当前已保存版本
    </button>
    <p v-if="status === 'UNDER_REVIEW'">当前版本审核中，编辑与新验证已暂停；可撤回后继续编辑。</p>
    <button
      :disabled="busy || editingBusy || validationPage <= 1"
      @click="load(page, validationPage - 1)"
    >
      上一页验证
    </button>
    <button
      :disabled="busy || editingBusy || validationPage * 20 >= validationTotal"
      @click="load(page, validationPage + 1)"
    >
      下一页验证
    </button>
    <ul>
      <li v-for="r in reviews" :key="r.reviewId">
        第 {{ r.reviewNo }} 次送审 · 冻结草稿版本 {{ r.draftVersion }} ·
        {{
          { PENDING: '待审核', WITHDRAWN: '已撤回', APPROVED: '已批准', REJECTED: '已驳回' }[
            r.status
          ]
        }}
        <p v-if="r.decisionReason">审核说明：{{ r.decisionReason }}</p>
        <RouterLink v-if="r.publishedSlug" :to="`/problems/${encodeURIComponent(r.publishedSlug)}`"
          >查看公开题目</RouterLink
        >
        <button :disabled="busy || editingBusy" @click="inspect(r)">
          查看第 {{ r.reviewNo }} 次冻结快照
        </button>
        <button v-if="r.status === 'PENDING'" :disabled="busy || editingBusy" @click="mutate(r)">
          撤回第 {{ r.reviewNo }} 次送审
        </button>
      </li>
    </ul>
    <button :disabled="busy || editingBusy || page <= 1" @click="load(page - 1)">上一页送审</button>
    <button :disabled="busy || editingBusy || page * 20 >= total" @click="load(page + 1)">
      下一页送审
    </button>
    <article v-if="frozen" data-testid="review-snapshot">
      <h5>第 {{ frozen.review.reviewNo }} 次送审快照 · 版本 {{ frozen.review.draftVersion }}</h5>
      <p>{{ frozen.content.metadata.title }} · {{ frozen.testCount }} 组冻结测试</p>
      <pre>{{ frozen.content.metadata.statement }}</pre>
      <p>输入：{{ frozen.content.metadata.inputDescription }}</p>
      <p>输出：{{ frozen.content.metadata.outputDescription }}</p>
      <p>来源：{{ frozen.content.metadata.originType }} {{ frozen.content.metadata.sourceUrl }}</p>
      <p>授权：{{ frozen.content.metadata.licenseStatement }}</p>
      <pre>{{ frozen.content.solutionIdea }}</pre>
      <details>
        <summary>冻结参考程序</summary>
        <pre>{{ frozen.content.referenceCode }}</pre>
      </details>
      <details>
        <summary>冻结独立题解</summary>
        <pre>{{ frozen.content.solutionCode }}</pre>
      </details>
    </article>
    <p role="status">{{ message }}</p>
  </section>
</template>
