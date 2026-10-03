<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError, type CsrfToken } from '@/services/forgeojApi'
import {
  createValidation,
  readValidation,
  listValidations,
  type ContentValidation,
} from '@/services/validationApi'
const props = defineProps<{
  draftId: string
  version: number
  status: string
  userId: number
  csrf: CsrfToken
  editingBusy?: boolean
}>()
const emit = defineEmits<{ conflict: [] }>()
const result = ref<ContentValidation | null>(null)
const history = ref<ContentValidation[]>([])
const page = ref(1),
  total = ref(0),
  busy = ref(false),
  message = ref('')
const stale = computed(
  () =>
    result.value &&
    (result.value.stale || result.value.draftVersion !== props.version || props.status !== 'DRAFT'),
)
let generation = 0,
  disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined
let pending: { draft: string; version: number; id: string } | null = null
function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}
function current(g: number) {
  return !disposed && g === generation
}
function failure(e: unknown) {
  if (e instanceof ApiRequestError && e.status === 409) {
    emit('conflict')
    return '已保存版本发生变化，请载入服务端版本后再验证。'
  }
  if (e instanceof ApiRequestError && e.status === 429) return '当前排队任务已满，请稍后重试。'
  if (e instanceof ApiRequestError && e.status === 400)
    return '请先保存完整题面、来源说明、两份程序、思路和至少一组测试。'
  if (e instanceof ApiRequestError && e.status === 401) return '登录已失效，请重新登录。'
  return e instanceof Error ? e.message : '验证服务暂时不可用。'
}
function monitor(g: number, count = 0) {
  stop()
  if (!result.value || ['FINISHED', 'SYSTEM_ERROR'].includes(result.value.processingStatus)) return
  if (count >= 30) {
    message.value = '任务仍在运行，可稍后刷新结果。'
    return
  }
  const job = result.value.jobId,
    draft = props.draftId
  timer = setTimeout(async () => {
    try {
      const value = await readValidation(draft, job)
      if (!current(g)) return
      result.value = value
      monitor(g, count + 1)
    } catch (e) {
      if (current(g)) message.value = failure(e)
    }
  }, 2000)
}
async function validate() {
  if (busy.value || props.editingBusy || props.status !== 'DRAFT') return
  const g = ++generation
  stop()
  busy.value = true
  message.value = ''
  const draft = props.draftId,
    version = props.version
  if (!pending || pending.draft !== draft || pending.version !== version)
    pending = { draft, version, id: crypto.randomUUID() }
  try {
    const value = await createValidation(draft, version, pending.id, props.csrf)
    if (!current(g)) return
    result.value = value
    pending = null
    monitor(g)
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
async function refresh() {
  if (busy.value || props.editingBusy || !result.value) return
  const g = ++generation
  stop()
  busy.value = true
  message.value = ''
  try {
    const value = await readValidation(props.draftId, result.value.jobId)
    if (!current(g)) return
    result.value = value
    monitor(g)
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
async function records(next = page.value) {
  if (busy.value || props.editingBusy) return
  const g = ++generation
  stop()
  busy.value = true
  message.value = ''
  try {
    const value = await listValidations(props.draftId, next)
    if (!current(g)) return
    history.value = value.items
    total.value = value.total
    page.value = next
  } catch (e) {
    if (current(g)) message.value = failure(e)
  } finally {
    if (current(g)) busy.value = false
  }
}
function inspect(value: ContentValidation) {
  if (busy.value) return
  generation++
  stop()
  result.value = value
  void refresh()
}
function outcome(value: string | null) {
  return value
    ? ((
        {
          ACCEPTED: '通过全部测试',
          WRONG_ANSWER: '答案错误',
          COMPILE_ERROR: '编译错误',
          RUNTIME_ERROR: '运行错误',
          TIME_LIMIT_EXCEEDED: '超时',
          OUTPUT_LIMIT_EXCEEDED: '输出超限',
          MEMORY_LIMIT_EXCEEDED: '内存超限',
          SECURITY_VIOLATION: '违反执行限制',
        } as Record<string, string>
      )[value] ?? '未知结果')
    : '等待执行'
}
function state(value: ContentValidation) {
  return value.validationStatus === 'PASSED'
    ? '验证通过'
    : value.validationStatus === 'FAILED'
      ? '验证未通过'
      : (
          {
            QUEUED: '排队中',
            RUNNING: '执行中',
            FINISHED: '已完成',
            SYSTEM_ERROR: '平台错误',
          } as Record<string, string>
        )[value.processingStatus]
}
watch(
  () => [props.draftId, props.userId],
  () => {
    generation++
    stop()
    pending = null
    result.value = null
    history.value = []
    page.value = 1
    total.value = 0
    busy.value = false
    message.value = ''
  },
)
onBeforeUnmount(() => {
  disposed = true
  generation++
  stop()
})
</script>
<template>
  <section class="content-validation">
    <h4>正式沙箱验证</h4>
    <p>
      仅验证当前已保存版本；本页未保存内容不会提交。参考程序和题解分别执行，验证通过后仍须送审。
    </p>
    <button :disabled="busy || editingBusy || status !== 'DRAFT'" @click="validate">
      验证当前已保存版本
    </button>
    <button :disabled="busy || editingBusy" @click="records()">查看验证记录</button>
    <article v-if="result" data-testid="validation-result" :data-job-id="result.jobId">
      <p>冻结草稿版本 {{ result.draftVersion }} · {{ state(result) }}</p>
      <p v-if="stale">此结果属于旧版本或已归档草稿，不能用于当前内容送审。</p>
      <p>
        参考程序：{{ outcome(result.referenceResult) }}；独立题解：{{
          outcome(result.solutionResult)
        }}
      </p>
      <button :disabled="busy || editingBusy" @click="refresh">刷新验证结果</button>
    </article>
    <ul>
      <li v-for="item in history" :key="item.jobId">
        <button :disabled="busy || editingBusy" @click="inspect(item)">
          版本 {{ item.draftVersion }} · {{ state(item) }}
        </button>
      </li>
    </ul>
    <p v-if="total">
      <button :disabled="busy || page <= 1" @click="records(page - 1)">验证记录上一页</button
      >{{ page }} / {{ Math.ceil(total / 20)
      }}<button :disabled="busy || page * 20 >= total" @click="records(page + 1)">
        验证记录下一页
      </button>
    </p>
    <p role="status">{{ message }}</p>
  </section>
</template>
