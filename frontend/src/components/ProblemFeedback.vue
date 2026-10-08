<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { requestJson, jsonHeaders, ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
const props = defineProps<{ slug: string; userId: number; csrf: CsrfToken }>()
interface Feedback {
  id: string
  category: string
  body: string
  caseStatus: string
  resolution: string | null
}
const items = ref<Feedback[]>([]),
  category = ref('AMBIGUITY'),
  body = ref(''),
  message = ref(''),
  busy = ref(false),
  page = ref(1),
  total = ref(0)
const categories = {
  AMBIGUITY: '题面歧义',
  SAMPLE_ERROR: '样例错误',
  TEST_ERROR: '测试数据问题',
  RESOURCE_LIMIT: '资源限制问题',
  COPYRIGHT: '来源或版权问题',
  OTHER: '其他',
}
let generation = 0,
  alive = true,
  pending: { key: string; request: string } | null = null
const path = () => `/api/v1/problems/${encodeURIComponent(props.slug)}/feedbacks`
function fail(e: unknown) {
  message.value =
    e instanceof ApiRequestError && e.status === 409
      ? '该案件已提交过反馈或题目状态变化；请刷新本人反馈。'
      : e instanceof ApiRequestError && [401, 403].includes(e.status)
        ? '登录已失效，请重新登录。'
        : e instanceof ApiRequestError && e.status === 429
          ? '提交过于频繁，请稍后重试。'
          : '服务暂时不可用，可重试原请求。'
  if (e instanceof ApiRequestError && [401, 403].includes(e.status)) {
    items.value = []
    body.value = ''
    pending = null
  }
}
async function load(next = page.value) {
  if (busy.value) return
  const g = ++generation
  busy.value = true
  try {
    const result = await requestJson<{ items: Feedback[]; page: number; total: number }>(
      `${path()}?page=${next}&size=20`,
    )
    if (alive && g === generation) {
      items.value = result.items
      page.value = result.page
      total.value = result.total
    }
  } catch (e) {
    if (alive && g === generation) fail(e)
  } finally {
    if (alive && g === generation) busy.value = false
  }
}
async function send() {
  if (busy.value || !body.value.trim()) return
  const g = ++generation
  busy.value = true
  const key = JSON.stringify([props.slug, props.userId, category.value, body.value.trim()])
  if (!pending || pending.key !== key) pending = { key, request: crypto.randomUUID() }
  try {
    await requestJson(path(), {
      method: 'POST',
      headers: jsonHeaders(props.csrf),
      body: JSON.stringify({
        clientRequestId: pending.request,
        category: category.value,
        body: body.value.trim(),
      }),
    })
    if (!alive || g !== generation) return
    body.value = ''
    pending = null
    message.value = '反馈已提交，同题反馈会合并由审核员处理。'
    const result = await requestJson<{ items: Feedback[]; total: number }>(
      `${path()}?page=1&size=20`,
    )
    if (alive && g === generation) {
      items.value = result.items
      page.value = 1
      total.value = result.total
    }
  } catch (e) {
    if (alive && g === generation) fail(e)
  } finally {
    if (alive && g === generation) busy.value = false
  }
}
watch(
  () => [props.slug, props.userId],
  () => {
    generation++
    busy.value = false
    items.value = []
    body.value = ''
    message.value = ''
    pending = null
    page.value = 1
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  alive = false
  generation++
  items.value = []
  body.value = ''
  pending = null
})
</script>
<template>
  <section class="problem-feedback">
    <h3>题目反馈</h3>
    <p>同一待处理案件每人可提交一条反馈。这里仅显示本人反馈，报告数量不会自动下架题目。</p>
    <form @submit.prevent="send">
      <label
        >分类<select v-model="category" :disabled="busy">
          <option v-for="(label, key) in categories" :key="key" :value="key">{{ label }}</option>
        </select></label
      ><label>说明<textarea v-model="body" maxlength="2000" required :disabled="busy" /></label
      ><button :disabled="busy || !body.trim()">提交反馈</button>
    </form>
    <button :disabled="busy" @click="load()">刷新本人反馈</button>
    <article v-for="item in items" :key="item.id">
      <p>{{ item.caseStatus === 'OPEN' ? '待处理' : '已处理' }} · {{ item.category }}</p>
      <pre>{{ item.body }}</pre>
      <p v-if="item.resolution">处理结论：{{ item.resolution }}</p>
    </article>
    <button :disabled="busy || page <= 1" @click="load(page - 1)">上一页反馈</button
    ><button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页反馈</button>
    <p role="status">{{ message }}</p>
  </section>
</template>
<style scoped>
label {
  display: block;
  margin: 0.6rem 0;
}
textarea {
  display: block;
  width: 100%;
  min-height: 4rem;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
