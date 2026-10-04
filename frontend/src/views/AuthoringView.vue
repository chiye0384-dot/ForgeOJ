<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError, restoreSession, type SessionResponse } from '@/services/forgeojApi'
import ContentValidation from '@/components/ContentValidation.vue'
import ContentReview from '@/components/ContentReview.vue'
import {
  authoredDetail,
  authoredList,
  authoredTests,
  authoredWrite,
  authoredZip,
  type AuthoredDetail,
  type AuthoredSummary,
  type AuthoredTest,
} from '@/services/contentApi'

const session = ref<SessionResponse | null>(null)
const lists = ref<AuthoredSummary[]>([])
const selected = ref<AuthoredDetail | null>(null)
const tests = ref<AuthoredTest[]>([])
const samples = ref('[]')
const title = ref('')
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const workflowBusy = ref(false)
const conflict = ref(false)
const message = ref('')
let generation = 0
let disposed = false
const editable = computed(
  () =>
    selected.value?.draft.status === 'DRAFT' &&
    !busy.value &&
    !workflowBusy.value &&
    !conflict.value,
)
function current(g: number) {
  return !disposed && g === generation
}
function error(e: unknown) {
  return e instanceof ApiRequestError && e.status === 401
    ? '登录已失效，请到账号页面重新登录。'
    : e instanceof ApiRequestError && e.status === 503
      ? '服务暂时不可用，请稍后重试。'
      : e instanceof Error
        ? e.message
        : '操作失败。'
}
async function load() {
  const g = ++generation
  busy.value = true
  message.value = ''
  try {
    const account = await restoreSession()
    if (!current(g)) return
    session.value = account
    if (!account.authenticated) {
      lists.value = []
      selected.value = null
      tests.value = []
      return
    }
    const result = await authoredList(page.value)
    if (!current(g)) return
    lists.value = result.items
    total.value = result.total
  } catch (e) {
    if (current(g)) message.value = error(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
async function open(id: string) {
  const g = ++generation
  busy.value = true
  message.value = ''
  try {
    const detail = await authoredDetail(id)
    if (!current(g)) return
    const entries = await authoredTests(id)
    if (!current(g)) return
    selected.value = detail
    tests.value = entries
    samples.value = JSON.stringify(detail.content.metadata.samples, null, 2)
    conflict.value = false
  } catch (e) {
    if (current(g)) message.value = error(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
async function change(
  action: () => Promise<AuthoredDetail>,
  refreshTests: boolean,
  preserveEditor = false,
) {
  if (busy.value || !session.value?.authenticated) return
  const g = ++generation
  const localContent = preserveEditor ? selected.value?.content : undefined
  const localSamples = samples.value
  busy.value = true
  message.value = ''
  try {
    const result = await action()
    if (!current(g)) return
    selected.value = localContent ? { ...result, content: localContent } : result
    samples.value = localContent
      ? localSamples
      : JSON.stringify(result.content.metadata.samples, null, 2)
    conflict.value = false
    if (refreshTests) {
      const entries = await authoredTests(result.draft.id)
      if (!current(g)) return
      tests.value = entries
    }
    const list = await authoredList(page.value)
    if (!current(g)) return
    lists.value = list.items
    total.value = list.total
    message.value = '草稿已更新。'
  } catch (e) {
    if (!current(g)) return
    if (e instanceof ApiRequestError && e.status === 409) {
      conflict.value = true
      message.value = '版本已变化。本页内容已保留，请载入服务端版本后再编辑。'
    } else message.value = error(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
function create() {
  if (!session.value) return
  const name = title.value
  void change(() => authoredWrite('', '', 'POST', { title: name }, session.value!.csrf), true)
}
function save() {
  if (!editable.value || !selected.value || !session.value) return
  try {
    const publicSamples: unknown = JSON.parse(samples.value)
    if (
      !Array.isArray(publicSamples) ||
      publicSamples.length > 10 ||
      publicSamples.some((s) => !s || typeof s.input !== 'string' || typeof s.output !== 'string')
    )
      throw new Error('样例须为最多 10 个 input/output 文本对象的 JSON 数组。')
    const content = JSON.parse(JSON.stringify(selected.value.content)) as AuthoredDetail['content']
    content.metadata.samples = publicSamples
    const { id, version } = selected.value.draft
    void change(
      () =>
        authoredWrite(id, '', 'PUT', { content, expectedVersion: version }, session.value!.csrf),
      false,
    )
  } catch (e) {
    message.value = error(e)
  }
}
function saveTests() {
  if (!editable.value || !selected.value || !session.value) return
  const { id, version } = selected.value.draft
  const entries = tests.value.map((t) => ({ input: t.input, expectedOutput: t.expectedOutput }))
  void change(
    () =>
      authoredWrite(
        id,
        '/tests',
        'PUT',
        { tests: entries, expectedVersion: version },
        session.value!.csrf,
      ),
    true,
    true,
  )
}
function importZip(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !editable.value || !selected.value || !session.value) return
  if (file.size > 8 * 1024 * 1024) {
    message.value = 'ZIP 最多 8 MiB。'
    return
  }
  const { id, version } = selected.value.draft
  void change(() => authoredZip(id, version, file, session.value!.csrf), true, true)
}
function archive() {
  if (!editable.value || !selected.value || !session.value) return
  const { id, version } = selected.value.draft
  void change(
    () => authoredWrite(id, '/archive', 'POST', { expectedVersion: version }, session.value!.csrf),
    false,
  )
}
function addTest() {
  tests.value.push({ sequence: tests.value.length + 1, input: '', expectedOutput: '' })
}
function workflow(summary: AuthoredSummary) {
  if (!selected.value || selected.value.draft.id !== summary.id) return
  selected.value = { ...selected.value, draft: summary }
  lists.value = lists.value.map((row) => (row.id === summary.id ? summary : row))
}
function previous() {
  page.value--
  selected.value = null
  void load()
}
function next() {
  page.value++
  selected.value = null
  void load()
}
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  generation++
})
</script>

<template>
  <section class="authoring">
    <h2>我的题目草稿</h2>
    <p>草稿、参考程序和测试数据仅本人可见。保存草稿后可继续完善内容。</p>
    <p v-if="session && !session.authenticated">
      请先到 <RouterLink to="/account">账号页面</RouterLink> 登录。
    </p>
    <fieldset v-if="session?.authenticated" :disabled="busy || workflowBusy">
      <label>新题目标题 <input v-model="title" maxlength="100" /></label>
      <button @click="create">创建内容草稿</button>
      <ul>
        <li v-for="draft in lists" :key="draft.id">
          <button @click="open(draft.id)">{{ draft.title }}</button> · {{ draft.status }} ·
          {{ draft.testCount }} 组测试
        </li>
      </ul>
      <button :disabled="page <= 1" @click="previous">上一页</button> {{ page }} /
      {{ Math.max(1, Math.ceil(total / 20)) }}
      <button :disabled="page * 20 >= total" @click="next">下一页</button>
    </fieldset>
    <template v-if="selected">
      <h3>{{ selected.draft.title }}</h3>
      <p>版本 {{ selected.draft.version }} · {{ selected.draft.status }}</p>
      <button v-if="conflict" :disabled="busy" @click="open(selected.draft.id)">
        载入服务端版本
      </button>
      <fieldset :disabled="!editable">
        <label>题目标题 <input v-model="selected.content.metadata.title" maxlength="100" /></label>
        <label>题面 <textarea v-model="selected.content.metadata.statement" /></label>
        <label>输入说明 <textarea v-model="selected.content.metadata.inputDescription" /></label>
        <label>输出说明 <textarea v-model="selected.content.metadata.outputDescription" /></label>
        <label>公开样例 JSON <textarea v-model="samples" /></label>
        <label
          >来源声明
          <select v-model="selected.content.metadata.originType">
            <option value="ORIGINAL">原创</option>
            <option value="ADAPTED">基于允许使用的来源改编</option>
          </select></label
        >
        <label>来源链接 <input v-model="selected.content.metadata.sourceUrl" /></label>
        <label
          >许可证或授权说明 <textarea v-model="selected.content.metadata.licenseStatement" />
        </label>
        <label
          >时间上限 ms
          <input
            v-model.number="selected.content.metadata.timeLimitMs"
            type="number"
            min="100"
            max="30000"
        /></label>
        <label
          >内存上限 MB
          <input
            v-model.number="selected.content.metadata.memoryLimitMb"
            type="number"
            min="64"
            max="2048"
        /></label>
        <label
          >输出上限 bytes
          <input
            v-model.number="selected.content.metadata.outputLimitBytes"
            type="number"
            min="1"
            max="16777216"
        /></label>
        <label
          >私有参考程序 Main.java
          <textarea v-model="selected.content.referenceCode" spellcheck="false" />
        </label>
        <label>官方题解核心思路 <textarea v-model="selected.content.solutionIdea" /></label>
        <label
          >独立题解代码 Main.java
          <textarea v-model="selected.content.solutionCode" spellcheck="false" />
        </label>
        <button @click="save">保存题目内容</button>
      </fieldset>
      <fieldset :disabled="!editable">
        <legend>测试数据</legend>
        <p>
          最多 100 对；单文件 1 MiB，总量 16 MiB。ZIP 仅包含根目录 001.in/001.out 等成对 UTF-8
          文件。
        </p>
        <div v-for="(entry, index) in tests" :key="index" class="test-pair">
          <label>测试 {{ index + 1 }} 输入 <textarea v-model="entry.input" /></label>
          <label>测试 {{ index + 1 }} 正确输出 <textarea v-model="entry.expectedOutput" /></label>
          <button @click="tests.splice(index, 1)">移除本页测试</button>
        </div>
        <button :disabled="tests.length >= 100" @click="addTest">添加测试</button>
        <button @click="saveTests">保存全部测试</button>
        <label
          >以 ZIP 替换全部测试 <input type="file" accept=".zip,application/zip" @change="importZip"
        /></label>
      </fieldset>
      <button :disabled="!editable" @click="archive">归档此草稿</button>
      <ContentValidation
        v-if="session?.user"
        :draft-id="selected.draft.id"
        :version="selected.draft.version"
        :status="selected.draft.status"
        :user-id="session.user.id"
        :csrf="session.csrf"
        :editing-busy="busy || workflowBusy || conflict"
        @conflict="conflict = true"
      />
      <ContentReview
        v-if="session?.user"
        :draft-id="selected.draft.id"
        :version="selected.draft.version"
        :status="selected.draft.status"
        :user-id="session.user.id"
        :csrf="session.csrf"
        :editing-busy="busy || conflict"
        @workflow="workflow"
        @busy="workflowBusy = $event"
        @conflict="conflict = true"
      />
    </template>
    <p role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.authoring {
  max-width: 1000px;
  margin: auto;
  padding: 2rem 1rem;
}
fieldset {
  border: 1px solid #dbe1e9;
  border-radius: 0.75rem;
  padding: 1rem;
  margin: 1rem 0;
  background: white;
}
label {
  display: block;
  margin: 0.8rem 0;
}
input:not([type='file']),
textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  padding: 0.5rem;
}
textarea {
  min-height: 6rem;
}
button {
  margin: 0.25rem;
  padding: 0.5rem 0.75rem;
}
.test-pair {
  border-bottom: 1px solid #dbe1e9;
  padding-bottom: 1rem;
}
</style>
