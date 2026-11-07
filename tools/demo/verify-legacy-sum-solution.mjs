// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Real judging only, using the dedicated test learner. Never writes judge results or content SQL.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { Client } from './demo-client.mjs'
import { legacySumSolution as content } from '../../content/starter/legacy-sum-solution.mjs'

const state = JSON.parse(await readFile('/reports/state.json', 'utf8'))
assert.equal(state.Project, process.argv[2])
assert.equal(state.ProductDemo, true)
const digest = value => createHash('sha256').update(value).digest('hex')
const path = '/reports/legacy-sum-solution-validation.json'
let receipt = { status: 'IN_PROGRESS', project: state.Project, contentSha256: digest(JSON.stringify(content)), programs: [] }
try { receipt = JSON.parse(await readFile(path, 'utf8')) } catch (error) { if (error.code !== 'ENOENT') throw error }
assert.equal(receipt.contentSha256, digest(JSON.stringify(content)))
assert.equal(receipt.project, state.Project)
const save = () => writeFile(path, JSON.stringify(receipt, null, 2) + '\n')
const client = new Client('verification-learner', 'forgeoj-dev-only')
await client.login()
const problem = await client.call('/api/v1/problems/' + content.slug)
assert.equal(problem.judgeVersion, content.judgeVersion)
for (const [index, sourceCode] of [content.sourceCode, content.independentCode].entries()) {
  const entry = receipt.programs[index] ??= { requestId: randomUUID(), sourceSha256: digest(sourceCode) }
  assert.equal(entry.sourceSha256, digest(sourceCode))
  await save()
  if (!entry.submissionId) {
    const accepted = await client.call('/api/v1/problems/' + content.slug + '/submissions', 'POST', { language: 'JAVA_21', sourceCode }, 202, { 'Idempotency-Key': entry.requestId })
    entry.submissionId = accepted.submissionId; await save()
  }
  let finished = false
  for (let poll = 0; poll < 120; poll++) {
    const result = await client.call('/api/v1/submissions/' + entry.submissionId)
    if (result.processingStatus === 'FINISHED') {
      entry.verdict = result.verdict; await save()
      assert.equal(result.verdict, 'AC'); finished = true; break
    }
    assert.notEqual(result.processingStatus, 'SYSTEM_ERROR')
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  assert.ok(finished, 'Unfinished job retained; inspect before continuing')
}
receipt.status = 'VERIFIED'; receipt.checkedAt = new Date().toISOString(); await save()
console.log('VERIFIED: two independent programs received actual Worker AC for the existing seed version.')
