// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
const read = name => readFile(`/reports/${name}`,'utf8')
const json = async name => JSON.parse((await read(name)).replace(/^\uFEFF/,''))
const lines = async name => (await read(name)).split(/\r?\n/).filter(s => s.startsWith('{')).map(s => JSON.parse(s))
const truth = value => value === true || value === 1
const http = await json('private-problems-http.json'), browser = await json('private-problems-browser.json')
const problems = await lines('private-problem-facts.jsonl'), validations = await lines('private-validation-facts.jsonl')
const submissions = await lines('private-submission-facts.jsonl'), views = await lines('private-early-views.jsonl'), selfTests = await lines('private-self-test-facts.jsonl')
const apiText = await read('private-api.log'), workerText = await read('private-worker.log')
const api = await lines('private-api.log'), worker = await lines('private-worker.log')
assert.equal(problems.length,3)
const cancelled = await json('private-browser-cancel-sql.json'), revokedFact = await json('private-browser-revocation.json')
assert.equal(cancelled.total,1);assert.equal(cancelled.browser,0)
assert.equal(revokedFact.classroomId,http.classroomId);assert.equal(revokedFact.memberUserId,2);assert.equal(revokedFact.status,'REMOVED');assert.equal(revokedFact.actualOwnerHttp,true)
assert.ok(http.realWorkerValidation && http.independentPrograms)
assert.ok(browser.published && browser.cancelDidNotWrite && browser.earlyViewConfirmed && browser.acUnlocked && browser.memberMaintenanceAbsent && browser.selfTestSucceeded && browser.membershipRevoked)
assert.ok(http.checks.length >= 70)
const published = problems.find(p => p.slug === http.publishedSlug), assistant = problems.find(p => p.slug === http.assistantSlug), ui = problems.find(p => p.slug === browser.slug)
assert.ok(published && assistant && ui);assert.equal(published.createdBy,1);assert.equal(assistant.createdBy,2)
assert.equal(assistant.status,'ARCHIVED');assert.equal(assistant.version,2)
assert.equal(ui.classroomId,http.classroomId);assert.equal(ui.jobId,http.browserJobId);assert.equal(ui.draftId,http.browserDraftId)
for (const p of problems) {
  assert.equal(p.scope,'CLASSROOM');assert.equal(p.judgeVersion,1);assert.equal(p.datasetSha256,p.snapshotDatasetSha256)
  assert.ok(truth(p.resourcesMatch) && truth(p.copiedTestsMatch));assert.equal(p.tests.length,2)
  const manifest = p.tests.sort((a,b) => a.ordinal-b.ordinal).map(t => `${t.ordinal}:${t.inputSha256}:${t.outputSha256}\n`).join('')
  assert.equal(createHash('sha256').update(manifest).digest('hex'),p.datasetSha256)
}
for (const id of http.validationJobs) {
  const v = validations.find(v => v.jobId === id);assert.ok(v)
  assert.equal(v.kind,'VALIDATE');assert.equal(v.status,'FINISHED');assert.equal(v.validationStatus,'PASSED');assert.equal(v.reference,'ACCEPTED');assert.equal(v.solution,'ACCEPTED')
  assert.ok(truth(v.sourceDigestsMatch) && truth(v.independentDigests) && truth(v.leaseCleared));assert.equal(v.attemptCount,1)
  assert.equal(v.attempts.length,1);assert.equal(v.attempts[0].status,'SUCCEEDED');assert.ok(truth(v.attempts[0].finished))
  assert.equal(v.events.length,1);assert.ok(truth(v.events[0].published));assert.equal(v.events[0].failed,false)
  const logs = worker.filter(l => l.contentJobId === id && l.contentAttemptId === v.attempts[0].id)
  const claim = logs.find(l => l.event === 'content.attempt_claimed'), commit = logs.find(l => l.event === 'content.attempt_committed'), ack = logs.find(l => l.event === 'content.ack_sent')
  assert.ok(claim && commit && ack);assert.ok(claim['@timestamp'] <= commit['@timestamp'] && commit['@timestamp'] <= ack['@timestamp'])
}
assert.equal(submissions.length,http.formalSubmissions.length + 1)
for (const observed of [...http.formalSubmissions,{id:browser.submissionId,verdict:'AC'}]) {
  const s = submissions.find(s => s.id === observed.id);assert.ok(s);assert.equal(s.verdict,observed.verdict)
  assert.equal(s.status,'FINISHED');assert.equal(s.taskStatus,'FINISHED');assert.equal(s.version,2);assert.equal(s.taskVersion,2);assert.equal(s.attemptCount,1)
  assert.ok(truth(s.snapshotMatches) && truth(s.leaseCleared));assert.equal(s.attempts.length,1);assert.equal(s.attempts[0].status,'SUCCEEDED');assert.ok(truth(s.attempts[0].finished))
  assert.equal(s.events.length,1);assert.ok(truth(s.events[0].published));assert.equal(s.events[0].failed,false)
  const created = api.filter(l => l.event === 'submission.created' && l.submissionId === s.id);assert.equal(created.length,1)
  assert.equal(created[0].judgeTaskId,s.taskId);assert.equal(created[0].outboxEventId,s.events[0].id)
  assert.ok(api.some(l => l.event === 'request.completed' && l.requestId === created[0].requestId && l.httpStatus === 202))
  const logs = worker.filter(l => l.submissionId === s.id && l.attemptId === s.attempts[0].id)
  const claim = logs.find(l => l.event === 'attempt.claimed'), commit = logs.find(l => l.event === 'attempt.finished' && l.outcome === s.verdict), ack = logs.find(l => l.event === 'delivery.ack_sent')
  assert.ok(claim && commit && ack);assert.ok(claim['@timestamp'] <= commit['@timestamp'] && commit['@timestamp'] <= ack['@timestamp'])
}
assert.ok(views.some(v => v.userId === 2 && v.slug === http.publishedSlug))
assert.ok(views.some(v => v.userId === 2 && v.slug === browser.slug))
assert.ok(!views.some(v => v.userId === 1));assert.equal(views.length,2)
assert.equal(selfTests.length,2)
for (const [id,output] of [[http.selfTestId,'15\n'],[browser.selfTestId,'11\n']]) {
  const s = selfTests.find(s => s.id === id);assert.ok(s);assert.equal(s.status,'FINISHED');assert.equal(s.result,'SUCCESS')
  assert.equal(s.outputSha256,createHash('sha256').update(output).digest('hex'));assert.ok(truth(s.leaseCleared))
  assert.equal(s.attempts.length,1);assert.equal(s.attempts[0].status,'SUCCEEDED');assert.ok(truth(s.attempts[0].finished))
  assert.equal(s.events.length,1);assert.ok(truth(s.events[0].published));assert.equal(s.events[0].failed,false)
  const logs = worker.filter(l => l.selfTestRunId === id && l.selfTestAttemptId === s.attempts[0].id)
  const commit = logs.find(l => l.event === 'selftest.attempt_committed'), ack = logs.find(l => l.event === 'selftest.ack_sent')
  assert.ok(commit && ack);assert.ok(commit['@timestamp'] <= ack['@timestamp'])
}
for (const text of [apiText,workerText]) for (const value of ['M3_REFERENCE_PRIVATE_SENTINEL','M3_INDEPENDENT_SOLUTION_SENTINEL','M3_BROWSER_PRIVATE_SOURCE_SENTINEL','forgeoj-dev-only','m1-e2e-','JSESSIONID=','X-CSRF-TOKEN']) assert.ok(!text.includes(value),`Sensitive fixture logged: ${value}`)
const queues = (await read('private-queues.tsv')).split(/\r?\n/).map(s => s.trim().split(/\s+/)).filter(p => p[0].startsWith('forgeoj.')).map(([name,ready,unacked]) => ({name,ready:Number(ready),unacked:Number(unacked)}))
assert.equal(queues.length,7);assert.ok(queues.every(q => q.ready === 0 && q.unacked === 0))
const privileges = await json('private-privilege-denials.json');assert.ok(privileges.allDenied);assert.equal(privileges.denials,13)
const before = await read('private-browser-before-confirm.txt'), early = await read('private-browser-early-view.txt'), ac = await read('private-browser-ac.txt'), revoked = await read('private-browser-revoked.txt')
assert.doesNotMatch(await read('private-browser-member.txt'),/查看教学维护数据|私有参考程序|从自己的已验证草稿发布/)
assert.match(await read('private-browser-published.txt'),/班级私有题已发布/)
assert.match(await read('private-browser-self-success.txt'),/FINISHED SUCCESS/)
assert.match(before,/再次确认/);assert.match(early,/EARLY_VIEW/);assert.match(ac,/FINISHED.*AC|AC/);assert.match(revoked,/不可访问/);assert.doesNotMatch(revoked,/读入两个整数/)
const result = {checkedAt:new Date().toISOString(),allPassed:true,privateProblems:problems.length,realPassedValidationJobs:http.validationJobs.length,httpChecks:http.checks.length,realFormalVerdicts:submissions.map(s => s.verdict),realSelfTests:selfTests.length,explicitPersonalEarlyViews:views.length,privatePrivilegeDenials:13,emptyQueues:7,snapshotCopiesMatch:true,commitBeforeAck:true,logsClean:true,multiAccountBrowser:true,membershipRevoked:true}
result.toolInputs = {}
for (const name of ['Replay-FixedLinux.ps1','replay-private-problems.mjs','replay-private-audit.mjs','replay-private-revoke.mjs','compose.replay.yml','replay-vite.mjs']) result.toolInputs[`tools/validation/${name}`] = createHash('sha256').update(await readFile(`/source/tools/validation/${name}`)).digest('hex')
await writeFile('/reports/private-problems-audit.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result))
