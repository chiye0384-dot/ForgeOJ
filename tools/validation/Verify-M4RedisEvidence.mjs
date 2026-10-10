// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {readFile,readdir,mkdir,writeFile,copyFile} from 'node:fs/promises'
import {resolve,relative,join} from 'node:path'
import {fileURLToPath} from 'node:url'
import {gzipSync,inflateRawSync} from 'node:zlib'
const root=resolve(fileURLToPath(new URL('../..',import.meta.url))),args=process.argv.slice(2)
assert.equal(args.length,3,'Provide build/replay/output directories')
const [build,replay,out]=args.map(p=>resolve(root,p))
for(const p of [build,replay,out])assert.ok(!relative(root,p).startsWith('..'),'Outside repository')
const sha=b=>createHash('sha256').update(b).digest('hex')
const json=async(dir,name)=>JSON.parse((await readFile(join(dir,name),'utf8')).replace(/^\uFEFF/,''))
const lines=async name=>(await readFile(join(replay,name),'utf8')).replace(/^\uFEFF/,'').trim().split(/\r?\n/).filter(Boolean)
const jsonl=async name=>(await lines(name)).map(s=>JSON.parse(s))
const manifest=text=>new Map(text.trim().split(/\r?\n/).map(line=>{const [hash,...name]=line.split(/\s+/);return [name.join(' ').replace(/^\.\//,''),hash]}))
async function walk(path){const files=[];for(const e of await readdir(join(root,path),{withFileTypes:true})){if(['node_modules','dist','target','.git','.idea'].includes(e.name)||e.name.endsWith('.local')||e.name.startsWith('.env'))continue;const p=path+'/'+e.name;if(e.isDirectory())files.push(...await walk(p));else files.push(p)}return files.sort()}
const bm=manifest(await readFile(join(build,'source-files.sha256'),'utf8'))
const rm=manifest(await readFile(join(replay,'frontend-runtime.sha256'),'utf8')),sm=manifest(await readFile(join(replay,'frontend-served-runtime.sha256'),'utf8'))
const apiInputs=['pom.xml','forgeoj-api/pom.xml',...await walk('forgeoj-api/src')]
const workerInputs=['pom.xml','forgeoj-judge-worker/pom.xml',...await walk('forgeoj-judge-worker/src'),...await walk('forgeoj-api/src/main/resources/db')]
const frontInputs=await walk('frontend'),inputFacts=[]
for(const p of [...new Set([...apiInputs,...workerInputs,...frontInputs])]){const digest=sha(await readFile(join(root,p)));assert.equal(digest,bm.get(p),'Build input drift '+p);if(p.startsWith('frontend/')){assert.equal(digest,rm.get(p),'Mounted frontend drift '+p);assert.equal(digest,sm.get(p),'Served frontend drift '+p)}inputFacts.push({path:p,sha256:digest})}
const helperInputs=['tools/validation/Replay-FixedLinux.ps1','tools/validation/compose.replay.yml','tools/validation/Verify-M4RedisReplay.ps1','tools/validation/replay-redis.mjs','tools/validation/redis-fixture.sql']
const hm=manifest(await readFile(join(replay,'redis-tool-inputs.sha256'),'utf8'))
for(const p of helperInputs)assert.equal(sha(await readFile(join(root,p))),hm.get(p),'Replay helper drift '+p)
// The post-replay auditor is not a runtime input. Preserve its frozen version
// and report both digests when correcting SQL JSON types after the first audit.
const auditorPath='tools/validation/Verify-M4RedisEvidence.mjs'
const auditorCurrent=await readFile(join(root,auditorPath))
const auditorAtReplay=sha(auditorCurrent)===hm.get(auditorPath)?auditorCurrent:await readFile(join(replay,'redis-evidence-auditor-at-replay.mjs'))
const auditorFrozen=sha(auditorAtReplay)
assert.equal(auditorFrozen,hm.get(auditorPath),'Frozen auditor drift')
for(const [p,h]of hm)if(p!==auditorPath)assert.equal(sha(await readFile(join(root,p))),h,'Helper input drift '+p)
async function suites(module){const details=[];for(const name of await readdir(join(build,module,'surefire-reports'))){if(!name.startsWith('TEST-')||!name.endsWith('.xml'))continue;const xml=await readFile(join(build,module,'surefire-reports',name),'utf8'),tag=xml.match(/<testsuite\b[^>]*>/)?.[0];assert.ok(tag);const attr=k=>Number(tag.match(new RegExp(`${k}="(\\d+)"`))?.[1]);const row={name,tests:attr('tests'),failures:attr('failures'),errors:attr('errors'),skipped:attr('skipped')};for(const k of ['failures','errors','skipped'])assert.equal(row[k],0,name+' '+k);details.push(row)}return {tests:details.reduce((n,r)=>n+r.tests,0),suites:details.length,details}}
const api=await suites('forgeoj-api'),worker=await suites('forgeoj-judge-worker')
assert.equal(api.tests,289);assert.equal(api.suites,50);assert.equal(worker.tests,133);assert.equal(worker.suites,23)
const backendLog=await readFile(join(build,'backend.log')),frontLog=await readFile(join(build,'frontend.log'))
assert.match(backendLog.toString(),/BUILD SUCCESS/)
const fl=frontLog.toString().replace(/\u001b\[[0-9;]*m/g,'');assert.match(fl,/24 passed \(24\)/);assert.match(fl,/124 passed \(124\)/);assert.match(fl,/built in/)
const apiJar=await readFile(join(build,'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar')),apiSha256=sha(apiJar),workerSha256=sha(await readFile(join(build,'forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar')))
const state=await json(replay,'state.json');assert.equal(state.RedisEnabled,true);assert.equal(state.ApiHash,apiSha256);assert.equal(state.WorkerHash,workerSha256)
const bootstrap=await jsonl('redis-bootstrap.jsonl');assert.equal(bootstrap.length,1);assert.equal(bootstrap[0].administrators,1);assert.equal(bootstrap[0].bootstrapEvents,1);assert.equal(bootstrap[0].bootstrapped,1);assert.equal(bootstrap[0].fixtureOnly,true)
const runtime=await json(replay,'redis-runtime.json');assert.equal(runtime.length,4)
for(const service of ['api','api-replica','worker','redis']){const r=runtime.find(r=>r.service===service);assert.ok(r);assert.equal(r.project,state.Project);if(service!=='redis')assert.equal(r.jarSha256,service==='worker'?workerSha256:apiSha256);if(service==='worker')assert.ok(!r.envNames.some(e=>e.includes('REDIS')));else assert.equal(r.hasDockerSocket,false)}
assert.notEqual(runtime.find(r=>r.service==='api').id,runtime.find(r=>r.service==='api-replica').id)
const redisRuntime=runtime.find(r=>r.service==='redis');assert.match(redisRuntime.serverVersion,/v=7\.2\.16\b/);assert.equal(redisRuntime.configuredImage,'redis:7.2.16-alpine@sha256:29e8589c3f9ba699b5f7aa4b3c7733c58852a3626439e619aa0ee78de08c6ca0')
const ownership=await json(replay,'redis-ownership.json');assert.equal(ownership.primary,runtime.find(r=>r.service==='api').id);assert.equal(ownership.replica,runtime.find(r=>r.service==='api-replica').id)
const http=await json(replay,'redis-http.json')
for(const phase of ['healthy','guard-failure','outage','recovered'])assert.ok(http.phaseFacts[phase],phase+' missing')
assert.equal(http.phaseFacts.healthy.sameKeyResponses,7);assert.equal(http.phaseFacts.healthy.realAc,true);assert.equal(http.phaseFacts.healthy.twoApiInstances,true)
assert.equal(http.phaseFacts['guard-failure'].positiveSessionCacheDidNotBypassMysqlFailure,true)
for(const k of ['realAcDuringOutage','ordinaryOldJwtRejected','adminDowngradeRejected','disabledAdminRejected','archivedPublicRejected','unexpiredRevocationProbes'])assert.equal(http.phaseFacts.outage[k],true,k)
for(const k of ['oldSessionStillRejected','currentDowngradedRole','sameOriginalReceipt','flushDidNotResetLocalLimits','publicCacheRebuilt'])assert.equal(http.phaseFacts.recovered[k],true,k)
const submissions=await jsonl('redis-submissions.jsonl');assert.equal(submissions.length,2)
for(const id of [http.accepted.submissionId,http.outageAccepted]){const s=submissions.find(s=>s.id===id);assert.ok(s);assert.equal(s.status,'FINISHED');assert.equal(s.verdict,'AC');assert.equal(s.taskCount,1);assert.equal(s.eventCount,1)}
assert.equal(submissions.find(s=>s.id===http.accepted.submissionId).request,http.accepted.request)
const pending=await jsonl('redis-pending.jsonl'),events=await jsonl('redis-outbox.jsonl')
assert.ok(pending.filter(e=>!e.delivered&&e.attempts>=1&&e.errorCode==='REDIS_UNAVAILABLE').length>=2,'No durable failed invalidation during outage')
assert.ok(events.length>=pending.length);assert.ok(events.every(e=>e.delivered));for(const p of pending)assert.ok(events.some(e=>e.id===p.id&&e.oldRevision===p.oldRevision&&e.delivered))
const cache=await json(replay,'redis-cache-facts.json');assert.equal(cache.pendingInvalidations,0);assert.ok(cache.facts.some(f=>f.kind==='public'));assert.ok(cache.facts.some(f=>f.kind==='session'))
for(const f of cache.facts){assert.ok(f.ttlMs>0);assert.ok(f.ttlMs<=(f.kind==='public'?300000:60000))}
const denials=await json(replay,'redis-sql-denials.json');assert.equal(denials.length,4);assert.ok(denials.every(d=>d.denied&&d.exitCode!==0))
const guard=await json(replay,'redis-guard-failure.json');assert.equal(guard.restored,true);assert.ok(guard.positiveIdentityCacheKeys>0)
const queues=(await lines('redis-queues.tsv')).filter(s=>s.startsWith('forgeoj.')||s.startsWith('content.'));assert.equal(queues.length,7);for(const q of queues){const [,ready,unacked]=q.split(/\s+/);assert.equal(Number(ready),0,q);assert.equal(Number(unacked),0,q)}
const workerEvents=(await lines('redis-worker.log')).filter(s=>s.startsWith('{')).map(s=>JSON.parse(s))
const formal=await jsonl('redis-formal-facts.jsonl')
for(const s of submissions){const f=formal.find(f=>f.id===s.id);assert.ok(f);assert.equal(f.taskStatus,'FINISHED');assert.equal(f.leaseCleared,true);assert.equal(f.closedAttempts,1);assert.equal(f.published,1);const index=workerEvents.findIndex(e=>e.event==='attempt.finished'&&e.judgeTaskId===f.taskId);assert.ok(index>=0,'Missing commit');assert.ok(workerEvents.findIndex((e,i)=>i>index&&e.event==='delivery.ack_sent'&&e.judgeTaskId===f.taskId)>index,'Missing ACK after commit')}
const matrix=await json(replay,'matrix.json');assert.equal(matrix.length,8);assert.ok(matrix.every(r=>r.verdict===r.expected&&r.statusVersion===2&&r.closeCode===1000))
const permissions=await json(replay,'permissions.json');for(const [k,v]of Object.entries({anonymous:401,otherOwner:404,missing:404,crossOrigin:403,logoutClose:1008}))assert.equal(permissions[k],v)
const library=await json(replay,'library.json');for(const k of ['anonymous','exactListFields','exactDetailFields'])assert.equal(library[k],true)
const learning=await json(replay,'learning.json');for(const k of ['ownerDenied','publicPersonalFactsAbsent','historyExactFields'])assert.equal(learning[k],true)
assert.equal((await json(replay,'classroom-http.json')).allPassed,true)
assert.equal((await json(replay,'admin-http.json')).allPassed,true)
const assignments=await json(replay,'assignments-http.json'),assignmentFacts=await jsonl('redis-assignment-facts.jsonl'),teacher=await json(replay,'teacher-http.json')
const hard=assignmentFacts.find(f=>f.submissionId===assignments.hard.queued.submissionId);assert.ok(hard);assert.equal(hard.assignmentId,assignments.hard.id);assert.equal(hard.verdict,'AC')
const utc=x=>Date.parse(x.includes('T')?x:x.replace(' ','T')+'Z');assert.ok(utc(hard.acceptedAt)<Date.parse(assignments.hard.deadlineAt));assert.ok(utc(hard.finishedAt)>Date.parse(assignments.hard.deadlineAt));assert.equal(assignmentFacts.filter(f=>f.submissionId===hard.submissionId).length,1)
for(const expected of assignments.formal){const f=assignmentFacts.find(f=>f.submissionId===expected.submissionId);assert.ok(f);assert.equal(f.verdict,expected.verdict)}
assert.ok(assignmentFacts.length>0);for(const f of assignmentFacts){assert.equal(f.status,'FINISHED');assert.equal(f.taskStatus,'FINISHED');assert.equal(f.bindingsMatch,true);assert.equal(f.resourcesMatch,true);assert.equal(f.leaseCleared,true);assert.equal(f.attempts,1)}
assert.ok(teacher.checks.length>0);assert.ok(teacher.sourceSubmissionId)
const browser=await json(replay,'redis-browser.json');for(const k of ['healthyOps','outagePublic','revokedCleared','downgradedReviewer','opsDenied','tabRetained'])assert.equal(browser[k],true,k)
for(const name of browser.snapshots){const dom=await readFile(join(replay,name),'utf8');assert.ok(dom.length>30);assert.ok(!dom.includes('FORGEOJ_ADMIN_ACCESS'))}
const cleanup=await json(replay,'redis-cleanup.json');for(const k of ['ownedContainers','ownedVolumes','ownedNetworks','ownedImages','builders','testcontainers','managedSandboxes'])assert.equal(cleanup[k],0,k)
// Read the actual executable JAR's central directory, including nested library bytes.
function zipEntries(bytes){let end=bytes.length-22;while(end>=0&&bytes.readUInt32LE(end)!==0x06054b50)end--;assert.ok(end>=0);let offset=bytes.readUInt32LE(end+16);const entries=new Map();for(let i=0;i<bytes.readUInt16LE(end+10);i++){assert.equal(bytes.readUInt32LE(offset),0x02014b50);const method=bytes.readUInt16LE(offset+10),size=bytes.readUInt32LE(offset+20),n=bytes.readUInt16LE(offset+28),x=bytes.readUInt16LE(offset+30),c=bytes.readUInt16LE(offset+32),local=bytes.readUInt32LE(offset+42),name=bytes.subarray(offset+46,offset+46+n).toString();const start=local+30+bytes.readUInt16LE(local+26)+bytes.readUInt16LE(local+28),payload=bytes.subarray(start,start+size);entries.set(name,method===0?payload:method===8?inflateRawSync(payload):null);offset+=46+n+x+c}return entries}
const jarEntries=zipEntries(apiJar),catalog=await json(out,'redis-dependencies.json'),dependencies=[]
for(const a of catalog.artifacts){const name='BOOT-INF/lib/'+a.artifact+'-'+a.version+'.jar';if(a.artifact==='spring-boot-starter-data-redis'){assert.ok(!jarEntries.has(name));continue}const bytes=jarEntries.get(name);assert.ok(bytes,'Missing actual dependency '+name);assert.equal(sha(bytes),a.jarSha256,'Dependency bytes differ '+name);dependencies.push({path:name,sha256:a.jarSha256})}assert.equal(dependencies.length,22)
await mkdir(out,{recursive:true})
const safe=['redis-http.json','redis-bootstrap.jsonl','redis-ownership.json','redis-runtime.json','redis-guard-failure.json','redis-pause.json','redis-resume-flush.json','redis-pending.jsonl','redis-outbox.jsonl','redis-submissions.jsonl','redis-cache-facts.json','redis-sql-denials.json','redis-queues.tsv','redis-formal-facts.jsonl','redis-assignment-facts.jsonl','redis-browser.json','redis-cleanup.json','matrix.json','permissions.json','library.json','learning.json','classroom-http.json','admin-http.json','assignments-http.json','teacher-http.json','redis-tool-inputs.sha256',...browser.snapshots,...browser.screenshots]
for(const name of safe){assert.ok(!name.includes('private'));await copyFile(join(replay,name),join(out,name))}
for(const name of ['redis-api.log','redis-api-replica.log','redis-worker.log']){const bytes=await readFile(join(replay,name));assert.ok(!/FORGEOJ_(ADMIN_)?(ACCESS|REFRESH)=|passwordHash|"sourceCode"|"source_code"|"token"\s*:/i.test(bytes.toString()),'Private payload in logs');await writeFile(join(out,name+'.gz'),gzipSync(bytes))}
await writeFile(join(out,'backend.log.gz'),gzipSync(backendLog));await writeFile(join(out,'frontend.log.gz'),gzipSync(frontLog))
await writeFile(join(out,'test-suites.json'),JSON.stringify({api,worker},null,2)+'\n')
await writeFile(join(out,'source-inputs.json'),JSON.stringify({inputs:inputFacts,dependencies},null,2)+'\n')
await writeFile(join(out,'redis-evidence-auditor-at-replay.mjs'),auditorAtReplay)
await writeFile(join(out,'auditor-correction.json'),JSON.stringify({frozenSha256:auditorFrozen,currentSha256:sha(await readFile(join(root,auditorPath))),reason:'Post-replay evidence-only correction: published scalar subquery is numeric 1; direct JSON logical expressions remain boolean true. Runtime helpers and business inputs unchanged.'},null,2)+'\n')
const verification={status:'VERIFIED',scope:'M4 step5 Redis/degradation only',checkedAt:new Date().toISOString(),build:relative(root,build),replay:relative(root,replay),api:{tests:api.tests,suites:api.suites,sha256:apiSha256},worker:{tests:worker.tests,suites:worker.suites,sha256:workerSha256},frontend:{tests:124,suites:24,allChecksPassed:true},httpChecks:http.checks.length,redisRealAc:2,sharedApiInstances:2,pendingInvalidationsReplayed:pending.filter(e=>!e.delivered).length,sqlDenials:4,cleanup,dependenciesCompared:22,fullM4:'IN_PROGRESS'}
await writeFile(join(out,'verification.json'),JSON.stringify(verification,null,2)+'\n');console.log(JSON.stringify(verification))
