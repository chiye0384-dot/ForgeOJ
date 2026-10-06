// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Real HTTP only, unique disposable replay. Never use against real development data.
import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
const base='http://localhost:5173'
const checks=[]
class Client {
  cookies=new Map();csrf
  async request(path,method='GET',body,expected=200) {
    const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; ')}
    if(method!=='GET') {headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf) headers[this.csrf.headerName]=this.csrf.token}
    const r=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
    for(const value of r.headers.getSetCookie()) {const pair=value.split(';')[0],i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await r.text();assert.equal(r.status,expected,`${method} ${path}: ${text}`)
    if([400,401,403,404,503].includes(expected)) assert.equal(text,'')
    if(path.includes('classrooms')) {assert.match(r.headers.get('cache-control')??'',/no-store/);checks.push({method,action:path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g,':id'),status:r.status})}
    return text?JSON.parse(text):null
  }
  async login(username) {this.csrf=(await this.request('/api/v1/auth/session')).csrf;this.csrf=(await this.request('/api/v1/auth/login','POST',{username,password:'forgeoj-dev-only'})).csrf}
}
const owner=new Client(),other=new Client(),third=new Client()
await owner.login('learner');await other.login('other-learner');await third.login('classroom-fixture')
const formalBefore=(await owner.request('/api/v1/me/submissions')).total
const request=randomUUID(),body={title:'M3 HTTP原创班级',clientRequestId:request}
let room=await owner.request('/api/v1/classrooms','POST',body,201)
assert.equal((await owner.request('/api/v1/classrooms','POST',body,201)).id,room.id)
const id=room.id,path=`/api/v1/classrooms/${id}`
const detail=async c=>c.request(path)
const act=async (c,action,extra={},expected=200)=>c.request(`${path}/${action}`,'POST',{expectedVersion:(await detail(owner)).version,...extra},expected)
let invite=(await act(owner,'invite',{enabled:true})).inviteCode
const inviteDigests=[createHash('sha256').update(invite).digest('hex')]
await other.request('/api/v1/classrooms/join','POST',{inviteCode:invite});await third.request('/api/v1/classrooms/join','POST',{inviteCode:invite})
await act(owner,'members/2/role',{role:'ASSISTANT'})
for(const c of [other,third]) {await act(c,'invite',{enabled:false},403);await act(c,'archive',{},403);await act(c,'members/3/role',{role:'ASSISTANT'},403)}
const second=await other.request('/api/v1/classrooms','POST',{title:'M3另一班级',clientRequestId:randomUUID()},201)
assert.equal(second.role,'OWNER');assert.equal((await detail(other)).role,'ASSISTANT')
await act(other,'leave',{},204);await other.request(path,'GET',undefined,404)
await other.request('/api/v1/classrooms/join','POST',{inviteCode:invite});assert.equal((await detail(other)).role,'MEMBER')
await act(owner,'members/2/remove');await other.request('/api/v1/classrooms/join','POST',{inviteCode:invite},404)
const old=invite;invite=(await act(owner,'invite',{enabled:true})).inviteCode;inviteDigests.push(createHash('sha256').update(invite).digest('hex'))
await other.request('/api/v1/classrooms/join','POST',{inviteCode:old},404);await other.request('/api/v1/classrooms/join','POST',{inviteCode:invite},404)
await act(owner,'members/2/restore');await act(owner,'leave',{},409)
const transferRequest=randomUUID(),t=await act(owner,'transfers',{targetUserId:2,clientRequestId:transferRequest})
const accepted=await act(other,`transfers/${t.id}/accept`);assert.equal(accepted.status,'ACCEPTED')
assert.equal((await detail(other)).role,'OWNER');assert.equal((await detail(owner)).role,'ASSISTANT')
await other.request(`${path}/transfers/${t.id}/accept`,'POST',{expectedVersion:1});
// The new OWNER controls all subsequent lifecycle actions; former owner is denied.
await act(owner,'archive',{},403)
room=await detail(other)
await other.request(`${path}/archive`,'POST',{expectedVersion:room.version},204)
await third.request('/api/v1/classrooms/join','POST',{inviteCode:invite},404)
room=await detail(other);await other.request(`${path}/leave`,'POST',{expectedVersion:room.version},204)
await other.request(path,'GET',undefined,404)
const list=await other.request('/api/v1/me/classrooms');room=list.items.find(r=>r.id===id);assert.equal(room.memberStatus,'LEFT')
await other.request(`${path}/restore`,'POST',{expectedVersion:room.version},204)
room=await detail(other);assert.equal(room.role,'OWNER');assert.equal(room.status,'ACTIVE')
await other.request(path+`?expectedVersion=${room.version}`,'DELETE',undefined,409)
for(const missing of [randomUUID(),'invalid']) await third.request(`/api/v1/classrooms/${missing}`,'GET',undefined,404)
const emptyRequest=randomUUID(),emptyBody={title:'M3 HTTP空班',clientRequestId:emptyRequest},empty=await owner.request('/api/v1/classrooms','POST',emptyBody,201)
await owner.request(`/api/v1/classrooms/${empty.id}?expectedVersion=1`,'DELETE',undefined,204)
await owner.request('/api/v1/classrooms','POST',emptyBody,404)
for(const forbidden of ['inviteSha256','email','passwordHash','sessionId','sourceCode']) assert.ok(!JSON.stringify(room).includes(forbidden))
const formalAfter=(await owner.request('/api/v1/me/submissions')).total;assert.equal(formalAfter,formalBefore)
await writeFile('/reports/classroom-invite-sentinels.json',JSON.stringify([old,invite]))
const report={checkedAt:new Date().toISOString(),classroomId:id,secondClassroomId:second.id,inviteDigests,checks,allPassed:true,scopeRoles:true,leftRestoredAsMember:true,removedSelfRestoreDenied:true,transferAccepted:true,archivedOwnerRestored:true,emptyDeletionKeepsReplayTombstone:true,finalOwnerId:room.ownerId,finalVersion:room.version,formalBefore,formalAfter}
await writeFile('/reports/classroom-http.json',JSON.stringify(report,null,2));console.log(JSON.stringify({checks:checks.length,allPassed:true,classroomId:id}))
