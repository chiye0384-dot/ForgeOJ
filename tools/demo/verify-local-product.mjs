// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Persistent acceptance submissions belong only to verification-learner, not learner.
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFile,writeFile} from 'node:fs/promises'
import {catalog} from '../../content/starter/catalog.mjs'
import {Client} from './demo-client.mjs'
const project=process.argv[2]
const state=JSON.parse(await readFile('/reports/state.json','utf8'))
const imported=JSON.parse(await readFile('/reports/starter-import.json','utf8'))
assert.match(project??'',/^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$/)
assert.equal(state.Project,project);assert.equal(state.ProductDemo,true);assert.equal(imported.status,'VERIFIED')
const result={project,status:'IN_PROGRESS',startedAt:new Date().toISOString(),submissions:[]}
const output='/reports/product-http-'+Date.now()+'.json'
const save=()=>writeFile(output,JSON.stringify(result,null,2)+'\n')
const learner=new Client('verification-learner','forgeoj-dev-only')
const owner=new Client('learner','forgeoj-dev-only')
const admin=new Client('starter_super','starter-demo-admin-changed-2026',true)
async function wait(queued,verdict){
 for(let i=0;i<120;i++){
  const actual=await learner.call('/api/v1/submissions/'+queued.submissionId)
  if(actual.processingStatus==='FINISHED'){assert.equal(actual.verdict,verdict);return actual}
  assert.notEqual(actual.processingStatus,'SYSTEM_ERROR')
  await new Promise(r=>setTimeout(r,1000))
 }
 throw new Error('Submission unfinished; inspect its retained ID before continuing')
}
try{
 await save();await learner.login();await owner.login();await admin.login()
 const originalHistory=(await owner.call('/api/v1/me/submissions')).total
 const library=await learner.call('/api/v1/problems?page=1&size=50')
 assert.equal(library.total,21)
 const lists=await learner.call('/api/v1/official-problem-lists')
 assert.ok(lists.items.some(l=>l.title==='Java 入门 20 题'))
 // Use the normal audited rebuild endpoint to verify an index against all current facts.
 const search=await admin.call('/api/v1/admin/search/status')
 if(search.activeRebuild===null&&search.readableEpoch===search.publicEpoch){
  result.indexAlreadyCurrent=true
 }else{
  result.rebuildIntent={expectedVersion:search.version,clientRequestId:randomUUID(),reason:'本机入门题库发布后的完整索引核验'}
  await save()
  result.rebuild=await admin.call('/api/v1/admin/search/rebuilds','POST',result.rebuildIntent)
 }
 await save()
 for(let i=0;i<120;i++){
  const r=await learner.call('/api/v1/problems/search?keyword='+encodeURIComponent('前缀和')+'&page=1&size=20')
  if(r.mode==='FULL_TEXT'&&r.total>=1){result.fullText={mode:r.mode,total:r.total};break}
  await new Promise(r=>setTimeout(r,1000))
 }
 assert.equal(result.fullText?.mode,'FULL_TEXT')
 for(const p of catalog){
  await learner.login()
  const slug=imported.items[p.id].publishedSlug
  const requestId=randomUUID()
  const body={language:'JAVA_21',sourceCode:p.content.solutionCode}
  const queued=await learner.call('/api/v1/problems/'+slug+'/submissions','POST',body,202,{'Idempotency-Key':requestId})
  result.submissions.push({id:queued.submissionId,slug,expected:'AC'});await save()
  const repeated=await learner.call('/api/v1/problems/'+slug+'/submissions','POST',body,202,{'Idempotency-Key':requestId})
  assert.equal(repeated.submissionId,queued.submissionId)
  await wait(queued,'AC')
  const unlocked=await learner.call('/api/v1/me/problems/'+slug+'/solution')
  assert.equal(unlocked.access,'AC')
  result.submissions.at(-1).verdict='AC';await save()
  console.log(p.id+': formal AC, idempotency and solution access PASS')
 }
 const first=imported.items[catalog[0].id].publishedSlug
 const wrong=await learner.call('/api/v1/problems/'+first+'/submissions','POST',{
  language:'JAVA_21',sourceCode:'public class Main {public static void main(String[] a){System.out.println("wrong");}}',
 },202,{'Idempotency-Key':randomUUID()})
 result.submissions.push({id:wrong.submissionId,slug:first,expected:'WA'});await save()
 await wait(wrong,'WA');result.submissions.at(-1).verdict='WA'
 await owner.call('/api/v1/submissions/'+wrong.submissionId,'GET',undefined,404)
 const self=await learner.call('/api/v1/problems/'+first+'/self-tests','POST',{
  requestId:randomUUID(),language:'JAVA_21',sourceCode:catalog[0].content.solutionCode,input:catalog[0].tests[0].input,
 },202)
 result.selfTest={id:self.runId};await save()
 for(let i=0;i<120;i++){
  const actual=await learner.call('/api/v1/self-tests/'+self.runId)
  if(actual.run.processingStatus==='FINISHED'){
   assert.equal(actual.run.executionResult,'SUCCESS');assert.equal(actual.output,catalog[0].tests[0].expectedOutput)
   result.selfTest.result='SUCCESS';break
  }
  assert.notEqual(actual.run.processingStatus,'SYSTEM_ERROR')
  await new Promise(r=>setTimeout(r,1000))
 }
 assert.equal(result.selfTest.result,'SUCCESS')
 assert.equal((await owner.call('/api/v1/me/submissions')).total,originalHistory)
 result.learnerHistoryUnchanged=true;result.libraryTotal=library.total;result.status='VERIFIED'
 await save();console.log(JSON.stringify({status:result.status,formalAc:20,formalWa:1,selfTest:'SUCCESS',output}))
}catch(error){result.status='FAILED';result.error=String(error);await save();throw error}
