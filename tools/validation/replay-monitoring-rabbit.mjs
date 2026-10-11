// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Existing business API only; no SQL write, counter reset or fabricated verdict.
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFile,writeFile} from 'node:fs/promises'
const phase=process.argv[2],base='http://localhost:5173',root='/api/v1/admin',checks=[]
class Client{
 cookies=new Map();csrf
 async call(path,method='GET',body,status=200,extra={}){
  const headers={Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...extra}
  if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
  const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
  for(const value of response.headers.getSetCookie()){const p=value.split(';')[0],i=p.indexOf('=');if(/Max-Age=0/i.test(value))this.cookies.delete(p.slice(0,i));else this.cookies.set(p.slice(0,i),p.slice(i+1))}
  const text=await response.text();assert.equal(response.status,status,method+' '+path+' unexpected status');assert.match(response.headers.get('cache-control')??'',/no-store/)
  if(status>=400)assert.equal(text,'');checks.push({path,method,status});const result=text?JSON.parse(text):null;if(result?.csrf)this.csrf=result.csrf;return result
 }
 async login(name,admin=false){const p=admin?root:'/api/v1';await this.call(p+'/auth/session');await this.call(p+'/auth/login','POST',{username:name,password:admin?'m4-public-super-changed-password':'forgeoj-dev-only'})}
}
const owner=new Client(),ops=new Client(),reviewer=new Client();await owner.login('learner');await ops.login('http_ops',true)
const file='/reports/monitoring-rabbit-http.json',sourceCode='import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'
let data
if(phase==='accept'){
 let checkpoint
 try{checkpoint=JSON.parse(await readFile('/reports/monitoring-rabbit-accept-checkpoint.json','utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
 const requestId=checkpoint?.requestId??randomUUID(),accepted=await owner.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode},202,{'Idempotency-Key':requestId})
 if(checkpoint)assert.equal(accepted.submissionId,checkpoint.submissionId)
 await writeFile('/reports/monitoring-rabbit-accept-checkpoint.json',JSON.stringify({requestId,submissionId:accepted.submissionId})+'\n')
 assert.equal((await owner.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode},202,{'Idempotency-Key':requestId})).submissionId,accepted.submissionId)
 const row=await owner.call('/api/v1/submissions/'+accepted.submissionId);assert.equal(row.processingStatus,'QUEUED')
 // The existing operations list shows exceptions only. A queued unpublished task
 // appears after its original bounded publication attempts fail, not immediately.
 let task
 for(let i=0;i<60;i++){task=(await ops.call(root+'/operations/tasks?kind=FORMAL&size=50')).items.find(t=>t.submissionId===accepted.submissionId);if(task)break;await new Promise(r=>setTimeout(r,3000))}
 assert.ok(task,'Original event must enter the existing exception list after its bounded publication budget')
 const attempts=await ops.call(root+'/operations/tasks/FORMAL/'+task.id+'/attempts');assert.equal(attempts.total,0)
 data={submissionId:accepted.submissionId,taskId:task.id,requestId,acceptedWhileRabbitUnavailable:true,duplicateSameId:true,noExecutionAttemptDuringOutage:true,checks:[]}
}else if(phase==='rearm'||phase==='recover'){
 data=JSON.parse(await readFile(file,'utf8'));const path=root+'/operations/tasks/FORMAL/'+data.taskId
 const detail=await ops.call(path),events=await ops.call(path+'/events');assert.equal(events.items.length,1);const event=events.items[0];data.originalEventId=event.id
 if(phase==='rearm'){
  // Resume the broker with this exact test Worker's process temporarily paused.
  // A timed-out confirm may still deliver buffered bytes; eligibility must be
  // checked before execution advances the task, without resetting SQL facts.
  assert.ok(event.failedAt);assert.equal(event.publishedAt,null)
  assert.equal(event.publishAttempts,5);assert.equal(event.deliveryRecoveryUsed,false);await reviewer.login('http_reviewer',true)
  const body={expectedVersion:detail.version,expectedPublishAttempts:event.publishAttempts,clientRequestId:randomUUID(),reason:'Rabbit私有暂停演练已恢复，重送原持久化事件'},endpoint=path+'/events/'+event.id+'/recover'
  await owner.call(endpoint,'POST',body,403);await reviewer.call(endpoint,'POST',body,403)
  const receipt=await ops.call(endpoint,'POST',body);assert.deepEqual(await ops.call(endpoint,'POST',body),receipt)
  await ops.call(endpoint,'POST',{...body,reason:body.reason+' changed'},409)
  data.manualDeliveryRecovery={receipt,reason:body.reason,originalPublishAttempts:event.publishAttempts,originalFailureCode:event.errorCode}
  await writeFile(file,JSON.stringify({...data,checks:[...data.checks,...checks]},null,2)+'\n')
  let published
  for(let i=0;i<60;i++){published=(await ops.call(path+'/events')).items.find(e=>e.id===event.id);if(published?.publishedAt)break;await new Promise(r=>setTimeout(r,2000))}
  assert.ok(published?.publishedAt,'Original event needs its real broker confirmation before the test Worker resumes')
  assert.equal((await ops.call(path+'/attempts')).total,0)
  data.sameEventPublished=true;data.rearmedBeforeWorkerResumed=true
  data.checks.push(...checks);data.checkedAt=new Date().toISOString();await writeFile(file,JSON.stringify(data,null,2)+'\n');console.log(JSON.stringify({phase,allPassed:true,checks:checks.length,manualRecovery:true}));process.exit(0)
 }
 assert.equal(data.rearmedBeforeWorkerResumed,true);assert.ok(data.manualDeliveryRecovery)
 let terminal
 for(let i=0;i<180;i++){terminal=await owner.call('/api/v1/submissions/'+data.submissionId);if(terminal.processingStatus==='FINISHED')break;assert.notEqual(terminal.processingStatus,'SYSTEM_ERROR');await new Promise(r=>setTimeout(r,500))}
 assert.equal(terminal.processingStatus,'FINISHED');assert.equal(terminal.verdict,'AC')
 const after=(await ops.call(path+'/events')).items;assert.equal(after.length,1);assert.equal(after[0].id,event.id);assert.ok(after[0].publishedAt)
 data.sameEventPublished=true;data.realWorkerAc=true;data.terminal=terminal;data.allPassed=true
}else throw Error('Unsupported Rabbit phase')
data.checks.push(...checks);data.checkedAt=new Date().toISOString();await writeFile(file,JSON.stringify(data,null,2)+'\n');console.log(JSON.stringify({phase,allPassed:true,checks:checks.length,manualRecovery:!!data.manualDeliveryRecovery}))
