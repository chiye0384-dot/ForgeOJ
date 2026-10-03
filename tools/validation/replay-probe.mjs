// Copyright 2026 池也
// SPDX-License-Identifier: Apache-2.0
// Disposable real HTTP/WebSocket probe. No third-party client or production feature.
import assert from 'node:assert/strict'
import http from 'node:http'
import { randomBytes, randomUUID, createHash } from 'node:crypto'
import { writeFile } from 'node:fs/promises'

const base = 'http://localhost:5173'
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
class Client {
  cookies = new Map()
  get cookie() { return [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; ') }
  csrf
  async request(path, method = 'GET', body, extra = {}) {
    const headers = { ...(this.cookie ? { Cookie: this.cookie } : {}), ...extra }
    if (method !== 'GET') headers.Origin = base
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (method !== 'GET' && this.csrf) headers[this.csrf.headerName] = this.csrf.token
    const response = await fetch(base + path, { method, headers,
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) })
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(';')[0], separator = pair.indexOf('=')
      if (separator < 1) continue
      const key = pair.slice(0, separator), token = pair.slice(separator + 1)
      if (!token || /Max-Age=0(?:;|$)/i.test(value)) this.cookies.delete(key)
      else this.cookies.set(key, token)
    }
    const text = await response.text()
    return { status: response.status, body: text ? JSON.parse(text) : null }
  }
  async login(username = 'learner') {
    this.csrf = (await this.request('/api/v1/auth/session')).body.csrf
    const result = await this.request('/api/v1/auth/login', 'POST', { username, password: 'forgeoj-dev-only' })
    assert.equal(result.status, 200)
    assert.equal(result.body.authenticated, true)
    // Session/CSRF rotation is authoritative, never replay the pre-login token.
    this.csrf = (await this.request('/api/v1/auth/session')).body.csrf
  }
  async submit(source) {
    const result = await this.request('/api/v1/problems/sum-two-integers/submissions', 'POST',
      { language: 'JAVA_21', sourceCode: source + '\n// E2E_SOURCE_SENTINEL' }, { 'Idempotency-Key': randomUUID() })
    assert.equal(result.status, 202)
    assert.deepEqual(Object.keys(result.body).sort(), ['processingStatus', 'statusVersion', 'submissionId'])
    return result.body
  }
}

