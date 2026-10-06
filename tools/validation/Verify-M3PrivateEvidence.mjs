// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Verify actual test reports, frozen executable/test inputs, runtime JARs and redacted replay.
import assert from 'node:assert/strict'
import { readFile, readdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, relative, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('../..',import.meta.url))
const [backendArg,frontendArg,replayArg,outArg] = process.argv.slice(2)
assert.ok(backendArg && frontendArg && replayArg && outArg,'Provide backend/frontend/replay/output directories')
const dirs = [backendArg,frontendArg,replayArg,outArg].map(p => resolve(root,p))
for (const p of dirs) assert.ok(!relative(root,p).startsWith('..'),'Evidence path outside repository')
const [backend,frontend,replay,out] = dirs
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const json = async (dir,name) => JSON.parse((await readFile(join(dir,name),'utf8')).replace(/^\uFEFF/,''))
function manifest(text) {
  return new Map(text.split(/\r?\n/).filter(Boolean).map(line => {
    const match = line.match(/^([0-9a-f]{64})\s+(.+)$/);assert.ok(match,'Invalid manifest line')
    return [match[2].replace(/^\.\//,'').replace(/^\/workspace\/ForgeOJ\//,''),match[1]]
  }))
}
const backendManifest = manifest(await readFile(join(backend,'source-files.sha256'),'utf8'))
const frontendManifest = manifest(await readFile(join(frontend,'source-files.sha256'),'utf8'))
const runtimeManifest = manifest(await readFile(join(replay,'frontend-runtime.sha256'),'utf8'))
async function walk(path) {
  const files = []
  for (const entry of await readdir(join(root,path),{withFileTypes:true})) {
    if (['node_modules','dist','target','.git','.idea'].includes(entry.name)||entry.name.endsWith('.local')||entry.name.startsWith('.env')) continue
    const child = `${path}/${entry.name}`
    if (entry.isDirectory()) files.push(...await walk(child));else files.push(child)
  }
  return files
}
const backendInputs = ['pom.xml','forgeoj-api/pom.xml','forgeoj-judge-worker/pom.xml',...await walk('forgeoj-api/src'),...await walk('forgeoj-judge-worker/src')]
for (const file of backendInputs) assert.equal(hash(await readFile(join(root,file))),backendManifest.get(file),`Untested backend input: ${file}`)
const frontendInputs = await walk('frontend')
for (const file of frontendInputs) {
  assert.equal(hash(await readFile(join(root,file))),frontendManifest.get(file),`Untested frontend input: ${file}`)
  assert.equal(runtimeManifest.get(file),frontendManifest.get(file),`Browser runtime input mismatch: ${file}`)
}
assert.equal(runtimeManifest.size,frontendInputs.length,'Unexpected browser runtime inputs')
async function suites(module) {
  let counts = {suites:0,tests:0,failures:0,errors:0,skipped:0}
  for (const file of await readdir(join(backend,module,'surefire-reports'))) if (file.startsWith('TEST-') && file.endsWith('.xml')) {
    const text = await readFile(join(backend,module,'surefire-reports',file),'utf8'), match = text.match(/<testsuite\s+[^>]*>/)
    assert.ok(match);counts.suites++
    for (const key of ['tests','failures','errors','skipped']) {const value = match[0].match(new RegExp(`\\b${key}="(\\d+)"`));assert.ok(value);counts[key]+=Number(value[1])}
  }
  assert.equal(counts.failures,0);assert.equal(counts.errors,0);assert.equal(counts.skipped,0);return counts
}
const api = await suites('forgeoj-api'), worker = await suites('forgeoj-judge-worker')
assert.equal(api.tests,203);assert.equal(worker.tests,133)
assert.match(await readFile(join(backend,'backend.log'),'utf8'),/BUILD SUCCESS/)
const frontLog = (await readFile(join(frontend,'frontend.log'),'utf8')).replace(/\u001b\[[0-9;]*m/g,'')
assert.match(frontLog,/16 passed \(16\)/);assert.match(frontLog,/82 passed \(82\)/);assert.match(frontLog,/built in/)
const state = await json(replay,'state.json')
const apiSha256 = hash(await readFile(join(backend,'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar')))
const workerSha256 = hash(await readFile(join(backend,'forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar')))
assert.equal(state.ApiHash,apiSha256);assert.equal(state.WorkerHash,workerSha256)
const regression = await json(replay,'audit.json'), privateAudit = await json(replay,'private-problems-audit.json')
assert.equal(regression.submissions,11);assert.equal(regression.finished,10);assert.equal(regression.cancelled,1);assert.equal(regression.emptyQueues,7)
assert.equal(privateAudit.allPassed,true);assert.equal(privateAudit.realPassedValidationJobs,3);assert.equal(privateAudit.realSelfTests,2)
assert.deepEqual([...privateAudit.realFormalVerdicts].sort(),['AC','AC','WA']);assert.equal(privateAudit.explicitPersonalEarlyViews,2);assert.equal(privateAudit.privatePrivilegeDenials,13)
for (const [file,digest] of Object.entries(privateAudit.toolInputs)) assert.equal(hash(await readFile(join(root,file))),digest,`Replay helper changed after execution: ${file}`)
const cleanup = await json(replay,'private-cleanup.json')
for (const key of ['ownedContainers','ownedVolumes','ownedNetworks','ownedImages','builders','testcontainers','managedSandboxes']) assert.equal(cleanup[key],0,`Residue: ${key}`)
const result = {verifiedAt:new Date().toISOString(),allPassed:true,backend:relative(root,backend),frontend:relative(root,frontend),replay:relative(root,replay),api,worker,frontendTests:82,backendInputFiles:backendInputs.length,frontendInputFiles:frontendInputs.length,apiSha256,workerSha256,publicRegression:regression,privateAudit,cleanup}
await writeFile(join(out,'verification.json'),JSON.stringify(result,null,2)+'\n')
console.log(JSON.stringify({allPassed:true,api:api.tests,worker:worker.tests,frontend:82,backendInputs:backendInputs.length,frontendInputs:frontendInputs.length,privateHttpChecks:privateAudit.httpChecks}))
