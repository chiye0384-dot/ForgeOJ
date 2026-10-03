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
  for (const sentinel of ['E2E_SOURCE_SENTINEL','E2E_INVALID_TOKEN','E2E_RUNTIME_DIAGNOSTIC_SENTINEL',
    'forgeoj-dev-only','m1-e2e-','JSESSIONID=','X-CSRF-TOKEN','input_gzip','expected_output_gzip']) {
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
for (const row of matrix) {
  assert.ok(facts.some(f => f.submissionId === row.submissionId && f.verdict === row.expected))
  assert.equal(row.closeCode, 1000)
  assert.ok(row.notices.some(n => n.processingStatus === 'FINISHED' && n.statusVersion === 2))
}
const queues = await json('queues.json')
assert.deepEqual(queues.map(q => q.name).sort(), ['forgeoj.judge.retry.v1','forgeoj.judge.self-test.v1',
  'forgeoj.judge.submission.dead.v1','forgeoj.judge.submission.v1'])
for (const queue of queues) { assert.equal(queue.messages_ready, 0); assert.equal(queue.messages_unacknowledged, 0) }
// Browser evidence is separately observed in the real UI, not simulated by this protocol probe.
const browser = await json('browser.json'), frontend = await lines('frontend.log')
assert.deepEqual(browser.map(b => b.mode).sort(), ['fallback','normal'])
for (const row of browser) {
  assert.deepEqual(row.visibleStates, ['QUEUED','FINISHED','AC'])
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
const summary = { verifiedAt:new Date().toISOString(), submissions:facts.length, finished:chains.length,
  cancelled:1, publishedOutbox:outbox.length, emptyQueues:queues.length, library, browser, chains }
await writeFile('/reports/audit.json', JSON.stringify(summary, null, 2))
console.log(JSON.stringify({ event:'E2E_AUDIT_VERIFIED', submissions:facts.length,
  finished:chains.length, cancelled:1, publishedOutbox:outbox.length, emptyQueues:queues.length }))
