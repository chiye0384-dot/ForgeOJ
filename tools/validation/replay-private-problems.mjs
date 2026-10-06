// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Original fixtures, real HTTP/Worker only. Run only in the uniquely owned disposable replay.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
const base = 'http://localhost:5173', checks = []
const referenceSentinel = 'M3_REFERENCE_PRIVATE_SENTINEL', solutionSentinel = 'M3_INDEPENDENT_SOLUTION_SENTINEL'
const code = 'import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); }}'
class Client {
  cookies = new Map(); csrf
  async request(path, method = 'GET', body, expected = 200, extra = {}) {
    const headers = { Cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; '), ...extra }
    if (method !== 'GET') { headers.Origin = base; headers['Content-Type'] = 'application/json'; if (this.csrf) headers[this.csrf.headerName] = this.csrf.token }
    const r = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
    for (const value of r.headers.getSetCookie()) { const pair = value.split(';')[0], i = pair.indexOf('='); this.cookies.set(pair.slice(0,i), pair.slice(i+1)) }
    const text = await r.text(); assert.equal(r.status, expected, `${method} ${path}: ${text}`)
    if ([400,401,403,404,503].includes(expected)) assert.equal(text, '')
    if (path.includes('classrooms')) assert.match(r.headers.get('cache-control') ?? '', /no-store/)
    checks.push({ method, path: path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, ':id'), status: r.status })
    return text ? JSON.parse(text) : null
  }
  async login(username) { this.csrf = (await this.request('/api/v1/auth/session')).csrf; this.csrf = (await this.request('/api/v1/auth/login','POST',{ username,password:'forgeoj-dev-only' })).csrf }
}
const owner = new Client(), member = new Client(), outsider = new Client()
await owner.login('learner'); await member.login('other-learner'); await outsider.login('classroom-fixture')
const room = await owner.request('/api/v1/classrooms','POST',{ title:'M3班级私有题真实验收',clientRequestId:randomUUID() },201)
const root = `/api/v1/classrooms/${room.id}`, problems = `${root}/problems`
async function roomAction(client, action, extra = {}, status = 200) {
  const d = await owner.request(root)
  return client.request(`${root}/${action}`,'POST',{ expectedVersion:d.version,...extra },status)
}
const invite = await roomAction(owner,'invite',{ enabled:true })
await member.request('/api/v1/classrooms/join','POST',{ inviteCode:invite.inviteCode })
await roomAction(owner,'members/2/role',{ role:'ASSISTANT' })
const authorRoot = '/api/v1/me/authored-problems'
async function wait(client,path,predicate) {
  const deadline = Date.now()+180000
  while (Date.now()<deadline) { const value = await client.request(path); if (predicate(value)) return value; await new Promise(r => setTimeout(r,350)) }
  throw new Error(`Execution did not finish: ${path}`)
}
async function fixture(client,title) {
  const created = await client.request(authorRoot,'POST',{ title },201), id = created.draft.id
  const content = { ...created.content, metadata:{ ...created.content.metadata, statement:'原创班级练习：读入两个整数并输出和。', inputDescription:'两个整数',outputDescription:'输出整数之和。', samples:[{input:'1 2\n',output:'3\n'}],licenseStatement:'池也原创，仅用于 ForgeOJ disposable 验收。' },referenceCode:code+`\n// ${referenceSentinel}`,solutionIdea:'直接读取并相加；独立题解。',solutionCode:code+`\n// ${solutionSentinel}` }
  await client.request(`${authorRoot}/${id}`,'PUT',{ expectedVersion:1,content })
  await client.request(`${authorRoot}/${id}/tests`,'PUT',{ expectedVersion:2,tests:[{input:'1 2\n',expectedOutput:'3\n'},{input:'8 9\n',expectedOutput:'17\n'}] })
  const queued = await client.request(`${authorRoot}/${id}/validations`,'POST',{ expectedVersion:3,requestId:randomUUID() },202)
  const result = await wait(client,`${authorRoot}/${id}/validations/${queued.jobId}`,r => ['FINISHED','SYSTEM_ERROR'].includes(r.processingStatus))
  assert.equal(result.validationStatus,'PASSED'); assert.equal(result.referenceResult,'ACCEPTED'); assert.equal(result.solutionResult,'ACCEPTED')
  return { draftId:id,draftVersion:3,validationJobId:queued.jobId,content }
}
const ownerFixture = await fixture(owner,'M3班级题 HTTP 原创候选')
const request = randomUUID(), publication = { draftId:ownerFixture.draftId,draftVersion:3,validationJobId:ownerFixture.validationJobId,clientRequestId:request,solutionPolicy:'AFTER_AC' }
const published = await owner.request(problems,'POST',publication,201), path = `${problems}/${published.slug}`
assert.deepEqual(await owner.request(problems,'POST',publication,201),published)
await owner.request(problems,'POST',{...publication,solutionPolicy:'IMMEDIATE'},409)
const learning = await member.request(path)
assert.equal(learning.problem.createdBy,1)
assert.doesNotMatch(JSON.stringify(learning), /referenceCode|solutionCode|solutionIdea|snapshotId|expectedOutput|8 9|M3_REFERENCE/)
await outsider.request(path,'GET',undefined,404)
await owner.request(`/api/v1/problems/${published.slug}`,'GET',undefined,404)
await owner.request(`/api/v1/me/problems/${published.slug}/solution`,'GET',undefined,404)
await owner.request(`/api/v1/me/problems/${published.slug}/solution/early-view`,'POST',{judgeVersion:1,confirmEarlyView:true},404)
await owner.request(`/api/v1/problems/${published.slug}/submissions`,'POST',{ language:'JAVA_21',sourceCode:code },404,{'Idempotency-Key':randomUUID()})
const library = await owner.request('/api/v1/problems?page=1&size=50'); assert.doesNotMatch(JSON.stringify(library), /M3班级题|class-/)
const list = await owner.request('/api/v1/me/problem-lists','POST',{title:'M3公共题单旁路验收'},201)
await owner.request(`/api/v1/me/problem-lists/${list.id}/items`,'POST',{problemSlug:published.slug,expectedVersion:list.version},404)
const secondRoom = await member.request('/api/v1/classrooms','POST',{title:'M3跨班作用域验收',clientRequestId:randomUUID()},201)
await member.request(`/api/v1/classrooms/${secondRoom.id}/problems/${published.slug}`,'GET',undefined,404)
const assistantFixture = await fixture(member,'M3助教原创候选')
const assistantProblem = await member.request(problems,'POST',{ draftId:assistantFixture.draftId,draftVersion:3,validationJobId:assistantFixture.validationJobId,clientRequestId:randomUUID(),solutionPolicy:'AFTER_AC' },201)
await roomAction(member,'leave',{},204)
await member.request(path,'GET',undefined,404)
await owner.request(`${problems}/${assistantProblem.slug}/maintenance`)
await member.request('/api/v1/classrooms/join','POST',{inviteCode:invite.inviteCode})
await member.request(`${path}/maintenance`,'GET',undefined,403)
const copied = await owner.request(`${problems}/${assistantProblem.slug}/copy`,'POST',{})
assert.equal(copied.draft.version,1); assert.equal(copied.draft.testCount,2)
await owner.request(problems,'POST',{draftId:copied.draft.id,draftVersion:1,validationJobId:assistantFixture.validationJobId,clientRequestId:randomUUID(),solutionPolicy:'AFTER_AC'},409)
await owner.request(`${authorRoot}/${ownerFixture.draftId}`,'PUT',{expectedVersion:3,content:{...ownerFixture.content,metadata:{...ownerFixture.content.metadata,statement:'发布后的个人草稿改写'}}})
assert.equal((await owner.request(path)).metadata.statement,'原创班级练习：读入两个整数并输出和。')
await owner.request(problems,'POST',{...publication,clientRequestId:randomUUID()},409)
assert.deepEqual(await owner.request(problems,'POST',publication,201),published)
const locked = await member.request(`${path}/solution`); assert.equal(locked.access,'LOCKED'); assert.equal(locked.sourceCode,null)
await member.request(`${path}/solution/early-view`,'POST',{expectedVersion:1,confirmEarlyView:false},400)
assert.equal((await member.request(`${path}/solution`)).access,'LOCKED')
await member.request(`${path}/solution/early-view`,'POST',{expectedVersion:2,confirmEarlyView:true},409)
const revealed = await member.request(`${path}/solution/early-view`,'POST',{expectedVersion:1,confirmEarlyView:true})
assert.equal(revealed.access,'EARLY_VIEW'); assert.match(revealed.sourceCode,new RegExp(solutionSentinel)); assert.doesNotMatch(revealed.sourceCode,new RegExp(referenceSentinel))
await member.request(`${path}/solution/early-view`,'POST',{expectedVersion:1,confirmEarlyView:true})
assert.equal((await owner.request(`${path}/solution`)).access,'LOCKED')
const run = await member.request(`${path}/self-tests`,'POST',{requestId:randomUUID(),language:'JAVA_21',sourceCode:code,input:'7 8\n'},202)
const self = await wait(member,`/api/v1/self-tests/${run.runId}`,r => ['FINISHED','SYSTEM_ERROR'].includes(r.run.processingStatus))
assert.equal(self.run.executionResult,'SUCCESS'); assert.equal(self.output,'15\n')
await owner.request(`/api/v1/self-tests/${run.runId}`,'GET',undefined,404)
async function submit(source) {
  const request = randomUUID(), body = { clientRequestId:request,language:'JAVA_21',sourceCode:source }
  const queued = await member.request(`${path}/submissions`,'POST',body,202)
  assert.deepEqual(await member.request(`${path}/submissions`,'POST',body,202),queued)
  return wait(member,`/api/v1/submissions/${queued.submissionId}`,r => ['FINISHED','SYSTEM_ERROR'].includes(r.processingStatus))
}
const wa = await submit('public class Main {public static void main(String[] args){System.out.println(0);}}'); assert.equal(wa.verdict,'WA')
const ac = await submit(code); assert.equal(ac.verdict,'AC'); assert.equal((await member.request(`${path}/solution`)).access,'AC')
await owner.request(`/api/v1/submissions/${ac.submissionId}`,'GET',undefined,404)
await roomAction(member,'leave',{},204)
for (const suffix of ['', '/solution', '/maintenance']) await member.request(path+suffix,'GET',undefined,404)
await member.request(`${path}/solution/early-view`,'POST',{expectedVersion:1,confirmEarlyView:true},404)
await member.request(`/api/v1/submissions/${ac.submissionId}`)
const history = await member.request('/api/v1/me/submissions?page=1&size=50'); assert.doesNotMatch(JSON.stringify(history),new RegExp(published.slug))
await member.request('/api/v1/classrooms/join','POST',{inviteCode:invite.inviteCode})
await roomAction(owner,'members/2/remove')
await member.request(path,'GET',undefined,404)
await roomAction(owner,'members/2/restore')
await owner.request(`${problems}/${assistantProblem.slug}/archive`,'POST',{expectedVersion:1},204)
await member.request(`${problems}/${assistantProblem.slug}/submissions`,'POST',{clientRequestId:randomUUID(),language:'JAVA_21',sourceCode:code},404)
await owner.request(root+`?expectedVersion=${(await owner.request(root)).version}`,'DELETE',undefined,409)
const archiveRoom = await owner.request('/api/v1/classrooms','POST',{title:'M3归档发布保护验收',clientRequestId:randomUUID()},201)
await owner.request(`/api/v1/classrooms/${archiveRoom.id}/archive`,'POST',{expectedVersion:archiveRoom.version},204)
await owner.request(`/api/v1/classrooms/${archiveRoom.id}/problems`,'POST',{...publication,clientRequestId:randomUUID()},409)
// A separate current PASSED draft is left for actual browser publication, not synthetic seeding.
const browserFixture = await fixture(owner,'M3班级题浏览器候选')
const report = { checkedAt:new Date().toISOString(), classroomId:room.id, secondClassroomId:secondRoom.id, archiveClassroomId:archiveRoom.id, publishedSlug:published.slug, assistantSlug:assistantProblem.slug, validationJobs:[ownerFixture.validationJobId,assistantFixture.validationJobId,browserFixture.validationJobId], browserDraftId:browserFixture.draftId,browserJobId:browserFixture.validationJobId, copiedDraftId:copied.draft.id, selfTestId:run.runId, formalSubmissions:[{id:wa.submissionId,verdict:wa.verdict},{id:ac.submissionId,verdict:ac.verdict}], independentPrograms:true, realWorkerValidation:true, checks }
await writeFile('/reports/private-problems-http.json',JSON.stringify(report,null,2)+'\n')
console.log(JSON.stringify({classroomId:room.id,checks:checks.length,validationJobs:3,formalVerdicts:['WA','AC'],selfTest:'SUCCESS',browserDraftId:browserFixture.draftId}))
