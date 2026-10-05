// Copyright 2026 池也
// SPDX-License-Identifier: Apache-2.0
// Read only redacted facts/logs from this disposable replay; fail on incomplete evidence.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
const read = name => readFile(`/reports/${name}`, 'utf8')
const json = async name => JSON.parse(await read(name))
const lines = async name => (await read(name)).split(/\r?\n/).filter(s => s.startsWith('{')).map(s => JSON.parse(s))
const matrix = await json('matrix.json'), permissions = await json('permissions.json')
const library = await json('library.json')
const learning = await json('learning.json')
assert.equal(learning.ownerDenied, true)
assert.deepEqual(learning.draftRace, [200,409])
assert.equal(learning.currentAcProgress, 1)
assert.equal(learning.publicPersonalFactsAbsent, true)
assert.equal(library.anonymous, true)
assert.equal(library.secondSlug, 'larger-of-two-integers')
assert.equal(library.filteredSlug, library.secondSlug)
assert.equal(library.invalidSizeStatus, 400)
assert.equal(library.exactListFields, true)
assert.equal(library.exactDetailFields, true)
assert.deepEqual(matrix.map(r => r.expected).sort(), ['AC','CE','MLE','OLE','RE','SECURITY_VIOLATION','TLE','WA'])
const facts = await lines('database.jsonl'), outbox = await lines('outbox.jsonl')
const apiText = await read('api.log'), workerText = await read('worker.log')
for (const text of [apiText, workerText]) {
  for (const sentinel of ['E2E_SOURCE_SENTINEL','E2E_DRAFT_SENTINEL','E2E_INVALID_TOKEN','E2E_RUNTIME_DIAGNOSTIC_SENTINEL',
    'forgeoj-dev-only','m1-e2e-','JSESSIONID=','X-CSRF-TOKEN','input_gzip','expected_output_gzip','E2E_CONTENT_PRIVATE_SENTINEL']) {
    assert.ok(!text.includes(sentinel), `Leaked sentinel: ${sentinel}`)
  }
}
const api = await lines('api.log'), worker = await lines('worker.log')
const chains = []
for (const fact of facts) {
  assert.ok(['FINISHED','CANCELLED'].includes(fact.processingStatus), 'Unclosed replay task')
  assert.equal(fact.taskStatus, fact.processingStatus)
  assert.equal(fact.taskVersion, fact.statusVersion)
  assert.equal(fact.leaseCleared, true)
  const created = api.filter(l => l.event === 'submission.created' && l.submissionId === fact.submissionId)
  assert.equal(created.length, 1)
  assert.equal(created[0].judgeTaskId, fact.judgeTaskId)
  assert.ok(api.some(l => l.event === 'request.completed' && l.requestId === created[0].requestId &&
    l.route === 'submission.create' && l.httpStatus === 202))
  const events = outbox.filter(o => o.judgeTaskId === fact.judgeTaskId)
  assert.equal(events.length, 1)
  const event = events[0]
  assert.equal(event.sequenceNo, 0); assert.equal(event.published, true); assert.equal(event.failed, false)
  assert.deepEqual(event.keys.sort(), ['contractVersion','submissionId','taskId','taskType'])
  assert.equal(event.outboxEventId, created[0].outboxEventId)
  assert.ok(api.some(l => l.event === 'outbox.published' && l.outboxEventId === event.outboxEventId &&
    l.judgeTaskId === fact.judgeTaskId && l.submissionId === fact.submissionId))
  const taskLogs = worker.filter(l => l.judgeTaskId === fact.judgeTaskId && l.submissionId === fact.submissionId)
  assert.ok(taskLogs.some(l => l.event === 'delivery.received'))
  if (fact.processingStatus === 'FINISHED') {
    assert.equal(fact.statusVersion, 2); assert.equal(fact.attemptCount, 1); assert.equal(fact.attempts.length, 1)
    const attempt = fact.attempts[0]
    assert.equal(attempt.attemptNo, 1); assert.equal(attempt.status, 'SUCCEEDED'); assert.equal(attempt.finished, true)
    const attemptLogs = taskLogs.filter(l => l.attemptId === attempt.attemptId && l.attemptNo === 1)
    const claim = attemptLogs.find(l => l.event === 'attempt.claimed')
    const finish = attemptLogs.find(l => l.event === 'attempt.finished' && l.outcome === fact.verdict)
    const ack = attemptLogs.find(l => l.event === 'delivery.ack_sent')
    assert.ok(claim && finish && ack)
    assert.ok(claim['@timestamp'] <= finish['@timestamp'] && finish['@timestamp'] <= ack['@timestamp'])
    chains.push({ submissionId:fact.submissionId, requestId:created[0].requestId, judgeTaskId:fact.judgeTaskId,
      outboxEventId:event.outboxEventId, attemptId:attempt.attemptId, verdict:fact.verdict })
  } else {
    assert.equal(fact.submissionId, permissions.submissionId)
    assert.equal(fact.statusVersion, 1); assert.equal(fact.attemptCount, 0); assert.equal(fact.attempts.length, 0)
    assert.equal(fact.verdict, null)
    assert.ok(taskLogs.some(l => l.event === 'delivery.ack_sent' && !l.attemptId))
  }
}
assert.equal(outbox.length, facts.length)
const allContent=await lines('content-database.jsonl'),allContentOutbox=await lines('content-outbox.jsonl')
const content=allContent.filter(j=>j.executionKind!=='OUTPUT_PREVIEW'),contentOutbox=allContentOutbox.filter(e=>content.some(j=>j.jobId===e.jobId))
if (content.length) {
  const browserContent = await json('content-browser.json'), httpContent = await json('content-http.json')
  assert.equal(httpContent.ownerDenied, true); assert.equal(httpContent.requestReplaySameJob, true)
  assert.equal(httpContent.deleteReferencedDraftStatus, 409)
  assert.equal(content.length, browserContent.jobs.length)
  for (const observed of browserContent.jobs) {
    const row = content.find(item => item.jobId === observed.jobId)
    assert.ok(row); assert.equal(row.draftId, browserContent.draftId)
    assert.equal(row.draftVersion, observed.draftVersion)
    assert.equal(row.processingStatus, 'FINISHED'); assert.equal(row.validationStatus, observed.validationStatus)
    assert.equal(row.referenceResult, observed.referenceResult); assert.equal(row.solutionResult, observed.solutionResult)
    assert.equal(row.stale, true); assert.equal(row.archived, true); assert.equal(row.leaseCleared, true)
    assert.equal(row.sourceDigestsMatch, true); assert.equal(row.testCount, browserContent.testCount??1)
    const attempts = row.attempts.sort((a,b) => a.attemptNo-b.attemptNo)
    assert.equal(row.attemptCount, observed.recovered ? 2 : 1)
    assert.equal(attempts.length, row.attemptCount)
    assert.deepEqual(attempts.map(a => a.status), observed.recovered ? ['LEASE_EXPIRED','SUCCEEDED'] : ['SUCCEEDED'])
    assert.ok(attempts.every(a => a.finished === true))
    const events = contentOutbox.filter(e => e.jobId === row.jobId)
    assert.equal(events.length, row.attemptCount)
    assert.deepEqual(events.map(e => e.sequenceNo), observed.recovered ? [0,1] : [0])
    const created = api.filter(l => l.event === 'content.validation_created' && l.contentJobId === row.jobId)
    assert.equal(created.length,1); assert.equal(created[0].outboxEventId,events[0].outboxEventId)
    assert.equal(created[0].draftVersion,row.draftVersion)
    assert.ok(api.some(l => l.event === 'request.completed' && l.requestId === created[0].requestId &&
      l.route === 'content.validation' && l.method === 'POST' && l.httpStatus === 202))
    for (const event of events) {
      assert.equal(event.eventType, 'CONTENT_VALIDATION_QUEUED'); assert.equal(event.published, true); assert.equal(event.failed, false)
      assert.deepEqual(event.keys.sort(), ['contractVersion','snapshotId','taskId','taskType'])
      assert.ok(api.some(l => l.event === 'outbox.published' && l.outboxEventId === event.outboxEventId && l.contentJobId === row.jobId))
    }
    const last = attempts.at(-1)
    const logs = worker.filter(l => l.contentJobId === row.jobId && l.contentAttemptId === last.attemptId)
    const claim = logs.find(l => l.event === 'content.attempt_claimed'), commit = logs.find(l => l.event === 'content.attempt_committed'), ack = logs.find(l => l.event === 'content.ack_sent')
    assert.ok(claim && commit && ack); assert.ok(claim['@timestamp'] <= commit['@timestamp'] && commit['@timestamp'] <= ack['@timestamp'])
    if (observed.recovered) {
      assert.ok(browserContent.crashVerified && browserContent.oldSandboxRemoved)
      assert.ok(worker.some(l => l.event === 'content.attempt_claimed' && l.contentAttemptId === attempts[0].attemptId))
    }
  }
  assert.equal(contentOutbox.length, content.reduce((n,j) => n+j.attemptCount,0))
} else assert.equal(contentOutbox.length, 0)
for (const row of matrix) {
  assert.ok(facts.some(f => f.submissionId === row.submissionId && f.verdict === row.expected))
  assert.equal(row.closeCode, 1000)
  assert.ok(row.notices.some(n => n.processingStatus === 'FINISHED' && n.statusVersion === 2))
}
const queues = await json('queues.json')
assert.deepEqual(queues.map(q => q.name).sort(), ['forgeoj.content.validation.dead.v1','forgeoj.content.validation.v1','forgeoj.judge.retry.v1','forgeoj.judge.self-test.v1',
  'forgeoj.judge.submission.dead.v1','forgeoj.judge.submission.v1'])
