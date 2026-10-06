// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Only the owned disposable replay fixture. Real owner HTTP, never synthetic membership SQL.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
const { classroomId } = JSON.parse(await readFile('/reports/private-problems-http.json','utf8'))
const base = 'http://localhost:5173', cookies = new Map()
let csrf
async function request(path, method = 'GET', body, expected = 200) {
  const headers = { Cookie:[...cookies].map(([k,v]) => `${k}=${v}`).join('; ') }
  if (method !== 'GET') { headers.Origin=base;headers['Content-Type']='application/json';if(csrf)headers[csrf.headerName]=csrf.token }
  const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
  for (const value of response.headers.getSetCookie()) { const pair=value.split(';')[0],i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1)) }
  assert.equal(response.status,expected)
  const text=await response.text();return text?JSON.parse(text):null
}
csrf=(await request('/api/v1/auth/session')).csrf
csrf=(await request('/api/v1/auth/login','POST',{username:'learner',password:'forgeoj-dev-only'})).csrf
const path=`/api/v1/classrooms/${classroomId}`, room=await request(path)
await request(path+'/members/2/remove','POST',{expectedVersion:room.version})
const after=await request(path)
assert.equal(after.members.find(m=>m.userId===2).status,'REMOVED')
await writeFile('/reports/private-browser-revocation.json',JSON.stringify({classroomId,memberUserId:2,status:'REMOVED',beforeVersion:room.version,afterVersion:after.version,actualOwnerHttp:true},null,2)+'\n')
console.log('Owned fixture member revoked through owner HTTP.')
