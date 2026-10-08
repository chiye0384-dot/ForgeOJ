<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import {
  adminAction,
  adminRequest,
  restoreAdmin,
  AdminRequestError,
  type AdminSession,
  type AdminPage,
} from '@/services/adminApi'
import type { ContentMetadata } from '@/services/contentApi'
const props = defineProps<{ section: 'reviews' | 'problems' | 'feedback' }>()
interface Entry {
  id: string | number
  title: string
  status: string
  version: number
  slug?: string
  dataInvalid?: boolean
  invalidReason?: string
  stateReason?: string
  decisionReason?: string
  publishedSlug?: string
  reports?: number
}
interface Frozen {
  revision?: {
    problemId: number
    expectedVersion: number
    revisionKind: 'TEXT' | 'CORRECTION'
  } | null
  review: Entry
  metadata: ContentMetadata
  solutionIdea: string
  solutionCode: string
  testCount: number
  validation: {
    processingStatus: string
    validationStatus: string
    referenceResult: string
    solutionResult: string
  }
}
interface Report {
  id: string
  userId: number
  category: string
  body: string
}
const session = ref<AdminSession | null>(null),
  items = ref<Entry[]>([]),
  selected = ref<Entry | null>(null)
const frozen = ref<Frozen | null>(null),
  reference = ref(''),
  reports = ref<Report[]>([])
const page = ref(1),
  total = ref(0),
  reportPage = ref(1),
  reportTotal = ref(0),
  reason = ref(''),
  message = ref(''),
  busy = ref(false),
  acknowledged = ref(false)
const allowed = computed(
  () =>
    session.value?.authenticated &&
    !session.value.admin?.mustChangePassword &&
    ['CONTENT_REVIEWER', 'SUPER_ADMIN'].includes(session.value.admin?.role ?? ''),
)
const canApprove = computed(
  () =>
    frozen.value?.validation.processingStatus === 'FINISHED' &&
    frozen.value.validation.validationStatus === 'PASSED' &&
    frozen.value.validation.referenceResult === 'ACCEPTED' &&
    frozen.value.validation.solutionResult === 'ACCEPTED',
)
const resource = computed(
  () =>
    ({ reviews: 'content-reviews', problems: 'public-problems', feedback: 'feedback-cases' })[
      props.section
    ],
)
const heading = computed(
  () =>
    ({ reviews: '公共题审核', problems: '公共题维护', feedback: '题目反馈案件' })[props.section],
)
let generation = 0,
  alive = true,
  pending: { key: string; id: string } | null = null
