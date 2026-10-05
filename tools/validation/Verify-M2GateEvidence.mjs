// Copyright 2026 池也
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

// Read actual saved builds; never run requests, load SMTP secrets, or change databases.
const root = fileURLToPath(new URL('../../', import.meta.url))
const backend = resolve(root, process.argv[2] ?? '')
assert.ok(relative(resolve(root, 'target'), backend).startsWith('forgeoj-linux-'), 'Pass a target/forgeoj-linux-* backend build')
const runtime = JSON.parse(await readFile(resolve(root, 'docs/evidence/m2-self-test/verification.json'), 'utf8'))
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const read = path => readFile(resolve(root, path))
const manifest = async (directory, file = 'source-files.sha256') => {
  const rows = []
  for (const line of (await readFile(resolve(directory, file), 'utf8')).split(/\r?\n/)) {
    const match = line.match(/^([a-f0-9]{64})  \.\/(.+)$/)
    if (match) rows.push({ path: match[2], expected: match[1], actual: hash(await read(match[2])) })
  }
  return rows
}
const executable = path => /^(pom\.xml$|forgeoj-(api|judge-worker)\/|frontend\/|contracts\/|tools\/validation\/)/.test(path)
const production = path => /^(pom\.xml$|forgeoj-(api|judge-worker)\/(pom\.xml$|src\/main\/)|contracts\/)/.test(path)
const modules = {}
assert.match(await readFile(resolve(backend, 'backend.log'), 'utf8'), /BUILD SUCCESS/)
const required = {
  'forgeoj-api': [
    'advancingCurrentVersionPreservesOldSubmissionAndIdempotentReplay',
    'disablingAccountRejectsEveryOldJwtAndRefreshCookie',
    'privateOwnershipEveryRouteAndIdenticalMissingResponses',
    'migratesCurrentTablesAndEnforcesDatabaseReadBoundaries',
    'readsResourceVerdictsWithoutLeakingDiagnosticsOrHiddenFields',
  ],
  'forgeoj-judge-worker': ['currentVersionAdvanceDoesNotRedirectExistingTaskSnapshotOrHiddenTests'],
}
for (const module of Object.keys(required)) {
  const reports = resolve(backend, module, 'surefire-reports')
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 }
  const cases = new Set()
  let suites = 0
  for (const file of await readdir(reports)) {
    if (!file.startsWith('TEST-') || !file.endsWith('.xml')) continue
    const xml = await readFile(resolve(reports, file), 'utf8')
    const header = xml.match(/<testsuite\s[^>]+>/)?.[0]
    assert.ok(header, file)
    for (const key of Object.keys(totals)) totals[key] += Number(header.match(new RegExp(`${key}="(\\d+)"`))?.[1])
    for (const match of xml.matchAll(/<testcase\s[^>]*\bname="([^"]+)"/g)) cases.add(match[1])
    suites++
  }
  assert.equal(totals.failures + totals.errors + totals.skipped, 0)
  assert.equal(totals.tests, module === 'forgeoj-api' ? 182 : 133)
  for (const name of required[module]) assert.ok(cases.has(name), `Missing gate case: ${name}`)
  modules[module] = { ...totals, suites, requiredCases: required[module], jarSha256: hash(await readFile(resolve(backend, module, `${module}-0.0.1-SNAPSHOT.jar`))) }
}
const freshInputs = (await manifest(backend)).filter(row => executable(row.path))
assert.ok(freshInputs.length > 200)
assert.deepEqual(freshInputs.filter(row => row.expected !== row.actual), [], 'Fresh backend inputs changed')
const priorInputs = await manifest(resolve(root, runtime.backendBuild), 'backend-source-files.sha256')
const runtimeInputs = priorInputs.filter(row => production(row.path))
assert.deepEqual(runtimeInputs.filter(row => row.expected !== row.actual), [], 'Accepted runtime production sources changed')
const productionPaths = ['pom.xml', 'forgeoj-api/pom.xml', 'forgeoj-judge-worker/pom.xml']
async function collectFiles(directory) {
  for (const entry of await readdir(resolve(root, directory), { withFileTypes: true })) {
    const path = `${directory}/${entry.name}`
    if (entry.isDirectory()) await collectFiles(path)
    else if (entry.isFile()) productionPaths.push(path)
    else throw new Error(`Unsupported production source entry: ${path}`)
  }
}
for (const directory of ['forgeoj-api/src/main', 'forgeoj-judge-worker/src/main', 'contracts']) await collectFiles(directory)
assert.deepEqual(productionPaths.sort(), runtimeInputs.map(row => row.path).sort(), 'Production file inventory changed')
const acceptedJars = {}
for (const module of Object.keys(required)) {
  acceptedJars[module] = hash(await readFile(resolve(root, runtime.backendBuild, module, `${module}-0.0.1-SNAPSHOT.jar`)))
  assert.equal(acceptedJars[module], runtime.modules[module].jarSha256, 'Accepted runtime JAR changed')
}
const frontendInputs = (await manifest(resolve(root, runtime.frontendBuild))).filter(row => row.path.startsWith('frontend/'))
assert.ok(frontendInputs.length > 30)
assert.deepEqual(frontendInputs.filter(row => row.expected !== row.actual), [], 'Frontend inputs changed')
const frontendLog = (await readFile(resolve(root, runtime.frontendBuild, 'frontend.log'), 'utf8')).replace(/\x1b\[[0-9;]*m/g, '')
assert.ok(/Tests\s+69 passed/.test(frontendLog), 'Frontend 69-test success summary missing')
assert.ok(/built in/.test(frontendLog), 'Frontend production build success missing')
const evidencePaths = [
  'm2-accounts/browser.json', 'm2-accounts/audit.json',
  'm2-learning/learning.json', 'm2-learning/audit.json',
  'm2-content-review/audit.json', 'm2-output-preview/audit.json',
  'm2-self-test/audit.json', 'm2-self-test/permissions.json',
  'm2-self-test/boundaries.json', 'm2-self-test/smtp-delivery.json',
]
const evidence = []
for (const suffix of evidencePaths) {
  const path = `docs/evidence/${suffix}`
  const bytes = await read(path)
  JSON.parse(bytes.toString('utf8'))
  evidence.push({ path, sha256: hash(bytes) })
}
const browser = JSON.parse((await read('docs/evidence/m2-accounts/browser.json')).toString())
assert.ok(browser.some(row => row.account === 'new-verified-email' && row.visibleStates.includes('AC')))
const smtp = JSON.parse((await read('docs/evidence/m2-self-test/smtp-delivery.json')).toString())
assert.equal(smtp.inboxConfirmed && smtp.linkConfirmed && smtp.emailVerified && smtp.accountState === 'ACTIVE', true)
const result = {
  checkedAt: new Date().toISOString(), baseline: '184ba300e8886ec63d216ad5aad1189977ea2ee1',
  backendBuild: relative(root, backend).replaceAll('\\', '/'), modules,
  sourceInputs: { freshExecutable: freshInputs.length, allFreshMatch: true, acceptedProduction: runtimeInputs.length, allProductionMatch: true, acceptedFrontend: frontendInputs.length, allFrontendMatch: true },
  frontend: { build: runtime.frontendBuild, tests: 69, reusedUnchangedInputs: true },
  runtime: { build: runtime.backendBuild, replay: runtime.replay, acceptedJars, freshReplayThisAudit: false },
  evidence,
}
const destination = resolve(root, 'docs/evidence/m2-gate')
await mkdir(destination, { recursive: true })
await writeFile(resolve(destination, 'verification.json'), JSON.stringify(result, null, 2) + '\n')
process.stdout.write('M2_GATE_EVIDENCE_VERIFIED: backend315; frontend69 unchanged; accepted runtime sources unchanged\n')
