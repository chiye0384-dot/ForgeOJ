// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {readFile,readdir,mkdir,writeFile,copyFile} from 'node:fs/promises'
import {join,resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
const root=resolve(fileURLToPath(new URL('../..',import.meta.url))),out=join(root,'docs/evidence/m4-monitoring')
const sha=b=>createHash('sha256').update(b).digest('hex')
const read=async p=>readFile(join(root,p))
const specs=[{module:'forgeoj-api',names:{RedisIntegrationTests:11,MonitoringHttpServerTests:3,MonitoringMetricsTests:3,MonitoringRuntimeTests:3,MonitoringSnapshotIntegrationTests:1,MonitoringSnapshotTests:4,RequestCorrelationFilterTests:3,ElasticSearchIntegrationTests:19}},{module:'forgeoj-judge-worker',names:{MonitoringHttpServerTests:3,MonitoringMetricsTests:1,MonitoringRuntimeTests:2,JudgingEventsTests:3,JudgeTaskListenerLoggingTests:6,SandboxRecoveryCleanerTests:2}}]
const tests=[]
for(const spec of specs){const names=await readdir(join(root,spec.module,'target/surefire-reports'));for(const [name,count]of Object.entries(spec.names)){
 const file=names.find(n=>n.startsWith('TEST-')&&n.endsWith('.'+name+'.xml'));assert.ok(file,'Missing suite '+name)
 const path=spec.module+'/target/surefire-reports/'+file,bytes=await read(path),tag=bytes.toString().match(/<testsuite\b[^>]*>/)?.[0];assert.ok(tag)
 const attr=k=>Number(tag.match(new RegExp(`${k}="(\\d+)"`))?.[1]);assert.equal(attr('tests'),count,name)
 for(const key of ['failures','errors','skipped'])assert.equal(attr(key),0,name+' '+key)
 tests.push({module:spec.module,suite:name,tests:count,failures:0,errors:0,skipped:0,report:path,reportSha256:sha(bytes)})
}}
const logs=[]
for(const [path,count]of [['target/monitoring-api-regression.log',45],['target/monitoring-api-format.log',7],['target/monitoring-worker-regression.log',17]]){const bytes=await read(path);assert.match(bytes.toString(),/BUILD SUCCESS/);assert.ok(bytes.toString().includes(`Tests run: ${count}, Failures: 0, Errors: 0, Skipped: 0`));logs.push({path,tests:count,sha256:sha(bytes)})}
const old=new Map((await read('target/monitoring-regression-source.sha256')).toString().trim().split(/\r?\n/).map(line=>[line.slice(66),line.slice(0,64)])),changes=[]
const allowed=['forgeoj-api/src/main/java/com/forgeoj/api/observability/MonitoringMetrics.java','forgeoj-api/src/main/java/com/forgeoj/api/observability/MonitoringSnapshot.java','forgeoj-api/src/test/java/com/forgeoj/api/observability/MonitoringMetricsTests.java','forgeoj-api/src/test/java/com/forgeoj/api/observability/MonitoringSnapshotTests.java']
for(const [path,originalSha256]of old){const currentSha256=sha(await read(path));if(originalSha256!==currentSha256){assert.ok(allowed.includes(path),'Unexplained native regression drift '+path);changes.push({path,originalSha256,currentSha256})}}assert.equal(changes.length,4)
async function walk(path){const result=[];for(const e of await readdir(join(root,path),{withFileTypes:true})){if(['node_modules','dist','target','.git','.idea'].includes(e.name)||e.name.endsWith('.local')||e.name.startsWith('.env'))continue;const p=path+'/'+e.name;if(e.isDirectory())result.push(...await walk(p));else result.push(p)}return result}
const paths=[...new Set(['pom.xml','forgeoj-api/pom.xml','forgeoj-judge-worker/pom.xml',...await walk('forgeoj-api/src'),...await walk('forgeoj-judge-worker/src'),...await walk('frontend')])].sort(),inputs=[]
for(const path of paths)inputs.push({path,sha256:sha(await read(path))})
const finalManifest=new Map((await read('target/monitoring-final-source.sha256')).toString().trim().split(/\r?\n/).map(line=>[line.slice(66),line.slice(0,64)]));assert.equal(finalManifest.size,inputs.length)
for(const input of inputs){assert.equal(input.sha256,finalManifest.get(input.path),'Final tested input drift '+input.path);if(!old.has(input.path))assert.ok(input.path.startsWith('frontend/'),'Unexpected unfrozen native business input '+input.path)}
const prior=JSON.parse((await read('docs/evidence/m4-search/source-inputs.json')).toString()),frontend=inputs.filter(i=>i.path.startsWith('frontend/'))
for(const input of frontend)assert.equal(input.sha256,prior.inputs.find(i=>i.path===input.path)?.sha256,'Frontend drift '+input.path);assert.equal(frontend.length,84)
const protectedFiles=[['docs/M2-NEXT-CHAT-HANDOFF.md','e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054'],['.smtp.qq.local','6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907']]
// These protected paths are hash-only. Do not read their contents here.
const cleanup=JSON.parse((await read('target/monitoring-native-cleanup.json')).toString());assert.equal(cleanup.testcontainers,0);assert.equal(cleanup.managedSandboxes,0);assert.equal(cleanup.protectedFilesUnchanged,true);assert.ok(cleanup.protectedHashCheckedAt)
await mkdir(out,{recursive:true});await copyFile(join(root,'target/monitoring-regression-source.sha256'),join(out,'native-regression-source.sha256'))
await copyFile(join(root,'target/monitoring-final-source.sha256'),join(out,'native-final-source.sha256'))
await writeFile(join(out,'source-inputs.json'),JSON.stringify({inputs,regressionFrozenInputs:old.size,postRegressionFormatChanges:changes,frontendUnchanged:84,protectedHashExpectations:protectedFiles,protectedHashCheckedAt:cleanup.protectedHashCheckedAt},null,2)+'\n')
const evidence={status:'IN_PROGRESS',scope:'M4 step7 native monitoring foundation only',checkedAt:new Date().toISOString(),baselineCommit:'cd2be91c4da523df7456778f87e1f7de3ba0fdbb',nativeChecksPassed:true,platform:'Windows/JDK21; Maven offline',groups:logs,latestSuiteSummaries:tests,uniqueCases:tests.reduce((n,t)=>n+t.tests,0),formatTestsOverlapPriorGroups:true,sourceInputs:inputs.length,frontendUnchanged:84,cleanup,fullLinux:false,sameJarReplay:false,promtool:false,prometheusGrafanaImported:false,browserDashboard:false,threeDependencyFaultReplay:false,grafanaLicense:'AWAITING_USER_CONFIRMATION',committed:false,pushed:false,fullM4:'IN_PROGRESS'}
await writeFile(join(out,'native-checkpoint.json'),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify({status:evidence.status,nativeChecksPassed:true,uniqueCases:evidence.uniqueCases,sourceInputs:evidence.sourceInputs,postRegressionFormatChanges:changes.length,cleanup}))
