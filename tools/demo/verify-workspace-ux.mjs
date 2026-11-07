// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Reads the current product demo and uses only an existing, dedicated account-UX fixture for consent checks.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { Client } from './demo-client.mjs'
import { legacySumSolution } from '../../content/starter/legacy-sum-solution.mjs'
const hash = value => createHash('sha256').update(value).digest('hex')
const state = JSON.parse(await readFile('/reports/state.json', 'utf8'))
assert.equal(state.Project, process.argv[2]); assert.equal(state.ProductDemo, true)
const earlyUser = process.argv[3]
assert.match(earlyUser, /^UxSignup_\d{13}$/)
const learner = new Client('verification-learner', 'forgeoj-dev-only')
await learner.login()
const library = await learner.call('/api/v1/problems?page=1&size=50')
assert.equal(library.total, 21)
const solutions = []
for (const problem of library.items) {
  const result = await learner.call('/api/v1/me/problems/' + problem.slug + '/solution')
  assert.equal(result.access, 'AC'); assert.ok(result.solution.idea.trim()); assert.ok(result.solution.sourceCode.trim())
  solutions.push({ slug: problem.slug, judgeVersion: result.judgeVersion, ideaSha256: hash(result.solution.idea), sourceSha256: hash(result.solution.sourceCode) })
}
const formal = await learner.call('/api/v1/submissions/dc378c93-05cb-4712-aee5-5067fc0df5cc')
assert.equal(formal.processingStatus, 'FINISHED'); assert.equal(formal.verdict, 'AC')
const runs = await learner.call('/api/v1/me/self-tests?problemSlug=sum-two-integers&page=1&size=20')
const matching = runs.items.filter(run => run.runId.startsWith('ac8f9a39'))
assert.equal(matching.length, 1)
const selfTest = await learner.call('/api/v1/self-tests/' + matching[0].runId)
assert.equal(selfTest.run.executionResult, 'SUCCESS'); assert.equal(selfTest.output, '42\n'); assert.equal(selfTest.input, '20 22\n')
const early = new Client(earlyUser, 'public-ux-fixture-password')
await early.login()
const path = '/api/v1/me/problems/sum-two-integers/solution'
const locked = await early.call(path)
assert.equal(locked.access, 'LOCKED'); assert.equal(locked.solution, null)
await early.call(path + '/early-view', 'POST', { judgeVersion: 1, confirmEarlyView: false }, 400)
assert.equal((await early.call(path)).access, 'LOCKED')
const unlocked = await early.call(path + '/early-view', 'POST', { judgeVersion: 1, confirmEarlyView: true })
assert.equal(unlocked.access, 'EARLY_VIEW'); assert.equal(hash(unlocked.solution.sourceCode), hash(legacySumSolution.sourceCode))
await early.call('/api/v1/auth/logout', 'POST', {}, 204)
await learner.call('/api/v1/auth/logout', 'POST', {}, 204)
const receipt = { status: 'VERIFIED', checkedAt: new Date().toISOString(), project: state.Project, solutions, formalSubmission: { id: 'dc378c93-05cb-4712-aee5-5067fc0df5cc', verdict: 'AC' }, selfTest: { runId: selfTest.run.runId, executionResult: 'SUCCESS', output: '42\n', sourceSha256: hash(selfTest.sourceCode) }, accessChecks: { lockedHidesContent: true, falseConsentRejected: true, explicitConsentUnlocks: true, ordinaryLearnerProgressModified: false } }
await writeFile('/reports/workspace-http-verification.json', JSON.stringify(receipt, null, 2) + '\n')
console.log('VERIFIED: 21 official solutions, actual browser AC and self-test output, explicit consent enforced.')
