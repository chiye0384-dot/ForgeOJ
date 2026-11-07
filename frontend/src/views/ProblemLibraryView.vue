<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQuery } from 'vue-router'

import {
  ApiRequestError,
  getProblems,
  restoreSession,
  getPracticeProblems,
  getProblemTags,
  type ProblemDifficulty,
  type ProblemListQuery,
  type ProblemListResponse,
} from '@/services/forgeojApi'

const route = useRoute()
const router = useRouter()
const keyword = ref('')
const authenticated = ref(false)
const progress = ref<ProblemListQuery['progress'] | ''>('')
const personalNotice = ref('')
const sessionReady = restoreSession()
  .then((value) => {
    authenticated.value = value.authenticated
  })
  .catch(() => {
    personalNotice.value = '登录状态暂时无法读取，当前显示公共题库。'
  })
const progressNames = { SOLVED: '已通过', ATTEMPTED: '已尝试', UNTRIED: '未尝试' }
const difficulty = ref<ProblemDifficulty | ''>('')
const tag = ref('')
const tags = ref<string[]>([])
const pageResult = ref<ProblemListResponse | null>(null)
const loading = ref(false)
const tagsLoading = ref(false)
const errorMessage = ref('')
const tagErrorMessage = ref('')
let requestGeneration = 0
let tagGeneration = 0
let disposed = false

const difficultyNames: Record<ProblemDifficulty, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

function singleValue(value: LocationQuery[string] | undefined): string {
  return typeof value === 'string' ? value : ''
}

function positiveInteger(value: string, fallback: number, maximum: number): number {
  if (!/^[1-9]\d*$/.test(value)) return fallback
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed <= maximum ? parsed : fallback
}

function readQuery(query: LocationQuery): ProblemListQuery {
  const requestedDifficulty = singleValue(query.difficulty)
  return {
    progress: ['SOLVED', 'ATTEMPTED', 'UNTRIED'].includes(singleValue(query.progress))
      ? (singleValue(query.progress) as ProblemListQuery['progress'])
      : undefined,
    keyword: singleValue(query.keyword).trim() || undefined,
    difficulty:
      requestedDifficulty === 'EASY' ||
      requestedDifficulty === 'MEDIUM' ||
      requestedDifficulty === 'HARD'
        ? requestedDifficulty
        : undefined,
    tag: singleValue(query.tag).trim() || undefined,
    page: positiveInteger(singleValue(query.page), 1, 2_147_483_647),
    size: positiveInteger(singleValue(query.size), 20, 50),
  }
}

const requestedQuery = computed(() => readQuery(route.query))
const totalPages = computed(() =>
  pageResult.value ? Math.max(1, Math.ceil(pageResult.value.total / pageResult.value.size)) : 1,
)

function routeQuery(query: ProblemListQuery): Record<string, string> {
  const parameters: Record<string, string> = { page: String(query.page), size: String(query.size) }
  if (query.keyword) parameters.keyword = query.keyword
  if (query.difficulty) parameters.difficulty = query.difficulty
  if (query.tag) parameters.tag = query.tag
  if (query.progress) parameters.progress = query.progress
  return parameters
}

async function loadProblems(query = requestedQuery.value): Promise<void> {
  const generation = ++requestGeneration
  loading.value = true
  errorMessage.value = ''
  pageResult.value = null
  try {
    await sessionReady
    const result = await (authenticated.value ? getPracticeProblems(query) : getProblems(query))
    if (!disposed && generation === requestGeneration) pageResult.value = result
  } catch (error) {
    if (!disposed && generation === requestGeneration) {
      errorMessage.value =
        error instanceof ApiRequestError && error.status === 400
          ? '筛选条件无效，请调整后重试。'
          : '题库暂时无法读取，请稍后重试。'
    }
  } finally {
    if (!disposed && generation === requestGeneration) loading.value = false
  }
}

async function loadTags(): Promise<void> {
  const generation = ++tagGeneration
  tagsLoading.value = true
  tagErrorMessage.value = ''
  try {
    const result = await getProblemTags()
    if (!disposed && generation === tagGeneration) tags.value = result.tags
  } catch {
    if (!disposed && generation === tagGeneration)
      tagErrorMessage.value = '标签暂时无法读取，仍可按标题或难度查找。'
  } finally {
    if (!disposed && generation === tagGeneration) tagsLoading.value = false
  }
}

