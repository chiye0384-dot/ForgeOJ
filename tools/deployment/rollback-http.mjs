// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Explicit fixture identity, owned loopback project only; never use production credentials.
import https from 'node:https'
import { randomUUID, createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const [privateDir, report, mode='submit', previous] = process.argv.slice(2)
assert.ok(['submit','queue','history-submit'].includes(mode))
const ca=await readFile(`${privateDir}/tls/fullchain.pem`),cookies=new Map()
function request(path, body, csrf, key){return new Promise((resolve,reject)=>{
  const req=https.request({hostname:'127.0.0.1',servername:'oj.test',port:18443,path,ca,
    method:body===undefined?'GET':'POST',headers:{Host:'oj.test:18443',Origin:'https://oj.test:18443',
      Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),
      ...(body===undefined?{}:{'Content-Type':'application/json'}),...(csrf?{[csrf.headerName]:csrf.token}:{}),
      ...(key?{'Idempotency-Key':key}:{})}},res=>{
    for(const cookie of res.headers['set-cookie']||[]){const [pair]=cookie.split(';'),at=pair.indexOf('=');cookies.set(pair.slice(0,at),pair.slice(at+1))}
    let text='';res.setEncoding('utf8');res.on('data',part=>text+=part)
    res.on('end',()=>resolve({status:res.statusCode,body:text?JSON.parse(text):null}))
  });req.setTimeout(10000,()=>req.destroy(new Error('Owned rollback HTTP timeout')));req.on('error',reject)
  req.end(body===undefined?undefined:JSON.stringify(body))
})}
let session=await request('/api/v1/auth/session');assert.equal(session.status,200)
session=await request('/api/v1/auth/login',{username:'learner',password:'forgeoj-dev-only'},session.body.csrf)
assert.equal(session.status,200);assert.equal(session.body.authenticated,true)
let history=null
if(previous){
  const original=JSON.parse(await readFile(previous,'utf8'))
  const result=await request(`/api/v1/submissions/${original.submissionId}`)
  assert.equal(result.status,200);assert.equal(result.body.processingStatus,'FINISHED');assert.equal(result.body.verdict,'AC')
  history={submissionId:original.submissionId,verdict:'AC',readViaHttps:true}
}
const marker='rollback-probe-'+randomUUID()
const sourceCode=`// ${marker}\nimport java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); } }`
const created=await request('/api/v1/problems/sum-two-integers/submissions',{language:'JAVA_21',sourceCode},session.body.csrf,randomUUID())
assert.equal(created.status,202)
let latest=await request(`/api/v1/submissions/${created.body.submissionId}`);assert.equal(latest.status,200)
if(mode==='queue'){
  assert.equal(latest.body.processingStatus,'QUEUED')
}else{
  const deadline=Date.now()+120000
  while(latest.body.processingStatus!=='FINISHED' && Date.now()<deadline){
    await new Promise(resolve=>setTimeout(resolve,1500))
    latest=await request(`/api/v1/submissions/${created.body.submissionId}`);assert.equal(latest.status,200)
  }
  assert.equal(latest.body.processingStatus,'FINISHED');assert.equal(latest.body.verdict,'AC')
}
const proof={status:'PASS',submissionId:created.body.submissionId,processingStatus:latest.body.processingStatus,
  verdict:latest.body.verdict,sourceSha256:createHash('sha256').update(sourceCode).digest('hex'),history,
  scope:'owned fixture; actual HTTPS and Worker; no manufactured verdict'}
await writeFile(report,JSON.stringify(proof,null,2));console.log(JSON.stringify(proof))
