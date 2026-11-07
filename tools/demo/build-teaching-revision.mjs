// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Guarded maintenance for the explicitly owned local demo, not a publication bypass for production.
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createHash} from 'node:crypto'
import {catalog} from '../../content/starter/catalog.mjs'
import {teachingRevision} from '../../content/starter/teaching-revision.mjs'
const hash=v=>createHash('sha256').update(v).digest('hex')
const text=v=>"CONVERT(UNHEX('"+Buffer.from(v).toString('hex')+"') USING utf8mb4)"
const state=JSON.parse(await readFile('/reports/state.json','utf8'))
const imported=JSON.parse(await readFile('/reports/starter-import.json','utf8'))
const proof=JSON.parse(await readFile('/reports/teaching-revision-validation.json','utf8'))
assert.equal(state.Project,process.argv[2]);assert.equal(state.ProductDemo,true)
for(const r of [imported,proof]){assert.equal(r.project,state.Project);assert.equal(r.status,'VERIFIED')}
assert.equal(imported.catalogSha256,hash(JSON.stringify(catalog)))
assert.equal(proof.programs.length,20)
assert.equal(proof.revisionSha256,hash(JSON.stringify(catalog.map(p=>({id:p.id,...teachingRevision(p)})))))
const digest=table=>`(SELECT SHA2(COALESCE(GROUP_CONCAT(row_digest ORDER BY row_digest SEPARATOR '|'),''),256) FROM (${table}) frozen)`
const reviews=digest("SELECT SHA2(CONCAT(r.id,':',r.snapshot_id,':',r.review_status,':',r.version,':',s.snapshot_sha256,':',s.metadata_text,':',s.reference_code,':',s.solution_idea,':',s.solution_code,':',s.test_dataset_sha256),256) row_digest FROM content_review r JOIN content_validation_snapshot s ON s.id=r.snapshot_id")
// Owner history and drafts are preserved, including the ordinary learner; no passwords/tokens are exported.
const histories=digest("SELECT SHA2(CONCAT(id,':',user_id,':',judge_version_id,':',source_code,':',processing_status,':',COALESCE(verdict,'')),256) row_digest FROM submission")
const drafts=digest("SELECT SHA2(CONCAT(user_id,':',problem_id,':',version,':',source_code),256) row_digest FROM user_code_draft")
const sql=['SET NAMES utf8mb4;','SET SESSION group_concat_max_len=16777216;',
 'CREATE TEMPORARY TABLE teaching_guard(ok INT NOT NULL CHECK(ok=1)) ENGINE=InnoDB;',
 'START TRANSACTION;',`SET @reviews=${reviews};`,`SET @history=${histories};`,`SET @drafts=${drafts};`,
 "SELECT p.id INTO @legacy FROM problem p JOIN problem_judge_version j ON j.id=p.current_judge_version_id WHERE p.id=1 AND p.slug='sum-two-integers' AND p.title="+text('两数之和')+" AND p.scope='PUBLIC' AND p.status='ACTIVE' AND j.id=1 AND j.test_dataset_sha256='37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf' FOR UPDATE;",
 'INSERT INTO teaching_guard VALUES(@legacy=1);']
for(const p of catalog){
 const old=imported.items[p.id],entry=proof.programs.find(e=>e.id===p.id),revised=teachingRevision(p)
 assert.match(old.publishedSlug,/^public-[a-f0-9-]{36}$/);assert.match(old.reviewId,/^[a-f0-9-]{36}$/)
 assert.equal(entry.slug,old.publishedSlug);assert.equal(entry.verdict,'AC');assert.match(entry.submissionId,/^[a-f0-9-]{36}$/)
 assert.equal(entry.sourceSha256,hash(revised.sourceCode));assert.equal(entry.ideaSha256,hash(revised.idea))
 assert.ok(Number.isSafeInteger(entry.judgeVersion)&&entry.judgeVersion>0)
 sql.push('SET @pid=NULL; SET @jv=NULL;',
 "SELECT p.id,j.id INTO @pid,@jv FROM problem p JOIN problem_judge_version j ON j.id=p.current_judge_version_id AND j.problem_id=p.id JOIN public_problem_governance g ON g.problem_id=p.id JOIN content_review r ON r.id=g.current_review_id WHERE p.slug='"+old.publishedSlug+"' AND p.scope='PUBLIC' AND p.status='ACTIVE' AND j.version_no="+entry.judgeVersion+" AND g.current_review_id='"+old.reviewId+"' AND r.review_status='APPROVED' AND g.data_invalid=FALSE FOR UPDATE;",
 'INSERT INTO teaching_guard VALUES(@pid IS NOT NULL AND @jv IS NOT NULL);',
 "INSERT INTO teaching_guard SELECT COUNT(*)=1 FROM submission s JOIN user_account u ON u.id=s.user_id WHERE s.id='"+entry.submissionId+"' AND s.problem_id=@pid AND s.judge_version_id=@jv AND u.username='verification-learner' AND s.processing_status='FINISHED' AND s.verdict='AC' AND SHA2(s.source_code,256)='"+entry.sourceSha256+"';",
 "UPDATE official_problem_solution SET idea="+text(revised.idea)+",source_code="+text(revised.sourceCode)+" WHERE judge_version_id=@jv AND language='JAVA_21' AND SHA2(idea,256)='"+hash(p.content.solutionIdea)+"' AND SHA2(source_code,256)='"+hash(p.content.solutionCode)+"';",
 'INSERT INTO teaching_guard VALUES(ROW_COUNT()=1);')
}
sql.push("UPDATE problem SET title="+text('整数相加（A + B）')+" WHERE id=@legacy;",
 "UPDATE cache_epoch SET revision=revision+1 WHERE namespace='public';",
 "UPDATE public_search_version SET data_version=data_version+1 WHERE problem_id=@legacy;",
 'INSERT INTO teaching_guard VALUES(ROW_COUNT()=1);',
 "INSERT INTO public_search_outbox(id,problem_id,data_version,public_epoch) SELECT UUID(),@legacy,data_version,(SELECT revision FROM cache_epoch WHERE namespace='public') FROM public_search_version WHERE problem_id=@legacy;",
 `INSERT INTO teaching_guard VALUES(@reviews=${reviews});`,`INSERT INTO teaching_guard VALUES(@history=${histories});`,`INSERT INTO teaching_guard VALUES(@drafts=${drafts});`,
 'COMMIT;',"SELECT JSON_OBJECT('status','VERIFIED','project','"+state.Project+"','revisedSolutions',20,'officialSolutions',(SELECT COUNT(*) FROM official_problem_solution),'reviewsSha256',@reviews,'historySha256',@history,'draftsSha256',@drafts,'legacyTitle',(SELECT title FROM problem WHERE id=1));")
await writeFile('/reports/teaching-revision.sql',sql.join('\n')+'\n')
console.log('Generated guarded local revision, bound to actual AC and original solution/review hashes.')
