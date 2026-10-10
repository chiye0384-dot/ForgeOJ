// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Original disposable fixture. Grades and verdicts come only from real Worker runs.
import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
const phase=process.argv[2],base='http://localhost:5173',checks=[]
const code='import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'
const digest=s=>createHash('sha256').update(s).digest('hex'),json=async name=>JSON.parse((await readFile('/reports/'+name,'utf8')).replace(/^\uFEFF/,''))
class Client {
  cookies=new Map();csrf
  async call(path,method='GET',body,status=200,extra={}) {
    const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; '),...extra}
    if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const r=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
    for(const c of r.headers.getSetCookie()){const p=c.split(';')[0],i=p.indexOf('=');this.cookies.set(p.slice(0,i),p.slice(i+1))}
    const text=await r.text();assert.equal(r.status,status,`${method} ${path}: ${text}`);if([400,401,403,404,503].includes(status))assert.equal(text,'')
    if(path.includes('/classrooms/')) assert.match(r.headers.get('cache-control')??'',/no-store/)
    checks.push({method,path:path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g,':id'),status});return text?JSON.parse(text):null
  }
  async login(username){this.csrf=(await this.call('/api/v1/auth/session')).csrf;this.csrf=(await this.call('/api/v1/auth/login','POST',{username,password:'forgeoj-dev-only'})).csrf}
}
const owner=new Client(),student=new Client(),assistant=new Client();await owner.login('learner');await student.login('other-learner');await assistant.login('classroom-fixture')
const old=await json('private-problems-http.json'),room=old.classroomId,slug=old.publishedSlug,root=`/api/v1/classrooms/${room}`,assign=root+'/assignments'
async function roomAction(action,extra={},status=200){const r=await owner.call(root);return owner.call(root+'/'+action,'POST',{expectedVersion:r.version,...extra},status)}
async function wait(client,queued,verdict){for(let n=0;n<500;n++){const r=await client.call('/api/v1/submissions/'+queued.submissionId);if(['FINISHED','SYSTEM_ERROR'].includes(r.processingStatus)){assert.equal(r.processingStatus,'FINISHED');assert.equal(r.verdict,verdict);return r}await new Promise(r=>setTimeout(r,300))}throw new Error('Real Worker timeout')}
async function formal(client,id,problem,source=code){const q=await client.call(`${assign}/${id}/problems/${problem}/submissions`,'POST',{clientRequestId:randomUUID(),language:'JAVA_21',sourceCode:source},202);return q}
async function create(title,existing,deadlineAt=new Date(Date.now()+3600000).toISOString()){const a=await owner.call(assign,'POST',{clientRequestId:randomUUID(),definition:{title,description:'原创教学验收作业',deadlineAt,acceptExistingAc:existing,allowLate:true,solutionPolicy:'AFTER_AC',problemSlugs:[slug,'sum-two-integers']}},201);return owner.call(assign+'/'+a.assignment.id+'/publish','POST',{expectedVersion:a.assignment.version,startsAt:null})}
async function save(report){report.checks.push(...checks);report.checkedAt=new Date().toISOString();await writeFile('/reports/teacher-http.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({phase,httpChecks:report.checks.length,classroomId:room,assignmentId:report.assignmentId,allPassed:true}))}
if(phase==='setup'){
  const invite=await roomAction('invite',{enabled:true});await assistant.call('/api/v1/classrooms/join','POST',{inviteCode:invite.inviteCode});await roomAction('members/3/role',{role:'ASSISTANT'})
  const pre=await create('此前完成权限隔离',true),active=await create('全员成绩与正式源码',false),id=active.assignment.id,t=`${assign}/${id}/teaching`,preRoot=`${assign}/${pre.assignment.id}/teaching`
  const privateAc=old.formalSubmissions.find(r=>r.verdict==='AC').id
  const preGrades=await owner.call(preRoot+'/grades');assert.equal(preGrades.items.find(p=>p.userId===2).problems[0].state,'PRECOMPLETED');assert.ok(!JSON.stringify(preGrades).includes(privateAc));await owner.call(preRoot+'/submissions/'+privateAc,'GET',undefined,404)
  const wa=await formal(student,id,slug,'public class Main {public static void main(String[] a){System.out.println(0);}}');await wait(student,wa,'WA')
  const ac=await formal(student,id,slug);await wait(student,ac,'AC');const pub=await formal(student,id,'sum-two-integers');await wait(student,pub,'AC')
  const other=await formal(assistant,pre.assignment.id,'sum-two-integers');await wait(assistant,other,'AC');await owner.call(t+'/submissions/'+other.submissionId,'GET',undefined,404)
  const free=await assistant.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode:code},202,{'Idempotency-Key':randomUUID()});await wait(assistant,free,'AC');await owner.call(t+'/submissions/'+free.submissionId,'GET',undefined,404)
  const self=await student.call(`${assign}/${id}/problems/${slug}/self-tests`,'POST',{requestId:randomUUID(),language:'JAVA_21',sourceCode:code,input:'5 6\n'},202)
  let run;for(let i=0;i<500;i++){run=await student.call('/api/v1/self-tests/'+self.runId);if(run.run.processingStatus==='FINISHED')break;await new Promise(r=>setTimeout(r,300))}assert.equal(run.run.executionResult,'SUCCESS');assert.equal(run.output,'11\n');await owner.call(t+'/submissions/'+self.runId,'GET',undefined,404)
  const grades=await owner.call(t+'/grades'),own=await student.call(assign+'/'+id),studentGrade=grades.items.find(p=>p.userId===2);assert.equal(grades.total,3);assert.equal(studentGrade.completed,2)
  for(let i=0;i<2;i++)for(const field of ['state','attempts','firstAcAt'])assert.equal(studentGrade.problems[i][field],own.problems[i].grade[field])
  assert.deepEqual(studentGrade.problems.map(p=>p.attempts),[2,1]);assert.ok(!JSON.stringify(grades).includes('sourceCode'))
  const attempts=await owner.call(t+'/participants/2/problems/1/attempts?size=1');assert.equal(attempts.total,2);assert.equal(attempts.items[0].submissionId,ac.submissionId);assert.ok(!JSON.stringify(attempts).includes('sourceCode'))
  assert.equal((await owner.call(t+'/participants/2/problems/1/attempts?page=2&size=1')).items[0].submissionId,wa.submissionId)
  const source=await owner.call(t+'/submissions/'+ac.submissionId);assert.equal(source.sourceCode,code);assert.equal(source.sourceSha256,digest(code));assert.equal(source.submission.userId,2);assert.equal(source.submission.problemSlug,slug)
  assert.deepEqual(Object.keys(source).sort(),['sourceCode','sourceSha256','submission']);assert.equal((await assistant.call(t+'/submissions/'+ac.submissionId)).sourceSha256,digest(code))
  await owner.call('/api/v1/submissions/'+ac.submissionId,'GET',undefined,404);await student.call(t+'/grades','GET',undefined,403);await student.call(t+'/submissions/'+ac.submissionId,'GET',undefined,403)
  await owner.call(t+'/submissions/'+privateAc,'GET',undefined,404);await owner.call(t+'/submissions/'+randomUUID(),'GET',undefined,404);await owner.call(t+'/submissions/not-an-id','GET',undefined,404)
  await owner.call(t+'/grades?size=51','GET',undefined,400);await owner.call(t+'/grades?page=0','GET',undefined,400);assert.deepEqual((await owner.call(t+'/grades?page=100&size=50')).items,[])
  assert.equal((await owner.call(t+'/grades?page=2&size=1')).items[0].userId,2)
  await owner.call(t+'/participants/2/problems/3/attempts','GET',undefined,404);await owner.call(t+'/participants/99999/problems/1/attempts','GET',undefined,404)
  await owner.call(`/api/v1/classrooms/${old.secondClassroomId}/assignments/${id}/teaching/submissions/${ac.submissionId}`,'GET',undefined,404)
  const late=await create('迟交延期教学成绩',false,new Date(Date.now()+2000).toISOString()),lateId=late.assignment.id
  await new Promise(r=>setTimeout(r,Math.max(0,Date.parse(late.assignment.deadlineAt)-Date.now())+80))
  const lateAc=await formal(student,lateId,slug);await wait(student,lateAc,'AC')
  const lateGrades=await owner.call(`${assign}/${lateId}/teaching/grades`);assert.equal(lateGrades.items.find(p=>p.userId===2).problems[0].state,'LATE_AC')
  const oldLate=await owner.call(assign+'/'+lateId);await owner.call(assign+'/'+lateId,'PUT',{expectedVersion:oldLate.assignment.version,definition:{title:'迟交延期教学成绩',description:'原创教学验收作业',deadlineAt:new Date(Date.now()+3600000).toISOString(),acceptExistingAc:false,allowLate:true,solutionPolicy:'AFTER_AC',problemSlugs:[slug,'sum-two-integers']}})
  assert.equal((await owner.call(`${assign}/${lateId}/teaching/grades`)).items.find(p=>p.userId===2).problems[0].state,'ON_TIME_AC')
  await save({classroomId:room,assignmentId:id,preAssignmentId:pre.assignment.id,lateAssignmentId:lateId,privateSlug:slug,sourceSubmissionId:ac.submissionId,sourceSha256:digest(code),formalIds:[wa.submissionId,ac.submissionId,pub.submissionId,other.submissionId,lateAc.submissionId],unrelatedPrivateId:privateAc,unrelatedFreeId:free.submissionId,selfTestId:self.runId,gradesMatchOwn:true,lateExtensionConsistent:true,privateAndPreSourceDenied:true,assistantAllowed:true,checks:[]})
} else {
  const report=await json('teacher-http.json'),t=`${assign}/${report.assignmentId}/teaching`
  if(phase==='reset-assistant'){
    // Restore Assignment replay's late-join prerequisite using the real leave API.
    // Existing accepted grades and teacher observations remain historical facts.
    const roomState=await owner.call(root)
    const membership=roomState.members.find(m=>m.userId===3)
    assert.ok(membership)
    if(membership.status==='ACTIVE')await assistant.call(root+'/leave','POST',{expectedVersion:roomState.version},204)
    else assert.equal(membership.status,'LEFT')
    assert.equal((await owner.call(root)).members.find(m=>m.userId===3).status,'LEFT')
    await assistant.call(t+'/grades','GET',undefined,404)
    report.assistantResetForLateJoin=true
  } else if(phase==='revoke'){
    await roomAction('members/3/role',{role:'MEMBER'});await assistant.call(t+'/grades','GET',undefined,403);await assistant.call(t+'/submissions/'+report.sourceSubmissionId,'GET',undefined,403)
    const r=await owner.call(root);await student.call(root+'/leave','POST',{expectedVersion:r.version},204);await student.call(t+'/grades','GET',undefined,404)
    const grades=await owner.call(t+'/grades');assert.equal(grades.items.find(p=>p.userId===2).memberStatus,'LEFT');report.revokedImmediately=true;report.historicalParticipant=true
  } else if(phase==='archive'){
    await roomAction('members/3/role',{role:'ASSISTANT'});await roomAction('archive',{},204)
    const grades=await owner.call(t+'/grades');assert.equal(grades.classroomStatus,'ARCHIVED');assert.equal(grades.items.find(p=>p.userId===2).memberStatus,'LEFT');assert.equal((await owner.call(t+'/submissions/'+report.sourceSubmissionId)).sourceSha256,report.sourceSha256);assert.equal((await assistant.call(t+'/submissions/'+report.sourceSubmissionId)).sourceSha256,report.sourceSha256);report.archivedReadOnly=true
  } else throw new Error('Unknown teacher phase')
  await save(report)
}