function clear() {
  items.value = []
  selected.value = null
  frozen.value = null
  reports.value = []
  reference.value = ''
  reason.value = ''
  acknowledged.value = false
  pending = null
}
function failed(e: unknown) {
  const code = e instanceof AdminRequestError ? e.status : 0
  message.value =
    code === 409
      ? '状态已变化，请刷新案件后重试。'
      : code === 429
        ? '操作过于频繁，请稍后重试。'
        : [401, 403].includes(code)
          ? '登录或审核权限已失效，请重新登录。'
          : '请求未完成，请检查状态后重试；重复处置会使用同一请求编号。'
  if ([401, 403].includes(code)) {
    clear()
    session.value = null
  }
}
async function task(action: (g: number) => Promise<void>) {
  if (busy.value) return
  busy.value = true
  const g = ++generation
  try {
    await action(g)
  } catch (e) {
    if (alive && g === generation) failed(e)
  } finally {
    if (alive && g === generation) busy.value = false
  }
}
function current(g: number) {
  return alive && g === generation
}
async function load(next = page.value) {
  await task(async (g) => {
    const s = await restoreAdmin()
    if (!current(g)) return
    if (s.admin?.id !== session.value?.admin?.id) clear()
    session.value = s
    if (!allowed.value) {
      clear()
      return
    }
    const data = await adminRequest<AdminPage<Entry>>(`/${resource.value}?page=${next}&size=20`)
    if (!current(g)) return
    items.value = data.items
    page.value = data.page
    total.value = data.total
    selected.value = null
    frozen.value = null
    reference.value = ''
    reports.value = []
    pending = null
    reason.value = ''
    acknowledged.value = false
    message.value = ''
  })
}
async function inspect(row: Entry, next = 1) {
  await task(async (g) => {
    reference.value = ''
    frozen.value = null
    reports.value = []
    selected.value = null
    reason.value = ''
    acknowledged.value = false
    pending = null
    if (props.section === 'reviews') {
      const data = await adminRequest<Frozen>(`/content-reviews/${encodeURIComponent(row.id)}`)
      if (current(g)) {
        selected.value = data.review
        frozen.value = data
      }
    } else if (props.section === 'feedback') {
      const data = await adminRequest<{ feedbackCase: Entry; reports: AdminPage<Report> }>(
        `/feedback-cases/${encodeURIComponent(row.id)}?page=${next}&size=20`,
      )
      if (current(g)) {
        selected.value = data.feedbackCase
        reports.value = data.reports.items
        reportPage.value = data.reports.page
        reportTotal.value = data.reports.total
      }
    } else selected.value = row
  })
}
async function readReference() {
  const row = selected.value
  if (!row || props.section !== 'reviews') return
  await task(async (g) => {
    const data = await adminRequest<{ reviewId: string; sourceCode: string }>(
      `/content-reviews/${encodeURIComponent(row.id)}/reference`,
    )
    if (current(g) && data.reviewId === row.id) reference.value = data.sourceCode
  })
}
async function act(action: string) {
  const row = selected.value,
    s = session.value,
    why = reason.value.trim()
  if (!row || !s || !allowed.value || !why || (action === 'invalidate' && !acknowledged.value))
    return
  if (action === 'approve' && !canApprove.value) return
  await task(async (g) => {
    const key = JSON.stringify([props.section, row.id, row.version, action, why])
    if (!pending || pending.key !== key) pending = { key, id: crypto.randomUUID() }
    const body =
      props.section === 'reviews'
        ? { expectedVersion: row.version, clientRequestId: pending.id, reason: why }
        : { expectedVersion: row.version, reason: why }
    await adminAction(`/${resource.value}/${encodeURIComponent(row.id)}/${action}`, body, s)
    if (!current(g)) return
    reference.value = ''
    frozen.value = null
    selected.value = null
    reports.value = []
    pending = null
    reason.value = ''
    acknowledged.value = false
    const data = await adminRequest<AdminPage<Entry>>(
      `/${resource.value}?page=${page.value}&size=20`,
    )
    if (current(g)) {
      items.value = data.items
      total.value = data.total
      message.value =
        action === 'recheck' ? '已提交冻结版本重验，完成后请重新读取案件。' : '处置已保存并留审计。'
    }
  })
}
watch(
  () => props.section,
  () => {
    generation++
    busy.value = false
    clear()
    page.value = 1
    message.value = ''
    void load(1)
  },
  { immediate: true },
)
onUnmounted(() => {
  alive = false
  generation++
  clear()
})
</script>
<template>
  <main class="admin-content">
    <nav>
      <RouterLink to="/admin">管理首页</RouterLink> ·
      <RouterLink to="/admin/reviews">公共题审核</RouterLink> ·
      <RouterLink to="/admin/problems">公共题维护</RouterLink> ·
      <RouterLink to="/admin/feedback">题目反馈</RouterLink>
    </nav>
    <h1>{{ heading }}</h1>
    <p v-if="!allowed">
      需要已完成初始改密的内容审核员或超级管理员账号。<RouterLink to="/admin/login"
        >前往管理登录</RouterLink
      >
    </p>
    <template v-else>
      <button :disabled="busy" @click="load()">刷新列表</button>
      <ul>
        <li v-for="row in items" :key="row.id">
          <button :disabled="busy" @click="inspect(row)">
            {{ row.title }} · {{ row.status }}<span v-if="row.dataInvalid"> · 判题数据已作废</span>
          </button>
        </li>
      </ul>
      <p>第 {{ page }} 页 · 共 {{ total }} 条</p>
      <button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button
      ><button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
      <article v-if="selected">
        <h2>{{ selected.title }} · {{ selected.status }} · 版本 {{ selected.version }}</h2>
        <p v-if="selected.decisionReason">审核结论：{{ selected.decisionReason }}</p>
        <p v-if="selected.invalidReason">作废原因：{{ selected.invalidReason }}</p>
        <p v-if="selected.stateReason">维护说明：{{ selected.stateReason }}</p>
        <RouterLink
          v-if="selected.publishedSlug || selected.slug"
          :to="`/problems/${encodeURIComponent(selected.publishedSlug || selected.slug || '')}`"
          >题目入口</RouterLink
        >
        <section v-if="frozen">
          <h3>冻结版本</h3>
          <p v-if="frozen.revision">
            {{
              frozen.revision.revisionKind === 'TEXT'
                ? '原题文案修订（保留判题版本）'
                : '关联新题（暂停旧题，旧 AC 不继承）'
            }}
            · 原题 ID {{ frozen.revision.problemId }} · 依据版本
            {{ frozen.revision.expectedVersion }}
          </p>
          <pre>{{ frozen.metadata.statement }}</pre>
          <p>输入：{{ frozen.metadata.inputDescription }}</p>
          <p>输出：{{ frozen.metadata.outputDescription }}</p>
          <div v-for="(sample, i) in frozen.metadata.samples" :key="i">
            <p>样例 {{ i + 1 }}</p>
            <pre>{{ sample.input }}</pre>
            <pre>{{ sample.output }}</pre>
          </div>
          <p>来源：{{ frozen.metadata.originType }} {{ frozen.metadata.sourceUrl }}</p>
          <p>许可：{{ frozen.metadata.licenseStatement }}</p>
          <p>
            {{ frozen.testCount }} 组测试 · 验证任务 {{ frozen.validation.processingStatus }} /
            {{ frozen.validation.validationStatus }} · {{ frozen.metadata.timeLimitMs }} ms /
            {{ frozen.metadata.memoryLimitMb }} MB
          </p>
          <h3>独立题解</h3>
          <pre>{{ frozen.solutionIdea }}</pre>
          <pre>{{ frozen.solutionCode }}</pre>
          <button :disabled="busy" @click="readReference">显式查看本案参考程序（留审计）</button>
          <pre v-if="reference">{{ reference }}</pre>
        </section>
        <section v-if="section === 'feedback'">
          <p>共 {{ selected.reports }} 条反馈</p>
          <article v-for="report in reports" :key="report.id">
            <p>报告人 {{ report.userId }} · {{ report.category }}</p>
            <pre>{{ report.body }}</pre>
          </article>
          <button :disabled="busy || reportPage <= 1" @click="inspect(selected, reportPage - 1)">
            上一页反馈</button
          ><button
            :disabled="busy || reportPage * 20 >= reportTotal"
            @click="inspect(selected, reportPage + 1)"
          >
            下一页反馈
          </button>
        </section>
        <label>处置理由<textarea v-model="reason" maxlength="500" :disabled="busy" /></label>
        <template v-if="section === 'reviews' && selected.status === 'PENDING'"
          ><button :disabled="busy || !reason.trim() || !canApprove" @click="act('approve')">
            批准并发布</button
          ><button :disabled="busy || !reason.trim()" @click="act('reject')">驳回</button
          ><button :disabled="busy || !reason.trim()" @click="act('recheck')">
            重验冻结版本
          </button></template
        >
        <template v-if="section === 'problems'"
          ><button
            v-if="selected.status === 'ACTIVE'"
            :disabled="busy || !reason.trim()"
            @click="act('archive')"
          >
            下架</button
          ><button
            v-if="selected.status === 'ARCHIVED' && !selected.dataInvalid"
            :disabled="busy || !reason.trim()"
            @click="act('restore')"
          >
            恢复</button
          ><template v-if="!selected.dataInvalid"
            ><label
              ><input
                v-model="acknowledged"
                type="checkbox"
                :disabled="busy"
              />已确认严重测试错误；作废旧判题依据后需另发修正版，旧题不能恢复</label
            ><button :disabled="busy || !reason.trim() || !acknowledged" @click="act('invalidate')">
              标记判题依据作废
            </button></template
          ></template
        >
        <button
          v-if="section === 'feedback' && selected.status === 'OPEN'"
          :disabled="busy || !reason.trim()"
          @click="act('close')"
        >
          保存处理结论并关闭案件
        </button>
      </article>
    </template>
    <p role="status">{{ message }}</p>
  </main>
</template>
<style scoped>
.admin-content {
  max-width: 1000px;
  margin: 2rem auto;
  padding: 1rem;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f4f4f4;
  padding: 0.8rem;
}
button {
  margin: 0.4rem;
}
label {
  display: block;
  margin: 1rem 0;
}
textarea {
  display: block;
  width: 100%;
  min-height: 5rem;
}
</style>
