// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Only real HTTP executions in the uniquely owned disposable replay; no assigned grades.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
const phase=process.argv[2], base='http://localhost:5173', checks=[]
const code='import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'
const file='/reports/assignments-http.json', json=async path=>JSON.parse((await readFile(path,'utf8')).replace(/^\uFEFF/,''))
class Client {
  cookies=new Map();csrf
  async call(path,method='GET',body,status=200) {
    const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; ')}
    if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const r=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
    for(const cookie of r.headers.getSetCookie()){const pair=cookie.split(';')[0],i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await r.text();assert.equal(r.status,status,`${method} ${path}: ${text}`);if([400,401,403,404,503].includes(status))assert.equal(text,'')
    if(path.includes('/classrooms/'))assert.match(r.headers.get('cache-control')??'',/no-store/)
    checks.push({method,path:path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g,':id'),status})
    return text?JSON.parse(text):null
  }
  async login(username){this.csrf=(await this.call('/api/v1/auth/session')).csrf;this.csrf=(await this.call('/api/v1/auth/login','POST',{username,password:'forgeoj-dev-only'})).csrf}
}
const owner=new Client(),member=new Client(),third=new Client();await owner.login('learner');await member.login('other-learner');await third.login('classroom-fixture')
const priv=await json('/reports/private-problems-http.json'),room=priv.classroomId,slug=priv.publishedSlug,root=`/api/v1/classrooms/${room}`,assign=`${root}/assignments`
const future=s=>new Date(Date.now()+s*1000).toISOString(), pause=ms=>new Promise(r=>setTimeout(r,ms))
async function wait(client,path,predicate){const deadline=Date.now()+180000;while(Date.now()<deadline){const result=await client.call(path);if(predicate(result))return result;await pause(350)}throw new Error(`Execution did not finish: ${path}`)}
const definition=(title,deadlineAt=future(300),acceptExistingAc=false,allowLate=true,solutionPolicy='AFTER_AC',problemSlugs=[slug])=>({title,description:'原创作业，仅用于一次性验收。',deadlineAt,acceptExistingAc,allowLate,solutionPolicy,problemSlugs})
async function create(d){return owner.call(assign,'POST',{clientRequestId:randomUUID(),definition:d},201)}
async function publish(d,startsAt=null){return owner.call(`${assign}/${d.assignment.id}/publish`,'POST',{expectedVersion:d.assignment.version,startsAt})}
async function active(d){return publish(await create(d))}
async function edit(id,d){const old=await owner.call(`${assign}/${id}`);return owner.call(`${assign}/${id}`,'PUT',{expectedVersion:old.assignment.version,definition:d})}
async function submit(client,id,source=code,key=randomUUID()){return client.call(`${assign}/${id}/problems/${slug}/submissions`,'POST',{clientRequestId:key,language:'JAVA_21',sourceCode:source},202)}
async function finish(client,queued,verdict){const result=await wait(client,`/api/v1/submissions/${queued.submissionId}`,r=>['FINISHED','SYSTEM_ERROR'].includes(r.processingStatus));assert.equal(result.processingStatus,'FINISHED');assert.equal(result.verdict,verdict);return result}
async function roomAction(client,action,extra={},status=200,roomPath=root){const d=await owner.call(roomPath);return client.call(`${roomPath}/${action}`,'POST',{expectedVersion:d.version,...extra},status)}
async function save(report){report.checks.push(...checks);report.checkedAt=new Date().toISOString();await writeFile(file,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({phase,checks:checks.length,room,realWorker:true}))}
if(phase==='setup'){
  const report={room,slug,checks:[],formal:[],selfTests:[],assignments:{},browser:{}}
  const pre=await active(definition('已有 AC 作业',future(300),true));report.assignments.pre=pre.assignment.id
  const proof=await member.call(`${assign}/${pre.assignment.id}`);assert.equal(proof.problems[0].grade.state,'PRECOMPLETED');assert.equal(proof.problems[0].grade.attempts,0);assert.equal(proof.problems[0].grade.completionSubmissionId,priv.formalSubmissions.find(s=>s.verdict==='AC').id)
  assert.doesNotMatch(JSON.stringify(await owner.call(`${assign}/${pre.assignment.id}`)),new RegExp(proof.problems[0].grade.completionSubmissionId))
  const d=definition('正式成绩作业'),work=await active(d),id=work.assignment.id;report.assignments.work=id
  const locked=await member.call(`${root}/problems/${slug}/solution`);assert.equal(locked.access,'LOCKED');assert.equal(locked.sourceCode,null)
  await member.call(`${root}/problems/${slug}/solution/early-view`,'POST',{expectedVersion:1,confirmEarlyView:true},409)
  assert.equal((await member.call(`${root}/problems/${slug.toUpperCase()}/solution`)).access,'LOCKED')
  await member.call(`${root}/problems/${slug.toUpperCase()}/solution/early-view`,'POST',{expectedVersion:1,confirmEarlyView:true},409)
  const key=randomUUID(),waBody='public class Main {public static void main(String[] a){System.out.println(0);}}'
  const pair=await Promise.all([submit(member,id,waBody,key),submit(member,id,waBody,key)]);assert.equal(pair[0].submissionId,pair[1].submissionId)
  report.formal.push(await finish(member,pair[0],'WA'));let own=await member.call(`${assign}/${id}`);assert.equal(own.problems[0].grade.state,'ATTEMPTING');assert.equal(own.problems[0].grade.attempts,1)
  const selfBody={requestId:randomUUID(),language:'JAVA_21',sourceCode:code,input:'7 8\n'},self=await member.call(`${assign}/${id}/problems/${slug}/self-tests`,'POST',selfBody,202)
  await member.call(`${assign}/${id}/problems/${slug}/self-tests`,'POST',selfBody,202)
  const output=await wait(member,`/api/v1/self-tests/${self.runId}`,r=>['FINISHED','SYSTEM_ERROR'].includes(r.run.processingStatus));assert.equal(output.run.executionResult,'SUCCESS');assert.equal(output.output,'15\n');report.selfTests.push(self.runId)
  assert.equal((await member.call(`${assign}/${id}`)).problems[0].grade.attempts,1)
  report.formal.push(await finish(member,await submit(member,id),'AC'));own=await member.call(`${assign}/${id}`);assert.equal(own.problems[0].grade.state,'ON_TIME_AC');assert.equal(own.problems[0].grade.attempts,2)
  await owner.call(`/api/v1/submissions/${own.problems[0].grade.completionSubmissionId}`,'GET',undefined,404)
  assert.equal((await member.call(`${assign}/${id}/problems/${slug}/solution`)).access,'AC')
  await owner.call(`${assign}/${id}`,'PUT',{expectedVersion:2,definition:{...d,allowLate:false}},409)
  const restriction=await active(definition('不可旁路的截止后题解',future(300),false,true,'AFTER_DEADLINE'));assert.equal((await member.call(`${assign}/${id}/problems/${slug}/solution`)).access,'LOCKED')
  await owner.call(`${assign}/${restriction.assignment.id}/cancel`,'POST',{expectedVersion:restriction.assignment.version,reason:'验收明确取消'});assert.equal((await member.call(`${assign}/${id}/problems/${slug}/solution`)).access,'AC')
  const invite=await roomAction(owner,'invite',{enabled:true});await third.call('/api/v1/classrooms/join','POST',{inviteCode:invite.inviteCode})
  await third.call(`${assign}/${id}`,'GET',undefined,404);await third.call(`${assign}/${id}/problems/${slug}/submissions`,'POST',{clientRequestId:randomUUID(),language:'JAVA_21',sourceCode:code},404)
  const added=await owner.call(`${assign}/${id}/participants`,'POST',{expectedVersion:2,userId:3});assert.equal(added.assignment.version,3);assert.equal((await third.call(`${assign}/${id}`)).participating,true)
  report.formal.push(await finish(third,await submit(third,id),'AC'));await roomAction(third,'leave',{},204)
  const historical=await third.call(`${assign}/${id}`);assert.equal(historical.member,false);assert.equal(historical.problems[0].metadata,null);assert.equal(historical.problems[0].slug,null);assert.equal(historical.problems[0].grade.attempts,1);assert.equal(historical.problems[0].grade.state,'ON_TIME_AC');report.browser.historyAssignment=id
  await third.call(`${assign}/${id}/problems/${slug}/solution`,'GET',undefined,404)
  const scheduled=await create(definition('自动开始作业',future(8),true,true,'AFTER_DEADLINE'));await publish(scheduled,future(2));await wait(member,`${assign}/${scheduled.assignment.id}`,r=>r.assignment.status==='ACTIVE');await pause(6500);assert.notEqual((await member.call(`${assign}/${scheduled.assignment.id}/problems/${slug}/solution`)).access,'LOCKED');report.assignments.scheduled=scheduled.assignment.id
  const lateDef=definition('迟交可升级作业',future(3)),late=await active(lateDef);await pause(3300);report.formal.push(await finish(member,await submit(member,late.assignment.id),'AC'));assert.equal((await member.call(`${assign}/${late.assignment.id}`)).problems[0].grade.state,'LATE_AC');await edit(late.assignment.id,{...lateDef,deadlineAt:future(300)});assert.equal((await member.call(`${assign}/${late.assignment.id}`)).problems[0].grade.state,'ON_TIME_AC');report.assignments.late=late.assignment.id
  const archiveRoom=await owner.call('/api/v1/classrooms','POST',{title:'作业归档隔离验收',clientRequestId:randomUUID()},201),other=`/api/v1/classrooms/${archiveRoom.id}`,otherAssign=`${other}/assignments`
  const body=definition('归档活动作业',future(300),false,true,'AFTER_DEADLINE',['sum-two-integers']),archive=await owner.call(otherAssign,'POST',{clientRequestId:randomUUID(),definition:body},201)
  await owner.call(`${otherAssign}/${archive.assignment.id}/publish`,'POST',{expectedVersion:1,startsAt:null});const standby=await owner.call(otherAssign,'POST',{clientRequestId:randomUUID(),definition:{...body,title:'归档定时作业'}},201);await owner.call(`${otherAssign}/${standby.assignment.id}/publish`,'POST',{expectedVersion:1,startsAt:future(120)})
  await roomAction(owner,'archive',{},204,other);assert.equal((await owner.call(`${otherAssign}/${archive.assignment.id}`)).assignment.status,'ENDED');assert.equal((await owner.call(`${otherAssign}/${standby.assignment.id}`)).assignment.status,'STOPPED');await roomAction(owner,'restore',{},204,other);assert.equal((await owner.call(`${otherAssign}/${archive.assignment.id}`)).assignment.status,'ENDED')
  const copy=await owner.call(`${otherAssign}/${archive.assignment.id}/copy`,'POST',{clientRequestId:randomUUID(),deadlineAt:future(360)},201);assert.equal(copy.assignment.status,'DRAFT');assert.equal(copy.participating,false)
  await owner.call(`${other}?expectedVersion=${(await owner.call(other)).version}`,'DELETE',undefined,409);report.archive={room:archiveRoom.id,active:archive.assignment.id,scheduled:standby.assignment.id,copy:copy.assignment.id}
  report.browser.assignment=(await active(definition('浏览器作业验收',future(3600)))).assignment.id
  await save(report)
}else if(phase==='queue'){
  const report=await json(file),d=await active(definition('截止前入队跨截止 AC',future(20),false,false)),id=d.assignment.id,key=randomUUID(),queued=await submit(member,id,code,key)
  assert.equal(queued.processingStatus,'QUEUED');report.hard={id,key,queued,deadlineAt:d.assignment.deadlineAt};await save(report)
}else if(phase==='finish'){
  const report=await json(file),h=report.hard;assert.ok(Date.now()>Date.parse(h.deadlineAt),'Restart Worker only after deadline')
  report.formal.push(await finish(member,h.queued,'AC'));const result=await member.call(`${assign}/${h.id}`);assert.equal(result.assignment.status,'ENDED');assert.equal(result.problems[0].grade.state,'ON_TIME_AC')
  assert.equal((await submit(member,h.id,code,h.key)).submissionId,h.queued.submissionId);await member.call(`${assign}/${h.id}/problems/${slug}/submissions`,'POST',{clientRequestId:randomUUID(),language:'JAVA_21',sourceCode:code},409)
  await edit(h.id,definition('截止前入队跨截止 AC',future(3600),false,false));assert.equal((await member.call(`${assign}/${h.id}`)).assignment.status,'ACTIVE');await save(report)
}else throw new Error('Use setup, queue or finish')
