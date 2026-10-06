// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
const json=async name=>JSON.parse(await readFile(`/reports/${name}`,'utf8'))
const facts=(await readFile('/reports/classroom-database.jsonl','utf8')).split(/\r?\n/).filter(Boolean).map(s=>JSON.parse(s))
const http=await json('classroom-http.json'),browser=await json('classroom-browser.json'),privileges=await json('privilege-denials.json')
assert.ok(http.allPassed&&http.scopeRoles&&http.leftRestoredAsMember&&http.removedSelfRestoreDenied&&http.transferAccepted&&http.archivedOwnerRestored)
assert.equal(http.formalBefore,http.formalAfter)
assert.ok(browser.allPassed&&browser.created&&browser.joined&&browser.assistantAssigned&&browser.leftRejoinedAsMember&&browser.removedBlocked&&browser.ownerRestored&&browser.transferAccepted&&browser.archivedRestored)
const row=facts.find(f=>f.id===http.classroomId);assert.ok(row);assert.equal(row.ownerId,http.finalOwnerId);assert.equal(row.version,http.finalVersion)
const observed=facts.find(f=>f.id===browser.classroomId);assert.ok(observed);assert.equal(observed.ownerId,browser.finalOwnerId);assert.equal(observed.status,browser.finalStatus);assert.equal(observed.version,browser.finalVersion)
for(const f of facts) {assert.equal(f.activeOwners,1);assert.ok(f.ownerMatches===true||f.ownerMatches===1)}
assert.ok(privileges.allDenied);assert.equal(privileges.classroomDenials,9);assert.equal(privileges.denials,38)
const logs=(await readFile('/reports/api.log','utf8'))+(await readFile('/reports/worker.log','utf8'))
for(const code of await json('classroom-invite-sentinels.json')) assert.ok(!logs.includes(code),'Invite leaked to logs')
const report={checkedAt:new Date().toISOString(),classrooms:facts.length,httpChecks:http.checks.length,multiAccountBrowser:true,allOwnerInvariants:true,privilegeDenials:privileges.denials,classroomDenials:9,inviteLogsClean:true,formalSubmissionUnchanged:true,httpClassroomId:http.classroomId,browserClassroomId:browser.classroomId,allPassed:true}
await writeFile('/reports/classroom-audit.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report))
