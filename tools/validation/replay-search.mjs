// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Cookies remain in this unique disposable replay directory, outside delivered evidence.
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFile,writeFile} from 'node:fs/promises'
const phase=process.argv[2],base='http://localhost:5173',admin='/api/v1/admin',checks=[]
const keyword='有符号整数',query='/api/v1/problems/search?keyword='+encodeURIComponent(keyword)
const code='import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'
class Client{
  cookies=new Map();csrf
  async call(path,method='GET',body,status=200,extra={}){
    const headers={Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...extra}
    if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
    for(const value of response.headers.getSetCookie()){const p=value.split(';')[0],i=p.indexOf('=');if(/Max-Age=0/i.test(value))this.cookies.delete(p.slice(0,i));else this.cookies.set(p.slice(0,i),p.slice(i+1))}
    const text=await response.text();assert.equal(response.status,status,method+' '+path+': '+text)
    assert.match(response.headers.get('cache-control')??'',/no-store/)
    if(status>=400)assert.equal(text,'')
    checks.push({method,path,status});const result=text?JSON.parse(text):null;if(result?.csrf)this.csrf=result.csrf;return result
  }
  async login(name,role=false,password=role?'m4-public-super-changed-password':'forgeoj-dev-only'){
    const prefix=role?admin:'/api/v1';await this.call(prefix+'/auth/session');return this.call(prefix+'/auth/login','POST',{username:name,password})
  }
  snapshot(){return {cookies:[...this.cookies],csrf:this.csrf}}
  static restore(s){const c=new Client();c.cookies=new Map(s.cookies);c.csrf=s.csrf;return c}
}
const factsPath='/reports/search-http.json',privatePath='/reports/search-private-clients.json'
let data=phase==='healthy'?{checks:[],phases:{}}:JSON.parse(await readFile(factsPath,'utf8')),clients={}
if(phase!=='healthy')for(const [name,value]of Object.entries(JSON.parse(await readFile(privatePath,'utf8'))))clients[name]=Client.restore(value)
// Stages can be separated by browser observation. Renew fixture authentication through
// the real login API instead of assuming that an old short-lived access JWT is valid.
if(phase!=='healthy'){
  await clients.ops.login('http_ops',true);await clients.super.login('m4_super',true)
  if(phase==='outage')await clients.ordinary.login('learner')
}
async function save(facts){data.phases[phase]={...facts,checkedAt:new Date().toISOString()};data.checks.push(...checks);await writeFile(factsPath,JSON.stringify(data,null,2)+'\n');await writeFile(privatePath,JSON.stringify(Object.fromEntries(Object.entries(clients).map(([k,c])=>[k,c.snapshot()])),null,2)+'\n',{mode:0o600});console.log(JSON.stringify({phase,checks:checks.length,totalChecks:data.checks.length,allPassed:true}))}
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function full(){for(let i=0;i<120;i++){const result=await clients.anon.call(query);if(result.mode==='FULL_TEXT')return result;await sleep(500)}throw Error('Full text recovery timed out')}
async function rebuild(reason){const c=clients.ops,status=await c.call(admin+'/search/status'),body={expectedVersion:status.version,clientRequestId:randomUUID(),reason};const queued=await c.call(admin+'/search/rebuilds','POST',body);const repeated=await c.call(admin+'/search/rebuilds','POST',body);assert.equal(repeated.id,queued.id);await c.call(admin+'/search/rebuilds','POST',{...body,reason:reason+' changed'},409);for(let i=0;i<120;i++){const jobs=await c.call(admin+'/search/rebuilds'),j=jobs.items.find(j=>j.id===queued.id);assert.ok(j);if(j.status==='SUCCEEDED'){data.jobs??=[];data.jobs.push({id:j.id,attempts:j.attempts,reason});return j}assert.notEqual(j.status,'FAILED');await sleep(500)}throw Error('Managed rebuild timed out')}
function whitelist(result){assert.deepEqual(Object.keys(result).sort(),['items','mode','notice','page','size','total']);for(const p of result.items){assert.deepEqual(Object.keys(p).sort(),['difficulty','highlights','judgeVersion','slug','tags','title']);for(const h of p.highlights)for(const segment of h)assert.deepEqual(Object.keys(segment).sort(),['matched','text'])}}
if(phase==='healthy'){
  clients={anon:new Client(),ops:new Client(),reviewer:new Client(),ordinary:new Client(),super:new Client()}
  await clients.ops.login('http_ops',true);await clients.super.login('m4_super',true);await clients.reviewer.login('http_reviewer',true);await clients.ordinary.login('learner')
  await clients.anon.call(admin+'/search/status','GET',undefined,401)
  await clients.reviewer.call(admin+'/search/status','GET',undefined,403);await clients.ordinary.call(admin+'/search/status','GET',undefined,403)
  const job=await rebuild('同JAR公共题搜索首次重建'),result=await full();whitelist(result);assert.ok(result.items.some(p=>p.slug==='sum-two-integers'));assert.equal(result.notice,null)
  const item=result.items.find(p=>p.slug==='sum-two-integers');assert.ok(!item.title.includes(keyword));assert.ok(item.highlights.some(h=>h.some(s=>s.matched&&s.text.includes(keyword))))
  const filtered=await clients.anon.call(query+'&difficulty='+item.difficulty+'&tag='+encodeURIComponent(item.tags[0])+'&page=1&size=1');assert.equal(filtered.mode,'FULL_TEXT');assert.equal(filtered.size,1);assert.ok(filtered.total>=1);whitelist(filtered)
  for(const tail of ['', '&size=51','&page=0'])await clients.anon.call(tail?query+tail:'/api/v1/problems/search?keyword=','GET',undefined,400)
  const absent=await clients.anon.call('/api/v1/problems/search?keyword=CLASSROOM_PRIVATE_BODY_SENTINEL');assert.equal(absent.total,0)
  const status=await clients.ops.call(admin+'/search/status');assert.deepEqual(Object.keys(status).sort(),['activeRebuild','blockedDeadLetters','deadLetters','enabled','failedDeadPublications','failedPublications','pendingEvents','publicEpoch','readableEpoch','version'])
  data.initialJob=job.id;await save({fullTextBody:true,plainTextHighlights:true,filtersAndPaging:true,managementRoles:true,responseWhitelist:true})
}else if(phase==='outage'){
  const fallback=await clients.anon.call(query);assert.equal(fallback.mode,'TITLE_FALLBACK');assert.ok(fallback.notice);assert.equal(fallback.items.some(p=>p.slug==='sum-two-integers'),false)
  const title=await clients.anon.call('/api/v1/problems/search?keyword='+encodeURIComponent('两数'));assert.equal(title.mode,'TITLE_FALLBACK');assert.ok(title.items.some(p=>p.slug==='sum-two-integers'))
  const accepted=await clients.ordinary.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode:code},202,{'Idempotency-Key':randomUUID()});
  for(let i=0;i<120;i++){const row=await clients.ordinary.call('/api/v1/submissions/'+accepted.submissionId);if(row.processingStatus==='FINISHED'){assert.equal(row.verdict,'AC');data.outageSubmission=accepted.submissionId;break}assert.notEqual(row.processingStatus,'SYSTEM_ERROR');await sleep(500)}assert.ok(data.outageSubmission)
  const p=(await clients.super.call(admin+'/public-problems')).items.find(p=>p.slug==='sum-two-integers');assert.ok(p)
  const archived=await clients.super.call(admin+'/public-problems/'+p.id+'/archive','POST',{expectedVersion:p.version,reason:'ES暂停期间公开状态复核'});await clients.anon.call('/api/v1/problems/sum-two-integers','GET',undefined,404)
  assert.ok(!(await clients.anon.call('/api/v1/problems/search?keyword='+encodeURIComponent('两数'))).items.some(p=>p.slug==='sum-two-integers'))
  await clients.super.call(admin+'/public-problems/'+p.id+'/restore','POST',{expectedVersion:archived.version,reason:'ES暂停期间恢复公开题'});await save({titleFallback:true,fixedNotice:true,realWorkerAc:true,archiveNotVisible:true})
}else if(phase==='recovered'||phase==='clear'){
  if(phase==='clear'){const fallback=await clients.anon.call(query);assert.equal(fallback.mode,'TITLE_FALLBACK')}
  await rebuild(phase==='clear'?'本项目索引清空后的受控重建':'ES恢复后的公开投影追赶重建');const result=await full();whitelist(result);assert.ok(result.items.some(p=>p.slug==='sum-two-integers'));await save({fullTextRestored:true,managedRebuild:true})
}else if(phase==='revoke-browser'){
  const account=(await clients.super.call(admin+'/accounts')).items.find(a=>a.username==='browser_ops');assert.ok(account)
  const disabled=await clients.super.call(admin+'/accounts/'+account.id+'/disable','POST',{expectedVersion:account.version,reason:'搜索运维浏览器撤权验收'});assert.equal(disabled.status,'DISABLED');await save({browserOpsDisabled:true})
}else throw Error('Unknown search replay phase')
