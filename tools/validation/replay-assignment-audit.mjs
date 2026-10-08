// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
const read=async name=>(await readFile('/reports/'+name,'utf8')).replace(/^\uFEFF/,'')
const json=async name=>JSON.parse(await read(name)), rows=async name=>(await read(name)).split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line))
const truth=x=>x===true||x===1, digest=x=>createHash('sha256').update(x).digest('hex')
const http=await json('assignments-http.json'),browser=await json('assignment-browser.json'),facts=await rows('assignment-facts.jsonl'),attempts=await rows('assignment-attempt-facts.jsonl'),pre=await rows('assignment-pre-facts.jsonl'),self=await rows('assignment-self-facts.jsonl')
assert.equal(browser.realAc,true);assert.equal(browser.teacherPublished,true);assert.equal(browser.cancelBeforePublish,true);assert.equal(browser.leftReadonly,true);assert.equal(browser.solutionLockedBeforeAc,true)
for(const expected of http.formal){const row=attempts.find(r=>r.submissionId===expected.submissionId);assert.ok(row);assert.equal(row.verdict,expected.verdict)}
assert.ok(attempts.some(r=>r.submissionId===browser.submissionId&&r.assignmentId===http.browser.assignment&&r.verdict==='AC'&&r.userId===2))
const apiText=await read('assignment-api.log'),workerText=await read('assignment-worker.log')
const worker=workerText.split(/\r?\n/).filter(l=>l.startsWith('{')).map(l=>JSON.parse(l))
for(const row of attempts){assert.equal(row.status,'FINISHED');assert.equal(row.taskStatus,'FINISHED');assert.ok(truth(row.bindingsMatch));assert.ok(truth(row.resourcesMatch));assert.ok(truth(row.leaseCleared));assert.equal(row.attemptCount,1);assert.equal(row.attempts.length,1);assert.equal(row.attempts[0].status,'SUCCEEDED');assert.ok(truth(row.attempts[0].finished));assert.equal(row.events.length,1);assert.ok(truth(row.events[0].published));assert.equal(row.events[0].failed,false)
  const logs=worker.filter(l=>l.submissionId===row.submissionId&&l.attemptId===row.attempts[0].id),commit=logs.find(l=>l.event==='attempt.finished'&&l.outcome===row.verdict),ack=logs.find(l=>l.event==='delivery.ack_sent');assert.ok(commit&&ack);assert.ok(commit['@timestamp']<=ack['@timestamp'])
}
const hard=attempts.find(r=>r.submissionId===http.hard.queued.submissionId),utc=text=>Date.parse(text.replace(' ','T')+'Z')
assert.ok(utc(hard.acceptedAt)<Date.parse(http.hard.deadlineAt));assert.ok(utc(hard.finishedAt)>Date.parse(http.hard.deadlineAt));assert.equal(hard.verdict,'AC')
assert.equal(facts.find(f=>f.id===http.assignments.pre).attempts,0);assert.equal(facts.find(f=>f.id===http.assignments.work).participants,3);assert.equal(facts.find(f=>f.id===http.assignments.work).attempts,3)
assert.equal(facts.find(f=>f.id===http.archive.active).status,'ENDED');assert.equal(facts.find(f=>f.id===http.archive.scheduled).status,'STOPPED');assert.equal(facts.find(f=>f.id===http.archive.copy).status,'DRAFT')
assert.ok(pre.length>0);assert.ok(pre.every(p=>truth(p.realOwnerVersionAc)))
assert.equal(self.length,http.selfTests.length+browser.selfTests);for(const s of self){assert.equal(s.status,'FINISHED');assert.equal(s.result,'SUCCESS');assert.ok(truth(s.basisMatches))}
const ownSelf=self.find(s=>s.id===http.selfTests[0]);assert.equal(ownSelf.outputSha256,digest('15\n'))
const queues=(await read('assignment-queues.tsv')).split(/\r?\n/).map(l=>l.trim().split(/\s+/)).filter(p=>p[0].startsWith('forgeoj.')).map(([name,ready,unacked])=>({name,ready:Number(ready),unacked:Number(unacked)}));assert.equal(queues.length,7);assert.ok(queues.every(q=>q.ready===0&&q.unacked===0))
const denials=await json('assignment-denials.json');assert.equal(denials.allDenied,true);assert.equal(denials.denials,17)
for(const text of [apiText,workerText])for(const secret of ['M3_REFERENCE_PRIVATE_SENTINEL','M3_INDEPENDENT_SOLUTION_SENTINEL','M3_ASSIGN_BROWSER_CODE_SENTINEL','forgeoj-dev-only','m1-e2e-','JSESSIONID=','X-CSRF-TOKEN'])assert.ok(!text.includes(secret),'Sensitive fixture leaked into logs')
assert.match(await read('assignment-browser-ac.txt'),/FINISHED AC/);assert.match(await read('assignment-browser-left.txt'),/已离开班级/);assert.doesNotMatch(await read('assignment-browser-left.txt'),/成员题面|Java 21 代码|提交作业|原创班级练习：/)
assert.match(await read('assignment-browser-locked.txt'),/尚未开放题解/);assert.match(await read('assignment-browser-teacher.txt'),/操作已保存|待开始/)
const result={checkedAt:new Date().toISOString(),allPassed:true,httpChecks:http.checks.length,formalAttempts:attempts.length,verdicts:attempts.map(a=>a.verdict),realSelfTests:self.length,precompleted:pre.length,acceptanceBeforeDeadline:true,acAfterDeadline:true,commitBeforeAck:true,logsClean:true,emptyQueues:7,privilegeDenials:17,multiAccountBrowser:true,apiWithoutDocker:true,toolInputs:{}}
for(const name of ['Replay-FixedLinux.ps1','Verify-M3AssignmentReplay.ps1','replay-assignments.mjs','replay-assignment-audit.mjs','replay-private-problems.mjs','compose.replay.yml','replay-vite.mjs'])result.toolInputs['tools/validation/'+name]=digest(await readFile('/source/tools/validation/'+name))
await writeFile('/reports/assignments-audit.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result))
