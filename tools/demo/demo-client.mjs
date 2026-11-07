// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
const base='http://localhost:5173'
export class Client {
 cookies=new Map();csrf;username;password;admin
 constructor(username,password,admin=false){this.username=username;this.password=password;this.admin=admin}
 async call(path,method='GET',body,status=200,extra={}){
  const headers={Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...extra}
  if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
  const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)})
  for(const value of response.headers.getSetCookie()){
   const pair=value.split(';')[0],at=pair.indexOf('=')
   if(/Max-Age=0/i.test(value))this.cookies.delete(pair.slice(0,at));else this.cookies.set(pair.slice(0,at),pair.slice(at+1))
  }
  const text=await response.text()
  assert.equal(response.status,status,method+' '+path+' returned '+response.status)
  const result=text?JSON.parse(text):null
  if(result?.csrf)this.csrf=result.csrf
  return result
 }
 async login(){
  const root=this.admin?'/api/v1/admin':'/api/v1'
  const session=await this.call(root+'/auth/session')
  if(session.authenticated)return session
  return this.call(root+'/auth/login','POST',{username:this.username,password:this.password})
 }
}
