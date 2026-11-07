// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Disposable @example.test registration only. No SMTP, real identities, or password storage.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
const exec = promisify(execFile)
const [statePath, outputPath, docker, mode = 'verify'] = process.argv.slice(2)
assert.ok(statePath && outputPath && docker)
const state = JSON.parse(await readFile(statePath, 'utf8'))
assert.equal(state.ProductDemo, true)
assert.match(state.Project, /^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$/)
const api = state.Project + '-api-1'
const inspected = await exec(docker, ['inspect', '--format', '{{json .Config.Labels}}', api])
assert.equal(JSON.parse(inspected.stdout)['com.docker.compose.project'], state.Project)
const base = 'http://127.0.0.1:8088'
const cookies = new Map()
let csrf
async function call(path, method = 'GET', body, expected = 200) {
  const headers = { Cookie: [...cookies].map(([k, v]) => k + '=' + v).join('; ') }
  if (method !== 'GET') {
    headers.Origin = base
    headers['Content-Type'] = 'application/json'
    if (csrf) headers[csrf.headerName] = csrf.token
  }
  const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) })
  assert.equal(response.status, expected, method + ' ' + path)
  for (const value of response.headers.getSetCookie()) {
    const pair = value.split(';')[0], at = pair.indexOf('=')
    if (/Max-Age=0/i.test(value)) cookies.delete(pair.slice(0, at))
    else cookies.set(pair.slice(0, at), pair.slice(at + 1))
  }
  const text = await response.text()
  const result = text ? JSON.parse(text) : null
  if (result?.csrf) csrf = result.csrf
  return result
}
const result = { status: 'IN_PROGRESS', project: state.Project, mode, checks: [] }
await writeFile(outputPath, JSON.stringify(result, null, 2))
try {
  assert.equal((await call('/api/v1/auth/session')).authenticated, false)
  assert.equal((await call('/api/v1/problems?page=1&size=50')).total, 21)
  const username = 'UxSignup_' + Date.now(), email = username.toLowerCase() + '@example.test'
  const password = 'public-ux-fixture-password'
  await call('/api/v1/auth/register', 'POST', { username, email, password, nickname: '' }, 202)
  await call('/api/v1/auth/login', 'POST', { username, password }, 401)
  const mail = await exec(docker, ['exec', api, 'curl', '--fail', '--silent', 'http://127.0.0.1:2525/'])
  const article = mail.stdout.split('<article>').find((part) => part.includes(email + ' · ACTIVATE'))
  assert.ok(article, 'fixture activation mail must exist in the private simulator')
  const link = new URL(/href="([^"]+)"/.exec(article)[1].replaceAll('&amp;', '&'))
  assert.equal(link.origin, base)
  const token = new URLSearchParams(link.hash.slice(1)).get('token')
  assert.match(token, /^[A-Za-z0-9_-]{43}$/)
  result.checks.push('21 problems preserved', 'pending account cannot log in', 'optional nickname accepted')
  result.fixtureUsername = username
  if (mode === 'prepare-ui') {
    // A short-lived fixture link is saved only in ignored target, never in public evidence.
    await writeFile(outputPath + '.private-link.json', JSON.stringify({ link: link.href }))
    result.status = 'PREPARED'
  } else {
    const previousCsrf = csrf.token
    const signedIn = await call('/api/v1/auth/registration/complete', 'POST', { token })
    assert.equal(signedIn.authenticated, true)
    assert.equal(signedIn.user.username, username)
    assert.notEqual(csrf.token, previousCsrf)
    assert.equal((await call('/api/v1/auth/session')).authenticated, true)
    assert.equal((await call('/api/v1/me/submissions')).total, 0)
    await call('/api/v1/auth/registration/complete', 'POST', { token }, 400)
    assert.equal((await call('/api/v1/auth/session')).authenticated, true)
    await call('/api/v1/auth/logout', 'POST', {}, 204)
    assert.equal((await call('/api/v1/auth/session')).authenticated, false)
    result.checks.push('activation logs in without password reentry', 'CSRF rotated', 'authenticated protected query succeeds', 'used link rejected', 'logout works')
    result.status = 'VERIFIED'
  }
  result.checkedAt = new Date().toISOString()
  await writeFile(outputPath, JSON.stringify(result, null, 2) + '\n')
  console.log(result.status + ': ' + result.checks.length + ' account checks; no real mail sent.')
} catch (error) {
  result.status = 'FAILED'
  await writeFile(outputPath, JSON.stringify(result, null, 2) + '\n')
  throw error
}
