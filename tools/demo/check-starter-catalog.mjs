// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { catalog } from '../../content/starter/catalog.mjs'

const root=resolve(fileURLToPath(new URL('../..',import.meta.url)))
const output=join(root,'target','starter-check-'+Date.now())
await mkdir(output,{recursive:true})
const report={checkedAt:new Date().toISOString(),status:'IN_PROGRESS',catalogSha256:createHash('sha256').update(JSON.stringify(catalog)).digest('hex'),problems:[]}
const save=()=>writeFile(join(output,'verification.json'),JSON.stringify(report,null,2)+'\n')
console.log('Catalog verification: '+output)
await save()
try{
 for(const p of catalog){
  assert.ok(p.tests.length>=6)
  assert.notEqual(p.content.referenceCode,p.content.solutionCode)
  const result={id:p.id,title:p.content.metadata.title,cases:p.tests.length,implementations:[]}
  report.problems.push(result)
  for(const [name,code] of [['reference',p.content.referenceCode],['solution',p.content.solutionCode]]){
   const dir=join(output,p.id,name);await mkdir(dir,{recursive:true});await writeFile(join(dir,'Main.java'),code)
   const compile=spawnSync('javac',['-encoding','UTF-8','--release','21','Main.java'],{cwd:dir,encoding:'utf8',timeout:20000,maxBuffer:1024*1024})
   assert.equal(compile.status,0,p.id+' '+name+' compile: '+compile.stderr)
   let passed=0
   for(const [index,test] of p.tests.entries()){
    const run=spawnSync('java',['-Xmx128m','Main'],{cwd:dir,input:test.input,encoding:'utf8',timeout:8000,maxBuffer:2*1024*1024})
    assert.equal(run.status,0,p.id+' '+name+' case '+index+': '+run.stderr)
    assert.equal(run.stdout.replaceAll('\r\n','\n'),test.expectedOutput,p.id+' '+name+' case '+index)
    passed++
   }
   result.implementations.push({name,passed})
  }
  await save()
  console.log(p.content.metadata.title+': '+p.tests.length+' cases x 2 PASS')
 }
 report.status='VERIFIED';report.executions=report.problems.reduce((n,p)=>n+2*p.cases,0);await save()
 console.log(JSON.stringify({status:report.status,problems:catalog.length,executions:report.executions,output}))
}catch(error){report.status='FAILED';report.error=String(error);await save();throw error}