// RFC 6455 subset for this server's uncompressed, final text/control frames only.
// Verify the handshake, bound buffers and fail on unsupported frames instead of guessing.
// https://www.rfc-editor.org/rfc/rfc6455.html (sections 4 and 5); no RFC code copied.
async function watch(client, id, origin = base, expectedStatus = 101) {
  const key = randomBytes(16).toString('base64')
  const notices = []
  let socket, buffer = Buffer.alloc(0), failure, closeCode
  const sendControl = (opcode, payload) => {
    const mask = randomBytes(4)
    const data = Buffer.from(payload)
    for (let i = 0; i < data.length; i++) data[i] ^= mask[i % 4]
    socket.write(Buffer.concat([Buffer.from([0x80 | opcode, 0x80 | data.length]), mask, data]))
  }
  const consume = data => {
    try {
      buffer = Buffer.concat([buffer, data])
      assert.ok(buffer.length <= 16384)
      while (buffer.length >= 2) {
        assert.equal(buffer[0] & 0xf0, 0x80, 'unsupported non-final/extended frame')
        assert.equal(buffer[1] & 0x80, 0, 'server frame must be unmasked')
        const opcode = buffer[0] & 0x0f
        let length = buffer[1] & 0x7f, offset = 2
        assert.notEqual(length, 127)
        if (length === 126) { if (buffer.length < 4) return; length = buffer.readUInt16BE(2); offset = 4 }
        assert.ok(length <= 8192)
        if (buffer.length < offset + length) return
        const payload = buffer.subarray(offset, offset + length)
        buffer = buffer.subarray(offset + length)
        if (opcode === 1) {
          const notice = JSON.parse(payload.toString('utf8'))
          assert.deepEqual(Object.keys(notice).sort(), ['processingStatus', 'statusVersion', 'submissionId'])
          assert.equal(notice.submissionId, id)
          assert.ok(Number.isSafeInteger(notice.statusVersion) && notice.statusVersion >= 0)
          assert.ok(notices.length === 0 || notice.statusVersion > notices.at(-1).statusVersion)
          notices.push(notice)
        } else if (opcode === 8) {
          closeCode = payload.length >= 2 ? payload.readUInt16BE(0) : 1005
          sendControl(8, payload)
          socket.end()
        } else if (opcode === 9) { assert.ok(length <= 125); sendControl(10, payload) }
        else assert.equal(opcode, 10)
      }
    } catch (error) { failure = error; socket.destroy() }
  }
  await new Promise((resolve, reject) => {
    const headers = { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13',
      'Sec-WebSocket-Key': key, Origin: origin, ...(client.cookie ? { Cookie: client.cookie } : {}) }
    // Vite closes denied upgrade sockets without forwarding HTTP status. Test
    // negative handshake codes against the real API, preserving the proxy Host.
    // Successful subscriptions still traverse the actual Vite WebSocket proxy.
    const target = expectedStatus === 101 ? base : 'http://api:8080'
    if (expectedStatus !== 101) headers.Host = new URL(base).host
    const request = http.request(target + `/api/v1/submissions/${id}/events`, { headers })
    const timer = setTimeout(() => { request.destroy(); reject(new Error('Handshake timeout')) }, 10000)
    request.on('error', error => { clearTimeout(timer); reject(error) })
    request.on('response', response => {
      clearTimeout(timer); response.resume()
      try { assert.equal(response.statusCode, expectedStatus); assert.notEqual(expectedStatus, 101); resolve() }
      catch (error) { reject(error) }
    })
    request.on('upgrade', (response, connected, head) => {
      clearTimeout(timer)
      socket = connected
      try {
        assert.equal(expectedStatus, 101)
        assert.equal(response.statusCode, 101)
        assert.equal(response.headers['sec-websocket-accept'], createHash('sha1')
          .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64'))
        connected.on('data', consume)
        connected.on('error', error => { failure = error })
        consume(head)
        resolve()
      } catch (error) { connected.destroy(); reject(error) }
    })
    request.end()
  })
  return { notices, get closeCode() { return closeCode }, check() { if (failure) throw failure },
    close() { socket?.destroy() } }
}

const ac = 'import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); } }'
const fixtures = {
  AC: ac,
  WA: 'public class Main { public static void main(String[] args) { System.out.println(0); } }',
  CE: 'public class Main { public static void main(String[] args) { E2E_INVALID_TOKEN; } }',
  RE: 'public class Main { public static void main(String[] args) { throw new RuntimeException("E2E_RUNTIME_DIAGNOSTIC_SENTINEL"); } }',
  TLE: 'public class Main { public static void main(String[] args) { while(true) { Thread.onSpinWait(); } } }',
  OLE: 'public class Main { public static void main(String[] args) { for(int i=0;i<200000;i++) System.out.println("01234567890123456789"); } }',
  MLE: `public class Main { public static void main(String[] args) throws Exception {
    if(args.length>0) { byte[] huge=new byte[384*1024*1024]; for(int i=0;i<huge.length;i+=4096) huge[i]=1; System.out.println(huge[0]); return; }
    Process child=new ProcessBuilder("java","-XX:ActiveProcessorCount=1","-Xmx768m","-cp","/workspace","Main","child").start();
    child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); child.destroyForcibly(); System.out.println(3); } }`,
  SECURITY_VIOLATION: `import java.util.*; public class Main { public static void main(String[] args) {
    List<Process> children=new ArrayList<>(); try { for(int i=0;i<80;i++) children.add(new ProcessBuilder("/usr/bin/sleep","10").start()); }
    catch(java.io.IOException|OutOfMemoryError bounded) { } finally { for(Process p:children) p.destroyForcibly(); } System.out.println(3); } }`,
}

async function matrix() {
  const client = new Client()
  await client.login()
  const problem = await client.request('/api/v1/problems/sum-two-integers')
  assert.equal(problem.status, 200)
  assert.deepEqual(Object.keys(problem.body).sort(), ['inputDescription','judgeVersion','outputDescription','publicSamples','resourceLimits','slug','statement','title'])
  const rows = []
  for (const [expected, source] of Object.entries(fixtures)) {
    const created = await client.submit(source)
    const subscription = await watch(client, created.submissionId)
    try {
      const deadline = Date.now() + 45000
      while (!subscription.notices.some(n => n.processingStatus === 'FINISHED') && Date.now() < deadline) {
        subscription.check(); await pause(100)
      }
      subscription.check()
      assert.ok(subscription.notices.some(n => n.processingStatus === 'FINISHED'), `No terminal notice: ${expected}`)
      const result = await client.request(`/api/v1/submissions/${created.submissionId}`)
      assert.equal(result.status, 200)
      assert.deepEqual(Object.keys(result.body).sort(), ['diagnosticMessage','processingStatus','statusVersion','submissionId','verdict'])
      assert.equal(result.body.verdict, expected)
      assert.equal(result.body.processingStatus, 'FINISHED')
      assert.equal(result.body.statusVersion, 2)
      if (expected !== 'CE') assert.equal(result.body.diagnosticMessage, null)
      for (let i = 0; subscription.closeCode === undefined && i < 30; i++) await pause(100)
      assert.equal(subscription.closeCode, 1000)
      const row = { expected, submissionId: created.submissionId, verdict: result.body.verdict,
        statusVersion: result.body.statusVersion, notices: subscription.notices, closeCode: subscription.closeCode }
      rows.push(row)
      await writeFile('/reports/matrix.json', JSON.stringify(rows, null, 2))
      console.log(JSON.stringify({ event: 'E2E_VERDICT_VERIFIED', ...row }))
    } finally { subscription.close() }
  }
  await writeFile('/reports/matrix.json', JSON.stringify(rows, null, 2))
}

async function permissions() {
  const owner = new Client(), other = new Client(), anonymous = new Client()
  await owner.login(); await other.login('other-learner')
  const created = process.argv[3] ? { submissionId:process.argv[3] } : await owner.submit(ac)
  const id = created.submissionId
  assert.equal((await owner.request(`/api/v1/submissions/${id}`)).body.processingStatus, 'QUEUED')
  await watch(anonymous, id, base, 401)
  await watch(other, id, base, 404)
  await watch(owner, randomUUID(), base, 404)
  await watch(owner, id, 'http://untrusted.invalid', 403)
  assert.equal((await other.request(`/api/v1/submissions/${id}`)).status, 404)
  const subscription = await watch(owner, id)
  try {
    for (let i=0; subscription.notices.length===0 && i<30; i++) await pause(100)
    assert.equal(subscription.notices[0].processingStatus, 'QUEUED')
    assert.equal((await owner.request('/api/v1/auth/logout','POST')).status, 204)
    for (let i=0; subscription.closeCode===undefined && i<30; i++) await pause(100)
    subscription.check()
    assert.equal(subscription.closeCode, 1008)
    assert.equal((await owner.request(`/api/v1/submissions/${id}`)).status, 401)
    await owner.login()
    const cancelled = await owner.request(`/api/v1/submissions/${id}/cancel`, 'POST')
    assert.equal(cancelled.status, 200)
    assert.equal(cancelled.body.processingStatus, 'CANCELLED')
    assert.equal(cancelled.body.statusVersion, 1)
    await writeFile('/reports/permissions.json', JSON.stringify({ submissionId:id,
      anonymous:401, otherOwner:404, missing:404, crossOrigin:403, logoutClose:1008, cancelled:cancelled.body }, null, 2))
    console.log('E2E_PERMISSIONS_VERIFIED=OWNER_ORIGIN_LOGOUT_QUEUED_CANCEL')
  } finally { subscription.close() }
}

if (process.argv[2] === 'matrix') await matrix()
else if (process.argv[2] === 'permissions') await permissions()
else if (process.argv[2] === 'library') await library()
else if (process.argv[2] === 'learning') await learning()
else throw new Error('Expected matrix, permissions, library or learning')

async function learning() {
  const owner=new Client(), other=new Client(), anonymous=new Client()
  await owner.login(); await other.login('other-learner')
  const official='00000000-0000-0000-0000-000000000101'
  const publicList=await anonymous.request('/api/v1/official-problem-lists/'+official)
  assert.equal(publicList.status,200)
  assert.ok(!JSON.stringify(publicList.body).includes('completed'))
  const created=await owner.request('/api/v1/me/problem-lists','POST',{title:'隔离学习验收'})
  assert.equal(created.status,201)
  const id=created.body.id, path='/api/v1/me/problem-lists/'+id
  assert.equal((await owner.request(path+'/items','POST',{problemSlug:'sum-two-integers',expectedVersion:1})).status,200)
  assert.equal((await owner.request(path+'/items','POST',{problemSlug:'larger-of-two-integers',expectedVersion:2})).status,200)
  const detail=await owner.request(path)
  assert.equal(detail.status,200);assert.equal(detail.body.list.completedCount,1)
  assert.equal(detail.body.list.availableCount,2)
  assert.equal((await other.request(path)).status,404)
  assert.equal((await other.request(path,'PATCH',{title:'other',expectedVersion:3})).status,404)
  const removed=await other.request(path+'?expectedVersion=3','DELETE')
  assert.equal(removed.status,404)
  assert.equal((await other.request(path+'/items','POST',{problemSlug:'sum-two-integers',expectedVersion:3})).status,404)
  const itemIds=detail.body.items.map(i=>i.itemId).reverse()
  assert.equal((await other.request(path+'/order','PUT',{itemIds,expectedVersion:3})).status,404)
  assert.equal((await other.request(path+'/items/'+itemIds[0]+'?expectedVersion=3','DELETE')).status,404)
  assert.equal((await owner.request(path+'/order','PUT',{itemIds,expectedVersion:3})).status,200)
  assert.equal((await owner.request(path+'/order','PUT',{itemIds,expectedVersion:3})).status,409)
  const listRace=await Promise.all(['one','two'].map(title=>owner.request(path,'PATCH',{title,expectedVersion:4})))
  assert.deepEqual(listRace.map(r=>r.status).sort(),[200,409])
  assert.equal((await owner.request(path,'PATCH',{title:'隔离学习验收',expectedVersion:5})).status,200)
  const draftPath='/api/v1/me/problems/larger-of-two-integers/draft'
  assert.equal((await owner.request(draftPath)).body.version,0)
  const draftBody={language:'JAVA_21',sourceCode:'// E2E_DRAFT_SENTINEL incomplete original fixture',expectedVersion:0}
  const first=await Promise.all([owner.request(draftPath,'PUT',draftBody),owner.request(draftPath,'PUT',draftBody)])
  assert.deepEqual(first.map(r=>r.status).sort(),[200,409])
  const second=await Promise.all([owner.request(draftPath,'PUT',{...draftBody,expectedVersion:1}),owner.request(draftPath,'PUT',{...draftBody,expectedVersion:1})])
  assert.deepEqual(second.map(r=>r.status).sort(),[200,409])
  assert.equal((await other.request(draftPath)).body.version,0)
  const history=await owner.request('/api/v1/me/submissions?size=2')
  assert.equal(history.status,200);assert.equal(history.body.total,9)
  const row=history.body.items[0]
  assert.deepEqual(Object.keys(row).sort(),['createdAt','judgeVersion','language','problem','processingStatus','statusVersion','submissionId','verdict'])
  const ownOfficial=await owner.request('/api/v1/me/official-problem-lists/'+official)
  assert.equal(ownOfficial.status,200);assert.equal(ownOfficial.body.list.completedCount,1)
  await writeFile('/reports/learning.json',JSON.stringify({verifiedAt:new Date().toISOString(),listId:id,
    officialListId:official,ownerDenied:true,draftRace:[200,409],existingDraftRace:[200,409],
    listRace:[200,409],currentAcProgress:1,available:2,historyTotal:9,
    publicPersonalFactsAbsent:true,historyExactFields:true},null,2))
  console.log('E2E_LEARNING_HTTP_VERIFIED')
}

async function library() {
  const client = new Client()
  const list = await client.request('/api/v1/problems?size=1')
  assert.equal(list.status, 200)
  assert.deepEqual(Object.keys(list.body).sort(), ['items','page','size','total'])
  assert.equal(list.body.total, 2)
  assert.equal(list.body.items.length, 1)
  assert.equal(list.body.items[0].slug, 'sum-two-integers')
  const next = await client.request('/api/v1/problems?size=1&page=2')
  assert.equal(next.status, 200)
  assert.deepEqual(Object.keys(next.body.items[0]).sort(), ['difficulty','judgeVersion','slug','tags','title'])
  assert.equal(next.body.items[0].slug, 'larger-of-two-integers')
  const filtered = await client.request('/api/v1/problems?keyword=' + encodeURIComponent('较大') + '&difficulty=EASY&tag=' + encodeURIComponent('比较'))
  assert.equal(filtered.status, 200)
  assert.equal(filtered.body.total, 1)
  assert.equal(filtered.body.items[0].slug, 'larger-of-two-integers')
  const wildcard = await client.request('/api/v1/problems?keyword=%25')
  assert.equal(wildcard.status, 200)
  assert.equal(wildcard.body.total, 0)
  const bad = await client.request('/api/v1/problems?size=51')
  assert.equal(bad.status, 400)
  const tags = await client.request('/api/v1/problem-tags')
  assert.equal(tags.status, 200)
  assert.deepEqual(Object.keys(tags.body), ['tags'])
  assert.deepEqual([...tags.body.tags].sort(), ['入门','数学','比较'].sort())
  const detail = await client.request('/api/v1/problems/larger-of-two-integers')
  assert.equal(detail.status, 200)
  assert.deepEqual(Object.keys(detail.body).sort(), ['inputDescription','judgeVersion','outputDescription','publicSamples','resourceLimits','slug','statement','title'])
  await writeFile('/reports/library.json', JSON.stringify({ verifiedAt:new Date().toISOString(), anonymous:true,
    total:list.body.total, firstSlug:list.body.items[0].slug, secondSlug:next.body.items[0].slug,
    filteredSlug:filtered.body.items[0].slug, literalWildcardTotal:0, invalidSizeStatus:400,
    tags:tags.body.tags, exactListFields:true, exactDetailFields:true }, null, 2))
  console.log(JSON.stringify({ event:'LIBRARY_HTTP_VERIFIED', total:list.body.total, anonymous:true }))
}
