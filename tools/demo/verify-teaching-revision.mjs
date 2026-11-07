// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Real Worker validation for a local teaching revision; no result or review-table writes.
import assert from 'node:assert/strict'
import {readFile, writeFile} from 'node:fs/promises'
import {createHash, randomUUID} from 'node:crypto'
import {catalog} from '../../content/starter/catalog.mjs'
import {teachingRevision} from '../../content/starter/teaching-revision.mjs'
import {Client} from './demo-client.mjs'
const hash = text => createHash('sha256').update(text).digest('hex')
const state = JSON.parse(await readFile('/reports/state.json', 'utf8'))
const imported = JSON.parse(await readFile('/reports/starter-import.json', 'utf8'))
assert.equal(state.Project, process.argv[2]); assert.equal(state.ProductDemo, true)
assert.equal(imported.project, state.Project); assert.equal(imported.status, 'VERIFIED')
assert.equal(imported.catalogSha256, hash(JSON.stringify(catalog)))
const path = '/reports/teaching-revision-validation.json'
const revisions = catalog.map(p => ({id:p.id, ...teachingRevision(p)}))
let receipt = {status:'IN_PROGRESS', project:state.Project, revisionSha256:hash(JSON.stringify(revisions)), programs:[]}
try {receipt=JSON.parse(await readFile(path,'utf8'))} catch(e) {if(e.code!=='ENOENT')throw e}
assert.equal(receipt.project,state.Project); assert.equal(receipt.revisionSha256,hash(JSON.stringify(revisions)))
const save=()=>writeFile(path,JSON.stringify(receipt,null,2)+'\n')
const client=new Client('verification-learner','forgeoj-dev-only')
await client.login()
for(const p of catalog){
 const revised=teachingRevision(p), slug=imported.items[p.id].publishedSlug
 const detail=await client.call('/api/v1/problems/'+slug)
 const entry=receipt.programs.find(e=>e.id===p.id)??{id:p.id,slug,judgeVersion:detail.judgeVersion,requestId:randomUUID(),sourceSha256:hash(revised.sourceCode),ideaSha256:hash(revised.idea)}
 if(!receipt.programs.includes(entry))receipt.programs.push(entry)
 assert.equal(entry.slug,slug);assert.equal(entry.judgeVersion,detail.judgeVersion)
 assert.equal(entry.sourceSha256,hash(revised.sourceCode));assert.equal(entry.ideaSha256,hash(revised.idea))
 await save()
 if(!entry.submissionId){
  const result=await client.call('/api/v1/problems/'+slug+'/submissions','POST',{language:'JAVA_21',sourceCode:revised.sourceCode},202,{'Idempotency-Key':entry.requestId})
  entry.submissionId=result.submissionId;await save()
 }
 let finished=false
 for(let poll=0;poll<120;poll++){
  const result=await client.call('/api/v1/submissions/'+entry.submissionId)
  if(result.processingStatus==='FINISHED'){
   entry.verdict=result.verdict;await save();assert.equal(result.verdict,'AC',p.id)
   finished=true;break
  }
  assert.notEqual(result.processingStatus,'SYSTEM_ERROR',p.id)
  await new Promise(resolve=>setTimeout(resolve,1000))
 }
 assert.ok(finished,'Unfinished original submission retained; inspect before resuming')
 console.log(p.id+': AC')
}
receipt.status='VERIFIED';receipt.checkedAt=new Date().toISOString();await save()
console.log('VERIFIED: 20 revised sources received real Worker AC; original review snapshots unchanged.')
