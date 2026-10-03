<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ApiRequestError,
  getProblems,
  getSubmission,
  restoreSession,
  type ProblemListItem,
  type SessionResponse,
  type SubmissionStatusResponse,
} from '@/services/forgeojApi'
import {
  getHistory,
  getList,
  getLists,
  mutateList,
  type HistoryItem,
  type ListDetail,
  type ListSummary,
} from '@/services/learningApi'

const query = new URLSearchParams(window.location.search)
const tab = ref(query.get('tab') === 'history' ? 'history' : 'personal')
const session = ref<SessionResponse | null>(null)
const lists = ref<ListSummary[]>([])
const detail = ref<ListDetail | null>(null)
const history = ref<HistoryItem[]>([])
const selectedSubmission = ref<SubmissionStatusResponse | null>(null)
const page = ref(1)
const total = ref(0)
const itemPage = ref(1)
const title = ref('')
const keyword = ref('')
const problemSlug = ref(query.get('problemSlug') ?? '')
const candidates = ref<ProblemListItem[]>([])
const selectedSlug = ref('')
const loading = ref(false)
const changing = ref(false)
const message = ref('')
const error = ref('')
let generation = 0
let disposed = false
function current(operation: number) {
  return !disposed && operation === generation
}
function fail(e: unknown) {
  return e instanceof ApiRequestError && e.status === 503
    ? '服务暂时不可用，请稍后重试。'
    : e instanceof ApiRequestError && e.status === 401
      ? '登录已失效，请重新登录。'
      : e instanceof Error
        ? e.message
        : '请求失败。'
}
async function load() {
  const operation = ++generation
  loading.value = true
  error.value = ''
  selectedSubmission.value = null
  try {
    const account = await restoreSession()
    if (!current(operation)) return
    session.value = account
    if (tab.value !== 'official' && !account.authenticated) {
      lists.value = []
      history.value = []
      detail.value = null
      total.value = 0
      return
    }
    if (tab.value === 'history') {
      const result = await getHistory(page.value, problemSlug.value || undefined)
      if (!current(operation)) return
      history.value = result.items
      total.value = result.total
    } else {
      const result = await getLists(tab.value === 'official', page.value)
      if (!current(operation)) return
      lists.value = result.items
      total.value = result.total
      if (detail.value) {
        const value = await getList(
          detail.value.list.id,
          tab.value === 'official',
          account.authenticated,
          itemPage.value,
        )
        if (current(operation)) {
          detail.value = value
          title.value = value.list.title
        }
      }
    }
  } catch (e) {
    if (current(operation)) error.value = fail(e)
  } finally {
    if (current(operation)) loading.value = false
  }
}
function chooseTab(value: string) {
  tab.value = value
  page.value = 1
  detail.value = null
  message.value = ''
  title.value = ''
  candidates.value = []
  void load()
}
async function openList(id: string, requestedPage = 1) {
  const operation = ++generation
  loading.value = true
  error.value = ''
  try {
    const value = await getList(
      id,
      tab.value === 'official',
      session.value?.authenticated === true,
      requestedPage,
    )
    if (current(operation)) {
      detail.value = value
      itemPage.value = requestedPage
      title.value = value.list.title
      candidates.value = []
      selectedSlug.value = ''
    }
  } catch (e) {
    if (current(operation)) error.value = fail(e)
  } finally {
    if (current(operation)) loading.value = false
  }
}
async function change(
  suffix: string,
  method: string,
  body: unknown,
  id = detail.value?.list.id ?? '',
) {
  if (changing.value || !session.value?.authenticated) return
  const operation = ++generation
  changing.value = true
  error.value = ''
  message.value = ''
  try {
    const value = await mutateList(id, suffix, method, body, session.value.csrf)
    if (!current(operation)) return
    if (method === 'DELETE' && suffix.startsWith('?')) detail.value = null
    else if (value) {
      const selected = await getList(value.id, false, true, 1)
      if (!current(operation)) return
      detail.value = selected
      itemPage.value = 1
      title.value = selected.list.title
    }
    message.value = '题单已更新。'
    await load()
  } catch (e) {
    if (current(operation)) {
      if (e instanceof ApiRequestError && e.status === 409) {
        message.value = '题单已被其他页面修改或操作达到限制；已重新读取，请再次选择。'
        await load()
      } else error.value = fail(e)
    }
  } finally {
    if (!disposed) changing.value = false
  }
}
async function searchProblems() {
  const operation = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await getProblems({ keyword: keyword.value, page: 1, size: 50 })
    if (current(operation)) {
      candidates.value = result.items
      selectedSlug.value = result.items[0]?.slug ?? ''
    }
  } catch (e) {
    if (current(operation)) error.value = fail(e)
  } finally {
    if (current(operation)) loading.value = false
  }
}
async function move(itemId: string, direction: number) {
  if (!detail.value) return
  // Fetch the complete bounded permutation; visible pagination never drops hidden entries.
  const operation = ++generation
  loading.value = true
  error.value = ''
  try {
    const first = await getList(detail.value.list.id, false, true, 1)
    const items = [...first.items]
    for (let p = 2; p <= Math.ceil(first.total / 20); p++) {
      const next = await getList(first.list.id, false, true, p)
      if (next.list.version !== first.list.version) throw new ApiRequestError(409)
      items.push(...next.items)
    }
    if (!current(operation)) return
    const index = items.findIndex((i) => i.itemId === itemId)
    const other = index + direction
    if (index < 0 || other < 0 || other >= items.length) return
    const order = items.map((i) => i.itemId)
    const temp = order[index]!
    order[index] = order[other]!
    order[other] = temp
    await change('/order', 'PUT', { itemIds: order, expectedVersion: first.list.version })
  } catch (e) {
    if (current(operation))
      error.value =
        e instanceof ApiRequestError && e.status === 409
          ? '排序期间题单发生变化，请重新读取后再操作。'
          : fail(e)
  } finally {
    if (current(operation)) loading.value = false
  }
}
async function inspect(id: string) {
  const operation = ++generation
  error.value = ''
  try {
    const result = await getSubmission(id)
    if (current(operation)) selectedSubmission.value = result
  } catch (e) {
    if (current(operation)) error.value = fail(e)
  }
}
function turn(delta: number) {
  page.value += delta
  void load()
}
function closeList() {
  detail.value = null
  title.value = ''
}
function queryHistory() {
  page.value = 1
  void load()
}
onMounted(() => {
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  generation += 1
})
</script>

