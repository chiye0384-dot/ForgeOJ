// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createHash,randomUUID} from 'node:crypto'
import {catalog} from '../../content/starter/catalog.mjs'
import {teachingRevision} from '../../content/starter/teaching-revision.mjs'
import {legacySumSolution} from '../../content/starter/legacy-sum-solution.mjs'
import {Client} from './demo-client.mjs'
const hash=s=>createHash('sha256').update(s).digest('hex')
const state=JSON.parse(await readFile('/reports/state.json','utf8'))
const imported=JSON.parse(await readFile('/reports/starter-import.json','utf8'))
const teaching=JSON.parse(await readFile('/reports/teaching-revision-validation.json','utf8'))
assert.equal(state.Project,process.argv[2]);assert.equal(state.ProductDemo,true)
const path='/reports/practice-continuity-http.json'
let proof={status:'IN_PROGRESS',project:state.Project,programs:{}}
try{proof=JSON.parse(await readFile(path,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
assert.equal(proof.project,state.Project)
const save=()=>writeFile(path,JSON.stringify(proof,null,2)+'\n')
const client=new Client('verification-learner','forgeoj-dev-only')
const other=new Client('UxSignup_1793325802371','public-ux-fixture-password')
await client.login();await other.login()
const original=teaching.programs[0]
const source=await client.call('/api/v1/me/submissions/'+original.submissionId+'/source')
assert.equal(hash(source.sourceCode),original.sourceSha256)
await other.call('/api/v1/me/submissions/'+original.submissionId+'/source','GET',undefined,404)
await new Client().call('/api/v1/me/submissions/'+original.submissionId+'/source','GET',undefined,401)
const privateResponse=await fetch('http://localhost:5173/api/v1/me/submissions/'+original.submissionId+'/source',{headers:{Cookie:[...client.cookies].map(([k,v])=>k+'='+v).join('; ')}})
assert.match(privateResponse.headers.get('Cache-Control'),/no-store/)
proof.ownerSource={submissionId:original.submissionId,sourceSha256:original.sourceSha256,otherOwner:404,anonymous:401,noStore:true}
await save()
async function submit(label,who,slug,sourceCode,expected,replay=false){
 const e=proof.programs[label]??={requestId:randomUUID(),slug,sourceSha256:hash(sourceCode)}
 assert.equal(e.slug,slug);assert.equal(e.sourceSha256,hash(sourceCode));await save()
 const post=()=>who.call('/api/v1/problems/'+slug+'/submissions','POST',{language:'JAVA_21',sourceCode},202,{'Idempotency-Key':e.requestId})
 if(!e.submissionId){const accepted=await post();e.submissionId=accepted.submissionId;await save()}
 if(replay){const accepted=await post();assert.equal(accepted.submissionId,e.submissionId);e.sameReceipt=true;await save()}
 let finished=false
 for(let i=0;i<120;i++){
  const result=await who.call('/api/v1/submissions/'+e.submissionId)
  if(result.processingStatus==='FINISHED'){e.verdict=result.verdict;await save();assert.equal(result.verdict,expected);finished=true;break}
  assert.notEqual(result.processingStatus,'SYSTEM_ERROR');await new Promise(r=>setTimeout(r,1000))
 }
 assert.ok(finished,'Original pending request retained; no new submission key')
 const code=await who.call('/api/v1/me/submissions/'+e.submissionId+'/source')
 assert.equal(code.sourceCode,sourceCode)
 return e
}
await submit('retry',client,'sum-two-integers',legacySumSolution.sourceCode,'AC',true)
await submit('attempted',other,imported.items['max-two'].publishedSlug,'public class Main {public static void main(String[] args){System.out.println(0);}}','WA')
await submit('solved',other,imported.items.parity.publishedSlug,teachingRevision(catalog.find(p=>p.id==='parity')).sourceCode,'AC')
const all=await other.call('/api/v1/me/practice-problems?size=50')
assert.equal(all.total,21);assert.equal(all.items.length,21)
assert.equal(all.items.find(p=>p.slug===proof.programs.attempted.slug).progress,'ATTEMPTED')
assert.equal(all.items.find(p=>p.slug===proof.programs.solved.slug).progress,'SOLVED')
proof.progress={}
for(const progress of ['SOLVED','ATTEMPTED','UNTRIED']){
 const expected=all.items.filter(p=>p.progress===progress)
 assert.ok(expected.length>0)
 const page=await other.call('/api/v1/me/practice-problems?progress='+progress+'&page=1&size=1')
 assert.equal(page.total,expected.length);assert.equal(page.items.length,1);assert.equal(page.items[0].progress,progress)
 proof.progress[progress]=page.total
}
const publicPage=await new Client().call('/api/v1/problems?size=50')
assert.ok(publicPage.items.every(p=>!('progress' in p)&&!('sourceCode' in p)))
const keyword=await other.call('/api/v1/me/practice-problems?keyword='+encodeURIComponent('整数')+'&size=50')
assert.equal(keyword.mode,'FULL_TEXT')
for(const progress of ['SOLVED','ATTEMPTED','UNTRIED']){
 const page=await other.call('/api/v1/me/practice-problems?keyword='+encodeURIComponent('整数')+'&progress='+progress+'&size=1')
 assert.equal(page.mode,'FULL_TEXT');assert.equal(page.total,keyword.items.filter(p=>p.progress===progress).length)
 assert.ok(page.items.every(p=>p.progress===progress))
}
proof.search={mode:keyword.mode,total:keyword.total,filterBeforePagination:true}
for(const p of catalog){
 const slug=imported.items[p.id].publishedSlug,expected=teachingRevision(p)
 const current=await client.call('/api/v1/me/problems/'+slug+'/solution')
 assert.equal(hash(current.solution.idea),hash(expected.idea));assert.equal(hash(current.solution.sourceCode),hash(expected.sourceCode))
}
assert.equal((await client.call('/api/v1/problems/sum-two-integers')).title,'整数相加（A + B）')
proof.status='VERIFIED';proof.checkedAt=new Date().toISOString();await save()
console.log('VERIFIED: retry receipt, private historical source, 3 progress states, FULL_TEXT filtering and 20 revised solutions.')
