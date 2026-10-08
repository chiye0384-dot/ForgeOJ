<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import {
  publishedList,
  copyPublicRevision,
  type PublishedProblem,
} from '@/services/publicRevisionApi'
const props = defineProps<{ userId: number; csrf: CsrfToken }>()
const emit = defineEmits<{ copied: [id: string] }>()
const opened = ref(false),
  items = ref<PublishedProblem[]>([]),
  page = ref(1),
  total = ref(0)
const busy = ref(false),
  message = ref(''),
  denied = ref(false)
let generation = 0,
  disposed = false
const requests = new Map<string, string>()
function current(g: number) {
  return !disposed && g === generation
}
function failure(e: unknown) {
  if (e instanceof ApiRequestError && [401, 403].includes(e.status)) {
    items.value = []
    requests.clear()
    denied.value = true
    message.value = '身份已失效，请重新登录。'
  } else if (e instanceof ApiRequestError && e.status === 409) {
    message.value = '原题版本或修订条件已变化，请刷新列表后重新选择。'
  } else message.value = '服务暂时不可用，可重试原操作。'
}
async function load() {
  const g = ++generation
  busy.value = true
  opened.value = true
  message.value = ''
  try {
    const result = await publishedList(page.value)
    if (current(g)) {
      items.value = result.items
      total.value = result.total
    }
  } catch (e) {
    if (current(g)) failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
function turnPage(delta: number) {
  page.value += delta
  void load()
}
async function copy(problem: PublishedProblem, kind: 'TEXT' | 'CORRECTION') {
  if (busy.value || denied.value || (kind === 'TEXT' && problem.dataInvalid)) return
  const key = `${problem.slug}:${problem.version}:${kind}`
  if (!requests.has(key)) requests.set(key, crypto.randomUUID())
  const g = ++generation
  busy.value = true
  message.value = ''
  try {
    const result = await copyPublicRevision(
      problem.slug,
      {
        expectedVersion: problem.version,
        revisionKind: kind,
        clientRequestId: requests.get(key),
      },
      props.csrf,
    )
    if (current(g)) {
      message.value = '已复制到新草稿。编辑后须重新验证、送审。'
      emit('copied', result.draft.id)
    }
  } catch (e) {
    if (current(g)) failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
watch(
  () => props.userId,
  () => {
    generation++
    items.value = []
    requests.clear()
    opened.value = false
    denied.value = false
    busy.value = false
    message.value = ''
    page.value = 1
    total.value = 0
  },
)
onBeforeUnmount(() => {
  disposed = true
  generation++
})
</script>
<template>
  <section aria-label="公开题修订">
    <button :disabled="busy || denied" @click="load">修订已公开题</button>
    <p v-if="opened">
      仅显示本人已发布的题目。文案修订须保持判题依据一致；关联新题会暂停旧题，旧 AC
      不算新题完成。复制后需要重新验证并送审。
    </p>
    <p role="status">{{ message }}</p>
    <p v-if="opened && !busy && !items.length">暂无可修订的公开题。</p>
    <ul>
      <li v-for="problem in items" :key="problem.id">
        {{ problem.title }} · 版本 {{ problem.version }} ·
        {{ problem.status === 'ACTIVE' ? '公开' : '已下架' }}
        <span v-if="problem.dataInvalid"> · 判题数据存在问题</span>
        <button :disabled="busy || denied || problem.dataInvalid" @click="copy(problem, 'TEXT')">
          复制文案修订
        </button>
        <button :disabled="busy || denied" @click="copy(problem, 'CORRECTION')">
          复制关联新题
        </button>
      </li>
    </ul>
    <div v-if="opened">
      <button :disabled="busy || denied || page === 1" @click="turnPage(-1)">上一页公开题</button>
      <button :disabled="busy || denied || page * 20 >= total" @click="turnPage(1)">
        下一页公开题
      </button>
    </div>
  </section>
</template>
