// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Only the verifier's new disposable project contains the explicitly installed test identity.
import https from 'node:https'
import { randomUUID, createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const [privateDir, report] = process.argv.slice(2)
const ca = await readFile(`${privateDir}/tls/fullchain.pem`)
const cookies = new Map()
function request(path, body, csrf, key) {
  return new Promise((resolve, reject) => {
    const req = https.request({hostname:'127.0.0.1',servername:'oj.test',port:18443,path,ca,
      method:body === undefined ? 'GET' : 'POST', headers:{Host:'oj.test:18443',Origin:'https://oj.test:18443',
      Cookie:[...cookies].map(([name,value]) => `${name}=${value}`).join('; '),
      ...(body === undefined ? {} : {'Content-Type':'application/json'}),
      ...(csrf ? {[csrf.headerName]:csrf.token} : {}), ...(key ? {'Idempotency-Key':key} : {})}}, res => {
      for (const cookie of res.headers['set-cookie'] || []) {
        const [pair] = cookie.split(';'); const at = pair.indexOf('='); cookies.set(pair.slice(0,at),pair.slice(at+1))
      }
      let text='';res.setEncoding('utf8');res.on('data',part => text+=part)
      res.on('end',() => resolve({status:res.statusCode,body:text ? JSON.parse(text) : null}))
    })
    req.setTimeout(10000,()=>req.destroy(new Error('Owned source HTTP timeout')))
    req.on('error',reject);req.end(body === undefined ? undefined : JSON.stringify(body))
  })
}
let session = await request('/api/v1/auth/session');assert.equal(session.status,200)
session = await request('/api/v1/auth/login',{username:'learner',password:'forgeoj-dev-only'},session.body.csrf)
assert.equal(session.status,200);assert.equal(session.body.authenticated,true)
const marker='backup-probe-'+randomUUID()
const sourceCode=`// ${marker}\nimport java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); } }`
const created=await request('/api/v1/problems/sum-two-integers/submissions',{language:'JAVA_21',sourceCode},session.body.csrf,randomUUID())
assert.equal(created.status,202)
let latest
const deadline=Date.now()+120000
while(Date.now()<deadline){
  latest=await request(`/api/v1/submissions/${created.body.submissionId}`)
  assert.equal(latest.status,200)
  if(latest.body.processingStatus==='FINISHED') break
  await new Promise(resolve=>setTimeout(resolve,1500))
}
assert.equal(latest.body.processingStatus,'FINISHED');assert.equal(latest.body.verdict,'AC')
await writeFile(report,JSON.stringify({status:'PASS',submissionId:created.body.submissionId,verdict:'AC',marker,sourceSha256:createHash('sha256').update(sourceCode).digest('hex'),scope:'ordinary test identity; real HTTPS submit and Worker; no manufactured verdict'},null,2))
console.log(JSON.stringify({status:'PASS',submissionId:created.body.submissionId,verdict:'AC'}))
