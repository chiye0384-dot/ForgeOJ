// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Test-only client; trusts the owned fixture certificate, never disables TLS validation.
import https from 'node:https'
import http from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const [privateDir, httpsPort, httpPort, report] = process.argv.slice(2)
const ca = await readFile(`${privateDir}/tls/fullchain.pem`)
let checks = 0
const expect = (actual, expected) => { assert.deepEqual(actual, expected); checks++ }
function request(path, options = {}, secure = true) {
  return new Promise((resolve, reject) => {
    const req = (secure ? https : http).request({hostname:'127.0.0.1', servername:'oj.test',
      port:secure ? httpsPort : httpPort, path, ca, method:options.method || 'GET',
      headers:{Host:`oj.test:${secure ? httpsPort : httpPort}`, ...options.headers}}, response => {
      let body = ''; response.setEncoding('utf8'); response.on('data', value => body += value)
      response.on('end', () => resolve({status:response.statusCode, headers:response.headers, body}))
    })
    req.setTimeout(10000, () => req.destroy(new Error('HTTPS smoke timeout')))
    req.on('error', reject); req.end(options.body)
  })
}
expect((await request('/')).status, 200)
expect((await request('/problems')).status, 200)
expect((await request('/dev-mail')).status, 404)
expect((await request('/src/main.ts')).status, 404)
const redirect = await request('/', {}, false)
expect(redirect.status, 308); expect(redirect.headers.location, `https://oj.test:${httpsPort}/`)
const session = await request('/api/v1/auth/session')
expect(session.status, 200); expect(JSON.parse(session.body).authenticated, false)
const cookies = session.headers['set-cookie'] || []
assert.ok(cookies.length > 0); checks++
assert.ok(cookies.every(cookie => /;\s*Secure/i.test(cookie) && /;\s*HttpOnly/i.test(cookie) && /;\s*SameSite=Strict/i.test(cookie))); checks++
const csrf = JSON.parse(session.body).csrf
const headers = {'Content-Type':'application/json', Cookie:cookies.map(value => value.split(';')[0]).join('; '), [csrf.headerName]:csrf.token}
expect((await request('/api/v1/auth/login', {method:'POST',headers:{Origin:`https://oj.test:${httpsPort}`,'Content-Type':'application/json'},body:'{}'})).status, 403)
await assert.rejects(request('/', {headers:{Host:'attacker.test'}}), error => error.code === 'ECONNRESET'); checks++
expect((await request('/api/v1/auth/login', {method:'POST',headers:{...headers,Origin:'https://attacker.test','X-Forwarded-Host':'attacker.test','X-Forwarded-Proto':'https'},body:'{}'})).status, 403)
expect((await request('/api/v1/auth/login', {method:'POST',headers:{...headers,Origin:`https://oj.test:${httpsPort}`,'X-Forwarded-Host':'attacker.test','X-Forwarded-Proto':'http'},body:JSON.stringify({username:'learner',password:'forgeoj-dev-only'})})).status, 401)
// Expected anonymous refusal must pass through the WebSocket proxy, never become a static page.
const ws = await request('/api/v1/submissions/00000000-0000-0000-0000-000000000001/events', {headers:{Origin:`https://oj.test:${httpsPort}`,Upgrade:'websocket',Connection:'Upgrade','Sec-WebSocket-Key':'MDEyMzQ1Njc4OWFiY2RlZg==','Sec-WebSocket-Version':'13'}})
assert.ok([401,403,404].includes(ws.status)); checks++
const result = {status:'PASS', checks, domain:'oj.test', tlsValidated:true,
  scope:'empty database HTTPS/secure-cookie/CSRF/origin/forwarding/anonymous-WS refusal; no real user or production SMTP',verifiedAt:new Date().toISOString()}
await writeFile(report, JSON.stringify(result,null,2)); console.log(JSON.stringify(result))
