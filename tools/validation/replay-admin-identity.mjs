// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Public credentials for a unique, disposable replay only. Never accepts real data.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
const phase=process.argv[2],base='http://localhost:5173',root='/api/v1/admin',checks=[]
const initial='m4-public-initial-admin-password',changed='m4-public-super-changed-password'
class Client {
  cookies=new Map();csrf
  async call(path,method='GET',body,status=200,extra={}){
    const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; '),...extra}
    if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
    for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0],i=pair.indexOf('=');if(pair.startsWith('FORGEOJ_ADMIN_')){assert.match(value,/HttpOnly/);assert.match(value,/SameSite=Strict/);assert.match(value,/Path=\/api\/v1\/admin(?:;|$)/);assert.ok(!/Domain=/i.test(value))}if(/Max-Age=0/i.test(value))this.cookies.delete(pair.slice(0,i));else this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await response.text();assert.equal(response.status,status,`${method} ${path}: ${text}`)
    if(path.startsWith(root))assert.match(response.headers.get('cache-control')??'',/no-store/)
    if([401,403,409,503].includes(status))assert.equal(text,'')
    checks.push({method,path:path.replace(/\b[0-9a-f]{8}-[0-9a-f-]{27}\b/g,':uuid'),status})
    const result=text?JSON.parse(text):null;if(result?.csrf)this.csrf=result.csrf;return result
  }
  async login(username,password=changed){await this.call(root+'/auth/session');return this.call(root+'/auth/login','POST',{username,password})}
  async ordinary(){await this.call('/api/v1/auth/session');await this.call('/api/v1/auth/login','POST',{username:'learner',password:'forgeoj-dev-only'})}
  copy(){const c=new Client();c.cookies=new Map(this.cookies);c.csrf=this.csrf;return c}
}
async function save(data){await writeFile('/reports/admin-http.json',JSON.stringify({...data,checkedAt:new Date().toISOString(),checks:[...(data.checks??[]),...checks]},null,2)+'\n');console.log(JSON.stringify({phase,allPassed:true,httpChecks:(data.checks?.length??0)+checks.length}))}
if(phase==='setup'){
  const admin=new Client();const first=await admin.login('m4_super',initial);assert.equal(first.admin.id,1);assert.equal(first.admin.mustChangePassword,true);await admin.call(root+'/accounts','GET',undefined,403)
  await admin.call(root+'/auth/password/change','POST',{currentPassword:initial,password:changed},204);assert.equal((await admin.call(root+'/auth/session')).authenticated,false);await admin.login('m4_super')
  const created=[]
  for(const [username,role] of [['http_reviewer','CONTENT_REVIEWER'],['http_ops','OPS_ADMIN'],['http_second_super','SUPER_ADMIN'],['browser_super','SUPER_ADMIN'],['browser_reviewer','CONTENT_REVIEWER'],['browser_ops','OPS_ADMIN']]){
    const body={clientRequestId:randomUUID(),username,role,password:initial,reason:'disposable admin acceptance'},a=await admin.call(root+'/accounts','POST',body,201);assert.equal(a.mustChangePassword,true);assert.equal(a.role,role);created.push(a)
    assert.equal((await admin.call(root+'/accounts','POST',body,201)).id,a.id)
  }
  for(const name of ['http_reviewer','http_ops']){const limited=new Client();await limited.login(name,initial);await limited.call(root+'/auth/password/change','POST',{currentPassword:initial,password:changed},204);await limited.login(name);await limited.call(root+'/accounts','GET',undefined,403);await limited.call(root+'/audit-events','GET',undefined,403);await limited.call(root+'/accounts','POST',{clientRequestId:randomUUID(),username:'forbidden_create',role:'OPS_ADMIN',password:initial,reason:'forbidden fixture'},403)}
  const ordinary=new Client();await ordinary.ordinary();await ordinary.call(root+'/accounts','GET',undefined,403);assert.equal((await ordinary.call(root+'/auth/session')).authenticated,false);const parallel=await ordinary.login('m4_super');assert.equal(parallel.admin.id,1);assert.equal(parallel.admin.username,'m4_super');await ordinary.call(root+'/accounts');const own=await ordinary.call('/api/v1/auth/session');assert.equal(own.user.id,1);assert.equal(own.user.username,'learner');ordinary.csrf=own.csrf
  const queued=await ordinary.call('/api/v1/problems/sum-two-integers/submissions','POST',{language:'JAVA_21',sourceCode:'import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'},202,{'Idempotency-Key':randomUUID()})
  let result;for(let i=0;i<400;i++){result=await ordinary.call('/api/v1/submissions/'+queued.submissionId);if(['FINISHED','SYSTEM_ERROR'].includes(result.processingStatus))break;await new Promise(resolve=>setTimeout(resolve,300))}assert.equal(result.verdict,'AC');assert.equal(result.processingStatus,'FINISHED')
  const stale=admin.copy();await admin.call(root+'/auth/refresh','POST',{});await stale.call(root+'/auth/refresh','POST',{},401);assert.equal((await admin.call(root+'/auth/session')).authenticated,false);await admin.login('m4_super')
  const events=await admin.call(root+'/audit-events?action=ADMIN_REFRESH_REUSE_REVOKED');assert.equal(events.total,1);assert.ok(!JSON.stringify(events).includes(initial));assert.ok(!JSON.stringify(events).includes('passwordHash'))
  await save({allPassed:true,firstPasswordGate:true,ordinaryDenied:true,limitedRolesDenied:true,refreshReuseRevoked:true,cookieContract:true,sameIdDomainsIndependent:true,ordinaryAc:queued.submissionId,accounts:created,checks:[]})
}else{
  const report=JSON.parse((await readFile('/reports/admin-http.json','utf8')).replace(/^\uFEFF/,'')),admin=new Client();await admin.login('m4_super')
  const account=report.accounts.find(a=>a.username==='browser_super');assert.ok(account)
  if(phase==='readybrowsers'){
    // API functional verification, separate from browser observation; no claim of UI password submission.
    for(const username of ['browser_super','browser_reviewer','browser_ops']){const c=new Client();await c.login(username,initial);await c.call(root+'/auth/password/change','POST',{currentPassword:initial,password:'m4-public-browser-changed-password'},204);assert.equal((await c.call(root+'/auth/session')).authenticated,false)}
    report.browserPasswordsChangedByHttp=true
  }else if(phase==='revoke'){
    const current=(await admin.call(root+'/accounts')).items.find(a=>a.id===account.id)
    await admin.call(`${root}/accounts/${account.id}/disable`,'POST',{expectedVersion:current.version,reason:'browser revocation acceptance'})
    report.browserSuperDisabled=true
  }else if(phase==='audit'){
    const events=await admin.call(root+'/audit-events?size=50');assert.ok(events.items.some(e=>e.action==='ADMIN_DISABLE'));assert.equal((await admin.call(root+'/audit-events?action=ADMIN_RECOVER')).total,1);assert.equal((await admin.call(root+'/audit-events?action=ADMIN_BOOTSTRAP')).total,1);for(const e of events.items){assert.match(e.correlationId,/^[0-9a-f-]{36}$/);assert.ok(!JSON.stringify(e).includes(initial))}report.durableAudit=true
  }else throw new Error('Unknown admin phase')
  await save(report)
}
