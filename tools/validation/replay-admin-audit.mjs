// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Read-only, current replay metadata. Does not claim ordinary browser WS/fallback verification.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
const read=async n=>(await readFile('/reports/'+n,'utf8')).replace(/^\uFEFF/,''),json=async n=>JSON.parse(await read(n)),lines=async n=>(await read(n)).split(/\r?\n/).filter(s=>s.startsWith('{')).map(s=>JSON.parse(s))
const facts=await lines('admin-formal-facts.jsonl'),http=await json('admin-http.json'),matrix=await json('matrix.json'),permission=await json('permissions.json'),classroom=await json('classroom-http.json'),api=await lines('admin-api.log'),worker=await lines('admin-worker.log')
assert.equal(classroom.allPassed,true);assert.equal(classroom.checks.length,62);assert.equal(classroom.formalBefore,classroom.formalAfter);assert.equal(facts.length,10)
assert.deepEqual(matrix.map(r=>r.expected).sort(),['AC','CE','MLE','OLE','RE','SECURITY_VIOLATION','TLE','WA'])
for(const row of matrix)assert.ok(facts.some(f=>f.id===row.submissionId&&f.verdict===row.expected&&f.status==='FINISHED'))
assert.ok(facts.some(f=>f.id===http.ordinaryAc&&f.verdict==='AC'));let finished=0,cancelled=0
for(const f of facts){assert.equal(f.taskStatus,f.status);assert.equal(f.taskVersion,f.statusVersion);assert.equal(f.leaseCleared,true);assert.equal(f.published,1);const created=api.filter(e=>e.event==='submission.created'&&e.submissionId===f.id);assert.equal(created.length,1);assert.equal(created[0].judgeTaskId,f.taskId);assert.equal(created[0].outboxEventId,f.outboxId);assert.ok(api.some(e=>e.event==='outbox.published'&&e.outboxEventId===f.outboxId));assert.ok(worker.some(e=>e.event==='delivery.ack_sent'&&e.judgeTaskId===f.taskId))
  if(f.status==='FINISHED'){finished++;assert.equal(f.attemptCount,1);assert.equal(f.closedAttempts,1);assert.equal(f.statusVersion,2);assert.ok(worker.some(e=>e.event==='attempt.finished'&&e.judgeTaskId===f.taskId&&e.outcome===f.verdict))}else{cancelled++;assert.equal(f.id,permission.submissionId);assert.equal(f.status,'CANCELLED');assert.equal(f.attemptCount,0);assert.equal(f.closedAttempts,0);assert.equal(f.statusVersion,1)}
}
assert.equal(finished,9);assert.equal(cancelled,1);const queues=(await read('admin-queues.tsv')).split(/\r?\n/).filter(line=>line.startsWith('forgeoj.')).map(line=>line.split('\t'));assert.equal(queues.length,7);for(const q of queues)assert.deepEqual(q.slice(1),['0','0'])
for(const n of ['admin-api.log','admin-worker.log']){const text=await read(n);for(const sentinel of ['forgeoj-dev-only','m1-e2e-','m4-public-initial-admin-password','m4-public-super-changed-password','E2E_SOURCE_SENTINEL'])assert.ok(!text.includes(sentinel),'Leaked fixture credential/source')}
const result={checkedAt:new Date().toISOString(),allPassed:true,submissions:facts.length,finished,cancelled,emptyQueues:queues.length,classroomChecks:classroom.checks.length,ordinaryAc:http.ordinaryAc,scope:'actual HTTP/WS protocol verdict matrix and classroom regression; no new ordinary browser fallback claim'}
await writeFile('/reports/admin-regression.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result))
