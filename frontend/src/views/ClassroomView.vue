<!-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError, restoreSession, type SessionResponse } from '@/services/forgeojApi'
import {
  classroomWrite,
  getClassroom,
  getClassrooms,
  type ClassroomSummary,
  type ClassroomDetail,
  type Member,
} from '@/services/classroomApi'

const session = ref<SessionResponse | null>(null)
const rooms = ref<ClassroomSummary[]>([])
const detail = ref<ClassroomDetail | null>(null)
const title = ref('')
const renameTitle = ref('')
const joinCode = ref('')
const inviteCode = ref('')
const message = ref('')
const busy = ref(false)
const page = ref(1)
const total = ref(0)
const confirmation = ref<{ message: string; run: () => void } | null>(null)
let generation = 0
let disposed = false
let createRequest: { title: string; id: string } | null = null
let transferRequest: { room: string; target: number; id: string } | null = null
function current(g: number) {
  return !disposed && g === generation
}
function error(e: unknown) {
  if (e instanceof ApiRequestError) {
    if (e.status === 409) return '班级状态或版本已变化，请刷新后重新选择操作。'
    if (e.status === 404) return '班级不可访问或邀请码无效。'
    if (e.status === 403) return '当前角色不能执行此操作。'
    if (e.status === 429) return '加入尝试过于频繁，请稍后重试。'
    if (e.status === 401) return '登录已失效，请重新登录。'
    if (e.status === 503) return '服务暂时不可用，请稍后重试。'
  }
  return e instanceof Error ? e.message : '请求失败，请稍后重试。'
}
async function load(id?: string) {
  confirmation.value = null
  const g = ++generation
  busy.value = true
  detail.value = null
  inviteCode.value = ''
  message.value = ''
  try {
    const account = await restoreSession()
    if (!current(g)) return
    if (session.value?.user?.id !== account.user?.id) {
      createRequest = null
      transferRequest = null
    }
    session.value = account
    rooms.value = []
    if (!account.authenticated) return
    const list = await getClassrooms(page.value)
    if (!current(g)) return
    rooms.value = list.items
    total.value = list.total
    if (id) {
      const result = await getClassroom(id)
      if (!current(g)) return
      detail.value = result
      renameTitle.value = result.title
    }
  } catch (e) {
    if (current(g)) {
      message.value = error(e)
      if (e instanceof ApiRequestError && e.status === 401) {
        session.value = null
        rooms.value = []
      }
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
async function mutate(path: string, body: object | undefined, method = 'POST', select?: string) {
  if (busy.value || !session.value?.authenticated) return
  confirmation.value = null
  const g = ++generation
  const identity = session.value.user?.id
  busy.value = true
  message.value = ''
  inviteCode.value = ''
  try {
    const account = await restoreSession()
    if (!current(g)) return
    if (!account.authenticated || account.user?.id !== identity) {
      session.value = account
      rooms.value = []
      detail.value = null
      createRequest = null
      transferRequest = null
      message.value = '账号已变化，请刷新班级列表。'
      return
    }
    session.value = account
    const result = await classroomWrite<{ id?: string; inviteCode?: string }>(
      path,
      body,
      account.csrf,
      method,
    )
    if (!current(g)) return
    const code = result?.inviteCode ?? ''
    const selected = select ?? (path === '' || path === '/join' ? result?.id : detail.value?.id)
    await load(selected)
    if (
      !disposed &&
      generation === g + 1 &&
      session.value?.user?.id === identity &&
      (!selected || detail.value?.id === selected)
    ) {
      inviteCode.value = code
      if (path === '') createRequest = null
      if (path.endsWith('/transfers')) transferRequest = null
      message.value = code ? '新邀请码仅显示本次，请复制给需要加入的成员。' : '操作成功。'
    }
  } catch (e) {
    if (current(g)) {
      message.value = error(e)
      if (e instanceof ApiRequestError && [401, 404].includes(e.status)) {
        detail.value = null
        inviteCode.value = ''
        if (e.status === 401) {
          session.value = null
          rooms.value = []
        }
      }
    }
  } finally {
    if (current(g)) busy.value = false
  }
}
function create() {
  if (!title.value.trim()) {
    message.value = '请输入班级名称。'
    return
  }
  if (!createRequest || createRequest.title !== title.value)
    createRequest = { title: title.value, id: crypto.randomUUID() }
  void mutate('', { title: title.value, clientRequestId: createRequest.id })
}
function act(action: string, extra = {}) {
  const d = detail.value
  if (d) void mutate(`/${d.id}/${action}`, { expectedVersion: d.version, ...extra })
}
function member(m: Member, action: string, extra = {}) {
  act(`members/${m.userId}/${action}`, extra)
}
function transfer(m: Member) {
  const d = detail.value
  if (!d) return
  if (!transferRequest || transferRequest.room !== d.id || transferRequest.target !== m.userId)
    transferRequest = { room: d.id, target: m.userId, id: crypto.randomUUID() }
  act('transfers', { targetUserId: m.userId, clientRequestId: transferRequest.id })
}
function removeRoom() {
  const d = detail.value
  if (d)
    ask('只允许删除没有其他成员或业务历史的空班级。确认删除？', () => {
      void mutate(`/${d.id}?expectedVersion=${d.version}`, undefined, 'DELETE', '')
    })
}
function archive() {
  ask('归档后班级停止加入和修改，保留历史。确认归档？', () => act('archive'))
}
function leave() {
  const d = detail.value
  if (d)
    ask('退出后不能访问本班私有内容，历史记录保留。确认退出？', () => {
      void mutate(`/${d.id}/leave`, { expectedVersion: d.version }, 'POST', '')
    })
}
function ask(message: string, run: () => void) {
  const g = generation
  confirmation.value = {
    message,
    run: () => {
      if (current(g)) run()
    },
  }
}
function confirmAction() {
  const pending = confirmation.value
  confirmation.value = null
  pending?.run()
}
function turn(next: number) {
  page.value = next
  void load()
}
onMounted(() => void load())
onBeforeUnmount(() => {
  disposed = true
  generation++
  inviteCode.value = ''
  confirmation.value = null
  createRequest = null
  transferRequest = null
})
</script>

<template>
  <section class="classrooms">
    <h2>我的班级</h2>
    <p>角色仅在对应班级内生效。班级负责人是学习小组管理者。</p>
    <p v-if="message" role="status">{{ message }}</p>
    <section v-if="confirmation" role="alert" aria-label="操作确认">
      <p>{{ confirmation.message }}</p>
      <button :disabled="busy" @click="confirmAction">确认操作</button>
      <button :disabled="busy" @click="confirmation = null">取消操作</button>
    </section>
    <p v-if="!session?.authenticated">请先<RouterLink to="/">登录账号</RouterLink>。</p>
    <template v-else>
      <form @submit.prevent="create">
        <label>班级名称 <input v-model="title" maxlength="64" :disabled="busy" /></label>
        <button :disabled="busy || !title.trim()">创建班级</button>
      </form>
      <form @submit.prevent="mutate('/join', { inviteCode: joinCode })">
        <label>邀请码 <input v-model="joinCode" autocomplete="off" :disabled="busy" /></label>
        <button :disabled="busy || !joinCode">加入班级</button>
      </form>
      <button :disabled="busy" @click="load(detail?.id)">刷新班级</button>
      <ul>
        <li v-for="r in rooms" :key="r.id">
          {{ r.title }} · {{ r.role }} · {{ r.status }} · {{ r.memberStatus }}
          <RouterLink :to="`/classrooms/${encodeURIComponent(r.id)}/assignments`"
            >作业与本人历史</RouterLink
          >
          <button v-if="r.memberStatus === 'ACTIVE'" :disabled="busy" @click="load(r.id)">
            打开班级
          </button>
          <button
            v-if="r.role === 'OWNER' && r.status === 'ARCHIVED' && r.memberStatus === 'LEFT'"
            :disabled="busy"
            @click="mutate(`/${r.id}/restore`, { expectedVersion: r.version })"
          >
            恢复班级并重新加入
          </button>
        </li>
      </ul>
      <button :disabled="busy || page <= 1" @click="turn(page - 1)">上一页</button>
      <span>第 {{ page }} 页，共 {{ total }} 个班级</span>
      <button :disabled="busy || page * 20 >= total" @click="turn(page + 1)">下一页</button>
      <article v-if="detail">
        <h3>{{ detail.title }}</h3>
        <p>
          <a :href="`/classrooms/${encodeURIComponent(detail.id)}/problems`">班级私有题与练习</a>
        </p>
        <form
          v-if="detail.role === 'OWNER' && detail.status === 'ACTIVE'"
          @submit.prevent="
            mutate(
              `/${detail.id}`,
              { title: renameTitle, expectedVersion: detail.version },
              'PATCH',
            )
          "
        >
          <label>新班级名称 <input v-model="renameTitle" maxlength="64" :disabled="busy" /></label>
          <button :disabled="busy || !renameTitle.trim()">修改名称</button>
        </form>
        <p>{{ detail.status }} · 本班角色 {{ detail.role }} · 版本 {{ detail.version }}</p>
        <template v-if="detail.role === 'OWNER'">
          <template v-if="detail.status === 'ACTIVE'">
            <button :disabled="busy" @click="act('invite', { enabled: true })">
              生成或轮换邀请码
            </button>
            <button :disabled="busy" @click="act('invite', { enabled: false })">关闭邀请码</button>
            <button :disabled="busy" @click="archive">归档班级</button>
          </template>
          <button v-else :disabled="busy" @click="act('restore')">恢复班级</button>
          <button :disabled="busy" @click="removeRoom">删除空班级</button>
          <p v-if="inviteCode">
            本次邀请码：<code>{{ inviteCode }}</code>
          </p>
        </template>
        <button
          v-if="detail.role !== 'OWNER' || detail.status === 'ARCHIVED'"
          :disabled="busy"
          @click="leave"
        >
          退出班级
        </button>
        <table>
          <thead>
            <tr>
              <th>成员</th>
              <th>角色</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in detail.members" :key="m.userId">
              <td>{{ m.username }}</td>
              <td>{{ m.role }}</td>
              <td>{{ m.status }}</td>
              <td
                v-if="
                  detail.role === 'OWNER' &&
                  detail.status === 'ACTIVE' &&
                  m.userId !== detail.ownerId
                "
              >
                <template v-if="m.status === 'ACTIVE'">
                  <button
                    :disabled="busy"
                    @click="
                      member(m, 'role', { role: m.role === 'ASSISTANT' ? 'MEMBER' : 'ASSISTANT' })
                    "
                  >
                    {{ m.role === 'ASSISTANT' ? '取消助教' : '设为助教' }}
                  </button>
                  <button :disabled="busy" @click="member(m, 'remove')">移出成员</button>
                  <button :disabled="busy || !!detail.pendingTransfer" @click="transfer(m)">
                    邀请接任负责人
                  </button>
                </template>
                <button v-else :disabled="busy" @click="member(m, 'restore')">恢复成员</button>
              </td>
              <td v-else>—</td>
            </tr>
          </tbody>
        </table>
        <p v-if="detail.pendingTransfer">
          待接受的负责人转让：成员 {{ detail.pendingTransfer.targetUserId }}
          <button
            v-if="detail.pendingTransfer.targetUserId === session.user?.id"
            :disabled="busy"
            @click="act(`transfers/${detail.pendingTransfer.id}/accept`)"
          >
            接受转让
          </button>
          <button
            v-if="detail.role === 'OWNER'"
            :disabled="busy"
            @click="act(`transfers/${detail.pendingTransfer.id}/withdraw`)"
          >
            撤回转让
          </button>
        </p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.classrooms {
  max-width: 1100px;
  margin: 1.5rem auto;
  padding: 1.2rem;
  background: white;
  border-radius: 12px;
}
form,
li {
  margin: 1rem 0;
}
button {
  margin: 0.25rem;
  padding: 0.4rem 0.7rem;
}
input {
  padding: 0.4rem;
}
table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 1rem;
}
th,
td {
  text-align: left;
  border-bottom: 1px solid #dce3ec;
  padding: 0.7rem;
}
code {
  overflow-wrap: anywhere;
}
</style>
