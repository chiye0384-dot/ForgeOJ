// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Fixed owned HTTPS target; no external load destination, credentials or cookie output.
import https from 'node:https'
import { createHash, randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { performance, monitorEventLoopDelay } from 'node:perf_hooks'
import assert from 'node:assert/strict'
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
const data=JSON.parse(await readFile('/public/dataset.json','utf8'))
const ca=await readFile('/public/ca.pem')
assert.ok(['cold','detail','list','judge'].includes(config.mode))
assert.ok([1,4,8].includes(config.concurrency))
const agent=new https.Agent({keepAlive:true,maxSockets:config.concurrency,maxFreeSockets:config.concurrency})
const cookies=new Map(),rows=[]
let origin=performance.now()
const sha=value=>createHash('sha256').update(value).digest('hex')
function request(path,phase,body,csrf,key){return new Promise(resolve=>{
  const started=performance.now(),offset=started-origin
  let done=false,timer
  const finish=(status,text,error)=>{
    if(done)return;done=true;clearTimeout(timer)
    const row={phase,offsetMs:offset,latencyMs:performance.now()-started,status,error:error||null,valid:false}
    rows.push(row);resolve({row,text})
  }
  const req=https.request({hostname:'web',servername:'oj.test',port:443,path,ca,agent,
    method:body===undefined?'GET':'POST',headers:{Host:'oj.test:18443',Origin:'https://oj.test:18443',
      ...(config.mode==='judge'?{Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; ')}:{}),
      ...(body===undefined?{}:{'Content-Type':'application/json'}),...(csrf?{[csrf.headerName]:csrf.token}:{}),...(key?{'Idempotency-Key':key}:{})}},res=>{
    if(config.mode==='judge')for(const cookie of res.headers['set-cookie']||[]){const [pair]=cookie.split(';'),at=pair.indexOf('=');cookies.set(pair.slice(0,at),pair.slice(at+1))}
    let text='';res.setEncoding('utf8');res.on('data',part=>{text+=part;if(text.length>2*1024*1024)req.destroy(Object.assign(new Error('Response bound'),{code:'RESPONSE_LIMIT'}))})
    res.on('end',()=>finish(res.statusCode,text));res.on('error',error=>finish(res.statusCode,'',error.code||'RESPONSE_ERROR'))
  })
  timer=setTimeout(()=>req.destroy(Object.assign(new Error('Request deadline'),{code:'TIMEOUT'})),5000)
  req.on('error',error=>finish(0,'',error.code||'NETWORK_ERROR'))
  req.end(body===undefined?undefined:JSON.stringify(body))
})}
function json(response,status=200){
  assert.equal(response.row.status,status);assert.equal(response.row.error,null)
  const body=response.text?JSON.parse(response.text):null;response.row.valid=true;return body
}
async function detail(index,phase){
  const p=data.problems[index%data.problems.length]
  const response=await request('/api/v1/problems/'+p.slug,phase)
  try{const body=json(response);assert.equal(body.slug,p.slug);assert.equal(body.title,p.title);assert.equal(sha(body.statement),p.statementSha256);assert.equal(body.judgeVersion,1);assert.equal(body.resourceLimits.memoryLimitMb,256)}catch{response.row.valid=false}
  return response.row
}
async function list(index,phase){
  const bucket=index%3,page=Math.floor(index/3)%5+1
  const tag=['perf-short','perf-medium','perf-long'][bucket],difficulty=['EASY','MEDIUM','HARD'][bucket]
  const response=await request(`/api/v1/problems?keyword=PERF&difficulty=${difficulty}&tag=${tag}&page=${page}&size=20`,phase)
  try{const body=json(response);assert.equal(body.total,100);assert.equal(body.page,page);assert.equal(body.items.length,20);assert.ok(body.items.every(p=>p.difficulty===difficulty&&p.tags.includes(tag)&&p.title.startsWith('PERF ')))}catch{response.row.valid=false}
  return response.row
}
async function parallel(count,number,fn){let next=0;await Promise.all(Array.from({length:count},async()=>{while(true){const index=next++;if(index>=number)break;await fn(index)}}))}
function quantile(values,p){if(!values.length)return null;const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.ceil(sorted.length*p)-1]}
function summarize(values){return {p50Ms:quantile(values,.5),p95Ms:quantile(values,.95),p99Ms:quantile(values,.99)}}
let result,failure
const loop=monitorEventLoopDelay({resolution:10})
try{
  if(config.mode==='judge'){
    const session=json(await request('/api/v1/auth/session','setup'))
    const login=json(await request('/api/v1/auth/login','setup',{username:'learner',password:'forgeoj-dev-only'},session.csrf))
    assert.equal(login.authenticated,true)
    const sum='import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); } }'
    const profiles=[...Array.from({length:6},(_,i)=>({name:'AC-'+(i+1),expected:'AC',code:'// baseline '+i+'\n'+sum})),
      {name:'WA',expected:'WA',code:'public class Main { public static void main(String[] args) { System.out.println(0); } }'},
      {name:'CE',expected:'CE',code:'public class Main { this is not Java; }'},
      {name:'RE',expected:'RE',code:'public class Main { public static void main(String[] args) { throw new RuntimeException("owned performance fixture"); } }'},
      {name:'TLE',expected:'TLE',code:'public class Main { public static void main(String[] args) { while(true) { Thread.onSpinWait(); } } }'},
      {name:'OLE',expected:'OLE',code:'public class Main { public static void main(String[] args) { for(int i=0;i<200000;i++) System.out.println("01234567890123456789"); } }'}]
    const jobs=[];let duplicateProbe=null
    const startedAt=new Date().toISOString(),start=performance.now()
    for(const profile of profiles){
      const key=randomUUID(),begin=performance.now()
      const response=await request('/api/v1/problems/sum-two-integers/submissions','judge-accept',{language:'JAVA_21',sourceCode:profile.code},login.csrf,key)
      const accepted=json(response,202)
      if(!jobs.length){const repeated=json(await request('/api/v1/problems/sum-two-integers/submissions','idempotency-probe',{language:'JAVA_21',sourceCode:profile.code},login.csrf,key),202);assert.equal(repeated.submissionId,accepted.submissionId);duplicateProbe={submissionId:accepted.submissionId,sameId:true}}
      let latest,observed=0
      const deadline=performance.now()+120000
      do{
        latest=json(await request('/api/v1/submissions/'+accepted.submissionId,'judge-poll'));observed=performance.now()
        if(latest.processingStatus==='FINISHED')break
        await new Promise(resolve=>setTimeout(resolve,200))
      }while(performance.now()<deadline)
      assert.equal(latest.processingStatus,'FINISHED');assert.equal(latest.verdict,profile.expected)
      jobs.push({name:profile.name,expected:profile.expected,submissionId:accepted.submissionId,sourceSha256:sha(profile.code),
        acceptanceMs:response.row.latencyMs,clientVisibleMs:observed-begin,verdict:latest.verdict,statusVersion:latest.statusVersion})
    }
    result={status:'PASS',mode:config.mode,startedAt,finishedAt:new Date().toISOString(),elapsedMs:performance.now()-start,jobs,duplicateProbe,pollIntervalMs:200,
      scope:'eleven sequential fresh-container observations; six AC plus five distinct verdict probes; not saturated judge throughput'}
  }else{
    if(config.mode!=='cold')await parallel(config.concurrency,config.mode==='detail'?300:30,index=>(config.mode==='detail'?detail:list)(index,'warmup'))
    assert.ok(rows.every(row=>row.valid),'Warmup response failed validation')
    origin=performance.now();const start=origin,startedAt=new Date().toISOString(),cpu=process.cpuUsage();loop.enable();loop.reset()
    if(config.mode==='cold')await parallel(1,300,index=>detail(index,'measurement'))
    else{
      let next=0;const deadline=start+20000
      await Promise.all(Array.from({length:config.concurrency},async()=>{while(performance.now()<deadline&&next<100000){const index=next++;await(config.mode==='detail'?detail:list)(index,'measurement')}}))
    }
    const elapsedMs=performance.now()-start,usage=process.cpuUsage(cpu);loop.disable()
    const measured=rows.filter(row=>row.phase==='measurement'),success=measured.filter(row=>row.status===200&&row.valid&&!row.error)
    const errors=measured.length-success.length,statuses={}
    for(const row of measured)statuses[row.status]=(statuses[row.status]||0)+1
    result={status:errors?'FAIL':'PASS',mode:config.mode,concurrency:config.concurrency,round:config.round,startedAt,finishedAt:new Date().toISOString(),
      elapsedMs,measurementCount:measured.length,warmupCount:rows.length-measured.length,successful:success.length,errors,
      timeouts:measured.filter(r=>r.error==='TIMEOUT').length,networkErrors:measured.filter(r=>r.error&&r.error!=='TIMEOUT').length,
      semanticErrors:measured.filter(r=>r.status===200&&!r.valid&&!r.error).length,statuses,
      attemptedRps:measured.length/(elapsedMs/1000),successfulRps:success.length/(elapsedMs/1000),errorRate:errors/measured.length,
      allLatency:summarize(measured.map(r=>r.latencyMs)),successLatency:summarize(success.map(r=>r.latencyMs)),
      clientCpuPercent:(usage.user+usage.system)/(elapsedMs*10),clientRssBytes:process.memoryUsage().rss,
      clientEventLoopP99Ms:loop.percentile(99)/1e6,model:'closed loop; no retry; same bridge; validated TLS; keep-alive',maxRequestCap:100000}
    assert.ok(measured.length>0)
    if(config.mode!=='cold')assert.ok(elapsedMs>=20000&&elapsedMs<30000&&measured.length<100000,'Truncated, suspended or request-capped stage')
  }
}catch(error){failure=error;result={...(result||{}),status:'FAIL',mode:config.mode,error:'Owned performance scenario failed; private response output withheld'}}
finally{
  loop.disable();agent.destroy()
  await writeFile('/output/requests.ndjson',rows.map(row=>JSON.stringify(row)).join('\n')+'\n')
  await writeFile('/output/summary.json',JSON.stringify(result,null,2))
}
console.log(JSON.stringify(result))
if(failure||result.status!=='PASS')process.exitCode=1
