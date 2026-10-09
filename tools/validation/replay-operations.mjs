// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Unique disposable same-JAR replay. No database writes or manufactured verdicts.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
const phase=process.argv[2],base='http://localhost:5173',root='/api/v1/admin',ops=root+'/operations/tasks',author='/api/v1/me/authored-problems',checks=[]
const code='import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'
class Client {
  cookies=new Map();csrf
  async call(path,method='GET',body,status=200,extra={},record=true){
    const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; '),...extra}
    if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)})
    for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0],i=pair.indexOf('=');if(/Max-Age=0/i.test(value))this.cookies.delete(pair.slice(0,i));else this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await response.text();assert.equal(response.status,status,`${method} ${path}: ${text}`)
    if(path.startsWith(ops))assert.match(response.headers.get('cache-control')??'',/no-store/)
    if([400,401,403,404,409,503].includes(status))assert.equal(text,'')
    if(record)checks.push({method,path,status});const result=text?JSON.parse(text):null;if(result?.csrf)this.csrf=result.csrf;return result
  }
  async login(username,admin=false,password=admin?'m4-public-super-changed-password':'forgeoj-dev-only'){
    const p=admin?root:'/api/v1';await this.call(p+'/auth/session');return this.call(p+'/auth/login','POST',{username,password})
  }
}
const owner=new Client(),op=new Client(),reviewer=new Client(),superAdmin=new Client()
// Restore only identities used by this phase; repeated unused logins consume the real production limits.
if(phase!=='revoke-ops'){await owner.login('learner');await op.login('http_ops',true)}
if(phase==='recover')await reviewer.login('http_reviewer',true)
if(phase==='revoke-ops')await superAdmin.login('m4_super',true)
const request=version=>({expectedVersion:version,clientRequestId:randomUUID(),reason:'已修复一次性 Worker Docker 创建故障，按原冻结任务恢复'})
async function save(data){await writeFile('/reports/operations-http.json',JSON.stringify({...data,checkedAt:new Date().toISOString(),checks:[...(data.checks??[]),...checks]},null,2)+'\n');console.log(JSON.stringify({phase,httpChecks:(data.checks?.length??0)+checks.length,allPassed:true}))}
async function wait(path,status){
  let value
  for(let i=0;i<180;i++){value=await owner.call(path,'GET',undefined,200,{},false);const state=value.run?.processingStatus??value.preview?.processingStatus??value.processingStatus;if(state===status)return value;if(['FINISHED','SYSTEM_ERROR'].includes(state))throw new Error('Unexpected terminal state '+state);await new Promise(r=>setTimeout(r,500))}
  throw new Error('Timed out waiting for '+status)
}
async function formal(){const accepted=await owner.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode:code},202,{'Idempotency-Key':randomUUID()});await wait('/api/v1/submissions/'+accepted.submissionId,'SYSTEM_ERROR');const tasks=await op.call(ops+'?kind=FORMAL&size=50');const task=tasks.items.find(t=>t.submissionId===accepted.submissionId);assert.ok(task);return {kind:'FORMAL',id:task.id,submissionId:accepted.submissionId,resultPath:'/api/v1/submissions/'+accepted.submissionId}}
if(phase==='fault'){
  const tasks=[];tasks.push(await formal());tasks.push({...await formal(),browser:true})
  const room=await owner.call('/api/v1/classrooms','POST',{title:'M4 截止后原提交恢复验收',clientRequestId:randomUUID()},201)
  const assignmentPath=`/api/v1/classrooms/${room.id}/assignments`,deadlineAt=new Date(Date.now()+15000).toISOString()
  const created=await owner.call(assignmentPath,'POST',{clientRequestId:randomUUID(),definition:{title:'原接受时间恢复',description:'原创一次性验收',deadlineAt,acceptExistingAc:false,allowLate:false,solutionPolicy:'AFTER_AC',problemSlugs:['sum-two-integers']}},201)
  const assignment=created.assignment.id
  await owner.call(`${assignmentPath}/${assignment}/publish`,'POST',{expectedVersion:created.assignment.version,startsAt:null})
  const accepted=await owner.call(`${assignmentPath}/${assignment}/problems/sum-two-integers/submissions`,'POST',{clientRequestId:randomUUID(),language:'JAVA_21',sourceCode:code},202)
  await wait('/api/v1/submissions/'+accepted.submissionId,'SYSTEM_ERROR')
  const assignmentTask=(await op.call(ops+'?kind=FORMAL&size=50')).items.find(t=>t.submissionId===accepted.submissionId);assert.ok(assignmentTask)
  tasks.push({kind:'FORMAL',id:assignmentTask.id,submissionId:accepted.submissionId,resultPath:'/api/v1/submissions/'+accepted.submissionId,assignmentPath:`${assignmentPath}/${assignment}`,deadlineAt})
  for(const [kind,endpoint] of [['VALIDATE','validations'],['OUTPUT_PREVIEW','output-previews']]){
    let detail=await owner.call(author,'POST',{title:'M4 运维原创恢复 '+kind},201);const id=detail.draft.id
    detail.content.metadata={...detail.content.metadata,statement:'读取两个整数并输出其和。',inputDescription:'两个 long 整数',outputDescription:'一行和',samples:[{input:'1 2\n',output:'3\n'}],licenseStatement:'原创一次性验收许可。'}
    detail.content.referenceCode=code;detail.content.solutionCode=code+'\n// independent fixture';detail.content.solutionIdea='输入相加。'
    detail=await owner.call(`${author}/${id}`,'PUT',{expectedVersion:detail.draft.version,content:detail.content})
    detail=await owner.call(`${author}/${id}/tests`,'PUT',{expectedVersion:detail.draft.version,tests:[{input:'1 2\n',expectedOutput:'3\n'}]})
    const accepted=await owner.call(`${author}/${id}/${endpoint}`,'POST',{expectedVersion:detail.draft.version,requestId:randomUUID()},202)
    const path=`${author}/${id}/${endpoint}/${accepted.jobId}`;await wait(path,'SYSTEM_ERROR');tasks.push({kind,id:accepted.jobId,draftId:id,draftVersion:detail.draft.version,resultPath:path})
  }
  const self=await owner.call('/api/v1/problems/sum-two-integers/self-tests','POST',{requestId:randomUUID(),language:'JAVA_21',sourceCode:code,input:'20 22\n'},202)
  const path='/api/v1/self-tests/'+self.runId;await wait(path,'SYSTEM_ERROR');tasks.push({kind:'SELF_TEST',id:self.runId,resultPath:path})
  for(const task of tasks){const d=await op.call(`${ops}/${task.kind}/${task.id}`),attempts=await op.call(`${ops}/${task.kind}/${task.id}/attempts`);assert.equal(d.attemptCount,3);assert.equal(d.maxAttempts,3);assert.equal(attempts.total,3);assert.ok(attempts.items.every(a=>a.finishedAt&&a.status!=='RUNNING'));task.before=d;task.oldAttempts=attempts.items;assert.equal(d.executionRecoveryUsed,false)}
  while(Date.now()<=Date.parse(deadlineAt))await new Promise(r=>setTimeout(r,500))
  assert.ok(Date.now()>Date.parse(deadlineAt),'Assignment must be naturally past cutoff before recovery')
  const beforeGrade=await owner.call(`${assignmentPath}/${assignment}`);assert.notEqual(beforeGrade.problems[0].grade.state,'ON_TIME_AC')
  await save({allPassed:true,faultsRealWorker:true,tasks,checks:[]})
}else{
  const report=JSON.parse((await readFile('/reports/operations-http.json','utf8')).replace(/^\uFEFF/,''))
  if(phase==='recover'){
    for(const task of report.tasks.filter(t=>!t.browser)){
      const path=`${ops}/${task.kind}/${task.id}`,body=request(task.before.version)
      await reviewer.call(path+'/retry','POST',body,403);await owner.call(path+'/retry','POST',body,403)
      const receipt=await op.call(path+'/retry','POST',body);assert.equal(receipt.taskId,task.id)
      assert.deepEqual(await op.call(path+'/retry','POST',body),receipt)
      await op.call(path+'/retry','POST',{...body,reason:'改动理由复用键'},409)
      const result=await wait(task.resultPath,'FINISHED')
      if(task.kind==='FORMAL')assert.equal(result.verdict,'AC')
      if(task.assignmentPath){assert.ok(Date.now()>Date.parse(task.deadlineAt));const grade=(await owner.call(task.assignmentPath)).problems[0].grade;assert.equal(grade.state,'ON_TIME_AC');assert.equal(grade.attempts,1);task.originalAcceptanceGradeVerified=true}
      if(task.kind==='VALIDATE'){assert.equal(result.validationStatus,'PASSED');assert.equal(result.referenceResult,'ACCEPTED');assert.equal(result.solutionResult,'ACCEPTED')}
      if(task.kind==='OUTPUT_PREVIEW'){assert.equal(result.preview.referenceResult,'ACCEPTED');assert.equal(result.preview.acceptedVersion,null);assert.equal(result.validationStatus,undefined);assert.equal((await owner.call(`${author}/${task.draftId}`)).draft.version,task.draftVersion)}
      if(task.kind==='SELF_TEST'){assert.equal(result.run.executionResult,'SUCCESS');assert.equal(result.output,'42\n')}
      const after=await op.call(path),attempts=await op.call(path+'/attempts'),history=await op.call(path+'/recoveries')
      assert.equal(after.attemptCount,4);assert.equal(after.maxAttempts,4);assert.equal(after.executionRecoveryUsed,true);assert.equal(attempts.total,4)
      for(const old of task.oldAttempts)assert.deepEqual(attempts.items.find(a=>a.id===old.id),old)
      assert.equal(history.total,1);assert.equal(history.items[0].previousAttempts,3);assert.equal(history.items[0].previousFailureCode,task.before.failureCode)
      await op.call(path+'/retry','POST',request(after.version),409);Object.assign(task,{after,receipt,resultVerified:true})
    }
    report.executionRecoveryPassed=true
  }else if(phase==='delivery-create'){
    // Called after deleting only this disposable broker's formal queue binding.
    const accepted=await owner.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode:code},202,{'Idempotency-Key':randomUUID()})
    let task,event
    for(let i=0;i<100;i++){
      const list=await op.call(ops+'?kind=FORMAL&size=50','GET',undefined,200,{},false);task=list.items.find(t=>t.submissionId===accepted.submissionId)
      if(task){event=(await op.call(`${ops}/FORMAL/${task.id}/events`,'GET',undefined,200,{},false)).items.find(e=>e.failedAt&&!e.publishedAt);if(event)break}
      await new Promise(r=>setTimeout(r,1500))
    }
    assert.ok(event);assert.equal(event.publishAttempts,5);assert.equal(event.errorCode,'UNROUTABLE');assert.equal(task.attemptCount,0)
    report.delivery={task,event,submissionId:accepted.submissionId}
  }else if(phase==='delivery-recover'){
    const {task,event,submissionId}=report.delivery,path=`${ops}/FORMAL/${task.id}`,body={...request(task.version),expectedPublishAttempts:event.publishAttempts}
    const receipt=await op.call(`${path}/events/${event.id}/recover`,'POST',body);assert.deepEqual(await op.call(`${path}/events/${event.id}/recover`,'POST',body),receipt)
    const result=await wait('/api/v1/submissions/'+submissionId,'FINISHED');assert.equal(result.verdict,'AC')
    const after=(await op.call(path+'/events')).items.find(e=>e.id===event.id);assert.equal(after.publishAttempts,6);assert.ok(after.publishedAt);assert.equal(after.deliveryRecoveryUsed,true)
    const history=await op.call(path+'/recoveries');assert.equal(history.items[0].previousFailureCode,'UNROUTABLE');assert.equal(history.items[0].previousAttempts,5)
    report.delivery={...report.delivery,receipt,after,resultVerified:true}
  }else if(phase==='revoke-ops'){
    const current=(await superAdmin.call(root+'/accounts')).items.find(a=>a.username==='browser_ops');assert.ok(current)
    await superAdmin.call(`${root}/accounts/${current.id}/disable`,'POST',{expectedVersion:current.version,reason:'运维页面当前授权撤销验收'})
    report.browserOpsRevoked=true
  }else if(phase==='browser-audit'){
    const task=report.tasks.find(t=>t.browser),path=`${ops}/FORMAL/${task.id}`,after=await op.call(path),attempts=await op.call(path+'/attempts')
    assert.equal(after.status,'FINISHED');assert.equal(after.attemptCount,4);assert.equal(after.maxAttempts,4);assert.equal(after.executionRecoveryUsed,true);assert.equal(attempts.total,4)
    for(const old of task.oldAttempts)assert.deepEqual(attempts.items.find(a=>a.id===old.id),old)
    const result=await owner.call(task.resultPath);assert.equal(result.verdict,'AC');task.resultVerified=true;task.after=after
  }else throw new Error('Unknown operations phase')
  await save(report)
}
