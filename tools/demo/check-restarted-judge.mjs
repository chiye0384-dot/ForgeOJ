// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { Client } from './demo-client.mjs'
import { catalog } from '../../content/starter/catalog.mjs'

const state = JSON.parse(await readFile('/reports/state.json', 'utf8'))
assert.equal(state.Project, process.argv[2])
assert.equal(state.ProductDemo, true)
const imported = JSON.parse(await readFile('/reports/starter-import.json', 'utf8'))
assert.equal(imported.project, state.Project)
assert.equal(imported.status, 'VERIFIED')
const record = { project: state.Project, checkedAt: new Date().toISOString(), status: 'IN_PROGRESS', requestId: randomUUID() }
const path = '/reports/restart-judge-' + Date.now() + '.json'
const save = () => writeFile(path, JSON.stringify(record, null, 2) + '\n')
await save()
try {
 const client = new Client('verification-learner', 'forgeoj-dev-only')
 await client.login()
 const slug = imported.items[catalog[0].id].publishedSlug
 const queued = await client.call('/api/v1/problems/' + slug + '/submissions', 'POST', {
  language: 'JAVA_21', sourceCode: catalog[0].content.solutionCode,
 }, 202, { 'Idempotency-Key': record.requestId })
 record.submissionId = queued.submissionId
 await save()
 for (let i = 0; i < 120; i++) {
  const result = await client.call('/api/v1/submissions/' + queued.submissionId)
  if (result.processingStatus === 'FINISHED') {
   assert.equal(result.verdict, 'AC')
   record.verdict = 'AC'; record.status = 'VERIFIED'; await save()
   console.log(JSON.stringify(record)); process.exit(0)
  }
  assert.notEqual(result.processingStatus, 'SYSTEM_ERROR')
  await new Promise(resolve => setTimeout(resolve, 1000))
 }
 throw new Error('Post-restart judging is unfinished; retain its ID and inspect instead of resubmitting')
} catch (error) { record.status = 'FAILED'; record.error = String(error); await save(); throw error }
