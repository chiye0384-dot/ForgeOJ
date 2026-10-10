// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Inspect and acknowledge only confirmed terminal events from the owned disposable search DLQ.
import assert from 'node:assert/strict'
import {writeFile} from 'node:fs/promises'
const allowed=new Set(JSON.parse(process.argv[2])),inspected=[]
const url='http://rabbitmq:15672/api/queues/%2Fforgeoj/forgeoj.search.public.dlq/get'
const headers={'Content-Type':'application/json',Authorization:'Basic '+Buffer.from('forgeoj:m1-e2e-rabbit-test-secret').toString('base64')}
for(let batch=0;batch<20;batch++){
  // Inspect without removing first; an unexpected binding/payload must remain in the queue.
  const response=await fetch(url,{method:'POST',headers,body:JSON.stringify({count:20,ackmode:'ack_requeue_true',encoding:'auto'}),signal:AbortSignal.timeout(5000)})
  assert.equal(response.status,200);const messages=await response.json();if(!messages.length)break
  for(const m of messages){const p=JSON.parse(m.payload);assert.deepEqual(Object.keys(p).sort(),['dataVersion','eventId','problemId','type']);assert.ok(allowed.has(p.eventId),'Dead event lacks confirmed MySQL terminal fact');assert.equal(p.type,'PUBLIC_SEARCH_CHANGED');assert.equal(m.properties.delivery_mode,2)}
  const acked=await fetch(url,{method:'POST',headers,body:JSON.stringify({count:messages.length,ackmode:'ack_requeue_false',encoding:'auto'}),signal:AbortSignal.timeout(5000)});assert.equal(acked.status,200)
  for(const m of await acked.json()){const p=JSON.parse(m.payload);assert.ok(allowed.has(p.eventId));inspected.push({eventId:p.eventId,problemId:p.problemId,dataVersion:p.dataVersion,durable:m.properties.delivery_mode===2,confirmedBeforeInspection:true})}
}
await writeFile('/reports/search-dead-inspection.json',JSON.stringify({inspected,checkedAt:new Date().toISOString()},null,2)+'\n');console.log(JSON.stringify({inspected:inspected.length}))
