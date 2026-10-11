// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Disposable diagnostic only. Fetch bounded identifier-only messages and requeue all copies.
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createHash} from 'node:crypto'
const read=async p=>readFile('/reports/'+p,'utf8')
const state=JSON.parse((await read('state.json')).replace(/^\uFEFF/,''))
assert.equal(state.Project,process.argv[2]);assert.match(state.Project,/^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$/)
const events=(await read('monitoring-dead-outbox.jsonl')).trim().split(/\r?\n/).map(l=>JSON.parse(l))
const queues=JSON.parse(await read('monitoring-queues-before-dlq.json'))
const kinds={
 'forgeoj.judge.submission.dead.v1':{type:'JUDGE_TASK_DEAD_LETTERED',routing:'judge.submission.dead.v1',taskTypes:['JUDGE_SUBMISSION'],binding:'submissionId'},
 'forgeoj.content.validation.dead.v1':{type:'CONTENT_VALIDATION_DEAD_LETTERED',routing:'content.validation.dead.v1',taskTypes:['CONTENT_VALIDATE'],binding:'snapshotId'},
 'forgeoj.judge.self-test.dead.v1':{type:'SELF_TEST_DEAD_LETTERED',routing:'judge.self-test.dead.v1',taskTypes:['SELF_TEST'],binding:'snapshotId'}
}
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=s=>createHash('sha256').update(s).digest('hex')
function contract(payload,kind){assert.deepEqual(Object.keys(payload).sort(),['contractVersion','taskId','taskType',kind.binding].sort());assert.equal(payload.contractVersion,1);assert.match(payload.taskId,uuid);assert.match(payload[kind.binding],uuid);assert.ok(kind.taskTypes.includes(payload.taskType))}
for(const e of events){const kind=Object.values(kinds).find(k=>k.type===e.type);assert.ok(kind);assert.match(e.id,uuid);assert.equal(e.published,true);assert.equal(e.failed,false);contract(e.payload,kind);assert.equal(e.taskId,e.payload.taskId)}
assert.equal(new Set(events.map(e=>e.id)).size,events.length)
const authorization='Basic '+Buffer.from('forgeoj:m1-e2e-rabbit-test-secret').toString('base64')
const facts=[]
for(const [name,kind]of Object.entries(kinds)){
 const q=queues.find(q=>q.name===name);assert.ok(q);assert.equal(q.messages_unacknowledged,0);assert.ok(Number.isInteger(q.messages_ready)&&q.messages_ready>0&&q.messages_ready<=100)
 const expected=events.filter(e=>e.type===kind.type)
 const response=await fetch('http://rabbitmq:15672/api/queues/%2Fforgeoj/'+encodeURIComponent(name)+'/get',{method:'POST',headers:{Authorization:authorization,'Content-Type':'application/json'},body:JSON.stringify({count:q.messages_ready,ackmode:'ack_requeue_true',encoding:'auto',truncate:1024}),signal:AbortSignal.timeout(15000)})
 assert.equal(response.status,200);const rows=await response.json();assert.equal(rows.length,q.messages_ready)
 const messages=rows.map(m=>{
  assert.equal(m.payload_encoding,'string');assert.equal(Buffer.byteLength(m.payload),m.payload_bytes);assert.ok(m.payload_bytes<=1024);assert.equal(m.exchange,'forgeoj.judge');assert.equal(m.routing_key,kind.routing);assert.equal(m.properties.delivery_mode,2);assert.equal(m.properties.content_type,'application/json')
  const payload=JSON.parse(m.payload);contract(payload,kind)
  const id=m.properties.message_id;assert.match(id,uuid);const e=expected.find(e=>e.id===id);assert.ok(e,'Unknown original dead-letter event');assert.deepEqual(payload,e.payload)
  return {id,payload,payloadJson:m.payload,payloadSha256:sha(m.payload),payloadBytes:m.payload_bytes,exchange:m.exchange,routingKey:m.routing_key,deliveryMode:m.properties.delivery_mode}
 })
 const observed=[...new Set(messages.map(m=>m.id))].sort();assert.deepEqual(observed,expected.map(e=>e.id).sort(),'Every published original event must still have retained copies')
 facts.push({name,observedReady:q.messages_ready,observedUnacked:q.messages_unacknowledged,uniqueEventIds:observed,messages})
}
const proof={project:state.Project,checkedAt:new Date().toISOString(),ackmode:'ack_requeue_true',allPassed:true,uniqueEvents:events.length,messageCopies:facts.reduce((n,q)=>n+q.messages.length,0),queues:facts}
await writeFile('/reports/monitoring-dead-letter-copies.json',JSON.stringify(proof,null,2)+'\n')
console.log(JSON.stringify({allPassed:true,uniqueEvents:proof.uniqueEvents,messageCopies:proof.messageCopies,requeued:true}))