<template>
  <section class="learning">
    <h2>学习记录</h2>
    <nav aria-label="学习记录分类">
      <button type="button" :disabled="changing" @click="chooseTab('personal')">个人题单</button>
      <button type="button" :disabled="changing" @click="chooseTab('official')">官方题单</button>
      <button type="button" :disabled="changing" @click="chooseTab('history')">提交历史</button>
    </nav>
    <p v-if="loading">正在读取……</p>
    <p v-if="session && !session.authenticated && tab !== 'official'">
      请先<a href="/account">登录账号</a>，查看本人的题单和提交历史。
    </p>
    <div v-if="tab !== 'history' && (tab === 'official' || session?.authenticated)">
      <form
        v-if="tab === 'personal' && !detail"
        @submit.prevent="change('', 'POST', { title }, '')"
      >
        <label>新题单标题 <input v-model="title" maxlength="64" required /></label>
        <button :disabled="changing || loading">创建题单</button>
      </form>
      <p v-if="!lists.length && !loading">暂无{{ tab === 'official' ? '官方' : '个人' }}题单。</p>
      <ul class="list-cards">
        <li v-for="list in lists" :key="list.id">
          <button type="button" :disabled="changing || loading" @click="openList(list.id)">
            {{ list.title }}
          </button>
          <span v-if="list.completedCount !== undefined">
            {{ list.completedCount }} / {{ list.availableCount }} 已完成</span
          >
          <span v-else> {{ list.availableCount }} 道可用题目</span>
          <span v-if="list.unavailableCount"> · {{ list.unavailableCount }} 道暂不可用</span>
        </li>
      </ul>
      <article v-if="detail" class="detail">
        <h3>{{ detail.list.title }}</h3>
        <p v-if="detail.list.description">{{ detail.list.description }}</p>
        <p v-if="detail.list.completedCount !== undefined">
          当前判题版本进度：{{ detail.list.completedCount }} /
          {{ detail.list.availableCount }}；暂不可用 {{ detail.list.unavailableCount }} 道
        </p>
        <p v-else>登录后可查看本人当前版本 AC 进度。</p>
        <form
          v-if="tab === 'personal'"
          @submit.prevent="change('', 'PATCH', { title, expectedVersion: detail!.list.version })"
        >
          <label>题单标题 <input v-model="title" maxlength="64" required /></label>
          <button :disabled="changing || loading">修改标题</button>
          <button
            type="button"
            :disabled="changing || loading"
            @click="change(`?expectedVersion=${detail.list.version}`, 'DELETE', undefined)"
          >
            删除题单
          </button>
        </form>
        <form v-if="tab === 'personal'" @submit.prevent="searchProblems">
          <label>搜索要加入的公共题 <input v-model="keyword" maxlength="100" /></label>
          <button :disabled="loading || changing">查找题目</button>
          <label v-if="candidates.length"
            >选择题目
            <select v-model="selectedSlug">
              <option v-for="candidate in candidates" :key="candidate.slug" :value="candidate.slug">
                {{ candidate.title }}
              </option>
            </select></label
          >
          <button
            v-if="candidates.length"
            type="button"
            :disabled="loading || changing"
            @click="
              change('/items', 'POST', {
                problemSlug: selectedSlug,
                expectedVersion: detail.list.version,
              })
            "
          >
            加入题单
          </button>
        </form>
        <ol>
          <li v-for="entry in detail.items" :key="entry.itemId" :value="entry.position">
            <a
              v-if="entry.available && entry.problem"
              :href="`/problems/${encodeURIComponent(entry.problem.slug)}`"
              >{{ entry.problem.title }}</a
            >
            <span v-else>题目暂不可用</span>
            <span v-if="entry.problem?.completed"> · 当前版本已 AC</span>
            <div v-if="tab === 'personal'" class="entry-actions">
              <button
                type="button"
                :disabled="changing || loading || entry.position === 1"
                @click="move(entry.itemId, -1)"
              >
                上移
              </button>
              <button
                type="button"
                :disabled="changing || loading || entry.position === detail.total"
                @click="move(entry.itemId, 1)"
              >
                下移
              </button>
              <button
                type="button"
                :disabled="changing || loading"
                @click="
                  change(
                    `/items/${entry.itemId}?expectedVersion=${detail.list.version}`,
                    'DELETE',
                    undefined,
                  )
                "
              >
                移除
              </button>
            </div>
          </li>
        </ol>
        <p>
          <button
            :disabled="loading || changing || itemPage <= 1"
            @click="openList(detail.list.id, itemPage - 1)"
          >
            题目上一页
          </button>
          {{ itemPage }} / {{ Math.max(1, Math.ceil(detail.total / 20)) }}
          <button
            :disabled="loading || changing || itemPage * 20 >= detail.total"
            @click="openList(detail.list.id, itemPage + 1)"
          >
            题目下一页
          </button>
        </p>
        <button type="button" :disabled="changing" @click="closeList">关闭题单</button>
      </article>
    </div>
    <div v-if="tab === 'history' && session?.authenticated">
      <form @submit.prevent="queryHistory">
        <label>题目 slug（可选） <input v-model.trim="problemSlug" maxlength="80" /></label
        ><button :disabled="loading">查询历史</button>
      </form>
      <p v-if="!history.length && !loading">暂无本人提交记录。</p>
      <ul>
        <li v-for="item in history" :key="item.submissionId">
          <a v-if="item.problem" :href="`/problems/${encodeURIComponent(item.problem.slug)}`">{{
            item.problem.title
          }}</a
          ><span v-else>题目暂不可用</span> · 判题版本 {{ item.judgeVersion }} ·
          {{ item.processingStatus }} {{ item.verdict ?? '' }} · {{ item.createdAt }}
          <button type="button" @click="inspect(item.submissionId)">查看本次状态</button>
        </li>
      </ul>
      <div v-if="selectedSubmission" class="detail">
        <p>提交 {{ selectedSubmission.submissionId }}</p>
        <strong>{{ selectedSubmission.processingStatus }} {{ selectedSubmission.verdict }}</strong>
        <pre v-if="selectedSubmission.diagnosticMessage">{{
          selectedSubmission.diagnosticMessage
        }}</pre>
      </div>
    </div>
    <p v-if="session?.authenticated || tab === 'official'">
      <button :disabled="loading || changing || page <= 1" @click="turn(-1)">上一页</button>
      {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}
      <button :disabled="loading || changing || page * 20 >= total" @click="turn(1)">下一页</button>
    </p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button type="button" :disabled="loading || changing" @click="load">重新读取</button>
  </section>
</template>
<style scoped>
.learning {
  width: min(1000px, calc(100% - 2rem));
  margin: 2rem auto;
}
nav,
form,
.entry-actions {
  display: flex;
  gap: 0.6rem;
  flex-wrap: wrap;
  margin: 1rem 0;
  align-items: center;
}
button,
input,
select {
  font: inherit;
  padding: 0.5rem;
}
button {
  cursor: pointer;
}
button:disabled {
  cursor: default;
}
.detail,
.list-cards {
  background: white;
  border: 1px solid #d8e0eb;
  border-radius: 12px;
  padding: 1.5rem;
}
li {
  margin: 0.8rem 0;
}
[role='alert'] {
  color: #a52832;
}
pre {
  white-space: pre-wrap;
}
</style>