function applyFilters(): void {
  const query: ProblemListQuery = {
    keyword: keyword.value.trim() || undefined,
    difficulty: difficulty.value || undefined,
    tag: tag.value || undefined,
    progress: progress.value || undefined,
    page: 1,
    size: requestedQuery.value.size,
  }
  void router.push({ name: 'problem-library', query: routeQuery(query) })
}

function changePage(page: number): void {
  void router.push({
    name: 'problem-library',
    query: routeQuery({ ...requestedQuery.value, page }),
  })
}

watch(
  requestedQuery,
  (query) => {
    keyword.value = query.keyword ?? ''
    difficulty.value = query.difficulty ?? ''
    tag.value = query.tag ?? ''
    progress.value = query.progress ?? ''
    void loadProblems(query)
  },
  { immediate: true },
)
void loadTags()

onBeforeUnmount(() => {
  disposed = true
  requestGeneration += 1
  tagGeneration += 1
})
</script>

<template>
  <section class="library" aria-labelledby="library-title">
    <div class="library-heading">
      <div>
        <p class="eyebrow">ForgeOJ</p>
        <h2 id="library-title">公共题库</h2>
        <p class="muted">搜索公开题目标题和正文，或按难度与标签筛选。</p>
      </div>
      <RouterLink to="/account">账号与登录</RouterLink>
    </div>

    <div class="practice-start">
      <div>
        <strong>从这里开始练习</strong>
        <p>Java 入门 20 题：从输入输出到数组、字符串、二分和前缀和。</p>
        <RouterLink to="/learning?tab=official">进入官方入门题单</RouterLink>
      </div>
      <RouterLink
        v-if="authenticated && pageResult?.resume"
        :to="{ name: 'problem-workspace', params: { slug: pageResult.resume.slug } }"
        >继续上次编辑：{{ pageResult.resume.title }}</RouterLink
      >
    </div>
    <p v-if="personalNotice" role="status">{{ personalNotice }}</p>
    <p v-if="!authenticated && requestedQuery.progress" role="status">
      登录后可按个人进度筛选；当前展示公共题库。
    </p>
    <form class="filters" data-testid="problem-filters" @submit.prevent="applyFilters">
      <label class="keyword-field">
        搜索关键词
        <input v-model="keyword" data-testid="problem-keyword" maxlength="100" />
      </label>
      <label>
        难度
        <select v-model="difficulty" data-testid="problem-difficulty">
          <option value="">全部难度</option>
          <option value="EASY">简单</option>
          <option value="MEDIUM">中等</option>
          <option value="HARD">困难</option>
        </select>
      </label>
      <label>
        标签
        <select v-model="tag" data-testid="problem-tag">
          <option value="">全部标签</option>
          <option v-if="tag && !tags.includes(tag)" :value="tag">{{ tag }}</option>
          <option v-for="option in tags" :key="option" :value="option">{{ option }}</option>
        </select>
      </label>
      <button type="submit">查找题目</button>
      <label
        >个人进度<select
          v-model="progress"
          :disabled="!authenticated"
          data-testid="problem-progress"
        >
          <option value="">全部进度</option>
          <option value="UNTRIED">未尝试</option>
          <option value="ATTEMPTED">已尝试</option>
          <option value="SOLVED">已通过</option>
        </select></label
      >
    </form>

    <p v-if="tagErrorMessage" class="muted" role="status">
      {{ tagErrorMessage }}
      <button class="text-button" type="button" :disabled="tagsLoading" @click="loadTags">
        重试标签
      </button>
    </p>

    <div class="problem-list" aria-live="polite" :aria-busy="loading">
      <p v-if="loading" class="muted">正在读取题库……</p>
      <div v-else-if="errorMessage" class="error" role="alert">
        <p>{{ errorMessage }}</p>
        <button type="button" @click="loadProblems()">重新读取</button>
      </div>
      <template v-else-if="pageResult">
        <p v-if="pageResult.mode === 'TITLE_FALLBACK'" role="status" data-testid="search-degraded">
          正文搜索暂不可用，已按标题搜索；难度和标签筛选仍可使用。
        </p>
        <p class="muted">共 {{ pageResult.total }} 题 · 每页 {{ pageResult.size }} 题</p>
        <p v-if="pageResult.items.length === 0" class="empty">暂无符合条件的题目。</p>
        <ul v-else>
          <li v-for="problem in pageResult.items" :key="problem.slug" class="problem-item">
            <div>
              <RouterLink :to="{ name: 'problem-workspace', params: { slug: problem.slug } }">
                {{ problem.title }}
              </RouterLink>
              <p class="muted">版本 {{ problem.judgeVersion }}</p>
              <p v-for="(snippet, index) in problem.highlights" :key="index" class="search-snippet">
                <template v-for="(segment, segmentIndex) in snippet" :key="segmentIndex">
                  <mark v-if="segment.matched">{{ segment.text }}</mark>
                  <span v-else>{{ segment.text }}</span>
                </template>
              </p>
            </div>
            <div class="metadata">
              <span v-if="authenticated && problem.progress" class="progress-badge">{{
                progressNames[problem.progress]
              }}</span>
              <span class="difficulty">
                {{ problem.difficulty ? difficultyNames[problem.difficulty] : '未标注' }}
              </span>
              <span v-for="problemTag in problem.tags" :key="problemTag" class="tag">
                {{ problemTag }}
              </span>
            </div>
          </li>
        </ul>
        <nav class="pagination" aria-label="题库分页">
          <button
            type="button"
            data-testid="previous-page"
            :disabled="pageResult.page <= 1"
            @click="changePage(pageResult.page - 1)"
          >
            上一页
          </button>
          <span>第 {{ pageResult.page }} / {{ totalPages }} 页</span>
          <button
            type="button"
            data-testid="next-page"
            :disabled="pageResult.page >= totalPages"
            @click="changePage(pageResult.page + 1)"
          >
            下一页
          </button>
        </nav>
      </template>
    </div>
  </section>
