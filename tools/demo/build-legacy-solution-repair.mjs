// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Generates a narrow, transactionally guarded LOCAL SEED repair, never a production publication API.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { legacySumSolution as content } from '../../content/starter/legacy-sum-solution.mjs'
const hash = value => createHash('sha256').update(value).digest('hex')
const hex = value => 'CONVERT(UNHEX(\'' + Buffer.from(value).toString('hex') + '\') USING utf8mb4)'
const state = JSON.parse(await readFile('/reports/state.json', 'utf8'))
const proof = JSON.parse(await readFile('/reports/legacy-sum-solution-validation.json', 'utf8'))
assert.equal(state.Project, process.argv[2]); assert.equal(state.ProductDemo, true)
assert.equal(proof.project, state.Project); assert.equal(proof.status, 'VERIFIED')
assert.equal(proof.contentSha256, hash(JSON.stringify(content)))
assert.equal(proof.programs.length, 2)
assert.notEqual(proof.programs[0].sourceSha256, proof.programs[1].sourceSha256)
for (const entry of proof.programs) {
  assert.equal(entry.verdict, 'AC'); assert.match(entry.submissionId, /^[a-f0-9-]{36}$/)
  assert.match(entry.sourceSha256, /^[a-f0-9]{64}$/)
}
const otherDigest = "(SELECT SHA2(GROUP_CONCAT(CONCAT(judge_version_id,':',SHA2(idea,256),':',SHA2(source_code,256)) ORDER BY judge_version_id SEPARATOR '|'),256) FROM official_problem_solution WHERE judge_version_id<>1)"
const sql = [
  'SET NAMES utf8mb4;', 'SET SESSION group_concat_max_len=100000;',
  'CREATE TEMPORARY TABLE repair_guard(ok INT NOT NULL CHECK(ok=1)) ENGINE=InnoDB;',
  'START TRANSACTION;',
  "SELECT j.id INTO @version FROM problem p JOIN problem_judge_version j ON j.id=p.current_judge_version_id AND j.problem_id=p.id WHERE p.id=1 AND p.slug='sum-two-integers' AND p.status='ACTIVE' AND p.scope='PUBLIC' AND j.id=1 AND j.version_no=1 AND j.test_dataset_sha256='" + content.datasetSha256 + "' FOR UPDATE;",
  'INSERT INTO repair_guard VALUES(@version=1);',
  'SET @other_before=' + otherDigest + ';',
  ...proof.programs.map(entry => "INSERT INTO repair_guard SELECT COUNT(*)=1 FROM submission s JOIN user_account u ON u.id=s.user_id WHERE s.id='" + entry.submissionId + "' AND u.username='verification-learner' AND s.problem_id=1 AND s.judge_version_id=1 AND s.processing_status='FINISHED' AND s.verdict='AC' AND SHA2(s.source_code,256)='" + entry.sourceSha256 + "';"),
  "INSERT INTO official_problem_solution(judge_version_id,idea,language,source_code) SELECT 1," + hex(content.idea) + ",'JAVA_21'," + hex(content.sourceCode) + ' WHERE NOT EXISTS(SELECT 1 FROM official_problem_solution WHERE judge_version_id=1);',
  'SET @inserted=ROW_COUNT();',
  "INSERT INTO repair_guard SELECT COUNT(*)=1 FROM official_problem_solution WHERE judge_version_id=1 AND SHA2(idea,256)='" + hash(content.idea) + "' AND SHA2(source_code,256)='" + hash(content.sourceCode) + "';",
  'INSERT INTO repair_guard VALUES(@other_before=' + otherDigest + ');',
  'COMMIT;',
  "SELECT JSON_OBJECT('status','VERIFIED','project','" + state.Project + "','inserted',@inserted,'officialSolutions',(SELECT COUNT(*) FROM official_problem_solution),'otherSolutionsSha256',@other_before,'judgeVersion',1,'sourceSha256','" + hash(content.sourceCode) + "');",
]
await writeFile('/reports/legacy-sum-solution-repair.sql', sql.join('\n') + '\n')
console.log('Generated local seed repair, bound to two actual AC submissions and the unchanged dataset.')
