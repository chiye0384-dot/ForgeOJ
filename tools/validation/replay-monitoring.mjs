// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Exact disposable network only. Never publish credentials or cookie state.
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
const phase=process.argv[2],checks=[]
const token=(await readFile('/private/token','utf8')).trim(),password=(await readFile('/private/grafana-password','utf8')).trim()
const authorization='Bearer '+token,adminAuthorization='Basic '+Buffer.from('forgeoj_monitor_admin:'+password).toString('base64')
const viewerAuthorization='Basic '+Buffer.from('forgeoj_viewer:m4-grafana-viewer-fixture-only').toString('base64')
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function call(base,path,{status=200,auth,method='GET',body,format='json'}={}){
 const response=await fetch(base+path,{method,headers:{...(auth?{Authorization:auth}:{}),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(8000)})
 const text=await response.text();assert.equal(response.status,status,method+' '+base+path+' unexpected status');checks.push({base,path,method,status})
 return format==='text'?text:(text?JSON.parse(text):null)
}
async function eventually(fn,limit=120000){const start=Date.now();let last;while(Date.now()-start<limit){try{return await fn()}catch(e){last=e;await sleep(1000)}}throw last??Error('Observation timeout')}
const targets=[['api',9091],['api-replica',9091],['worker',9092]]
async function metrics(name,port){const text=await call(`http://${name}:${port}`,'/metrics',{auth:authorization,format:'text'});assert.match(text,/forgeoj_monitoring_snapshot_fresh 1/);for(const forbidden of [token,password,'sourceCode','source_code','password','CLASSROOM_PRIVATE','learner@','Idempotency-Key'])assert.ok(!text.includes(forbidden),'Sensitive metric data');return text}
async function query(expr){const r=await call('http://prometheus:9090','/api/v1/query?query='+encodeURIComponent(expr));assert.equal(r.status,'success');return r.data.result}
async function graf(path,options={}){return call('http://grafana:3000',path,{auth:viewerAuthorization,...options})}
// Keep the root-owned synthetic JWT reader's report separate from the nobody-owned normal probe.
const output=phase==='jwt-denied'?'/reports/monitoring-jwt-http.json':'/reports/monitoring-http.json'
let data
try{data=JSON.parse(await readFile(output,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e;data={checks:[],phases:{}}}
if(phase==='healthy'){
 for(const [name,port]of targets){const base=`http://${name}:${port}`
  for(const path of ['/metrics','/health/live','/health/ready'])await call(base,path,{status:401,format:'text'})
  await call(base,'/metrics',{status:401,auth:'Bearer ordinary-or-admin-JWT-is-not-monitoring-token',format:'text'})
  await call(base,'/metrics?keyword=private',{status:400,auth:authorization,format:'text'})
  await call(base,'/metrics',{status:405,auth:authorization,method:'POST',format:'text'})
  await eventually(()=>call(base,'/health/ready',{auth:authorization,format:'text'}))
  await writeFile('/reports/monitoring-'+name+'.prom',await metrics(name,port))
 }
 // Business router has no new public metrics route or security bypass.
 for(const path of ['/metrics','/health/live','/health/ready'])await call('http://api:8080',path,{status:401,format:'text'})
 await eventually(async()=>{const r=await query('up');assert.equal(r.length,4);assert.ok(r.every(x=>x.value[1]==='1'));return r})
 const health=await eventually(()=>call('http://grafana:3000','/api/health'));assert.equal(health.version,'13.2.3')
 await call('http://grafana:3000','/api/dashboards/uid/forgeoj-overview',{status:401})
 if(!data.grafana)await call('http://grafana:3000','/api/admin/users',{auth:adminAuthorization,method:'POST',body:{name:'ForgeOJ monitoring viewer',login:'forgeoj_viewer',email:'forgeoj-viewer@example.invalid',password:'m4-grafana-viewer-fixture-only'}})
 const me=await graf('/api/user');assert.equal(me.login,'forgeoj_viewer');assert.equal(me.isGrafanaAdmin,false)
 const dashboard=await graf('/api/dashboards/uid/forgeoj-overview');assert.equal(dashboard.dashboard.panels.length,14);assert.equal(dashboard.dashboard.editable,false);assert.equal(dashboard.meta.canEdit,false)
 await graf('/api/dashboards/db',{method:'POST',status:403,body:{dashboard:{title:'forbidden-write',uid:'forbidden'},overwrite:false}})
 await graf('/api/admin/users',{status:403,method:'POST',body:{login:'forbidden',password:'forbidden'}})
 const expressions=[]
 for(const panel of dashboard.dashboard.panels){for(const target of panel.targets){await query(target.expr);expressions.push(target.expr)}}
 const from=String(Date.now()-1800000),to=String(Date.now())
 const ds=await graf('/api/ds/query',{method:'POST',body:{from,to,queries:expressions.map((expr,i)=>({refId:'Q'+i,expr,datasource:{type:'prometheus',uid:'forgeoj-prometheus'},intervalMs:15000,maxDataPoints:120,range:true}))}})
 assert.equal(Object.keys(ds.results).length,expressions.length);for(const result of Object.values(ds.results)){assert.ok(!result.error);assert.equal(result.status,200)}
 data.grafana={version:health.version,commit:health.commit,panels:14,validExpressions:expressions.length,viewer:true,anonymousDenied:true,viewerWriteDenied:true};data.baseline={api:await metrics('api',9091),replica:await metrics('api-replica',9091),worker:await metrics('worker',9092)}
}else if(phase==='jwt-denied'){
 const clients=JSON.parse(await readFile('/reports/search-private-clients.json','utf8'))
 for(const [who,key]of [['ordinary','FORGEOJ_ACCESS'],['ops','FORGEOJ_ADMIN_ACCESS']]){
  const jwt=clients[who].cookies.find(([k])=>k===key)?.[1];assert.ok(jwt)
  const claims=JSON.parse(Buffer.from(jwt.split('.')[1],'base64url'));assert.ok(claims.exp*1000>Date.now(),'JWT boundary probe needs an unexpired credential')
  for(const [name,port]of targets){const text=await call(`http://${name}:${port}`,'/metrics',{status:401,auth:'Bearer '+jwt,format:'text'});assert.equal(text,'')}
 }
}else if(phase==='redis-outage'||phase==='es-outage'){
 const name=phase==='redis-outage'?'redis_unavailable':'search_title_fallback'
 await eventually(async()=>{const text=await metrics('api',9091);const value=Number(text.match(new RegExp(`forgeoj_api_events_total\\{event="${name}"\\} (\\d+)`))?.[1]);const old=Number(data.baseline.api.match(new RegExp(`forgeoj_api_events_total\\{event="${name}"\\} (\\d+)`))?.[1]);assert.ok(value>old);return true})
 await eventually(async()=>{const values=await query(`sum(forgeoj_api_events_total{event="${name}"})`);assert.ok(Number(values[0]?.value[1])>0)})
 for(const [name,port]of targets)await call(`http://${name}:${port}`,'/health/ready',{auth:authorization,format:'text'})
}else if(phase==='rabbit-outage'){
 await eventually(async()=>{const result=await query('up{job="rabbitmq"}');assert.equal(result[0]?.value[1],'0')})
 await call('http://api:9091','/health/ready',{auth:authorization,format:'text'})
 await eventually(async()=>{const text=await metrics('api',9091);const pending=Number(text.match(/forgeoj_outbox_pending (\d+)/)?.[1]);const failed=Number(text.match(/forgeoj_outbox_failed (\d+)/)?.[1]);assert.ok(pending+failed>=1)})
 await eventually(()=>call('http://worker:9092','/health/ready',{auth:authorization,status:503,format:'text'}),180000)
}else if(phase.endsWith('-recovered')){
 await eventually(async()=>{const r=await query('up');assert.equal(r.length,4);assert.ok(r.every(x=>x.value[1]==='1'))})
 for(const [name,port]of targets)await eventually(()=>call(`http://${name}:${port}`,'/health/ready',{auth:authorization,format:'text'}))
}else throw Error('Unsupported monitoring phase')
data.phases[phase]={allPassed:true,checkedAt:new Date().toISOString(),checks:checks.length};data.checks.push(...checks)
await writeFile(output,JSON.stringify(data,null,2)+'\n');console.log(JSON.stringify({phase,allPassed:true,checks:checks.length,grafana:data.grafana}))