</template>

<style scoped>
.library {
  width: min(1180px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 2rem 0 4rem;
}
.practice-start {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  justify-content: space-between;
  align-items: center;
  margin-top: 1.25rem;
  padding: 1rem 1.5rem;
  background: #e7f5f8;
  border-radius: 12px;
}
.practice-start p {
  margin: 0.5rem 0;
}
.progress-badge {
  color: #15576d;
  font-weight: 700;
}

.library-heading,
.problem-item,
.metadata,
.pagination {
  display: flex;
  gap: 1rem;
  align-items: center;
  justify-content: space-between;
}

h2 {
  margin: 0;
}

.eyebrow {
  margin: 0 0 0.35rem;
  color: #176b87;
  font-size: 0.78rem;
  font-weight: 700;
}

.muted {
  color: #5d6878;
}

a {
  color: #15576d;
  font-weight: 650;
}

.filters,
.problem-list {
  margin-top: 1.25rem;
  padding: 1.5rem;
  border: 1px solid #d8e0eb;
  border-radius: 16px;
  background: #ffffff;
}

.filters {
  display: flex;
  gap: 1rem;
  align-items: end;
  flex-wrap: wrap;
}

label {
  display: grid;
  gap: 0.45rem;
  font-weight: 650;
}

.keyword-field {
  flex: 1;
  min-width: 160px;
}

input,
select {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #b8c4d4;
  border-radius: 8px;
  padding: 0.72rem 0.8rem;
  color: #152033;
  background: #fbfcfe;
  font: inherit;
}

input:focus,
select:focus {
  outline: 3px solid rgb(36 143 175 / 18%);
  border-color: #248faf;
}

button {
  border: 0;
  border-radius: 9px;
  padding: 0.8rem 1.1rem;
  color: white;
  background: #176b87;
  font-weight: 700;
  cursor: pointer;
}

button:disabled {
  cursor: default;
  opacity: 0.6;
}

.text-button {
  padding: 0.25rem 0.5rem;
  color: #15576d;
  background: transparent;
  text-decoration: underline;
}

ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.problem-item {
  padding: 1rem 0;
  border-bottom: 1px solid #e3e8ef;
}

.problem-item p {
  margin: 0.4rem 0 0;
  font-size: 0.82rem;
}

.metadata {
  gap: 0.5rem;
  flex-wrap: wrap;
  justify-content: end;
}

.difficulty,
.tag {
  padding: 0.35rem 0.65rem;
  border-radius: 999px;
  background: #e7f5f8;
  font-size: 0.8rem;
}

.pagination {
  margin-top: 1.5rem;
  flex-wrap: wrap;
  justify-content: center;
}

.empty {
  padding: 1rem 0;
}

.error {
  color: #8a2424;
}

@media (max-width: 600px) {
  .library-heading,
  .problem-item {
    align-items: start;
    flex-direction: column;
  }

  .metadata {
    justify-content: start;
  }
}
</style>
