// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Public fixture credentials, owned replay only. No cookie or private body is exported.
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {writeFile,access} from 'node:fs/promises'
const base=process.env.FORGEOJ_INDEX_DELETION_DIRECT_API==='true'?'http://api:8080':'http://localhost:5173',origin=base,root='/api/v1/admin',checks=[],cookies=new Map()
let csrf
async function call(path,method='GET',body,authenticated=false,status=200){
  const headers=authenticated?{Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; ')}:{}
  if(method!=='GET'){headers.Origin=origin;headers['Content-Type']='application/json';if(csrf)headers[csrf.headerName]=csrf.token}
  const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
  assert.equal(response.status,status,path);assert.match(response.headers.get('cache-control')??'',/no-store/)
  if(authenticated)for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0],i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1))}
  const text=await response.text(),result=text?JSON.parse(text):null;if(result?.csrf)csrf=result.csrf;checks.push({method,path,status:response.status});return result
}
const query='/api/v1/problems/search?keyword='+encodeURIComponent('有符号整数')
if(process.argv[2]==='prepare'){
  await access('/reports/search-index-deletion-prepare.json').then(()=>{throw Error('Fixture was already prepared')},()=>{})
  await call(root+'/auth/session','GET',undefined,true)
  const first=await call(root+'/auth/login','POST',{username:'m4_super',password:'m4-public-initial-admin-password'},true);assert.equal(first.admin.mustChangePassword,true)
  await call(root+'/auth/password/change','POST',{currentPassword:'m4-public-initial-admin-password',password:'m4-public-super-changed-password'},true,204)
  await call(root+'/auth/session','GET',undefined,true);await call(root+'/auth/login','POST',{username:'m4_super',password:'m4-public-super-changed-password'},true)
  await call(root+'/accounts','POST',{clientRequestId:randomUUID(),username:'http_ops',role:'OPS_ADMIN',password:'m4-public-initial-admin-password',reason:'whole index deletion disposable fixture'},true,201)
  await call(root+'/auth/session','GET',undefined,true);await call(root+'/auth/login','POST',{username:'http_ops',password:'m4-public-initial-admin-password'},true)
  await call(root+'/auth/password/change','POST',{currentPassword:'m4-public-initial-admin-password',password:'m4-public-super-changed-password'},true,204)
  await call(root+'/auth/session','GET',undefined,true);await call(root+'/auth/login','POST',{username:'http_ops',password:'m4-public-super-changed-password'},true)
  const initial=await managedRebuild('补充整索引删除演练的初始投影');await fullText()
  await writeFile('/reports/search-index-deletion-prepare.json',JSON.stringify({allPassed:true,initialRebuildId:initial.id,initialFullTextVerified:true,firstPasswordGatesUsedRealHttp:true,checks,checkedAt:new Date().toISOString()},null,2)+'\n')
  console.log(JSON.stringify({supplementalFixturePrepared:true,checks:checks.length}));process.exit(0)
}
const missing=await call(query);assert.equal(missing.mode,'TITLE_FALLBACK');assert.ok(!missing.items.some(p=>p.slug==='sum-two-integers'))
const title=await call('/api/v1/problems/search?keyword='+encodeURIComponent('两数'));assert.equal(title.mode,'TITLE_FALLBACK');assert.ok(title.items.some(p=>p.slug==='sum-two-integers'))
await call(root+'/auth/session','GET',undefined,true)
await call(root+'/auth/login','POST',{username:'http_ops',password:'m4-public-super-changed-password'},true)
const job=await managedRebuild('整个受管索引删除后的受审计重建');await fullText()
await writeFile('/reports/search-index-deletion-http.json',JSON.stringify({allPassed:true,deletedIndexFallsBack:true,titleFallbackStillWorks:true,rebuildId:job.id,replayedSameId:true,rebuildSucceeded:true,fullTextRestored:true,checks,checkedAt:new Date().toISOString()},null,2)+'\n')
console.log(JSON.stringify({wholeIndexDeletionRecoveryPassed:true,checks:checks.length,jobId:job.id}))
async function managedRebuild(reason){const status=await call(root+'/search/status','GET',undefined,true),request={expectedVersion:status.version,clientRequestId:randomUUID(),reason},job=await call(root+'/search/rebuilds','POST',request,true),repeat=await call(root+'/search/rebuilds','POST',request,true);assert.equal(job.id,repeat.id);for(let i=0;i<60;i++){const jobs=await call(root+'/search/rebuilds','GET',undefined,true),row=jobs.items.find(j=>j.id===job.id);assert.ok(row);assert.notEqual(row.status,'FAILED');if(row.status==='SUCCEEDED')return row;await new Promise(r=>setTimeout(r,1000))}throw Error('Managed rebuild timed out')}
async function fullText(){for(let i=0;i<40;i++){const result=await call(query);if(result.mode==='FULL_TEXT'){assert.ok(result.items.some(p=>p.slug==='sum-two-integers'));assert.equal(result.notice,null);return result}await new Promise(r=>setTimeout(r,500))}throw Error('Full text did not recover')}
