// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Same-JAR disposable replay. Private fixture cookies stay under /reports and are never delivered as evidence.
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFile,writeFile} from 'node:fs/promises'
const phase=process.argv[2],base='http://localhost:5173',root='/api/v1/admin',checks=[]
const changed='m4-redis-changed-fixture-password',initial='m4-redis-initial-fixture-password'
const code='import java.util.Scanner; public class Main { public static void main(String[] a) {Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong());}}'
class Client {
  cookies=new Map();csrf
  constructor(url=base){this.base=url}
  async call(path,method='GET',body,status=200,extra={}) {
    const headers={Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...extra}
    if(method!=='GET'){headers.Origin=this.base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const response=await fetch(this.base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)})
    for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0],i=pair.indexOf('=');if(/Max-Age=0/i.test(value))this.cookies.delete(pair.slice(0,i));else this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await response.text();assert.equal(response.status,status,method+' '+path+': '+text)
    if(path.startsWith(root)||path.startsWith('/api/v1/me')||path.startsWith('/api/v1/submissions'))assert.match(response.headers.get('cache-control')??'',/no-store/)
    checks.push({method,path,status,instance:this.base==='http://api-replica:8080'?'replica':this.base==='http://api:8080'?'primary-direct':'frontend'})
    const data=text?JSON.parse(text):null;if(data?.csrf)this.csrf=data.csrf;return data
  }
  async login(name,password,admin=false){const prefix=admin?root:'/api/v1';await this.call(prefix+'/auth/session');return this.call(prefix+'/auth/login','POST',{username:name,password})}
  snapshot(){return {base:this.base,cookies:[...this.cookies],csrf:this.csrf}}
  static restore(value){const client=new Client(value.base);client.cookies=new Map(value.cookies);client.csrf=value.csrf;return client}
}
const privatePath='/reports/redis-private-clients.json',factsPath='/reports/redis-http.json'
let data=phase==='healthy'?{checks:[],phaseFacts:{}}:JSON.parse(await readFile(factsPath,'utf8'))
let clients={}
if(phase!=='healthy'){const saved=JSON.parse(await readFile(privatePath,'utf8'));for(const [name,value]of Object.entries(saved))clients[name]=Client.restore(value)}
async function save(facts) {
  data.phaseFacts[phase]={...facts,checkedAt:new Date().toISOString()};data.checks.push(...checks)
  await writeFile(factsPath,JSON.stringify(data,null,2)+'\n')
  await writeFile(privatePath,JSON.stringify(Object.fromEntries(Object.entries(clients).map(([name,c])=>[name,c.snapshot()])),null,2)+'\n',{mode:0o600})
  console.log(JSON.stringify({phase,checks:checks.length,totalChecks:data.checks.length,allPassed:true}))
}
async function waitFormal(client,id){for(let i=0;i<90;i++){const row=await client.call('/api/v1/submissions/'+id);if(row.processingStatus==='FINISHED'){assert.equal(row.verdict,'AC');return row}assert.notEqual(row.processingStatus,'SYSTEM_ERROR');await new Promise(r=>setTimeout(r,500))}throw Error('Real Worker AC timed out')}
async function submit(client,request){return client.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode:code},202,{'Idempotency-Key':request})}
function requireUnexpiredAccess(client,admin=false){
  const token=client.cookies.get(admin?'FORGEOJ_ADMIN_ACCESS':'FORGEOJ_ACCESS');assert.ok(token,'Retained access cookie missing')
  const claims=JSON.parse(Buffer.from(token.split('.')[1],'base64url').toString('utf8'))
  assert.ok(Number.isFinite(claims.exp)&&claims.exp*1000>Date.now(),'Revocation probe requires an unexpired JWT')
}
if(phase==='healthy'){
  const anonymous=new Client(),ordinary=new Client(),superAdmin=new Client()
  const detail=await anonymous.call('/api/v1/problems/sum-two-integers');assert.deepEqual(await anonymous.call('/api/v1/problems/sum-two-integers'),detail)
  assert.deepEqual(Object.keys(detail).sort(),['slug','title','statement','inputDescription','outputDescription','publicSamples','judgeVersion','resourceLimits'].sort())
  const lists=await anonymous.call('/api/v1/official-problem-lists');assert(lists.items.length>0)
  const officialId=lists.items[0].id,official=await anonymous.call('/api/v1/official-problem-lists/'+officialId)
  assert.deepEqual(await anonymous.call('/api/v1/official-problem-lists/'+officialId),official)
  assert(!JSON.stringify(official).includes('completed'))
  await ordinary.login('redis_learner','forgeoj-dev-only')
  const request=randomUUID(),accepted=await submit(ordinary,request)
  const repeated=await Promise.all(Array.from({length:6},()=>submit(ordinary,request)))
  assert(repeated.every(r=>r.submissionId===accepted.submissionId));const terminal=await waitFormal(ordinary,accepted.submissionId)
  await superAdmin.login('m4_super','m4-public-super-changed-password',true)
  const created={}
  for(const [name,role]of [['redis_ops','OPS_ADMIN'],['redis_reviewer','CONTENT_REVIEWER']]){
    const row=await superAdmin.call(root+'/accounts','POST',{clientRequestId:randomUUID(),username:name,role,password:initial,reason:'Redis same-JAR public fixture'},201)
    const c=new Client();await c.login(name,initial,true);await c.call(root+'/auth/password/change','POST',{currentPassword:initial,password:changed},204);await c.login(name,changed,true)
    clients[name]=c;created[name]={id:row.id,role}
  }
  await clients.redis_ops.call(root+'/operations/tasks');await clients.redis_reviewer.call(root+'/content-reviews')
  const one=new Client('http://api:8080'),two=new Client('http://api-replica:8080'),limitedName='redis_limit_'+randomUUID().slice(0,8)
  await one.call('/api/v1/auth/session');await two.call('/api/v1/auth/session')
  for(let i=0;i<11;i++)await (i%2?two:one).call('/api/v1/auth/login','POST',{username:limitedName,password:'invalid-public-fixture'},i<10?401:429)
  clients={...clients,anonymous,ordinary,superAdmin,one,two}
  data.accepted={request,submissionId:accepted.submissionId,status:terminal.processingStatus,verdict:terminal.verdict};data.officialId=officialId;data.created=created;data.limitedName=limitedName
  await save({sameKeyResponses:7,realAc:true,sharedLoginBudget:10,twoApiInstances:true})
}else if(phase==='guard-failure'){
  await clients.ordinary.call('/api/v1/submissions/'+data.accepted.submissionId,'GET',undefined,503)
  await clients.anonymous.call('/api/v1/problems/sum-two-integers')
  await save({positiveSessionCacheDidNotBypassMysqlFailure:true,anonymousPublicStillAvailable:true})
}else if(phase==='outage'){
  const {anonymous,ordinary,superAdmin,one,two}=clients
  await anonymous.call('/api/v1/problems/sum-two-integers');await anonymous.call('/api/v1/official-problem-lists/'+data.officialId)
  assert.equal((await submit(ordinary,data.accepted.request)).submissionId,data.accepted.submissionId)
  const fresh=await submit(ordinary,randomUUID());await waitFormal(ordinary,fresh.submissionId);data.outageAccepted=fresh.submissionId
  await clients.redis_ops.call(root+'/operations/tasks');await clients.redis_reviewer.call(root+'/content-reviews')
  // Capture old browser credentials before the real HTTP revocation; they stay only in the private fixture file.
  clients.staleOrdinary=Client.restore(ordinary.snapshot());await ordinary.call('/api/v1/auth/logout','POST',{},204)
  requireUnexpiredAccess(clients.staleOrdinary)
  await clients.staleOrdinary.call('/api/v1/submissions/'+data.accepted.submissionId,'GET',undefined,401)
  const accounts=await superAdmin.call(root+'/accounts'),op=accounts.items.find(a=>a.id===data.created.redis_ops.id),reviewer=accounts.items.find(a=>a.id===data.created.redis_reviewer.id)
  assert(op&&reviewer)
  await superAdmin.call(root+'/accounts/'+op.id+'/role','PUT',{expectedVersion:op.version,role:'CONTENT_REVIEWER',reason:'Redis unavailable current-role fixture'})
  requireUnexpiredAccess(clients.redis_ops,true)
  await clients.redis_ops.call(root+'/operations/tasks','GET',undefined,401)
  await superAdmin.call(root+'/accounts/'+reviewer.id+'/disable','POST',{expectedVersion:reviewer.version,reason:'Redis unavailable current-revocation fixture'})
  requireUnexpiredAccess(clients.redis_reviewer,true)
  await clients.redis_reviewer.call(root+'/content-reviews','GET',undefined,401)
  const problems=await superAdmin.call(root+'/public-problems'),p=problems.items.find(p=>p.slug==='sum-two-integers');assert(p)
  const archived=await superAdmin.call(root+'/public-problems/'+p.id+'/archive','POST',{expectedVersion:p.version,reason:'Redis unavailable public visibility fixture'})
  await anonymous.call('/api/v1/problems/sum-two-integers','GET',undefined,404)
  await superAdmin.call(root+'/public-problems/'+p.id+'/restore','POST',{expectedVersion:archived.version,reason:'Restore disposable public fixture'})
  await anonymous.call('/api/v1/problems/sum-two-integers')
  for(let i=0;i<5;i++)await one.call('/api/v1/auth/login','POST',{username:data.limitedName,password:'invalid-public-fixture'},i<4?401:429)
  for(let i=0;i<6;i++)await two.call('/api/v1/auth/login','POST',{username:data.limitedName,password:'invalid-public-fixture'},i<5?401:429)
  await save({realAcDuringOutage:true,ordinaryOldJwtRejected:true,adminDowngradeRejected:true,disabledAdminRejected:true,archivedPublicRejected:true,unexpiredRevocationProbes:true,retainedLocalBudgets:{primaryRemaining:4,replicaRemaining:5}})
}else if(phase==='recovered'){
  const {anonymous,ordinary}=clients
  await anonymous.call('/api/v1/problems/sum-two-integers');await anonymous.call('/api/v1/problems/sum-two-integers')
  await anonymous.call('/api/v1/official-problem-lists/'+data.officialId)
  await clients.staleOrdinary.call('/api/v1/submissions/'+data.accepted.submissionId,'GET',undefined,401)
  await ordinary.login('redis_learner','forgeoj-dev-only');assert.equal((await submit(ordinary,data.accepted.request)).submissionId,data.accepted.submissionId)
  const downgraded=new Client();const session=await downgraded.login('redis_ops',changed,true);assert.equal(session.admin.role,'CONTENT_REVIEWER')
  await downgraded.call(root+'/operations/tasks','GET',undefined,403);await downgraded.call(root+'/content-reviews')
  await clients.redis_reviewer.call(root+'/content-reviews','GET',undefined,401)
  clients.downgraded=downgraded
  // Redis FLUSHDB was part of recovery; the old process-local budgets must still deny these attempts.
  await clients.one.call('/api/v1/auth/login','POST',{username:data.limitedName,password:'invalid-public-fixture'},429)
  await clients.two.call('/api/v1/auth/login','POST',{username:data.limitedName,password:'invalid-public-fixture'},429)
  await save({oldSessionStillRejected:true,currentDowngradedRole:true,sameOriginalReceipt:true,flushDidNotResetLocalLimits:true,publicCacheRebuilt:true})
}else if(phase==='warm'){
  assert.equal(data.phaseFacts.recovered?.publicCacheRebuilt,true)
  await clients.anonymous.call('/api/v1/problems/sum-two-integers')
  await clients.anonymous.call('/api/v1/official-problem-lists/'+data.officialId)
  await clients.ordinary.call('/api/v1/submissions/'+data.accepted.submissionId)
  await clients.downgraded.call(root+'/content-reviews')
  await save({currentClientsRecheckedWithoutNewLogin:true})
}else throw Error('Unsupported phase')