for (const queue of queues) { assert.equal(queue.messages_ready, 0); assert.equal(queue.messages_unacknowledged, 0) }
// Browser evidence is separately observed in the real UI, not simulated by this protocol probe.
const browser = await json('browser.json'), frontend = await lines('frontend.log')
assert.deepEqual(browser.map(b => b.mode).sort(), ['fallback','normal'])
for (const row of browser) {
  assert.ok(['QUEUED','SUBMITTING'].includes(row.visibleStates[0]))
  assert.deepEqual(row.visibleStates.slice(1), ['FINISHED','AC'])
  assert.ok(facts.some(f => f.submissionId === row.submissionId && f.verdict === 'AC'))
  const gets = frontend.filter(l => l.event === 'validation.result_get' && l.submissionId === row.submissionId && l.port === row.port)
  assert.ok(gets.length >= 2)
  const ws = frontend.filter(l => l.event === 'validation.ws_forwarded' && l.submissionId === row.submissionId && l.port === row.port)
  if (row.mode === 'normal') assert.equal(ws.length, 1)
  else {
    assert.equal(ws.length, 0)
    assert.ok(frontend.some(l => l.event === 'validation.ws_blocked' && l.submissionId === row.submissionId && l.port === row.port))
  }
}
assert.equal(facts.length, matrix.length + 1 + browser.length)
const reviewFacts=await lines('review-database.jsonl')
if(reviewFacts.length) {
  const observed=await json('review-browser.json'), reviewHttp=await json('review-http.json'), reviewLock=await json('review-lock.json')
  assert.ok(reviewHttp.ownerDenied && reviewHttp.requestReplayReturnsOriginalWithdrawn && reviewHttp.allFrozenStatementsMatch && reviewHttp.archivedHistoryReadable)
  assert.equal(reviewHttp.referencedDeletion,409);assert.equal(reviewHttp.publicProblemCount,2)
  assert.ok(reviewLock.allConflict && reviewLock.oldWithdrawalLeavesNewPending);assert.equal(reviewLock.blockedWrites,6);assert.equal(reviewLock.crossOrigin,403)
  assert.equal(reviewFacts.length,observed.reviews.length)
  for(const r of observed.reviews) {
    const row=reviewFacts.find(f=>f.reviewId===r.reviewId);assert.ok(row)
    for(const key of ['draftVersion','reviewNo','validationJobId','frozenStatementSha256']) assert.equal(row[key],r[key])
    assert.equal(row.draftId,observed.draftId);assert.equal(row.status,'WITHDRAWN');assert.equal(row.version,1)
    assert.equal(row.withdrawn,true);assert.equal(row.active,false);assert.equal(row.passedBinding,true)
    const submit=api.filter(l=>l.event==='content.review_submitted' && l.contentReviewId===row.reviewId)
    const withdraw=api.filter(l=>l.event==='content.review_withdrawn' && l.contentReviewId===row.reviewId)
    assert.equal(submit.length,1);assert.equal(withdraw.length,1)
    assert.ok(submit[0]['@timestamp']<=withdraw[0]['@timestamp'])
    assert.ok(api.some(l=>l.event==='request.completed' && l.requestId===submit[0].requestId && l.route==='content.review' && l.method==='POST' && l.httpStatus===202))
    assert.ok(api.some(l=>l.event==='request.completed' && l.requestId===withdraw[0].requestId && l.route==='content.review.withdraw' && l.method==='POST' && l.httpStatus===200))
  }
}

