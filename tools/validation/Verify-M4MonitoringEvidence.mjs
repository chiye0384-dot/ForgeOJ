// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Final reader of actual, completed runs. No live data writes or invented results.
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {readFile,readdir,writeFile,mkdir,copyFile} from 'node:fs/promises'
import {join,resolve,relative} from 'node:path'
const root=process.cwd(),[buildArg,replayArg,mode]=process.argv.slice(2),preflight=mode==='--preflight'
assert.ok(mode===undefined||preflight,'Only --preflight is supported')
assert.ok(buildArg&&replayArg,'Provide exact accepted build and replay')
const build=resolve(root,buildArg),run=resolve(root,replayArg),out=join(root,'docs/evidence/m4-monitoring')
for(const p of [build,run])assert.ok(relative(join(root,'target'),p)&&!relative(join(root,'target'),p).startsWith('..'))
const sha=b=>createHash('sha256').update(b).digest('hex')
const read=async(d,p)=>readFile(join(d,p)),json=async(d,p)=>JSON.parse((await read(d,p)).toString().replace(/^\uFEFF/,''))
const manifest=b=>new Map(b.toString().trim().split(/\r?\n/).map(l=>[l.slice(66).replace(/^\.\//,''),l.slice(0,64)]))
const bm=manifest(await read(build,'source-files.sha256')),fm=manifest(await read(build,'source-files.frontend.sha256'))
async function walk(p){const list=[];for(const e of await readdir(join(root,p),{withFileTypes:true})){if(['target','node_modules','dist','.git','.idea'].includes(e.name)||e.name.endsWith('.local')||e.name.startsWith('.env'))continue;const n=p+'/'+e.name;if(e.isDirectory())list.push(...await walk(n));else list.push(n)}return list}
const inputs=[]
for(const p of ['pom.xml','forgeoj-api/pom.xml','forgeoj-judge-worker/pom.xml',...await walk('forgeoj-api/src'),...await walk('forgeoj-judge-worker/src'),...await walk('frontend')]){const h=sha(await read(root,p));assert.equal(h,bm.get(p),'Accepted build input drift '+p);if(p.startsWith('frontend/'))assert.equal(h,fm.get(p),'Frontend gate drift '+p);inputs.push({path:p,sha256:h})}
async function suites(module){const result=[];for(const n of await readdir(join(build,module,'surefire-reports'))){if(!n.startsWith('TEST-')||!n.endsWith('.xml'))continue;const b=await read(build,module+'/surefire-reports/'+n),tag=b.toString().match(/<testsuite\b[^>]*>/)?.[0];assert.ok(tag);const attr=k=>Number(tag.match(new RegExp(`${k}="(\\d+)"`))?.[1]);const t={suite:n,tests:attr('tests'),failures:attr('failures'),errors:attr('errors'),skipped:attr('skipped'),sha256:sha(b)};for(const k of ['failures','errors','skipped'])assert.equal(t[k],0,n+' '+k);result.push(t)}return {tests:result.reduce((n,s)=>n+s.tests,0),suites:result.length,details:result}}
const api=await suites('forgeoj-api'),worker=await suites('forgeoj-judge-worker');assert.ok(api.tests>=335);assert.ok(worker.tests>=139)
const backend=(await read(build,'backend.log')).toString();assert.match(backend,/BUILD SUCCESS/)
const frontend=(await read(build,'frontend.log')).toString();assert.match(frontend,/129 passed/);assert.match(frontend,/25 passed/)
const state=await json(run,'state.json'),apiSha=sha(await read(build,'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar')),workerSha=sha(await read(build,'forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar'))
assert.equal(state.ApiHash,apiSha);assert.equal(state.WorkerHash,workerSha);assert.ok(state.MonitoringEnabled)
const frontendInputs=inputs.filter(i=>i.path.startsWith('frontend/'))
assert.equal(frontendInputs.length,84)
for(const file of ['frontend-runtime.sha256','frontend-served-runtime.sha256']){const m=manifest(await read(run,file));assert.equal(m.size,84);for(const i of frontendInputs)assert.equal(m.get(i.path),i.sha256,file+' '+i.path)}
assert.equal((await json(run,'teacher-runtime.json')).readOnlySourceMount,true)
// Runtime helpers can change after a failed attempt. Preserve each exact generation,
// require current helpers/config to match the final captured generation, and do not
// describe those changes as part of an earlier business-source build snapshot.
const helperInputs=manifest(await read(run,'monitoring-tool-inputs.sha256'))
for(const [p,h] of helperInputs){assert.match(p,/^(tools\/validation|deploy\/monitoring)\/[A-Za-z0-9.-]+$/);assert.equal(sha(await read(root,p)),h,'Final runtime helper/config drift '+p)}
const buildHelperDifferences=[]
for(const [p,h] of bm){if(!p.startsWith('tools/validation/')&&!p.startsWith('contracts/'))continue;const current=sha(await read(root,p));if(current!==h){assert.ok(['tools/validation/Verify-M4MonitoringEvidence.mjs','tools/validation/Verify-M4MonitoringReplay.ps1','tools/validation/replay-monitoring-rabbit.mjs','tools/validation/replay-redis.mjs','tools/validation/Verify-M4RedisReplay.ps1'].includes(p),'Unexplained build helper drift '+p);buildHelperDifferences.push({path:p,buildSha256:h,currentSha256:current,reason:p.endsWith('Evidence.mjs')?'Final read-only evidence correlation':p.endsWith('MonitoringReplay.ps1')?'Bounded diagnostic message capture with requeue and exact before/after retention':p.includes('Redis')||p.includes('redis')?'Normal HTTP fixture reauthentication and a new measured rate window after expired credentials; no business-source change':'Bounded runtime exception polling preserves the existing operations rate limit'})}}
const helperHistory=[]
for(const name of (await readdir(join(run,'monitoring-tool-history'))).sort()){assert.match(name,/^[A-Za-z0-9.-]+\.sha256$/);const bytes=await read(run,'monitoring-tool-history/'+name);helperHistory.push({file:name,sha256:sha(bytes),inputs:[...manifest(bytes)].map(([path,sha256])=>({path,sha256}))})}
assert.ok(helperHistory.length>=10)
const http=await json(run,'monitoring-http.json');for(const p of ['healthy','redis-outage','redis-recovered','es-outage','es-recovered','rabbit-outage','rabbit-recovered'])assert.equal(http.phases[p]?.allPassed,true,p)
const jwt=await json(run,'monitoring-jwt-http.json');assert.equal(jwt.phases['jwt-denied'].allPassed,true);assert.equal(jwt.checks.length,6);for(const c of jwt.checks)assert.equal(c.status,401)
assert.equal(http.grafana.panels,14);for(const k of ['viewer','anonymousDenied','viewerWriteDenied'])assert.equal(http.grafana[k],true)
assert.equal((await json(run,'monitoring-promtool.json')).allPassed,true)
const runtime=await json(run,'monitoring-runtime.json');for(const c of runtime.containers){if(['api','api-replica','worker'].includes(c.service))assert.equal(c.jarSha256,c.service==='worker'?workerSha:apiSha);if(c.service!=='worker')assert.equal(c.dockerSocket,false);assert.equal(c.publishedPortCount,c.service==='grafana'?1:0)}
assert.equal(runtime.containers.find(c=>c.service==='prometheus').configuredImage,'prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e')
assert.equal(runtime.containers.find(c=>c.service==='grafana').configuredImage,'grafana/grafana:13.2.3@sha256:b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572')
const rabbit=await json(run,'monitoring-rabbit-http.json');for(const k of ['acceptedWhileRabbitUnavailable','duplicateSameId','noExecutionAttemptDuringOutage','sameEventPublished','rearmedBeforeWorkerResumed','realWorkerAc','allPassed'])assert.equal(rabbit[k],true,k)
assert.ok(rabbit.manualDeliveryRecovery);assert.equal(rabbit.manualDeliveryRecovery.originalPublishAttempts,5)
const wp=await json(run,'monitoring-worker-pause.json'),wr=await json(run,'monitoring-worker-resume.json');assert.equal(wp.id,wr.id);assert.equal(wp.project,state.Project);assert.equal(wr.project,state.Project)
const pending=await json(run,'monitoring-rabbit-pending-facts.json'),finished=await json(run,'monitoring-rabbit-final-facts.json');assert.equal(pending.attempts,0);assert.equal(pending.quotaSlots,1);assert.equal(finished.verdict,'AC');assert.equal(finished.attempts,1);assert.equal(finished.quotaSlots,0);assert.equal(pending.sourceSha256,finished.sourceSha256);assert.equal(pending.events[0].id,finished.events[0].id)
const logs=(await read(run,'monitoring-worker.log')).toString().split(/\r?\n/).filter(l=>l.startsWith('{')).map(l=>JSON.parse(l)),commit=logs.findIndex(l=>l.event==='attempt.finished'&&l.judgeTaskId===rabbit.taskId);assert.ok(commit>=0);assert.ok(logs.findIndex((l,i)=>i>commit&&l.event==='delivery.ack_sent'&&l.judgeTaskId===rabbit.taskId)>commit)
const operationEvents=(await read(run,'operations-outbox-facts.jsonl')).toString().split(/\r?\n/).filter(Boolean).map(l=>JSON.parse(l))
const deadQueues={'forgeoj.judge.submission.dead.v1':'JUDGE_TASK_DEAD_LETTERED','forgeoj.content.validation.dead.v1':'CONTENT_VALIDATION_DEAD_LETTERED','forgeoj.judge.self-test.dead.v1':'SELF_TEST_DEAD_LETTERED'}
const deadEvents=(await read(run,'monitoring-dead-outbox.jsonl')).toString().split(/\r?\n/).filter(Boolean).map(l=>JSON.parse(l))
assert.equal(new Set(deadEvents.map(e=>e.id)).size,6);assert.equal(deadEvents.length,6)
const copies=await json(run,'monitoring-dead-letter-copies.json');assert.equal(copies.project,state.Project);assert.equal(copies.ackmode,'ack_requeue_true');assert.equal(copies.allPassed,true);assert.equal(copies.uniqueEvents,6);assert.equal(copies.queues.length,3)
const beforeQueues=await json(run,'monitoring-queues-before-dlq.json')
const queues=await json(run,'monitoring-queues.json');assert.equal(queues.length,9);let retainedDeadLetters=0,retainedDeadLetterCopies=0
for(const q of queues){assert.equal(q.messages_unacknowledged,0);assert.deepEqual(q,beforeQueues.find(b=>b.name===q.name),'Diagnostic must preserve every ready/unacked count');const type=deadQueues[q.name];if(type){
 const expected=operationEvents.filter(e=>e.type===type&&e.published),snapshot=deadEvents.filter(e=>e.type===type),observed=copies.queues.find(c=>c.name===q.name)
 assert.deepEqual(snapshot.map(e=>e.id).sort(),expected.map(e=>e.id).sort());assert.ok(observed);assert.equal(observed.observedReady,q.messages_ready);assert.equal(observed.observedUnacked,0);assert.equal(observed.messages.length,q.messages_ready)
 assert.deepEqual(observed.uniqueEventIds.slice().sort(),expected.map(e=>e.id).sort());assert.deepEqual([...new Set(observed.messages.map(m=>m.id))].sort(),expected.map(e=>e.id).sort(),'Every original event retained and no foreign message')
 for(const m of observed.messages){const e=snapshot.find(e=>e.id===m.id);assert.ok(e);assert.equal(e.published,true);assert.equal(e.failed,false);assert.equal(e.payload.taskId,e.taskId);assert.equal(e.payload.contractVersion,1);assert.equal(Object.keys(e.payload).length,4);assert.deepEqual(m.payload,e.payload);assert.deepEqual(JSON.parse(m.payloadJson),e.payload);assert.equal(sha(Buffer.from(m.payloadJson)),m.payloadSha256);assert.equal(Buffer.byteLength(m.payloadJson),m.payloadBytes);assert.equal(m.deliveryMode,2);assert.equal(m.exchange,'forgeoj.judge');assert.equal(m.routingKey,q.name.replace(/^forgeoj\./,''))}
 retainedDeadLetters+=expected.length;retainedDeadLetterCopies+=observed.messages.length
 }else assert.equal(q.messages_ready,0)}
assert.equal(retainedDeadLetters,6)
assert.equal(copies.messageCopies,retainedDeadLetterCopies)
const admin=await json(run,'admin-http.json');for(const k of ['allPassed','firstPasswordGate','ordinaryDenied','limitedRolesDenied','refreshReuseRevoked','cookieContract','sameIdDomainsIndependent'])assert.equal(admin[k],true);const search=await json(run,'search-http.json');for(const p of ['healthy','outage','recovered'])assert.ok(search.phases[p]);const redis=await json(run,'redis-http.json');for(const p of ['healthy','guard-failure','outage','recovered'])assert.ok(redis.phaseFacts[p]);assert.ok(redis.phaseFacts.outage.realAcDuringOutage)
const matrix=await json(run,'matrix.json');assert.deepEqual(matrix.map(r=>r.expected).sort(),['AC','WA','CE','RE','TLE','OLE','MLE','SECURITY_VIOLATION'].sort());for(const r of matrix){assert.equal(r.verdict,r.expected);assert.equal(r.statusVersion,2);assert.equal(r.closeCode,1000);assert.ok(r.notices.some(n=>n.processingStatus==='FINISHED'))}
const permission=await json(run,'permissions.json');assert.equal(permission.anonymous,401);assert.equal(permission.otherOwner,404);assert.equal(permission.crossOrigin,403);assert.equal(permission.logoutClose,1008);assert.equal(permission.cancelled.processingStatus,'CANCELLED')
const classroom=await json(run,'classroom-http.json');assert.equal(classroom.allPassed,true)
const privateProblems=await json(run,'private-problems-http.json');assert.equal(privateProblems.realWorkerValidation,true);assert.equal(privateProblems.independentPrograms,true)
const review=await json(run,'public-review-http.json');for(const k of ['allPassed','copyDoesNotInheritPassed','textJudgePreserved','oldAcIsolated','invalidAssignmentPreservesAttempts'])assert.equal(review[k],true)
const operations=await json(run,'operations-http.json');assert.equal(operations.executionRecoveryPassed,true);const recovered=operations.tasks.filter(t=>!t.browser);assert.equal(recovered.length,5);for(const t of recovered){assert.equal(t.resultVerified,true);assert.equal(t.before.attemptCount,3);assert.equal(t.after.attemptCount,4);assert.equal(t.after.executionRecoveryUsed,true)}
const frozen=async file=>(await read(run,file)).toString().split(/\r?\n/).filter(Boolean).map(l=>JSON.parse(l))
const oldFrozen=await frozen('operations-frozen-before.jsonl'),newFrozen=await frozen('operations-frozen-after.jsonl');for(const old of oldFrozen)assert.deepEqual(newFrozen.find(f=>f.taskId===old.taskId&&f.kind===old.kind),old,'Frozen business basis changed')
// The historical browser fixture is intentionally excluded here; no new browser
// retry or revocation is claimed for it. This run covers five real HTTP recoveries.
const audit=(await read(run,'monitoring-admin-audit.jsonl')).toString().split(/\r?\n/).filter(Boolean).map(l=>JSON.parse(l));assert.ok(audit.some(a=>a.action==='SEARCH_REBUILD_SUCCEEDED'&&a.actorAdminId&&a.targetId&&a.reason&&a.occurredAt));if(rabbit.manualDeliveryRecovery)assert.ok(audit.some(a=>a.action==='OPS_DELIVERY_RECOVERY'&&a.actorAdminId&&a.targetId===rabbit.taskId&&a.reason&&a.occurredAt))
const deletion=await json(run,'search-index-deletion-facts.json');assert.ok(deletion.deleteAcknowledged);assert.equal(deletion.deletedIndexStatus,'404');assert.notEqual(deletion.before.indexName,deletion.after.indexName);assert.equal(deletion.before.publicRows,deletion.after.publicRows);assert.equal(deletion.after.queuedAudit,1);assert.equal(deletion.after.terminalAudit,1)
const browser=await json(run,'monitoring-browser.json');for(const k of ['anonymousLoginRequired','viewerDashboard','viewerCannotEdit','logoutCleared','tabRetained'])assert.equal(browser[k],true)
for(const p of browser.screenshots){const b=await read(run,p);assert.ok(b.length>1000);assert.equal(b.subarray(0,3).toString('hex'),'ffd8ff');assert.equal(b.subarray(-2).toString('hex'),'ffd9')}
for(const p of browser.snapshots)assert.ok((await read(run,p)).length>30)
const cleanup=preflight?null:await json(run,'monitoring-cleanup.json');if(!preflight)for(const k of ['ownedContainers','ownedVolumes','ownedNetworks','ownedImages','builders','testcontainers','managedSandboxes'])assert.equal(cleanup[k],0)
const license=await json(out,'upstream-images.json');for(const n of license.notices)assert.equal(sha(await read(root,n.path)),n.sha256)
if(preflight){console.log(JSON.stringify({status:'PREFLIGHT_PASSED',uniqueDeadEvents:retainedDeadLetters,retainedDeadLetterCopies,cleanupPending:true}));process.exit(0)}
await mkdir(out,{recursive:true})
const safeFiles=['monitoring-http.json','monitoring-jwt-http.json','monitoring-promtool.json','monitoring-runtime.json','monitoring-rabbit-http.json','monitoring-rabbit-pending-facts.json','monitoring-rabbit-final-facts.json','monitoring-admin-audit.jsonl','monitoring-queues.json','monitoring-browser.json','monitoring-cleanup.json','search-index-deletion-facts.json','search-index-deletion-http.json','admin-http.json','redis-http.json','search-http.json',...browser.screenshots,...browser.snapshots]
safeFiles.push('frontend-runtime.sha256','frontend-served-runtime.sha256','teacher-runtime.json','monitoring-rabbit-pause.json','monitoring-rabbit-resume.json','monitoring-worker-pause.json','monitoring-worker-resume.json','monitoring-tool-inputs.sha256')
safeFiles.push('matrix.json','permissions.json','classroom-http.json','private-problems-http.json','public-review-http.json','operations-http.json')
safeFiles.push('operations-outbox-facts.jsonl','operations-frozen-before.jsonl','operations-frozen-after.jsonl','operations-denials.json')
safeFiles.push('monitoring-dead-outbox.jsonl','monitoring-dead-letter-copies.json','monitoring-queues-before-dlq.json')
for(const p of safeFiles)await copyFile(join(run,p),join(out,p))
try{await copyFile(join(run,'monitoring-fixture-failures.json'),join(out,'monitoring-fixture-failures.json'))}catch(e){if(e.code!=='ENOENT')throw e;await writeFile(join(out,'monitoring-fixture-failures.json'),'[]\n')}
await writeFile(join(out,'linux-source-inputs.json'),JSON.stringify({inputs,api,worker,apiSha256:apiSha,workerSha256:workerSha},null,2)+'\n')
await writeFile(join(out,'runtime-helper-history.json'),JSON.stringify({currentInputs:[...helperInputs].map(([path,sha256])=>({path,sha256})),buildHelperDifferences,runtimeOnlyHelpers:[...helperInputs].filter(([p])=>!bm.has(p)).map(([path,sha256])=>({path,sha256})),history:helperHistory},null,2)+'\n')
const proof={status:'VERIFIED',scope:'M4 step7 minimal monitoring and complete M4 gates',checkedAt:new Date().toISOString(),build:relative(root,build).replaceAll('\\','/'),replay:state.Project,apiSha256:apiSha,workerSha256:workerSha,apiTests:api.tests,workerTests:worker.tests,frontendTests:129,sourceInputs:inputs.length,actualServedFrontendInputs:84,panels:14,httpChecks:http.checks.length+jwt.checks.length,retainedOriginalDeadLetters:retainedDeadLetters,retainedDeadLetterCopies,freshHttpExecutionRecoveries:recovered.length,rabbit,cleanup,gates:{ordinaryAdmin403:true,adminRoleDenials:true,threeDependencyFaults:true,wholeIndexDeletionRebuild:true,adminObjectTimeReasonTrace:true},fullM4:'VERIFIED',m5:'PLANNED',release:false,resumeReady:false}
await writeFile(join(out,'verification.json'),JSON.stringify(proof,null,2)+'\n');console.log(JSON.stringify({status:proof.status,apiTests:api.tests,workerTests:worker.tests,sourceInputs:inputs.length,panels:14}))
