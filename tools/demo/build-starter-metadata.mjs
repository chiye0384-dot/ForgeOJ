// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Generates SQL only for an explicitly owned demo; never connects to a database.
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createHash} from 'node:crypto'
import {catalog} from '../../content/starter/catalog.mjs'
const project=process.argv[2]
assert.match(project??'',/^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$/)
const state=JSON.parse(await readFile('/reports/state.json','utf8'))
const receipt=JSON.parse(await readFile('/reports/starter-import.json','utf8'))
assert.equal(state.Project,project);assert.equal(receipt.project,project)
assert.equal(state.ProductDemo,true)
assert.equal(receipt.status,'VERIFIED')
assert.equal(receipt.catalogSha256,createHash('sha256').update(JSON.stringify(catalog)).digest('hex'))
const text=s=>'CONVERT(0x'+Buffer.from(s,'utf8').toString('hex')+' USING utf8mb4)'
const list='0b097700-2c02-4ad4-9dde-2c74988e1955'
const bindings=catalog.map(p=>{
 const e=receipt.items[p.id]
 assert.match(e.publishedSlug,/^public-[a-f0-9-]{36}$/);assert.match(e.reviewId,/^[a-f0-9-]{36}$/)
 return "(p.slug='"+e.publishedSlug+"' AND g.current_review_id='"+e.reviewId+"')"
}).join(' OR ')
const safe=" FROM problem p JOIN public_problem_governance g ON g.problem_id=p.id JOIN content_review r ON r.id=g.current_review_id WHERE p.scope='PUBLIC' AND p.status='ACTIVE' AND g.author_id=1 AND r.review_status='APPROVED' AND ("+bindings+")"
const sql=[
 'START TRANSACTION;',
 'CREATE TEMPORARY TABLE starter_assert(ok TINYINT NOT NULL CHECK(ok=1));',
 'INSERT INTO starter_assert SELECT IF(COUNT(*)=20,1,0)'+safe+';',
 "INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'verification-learner',password_hash,'ACTIVE' FROM user_account WHERE id=1;",
 "INSERT INTO starter_assert SELECT IF(COUNT(*)=1,1,0) FROM user_account WHERE id=2 AND username='verification-learner';",
 "INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2);",
 "INSERT INTO official_problem_list(id,title,description) VALUES('"+list+"',"+text('Java 入门 20 题')+','+text('从输入输出到数组、字符串、二分和前缀和，按编号练习。')+') ON DUPLICATE KEY UPDATE title=VALUES(title),description=VALUES(description);',
]
for(const [i,p] of catalog.entries()){
 const e=receipt.items[p.id],where="p.slug='"+e.publishedSlug+"' AND g.current_review_id='"+e.reviewId+"' AND g.author_id=1 AND p.scope='PUBLIC' AND p.status='ACTIVE'"
 sql.push("UPDATE problem p JOIN public_problem_governance g ON g.problem_id=p.id SET p.difficulty='"+p.difficulty+"' WHERE "+where+';')
 for(const tag of ['入门',p.topic])sql.push('INSERT IGNORE INTO problem_tag(problem_id,tag) SELECT p.id,'+text(tag)+' FROM problem p JOIN public_problem_governance g ON g.problem_id=p.id WHERE '+where+';')
 sql.push("INSERT INTO official_problem_list_item(id,list_id,problem_id,position) SELECT UUID(),'"+list+"',p.id,"+(i+1)+" FROM problem p JOIN public_problem_governance g ON g.problem_id=p.id WHERE "+where+" AND NOT EXISTS(SELECT 1 FROM official_problem_list_item x WHERE x.list_id='"+list+"' AND x.problem_id=p.id);")
}
sql.push(
 "UPDATE cache_epoch SET revision=revision+1 WHERE namespace='public';",
 "UPDATE public_search_version v JOIN problem p ON p.id=v.problem_id JOIN public_problem_governance g ON g.problem_id=p.id SET v.data_version=v.data_version+1 WHERE ("+bindings+");",
 "INSERT INTO public_search_outbox(id,problem_id,data_version,public_epoch) SELECT UUID(),p.id,v.data_version,(SELECT revision FROM cache_epoch WHERE namespace='public') FROM problem p JOIN public_problem_governance g ON g.problem_id=p.id JOIN public_search_version v ON v.problem_id=p.id WHERE ("+bindings+");",
 'COMMIT;',
 "SELECT JSON_OBJECT('project','"+project+"','published',COUNT(*),'classified',SUM(p.difficulty IS NOT NULL),'listItems',(SELECT COUNT(*) FROM official_problem_list_item WHERE list_id='"+list+"'))"+safe+';'
)
await writeFile('/reports/starter-metadata.sql',sql.join('\n')+'\n')
console.log(JSON.stringify({project,statements:sql.length,listId:list}))
