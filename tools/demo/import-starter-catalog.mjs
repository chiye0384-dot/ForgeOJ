// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Local owned-demo HTTP import only. No direct judge/result/database writes.
import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
import { readFile, writeFile, rename, appendFile } from 'node:fs/promises'
import { catalog } from '../../content/starter/catalog.mjs'
import { Client } from './demo-client.mjs'

const project=process.argv[2]
assert.match(project??'',/^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$/)
const state=JSON.parse(await readFile('/reports/state.json','utf8'))
assert.equal(state.Project,project,'Owned demo state mismatch')
assert.equal(state.ProductDemo,true,'Only the new dedicated product demo may receive this catalog')
const path='/reports/starter-import.json'
const digest=createHash('sha256').update(JSON.stringify(catalog)).digest('hex')
let receipt={project,catalogSha256:digest,status:'IN_PROGRESS',items:{}}
try{receipt=JSON.parse(await readFile(path,'utf8'))}catch(error){if(error.code!=='ENOENT')throw error}
assert.equal(receipt.project,project)
assert.equal(receipt.catalogSha256,digest,'Catalog changed; retain receipts and review before continuing')
async function save(stage,id){
 receipt.updatedAt=new Date().toISOString()
 await writeFile(path+'.tmp',JSON.stringify(receipt,null,2)+'\n')
 await rename(path+'.tmp',path)
 await appendFile('/reports/starter-import-history.jsonl',JSON.stringify({at:receipt.updatedAt,stage,id})+'\n')
}
const owner=new Client('learner','forgeoj-dev-only')
const reviewer=new Client('starter_super','starter-demo-admin-changed-2026',true)
const author='/api/v1/me/authored-problems',admin='/api/v1/admin/content-reviews'
if(process.argv[3]==='prepare-admin'){
 const initial=new Client('starter_super','starter-demo-admin-initial-2026',true)
 const session=await initial.login()
 assert.equal(session.admin.mustChangePassword,true)
 await initial.call('/api/v1/admin/auth/password/change','POST',{
  currentPassword:'starter-demo-admin-initial-2026',password:'starter-demo-admin-changed-2026',
 },204)
 await reviewer.login()
 console.log('Owned demo administrator prepared; no ordinary user password changed.')
 process.exit(0)
}
async function waitValidation(draftId,jobId){
 for(let poll=0;poll<240;poll++){
  const result=await owner.call(author+'/'+draftId+'/validations/'+jobId)
  if(result.processingStatus==='FINISHED'){
   assert.equal(result.validationStatus,'PASSED')
   assert.equal(result.referenceResult,'ACCEPTED')
   assert.equal(result.solutionResult,'ACCEPTED')
   return result
  }
  assert.notEqual(result.processingStatus,'SYSTEM_ERROR','Worker failure; do not automatically resubmit')
  await new Promise(resolve=>setTimeout(resolve,1000))
 }
 throw new Error('Validation still unfinished; retain its job ID and inspect before continuing')
}
try{
 await save('started')
 for(const p of catalog){
  await owner.login();await reviewer.login()
  const entry=receipt.items[p.id]??={id:p.id,title:p.content.metadata.title}
  if(entry.publishedSlug){
   const actual=await owner.call('/api/v1/problems/'+entry.publishedSlug)
   assert.equal(actual.title,p.content.metadata.title)
   continue
  }
  let detail
  if(!entry.draftId){
   const drafts=(await owner.call(author+'?page=1&size=50')).items.filter(d=>d.title===p.content.metadata.title)
   assert.ok(drafts.length<=1,'Ambiguous matching draft; inspect instead of duplicating')
   detail=drafts.length?await owner.call(author+'/'+drafts[0].id):await owner.call(author,'POST',{title:p.content.metadata.title},201)
   entry.draftId=detail.draft.id
   await save('draft',p.id)
  }
  detail??=await owner.call(author+'/'+entry.draftId)
  if(!entry.contentSaved){
   if(JSON.stringify(detail.content)!==JSON.stringify(p.content)){
    detail=await owner.call(author+'/'+entry.draftId,'PUT',{expectedVersion:detail.draft.version,content:p.content})
   }
   entry.contentSaved=true;await save('content',p.id)
  }
  if(!entry.testsSaved){
   const tests=(await owner.call(author+'/'+entry.draftId+'/tests')).map(t=>({input:t.input,expectedOutput:t.expectedOutput}))
   if(JSON.stringify(tests)!==JSON.stringify(p.tests)){
    detail=await owner.call(author+'/'+entry.draftId+'/tests','PUT',{expectedVersion:detail.draft.version,tests:p.tests})
   }
   entry.testsSaved=true;await save('tests',p.id)
  }
  if(!entry.validationIntent){
   entry.validationIntent={expectedVersion:detail.draft.version,requestId:randomUUID()}
   await save('validation-intent',p.id)
  }
  if(!entry.validationJobId){
   entry.validationJobId=(await owner.call(author+'/'+entry.draftId+'/validations','POST',entry.validationIntent,202)).jobId
   await save('validation-queued',p.id)
  }
  entry.validation=await waitValidation(entry.draftId,entry.validationJobId)
  await save('validated',p.id)
  if(!entry.reviewIntent){
   entry.reviewIntent={expectedVersion:entry.validationIntent.expectedVersion,validationJobId:entry.validationJobId,requestId:randomUUID()}
   await save('review-intent',p.id)
  }
  if(!entry.reviewId){
   entry.reviewId=(await owner.call(author+'/'+entry.draftId+'/reviews','POST',entry.reviewIntent,202)).reviewId
   await save('review',p.id)
  }
  if(!entry.approvalIntent){
   entry.approvalIntent={expectedVersion:0,clientRequestId:randomUUID(),reason:'本机原创入门题：题面、许可、样例及两份实现已检查，真实 Worker 双验证通过。'}
   await save('approval-intent',p.id)
  }
  const approved=await reviewer.call(admin+'/'+entry.reviewId+'/approve','POST',entry.approvalIntent)
  entry.publishedSlug=approved.publishedSlug
  const actual=await owner.call('/api/v1/problems/'+entry.publishedSlug)
  assert.equal(actual.title,p.content.metadata.title)
  assert.deepEqual(actual.publicSamples,p.content.metadata.samples)
  await save('published',p.id)
  console.log(p.content.metadata.title+' -> '+entry.publishedSlug)
 }
 receipt.status='VERIFIED';receipt.published=Object.values(receipt.items).filter(p=>p.publishedSlug).length
 assert.equal(receipt.published,20)
 await save('complete')
 console.log(JSON.stringify({status:receipt.status,published:receipt.published,project}))
}catch(error){receipt.status='FAILED';receipt.error=String(error);await save('failed');throw error}
