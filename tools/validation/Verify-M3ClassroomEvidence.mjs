// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// This stage deliberately ties separate final API / unchanged Worker / frontend runs.
import assert from 'node:assert/strict'
import {readFile,readdir,writeFile,mkdir} from 'node:fs/promises'
import {createHash} from 'node:crypto'
import path from 'node:path'
const [apiDir,workerDir,frontendDir,replayDir]=process.argv.slice(2)
assert.ok(apiDir&&workerDir&&frontendDir&&replayDir,'Expected final API, Worker, frontend and replay directories')
const sha=b=>createHash('sha256').update(b).digest('hex')
const json=async p=>JSON.parse(await readFile(p,'utf8'))
const moduleSummary=async (dir,module,expected)=>{
  const reports=path.join(dir,module,'surefire-reports');let tests=0,failures=0,errors=0,skipped=0,suites=0;const cases=[]
  for(const name of await readdir(reports)) if(name.startsWith('TEST-')&&name.endsWith('.xml')) {
    const xml=await readFile(path.join(reports,name),'utf8');const head=xml.match(/<testsuite\b[^>]*>/)?.[0];assert.ok(head)
    const n=k=>Number(head.match(new RegExp(`\\b${k}="(\\d+)"`))?.[1]??0)
    tests+=n('tests');failures+=n('failures');errors+=n('errors');skipped+=n('skipped');suites++
    for(const m of xml.matchAll(/<testcase\b[^>]*\bname="([^"]+)"/g)) cases.push(m[1])
  }
  assert.equal(tests,expected);assert.equal(failures,0);assert.equal(errors,0);assert.equal(skipped,0)
  const log=await readFile(path.join(dir,'backend.log'),'utf8');assert.ok(log.includes('BUILD SUCCESS'))
  return {tests,failures,errors,skipped,suites,cases,jarSha256:sha(await readFile(path.join(dir,module,`${module}-0.0.1-SNAPSHOT.jar`)))}
}
const api=await moduleSummary(apiDir,'forgeoj-api',192),worker=await moduleSummary(workerDir,'forgeoj-judge-worker',133)
for(const c of ['targetAccountForeignKeyLockIsAcquiredBeforeClassroom','transferAcceptWithdrawAndExitRacesKeepSingleOwner','v15PreservesAllExistingTablesAndAddsOnlyEmptyClassroomTables']) assert.ok(api.cases.includes(c),`Missing ${c}`)
// Compare both every frozen input and the current file inventory; no new runtime file can slip in.
const matches=async (dir,predicate)=>{
  const text=await readFile(path.join(dir,'source-files.sha256'),'utf8');let count=0;const frozen=new Set()
  for(const line of text.split(/\r?\n/)) {
    const m=line.match(/^([0-9a-f]{64})\s+\.\/(.+)$/);if(!m||!predicate(m[2])) continue
    assert.equal(sha(await readFile(m[2])),m[1],`Changed frozen input: ${m[2]}`);frozen.add(m[2]);count++
  }
  const walk=async p=>{for(const entry of await readdir(p,{withFileTypes:true})){if(['target','node_modules','dist','.git'].includes(entry.name)) continue;const f=`${p}/${entry.name}`;if(entry.isDirectory()) await walk(f);else if(predicate(f)) assert.ok(frozen.has(f),`New input omitted from frozen manifest: ${f}`)}}
  await walk('forgeoj-api');await walk('forgeoj-judge-worker');await walk('frontend');await walk('contracts')
  assert.ok(count>0);return count
}
const backendInput=p=>p==='pom.xml'||/^(forgeoj-api|forgeoj-judge-worker)\/pom\.xml$/.test(p)||p.startsWith('forgeoj-api/src/')||p.startsWith('contracts/')
const workerInput=p=>p==='pom.xml'||p==='forgeoj-judge-worker/pom.xml'||p.startsWith('forgeoj-judge-worker/src/')||p.startsWith('contracts/')||p.startsWith('forgeoj-api/src/main/resources/db/')
const frontendInput=p=>p.startsWith('frontend/')
const apiInputs=await matches(apiDir,backendInput),workerInputs=await matches(workerDir,workerInput),frontendInputs=await matches(frontendDir,frontendInput)
const runtimeFrontend=(await readFile(path.join(replayDir,'frontend-runtime.sha256'),'utf8')).trim().split(/\r?\n/)
for(const line of runtimeFrontend) {
  const m=line.match(/^([0-9a-f]{64})\s+(frontend\/.+)$/);assert.ok(m)
  assert.equal(sha(await readFile(m[2])),m[1],`Changed actual browser frontend: ${m[2]}`)
}
assert.equal(runtimeFrontend.length,frontendInputs)
const frontendLog=(await readFile(path.join(frontendDir,'frontend.log'),'utf8')).replace(/\x1b\[[0-9;]*m/g,'')
assert.match(frontendLog,/Tests\s+75 passed \(75\)/);assert.ok(frontendLog.includes('built in'))
const state=await json(path.join(replayDir,'state.json'));assert.equal(state.ApiHash,api.jarSha256);assert.equal(state.WorkerHash,worker.jarSha256)
const audit=await json(path.join(replayDir,'audit.json')),classroom=await json(path.join(replayDir,'classroom-audit.json'))
assert.ok(classroom.allPassed);assert.equal(classroom.privilegeDenials,38);assert.equal(audit.emptyQueues,7)
const cleanup=await json('docs/evidence/m3-classroom/cleanup.json');assert.ok(cleanup.daemonReached&&cleanup.allClean)
assert.equal(cleanup.managedSandboxes,0);assert.equal(cleanup.testcontainers,0);assert.equal(cleanup.builders,0)
for(const project of Object.values(cleanup.projects)) for(const count of Object.values(project)) assert.equal(count,0)
const report={checkedAt:new Date().toISOString(),apiBuild:apiDir,workerBuild:workerDir,frontendBuild:frontendDir,replay:replayDir,api:{...api,cases:undefined},worker:{...worker,cases:undefined},frontend:{tests:75},sourceInputs:{api:apiInputs,worker:workerInputs,frontend:frontendInputs,browserFrontend:runtimeFrontend.length,allMatch:true},runtimeBundle:state.BuildDirectory,separateBuilds:true,workerDriverPassed:false,workerDriverNote:'Maven completed all Worker tests and BUILD SUCCESS; shell wrapper subsequently failed because a mounted script changed while being streamed. Final complete Worker business/test/migration inputs and JAR are unchanged and hash checked. Final API and frontend use separate successful drivers.',classroom,judge:{submissions:audit.submissions,finished:audit.finished,cancelled:audit.cancelled,emptyQueues:audit.emptyQueues},cleanup:{allClean:true,checkedAt:cleanup.checkedAt},allPassed:true}
await mkdir('docs/evidence/m3-classroom',{recursive:true});await writeFile('docs/evidence/m3-classroom/verification.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2))