const outputFacts=await lines('output-database.jsonl')
if(outputFacts.length) {
 const observed=await json('output-browser.json'),http=await json('output-http.json')
 assert.ok(http.ownerDenied && http.requestReplaySameJob && http.purposeIsolation && http.acceptedReplayNeverOverwrites && http.allOutputDigestsMatch)
 assert.equal(outputFacts.length,observed.jobs.length)
 for(const j of observed.jobs) {
  const row=allContent.find(f=>f.jobId===j.jobId),output=outputFacts.find(f=>f.jobId===j.jobId)
  assert.ok(row && output);assert.equal(row.executionKind,'OUTPUT_PREVIEW');assert.equal(row.draftVersion,j.draftVersion)
  assert.equal(row.processingStatus,'FINISHED');assert.equal(row.referenceResult,j.referenceResult);assert.equal(row.validationStatus,null);assert.equal(row.solutionResult,null)
  assert.ok(row.archived && row.stale && row.leaseCleared && row.sourceDigestsMatch);assert.equal(row.testCount,observed.testCount)
  const attempts=row.attempts.sort((a,b)=>a.attemptNo-b.attemptNo)
  assert.deepEqual(attempts.map(a=>a.status),j.recovered?['LEASE_EXPIRED','SUCCEEDED']:['SUCCEEDED']);assert.ok(attempts.every(a=>a.finished))
  const events=allContentOutbox.filter(e=>e.jobId===j.jobId);assert.equal(events.length,attempts.length);assert.ok(events.every(e=>e.published && !e.failed))
  for(const e of events) assert.deepEqual(e.keys.sort(),['contractVersion','snapshotId','taskId','taskType'])
  const final=attempts.at(-1),logs=worker.filter(l=>l.contentJobId===j.jobId && l.contentAttemptId===final.attemptId)
  const claimed=logs.find(l=>l.event==='content.attempt_claimed'),committed=logs.find(l=>l.event==='content.attempt_committed'),ack=logs.find(l=>l.event==='content.ack_sent')
  assert.ok(claimed && committed && ack);assert.ok(claimed['@timestamp']<=committed['@timestamp'] && committed['@timestamp']<=ack['@timestamp'])
  if(j.recovered){assert.ok(observed.crashVerified && observed.oldSandboxRemoved);assert.ok(worker.some(l=>l.event==='content.attempt_claimed' && l.contentAttemptId===attempts[0].attemptId))}
  output.outputs.sort((a,b)=>a.sequence-b.sequence)
  if(j.referenceResult==='ACCEPTED'){assert.equal(output.outputs.length,observed.testCount);assert.deepEqual(output.outputs.map(o=>o.sha256),j.outputSha256)}else assert.equal(output.outputs.length,0)
  assert.equal(output.acceptedVersion,j.acceptedVersion??null)
  const accepted=api.filter(l=>l.event==='content.output_accepted' && l.contentJobId===j.jobId)
  assert.equal(accepted.length,j.acceptedVersion?1:0)
  if(j.acceptedVersion)assert.ok(api.some(l=>l.event==='request.completed' && l.requestId===accepted[0].requestId && l.route==='content.output.accept' && l.httpStatus===200))
 }
 assert.equal(allContentOutbox.length,allContent.reduce((n,j)=>n+j.attemptCount,0))
}
const summary = { verifiedAt:new Date().toISOString(), contentReviews:reviewFacts.length, outputPreviews:outputFacts.length, submissions:facts.length, finished:chains.length,
  cancelled:1, publishedOutbox:outbox.length, contentJobs:content.length, contentPublishedOutbox:contentOutbox.length, emptyQueues:queues.length, library, learning, browser, chains }
await writeFile('/reports/audit.json', JSON.stringify(summary, null, 2))
console.log(JSON.stringify({ event:'E2E_AUDIT_VERIFIED', submissions:facts.length,
  finished:chains.length, cancelled:1, publishedOutbox:outbox.length, emptyQueues:queues.length }))
