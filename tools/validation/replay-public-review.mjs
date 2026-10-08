// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Only unique disposable replay HTTP data; PASSED/AC are obtained from the real Worker.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
const base='http://localhost:5173',root='/api/v1/admin',author='/api/v1/me/authored-problems',checks=[],jobs=[],formal=[],reviews=[]
const password='m4-public-super-changed-password'
const code='import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}'
class Client {
  cookies=new Map();csrf
  async call(path,method='GET',body,status=200,extra={},record=true){
    const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; '),...extra}
    if(method!=='GET'){headers.Origin=base;headers['Content-Type']='application/json';if(this.csrf)headers[this.csrf.headerName]=this.csrf.token}
    const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)})
    for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0],i=pair.indexOf('=');if(/Max-Age=0/i.test(value))this.cookies.delete(pair.slice(0,i));else this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await response.text();assert.equal(response.status,status,`${method} ${path}: ${text}`)
    if(path.startsWith(root)||path.startsWith(author)||path.startsWith('/api/v1/me/public-problems'))assert.match(response.headers.get('cache-control')??'',/no-store/)
    if([400,401,403,404,409,503].includes(status))assert.equal(text,'')
    if(record)checks.push({method,path,status});const result=text?JSON.parse(text):null;if(result?.csrf)this.csrf=result.csrf;return result
  }
  async login(username,admin=false,pass=admin?password:'forgeoj-dev-only'){const p=admin?root:'/api/v1';await this.call(p+'/auth/session');return this.call(p+'/auth/login','POST',{username,password:pass})}
}
if(process.argv[2]){
  assert.equal(process.argv[2],'revoke-reviewer')
  const admin=new Client();await admin.login('m4_super',true)
  const account=(await admin.call(root+'/accounts')).items.find(a=>a.username==='browser_reviewer');assert.ok(account);assert.equal(account.status,'ACTIVE');assert.equal(account.role,'CONTENT_REVIEWER')
  const result=await admin.call(`${root}/accounts/${account.id}/disable`,'POST',{expectedVersion:account.version,reason:'public-review browser revocation acceptance'});assert.equal(result.status,'DISABLED')
  await writeFile('/reports/public-review-browser-revocation.json',JSON.stringify({checkedAt:new Date().toISOString(),allPassed:true,adminId:account.id,username:account.username,beforeVersion:account.version,afterVersion:result.version,checks},null,2)+'\n')
  console.log(JSON.stringify({reviewerDisabled:true,adminId:account.id}));process.exit(0)
}
const owner=new Client(),other=new Client(),reviewer=new Client(),ops=new Client(),superAdmin=new Client()
await owner.login('learner');await other.login('other-learner');await reviewer.login('http_reviewer',true);await ops.login('http_ops',true);await superAdmin.login('m4_super',true)
const decision=reason=>({expectedVersion:0,clientRequestId:randomUUID(),reason})
async function waitValidation(draft,job){
  let result
  for(let i=0;i<240;i++){result=await owner.call(`${author}/${draft}/validations/${job}`,'GET',undefined,200,{},false);if(['FINISHED','SYSTEM_ERROR'].includes(result.processingStatus))break;await new Promise(r=>setTimeout(r,1000))}
  assert.equal(result.processingStatus,'FINISHED');assert.equal(result.validationStatus,'PASSED');assert.equal(result.referenceResult,'ACCEPTED');assert.equal(result.solutionResult,'ACCEPTED')
  jobs.push({id:job,draftId:draft,version:result.draftVersion,processingStatus:result.processingStatus,validationStatus:result.validationStatus});return result
}
async function submitReview(detail){
  const {id,version}=detail.draft,validation=await owner.call(`${author}/${id}/validations`,'POST',{expectedVersion:version,requestId:randomUUID()},202)
  await waitValidation(id,validation.jobId)
  const review=await owner.call(`${author}/${id}/reviews`,'POST',{expectedVersion:version,validationJobId:validation.jobId,requestId:randomUUID()},202)
  reviews.push({id:review.reviewId,draftId:id,validationId:validation.jobId});return review
}
async function approve(review){const body=decision('已检查原作者、授权及冻结双验证'),result=await reviewer.call(`${root}/content-reviews/${review.reviewId}/approve`,'POST',body);assert.equal(result.status,'APPROVED');assert.equal((await reviewer.call(`${root}/content-reviews/${review.reviewId}/approve`,'POST',body)).publishedSlug,result.publishedSlug);return result.publishedSlug}
async function waitFormal(client,queued,verdict){
  let result;for(let i=0;i<240;i++){result=await client.call('/api/v1/submissions/'+queued.submissionId,'GET',undefined,200,{},false);if(['FINISHED','SYSTEM_ERROR'].includes(result.processingStatus))break;await new Promise(r=>setTimeout(r,1000))}
  assert.equal(result.processingStatus,'FINISHED');assert.equal(result.verdict,verdict);formal.push({id:queued.submissionId,verdict});return result
}
async function copy(slug,version,kind){
  const body={expectedVersion:version,revisionKind:kind,clientRequestId:randomUUID()},path=`/api/v1/me/public-problems/${slug}/revisions`
  const detail=await owner.call(path,'POST',body,201);assert.equal((await owner.call(path,'POST',body,201)).draft.id,detail.draft.id)
  assert.equal((await owner.call(`${author}/${detail.draft.id}/validations`)).total,0)
  await other.call(path,'POST',{...body,clientRequestId:randomUUID()},404);return detail
}
// Rejection retains its old frozen version and returns the author's editing permission.
let detail=await owner.call(author,'POST',{title:'M4 原创公共求和验收'},201),id=detail.draft.id
detail.content.metadata={...detail.content.metadata,statement:'两个整数相加。M4_PUBLIC_STATEMENT_SENTINEL',inputDescription:'两个 long 整数',outputDescription:'一行和',samples:[{input:'1 2\n',output:'3\n'},{input:'2 3\n',output:'5\n'}],licenseStatement:'原创，一次性验收许可。'}
detail.content.referenceCode=code+'\n// M4_PRIVATE_REFERENCE_SENTINEL';detail.content.solutionCode=code+'\n// M4_INDEPENDENT_SOLUTION_SENTINEL';detail.content.solutionIdea='读取两个整数并输出和。'
detail=await owner.call(`${author}/${id}`,'PUT',{expectedVersion:detail.draft.version,content:detail.content})
detail=await owner.call(`${author}/${id}/tests`,'PUT',{expectedVersion:detail.draft.version,tests:[{input:'111 222\n',expectedOutput:'333\n'},{input:'-7 9\n',expectedOutput:'2\n'}]})
const first=await submitReview(detail);await reviewer.call(`${root}/content-reviews/${first.reviewId}/reject`,'POST',decision('完善文案后重新验证'))
const history=await owner.call(`${author}/${id}/reviews`);assert.ok(JSON.stringify(history).includes('完善文案后重新验证'))
detail=await owner.call(`${author}/${id}`);detail.content.metadata.statement+=' 完成明确说明。';detail=await owner.call(`${author}/${id}`,'PUT',{expectedVersion:detail.draft.version,content:detail.content})
const initial=await submitReview(detail),pending=await reviewer.call(`${root}/content-reviews/${initial.reviewId}`)
assert.ok(!JSON.stringify(pending).includes('M4_PRIVATE_REFERENCE_SENTINEL'));assert.ok(!JSON.stringify(pending).includes('111 222'))
assert.ok((await reviewer.call(`${root}/content-reviews/${initial.reviewId}/reference`)).sourceCode.includes('M4_PRIVATE_REFERENCE_SENTINEL'))
for(const c of [ops,other])for(const path of ['/content-reviews',`/content-reviews/${initial.reviewId}`,`/content-reviews/${initial.reviewId}/reference`,'/public-problems','/feedback-cases'])await c.call(root+path,'GET',undefined,403)
const recheckBody=decision('真实 Worker 重新验证原冻结快照'),recheck=await reviewer.call(`${root}/content-reviews/${initial.reviewId}/recheck`,'POST',recheckBody,202)
assert.equal((await reviewer.call(`${root}/content-reviews/${initial.reviewId}/recheck`,'POST',recheckBody,202)).jobId,recheck.jobId)
await reviewer.call(`${root}/content-reviews/${initial.reviewId}/approve`,'POST',decision('排队期间不能借用旧验证通过'),409)
await waitValidation(id,recheck.jobId)
let slug=await approve(initial),publicDetail=await owner.call('/api/v1/problems/'+slug)
assert.equal(publicDetail.publicSamples.length,2);assert.equal(publicDetail.attribution.authorName,'learner');assert.ok(!JSON.stringify(publicDetail).includes('M4_PRIVATE_REFERENCE_SENTINEL'))
// Text changes retain the judge; stale or changed bases are contract-tested separately in MySQL tests.
let revision=await copy(slug,1,'TEXT');revision.content.metadata.statement='审核后的文案修订。';revision=await owner.call(`${author}/${revision.draft.id}`,'PUT',{expectedVersion:1,content:revision.content})
const textReview=await submitReview(revision);assert.equal((await reviewer.call(`${root}/content-reviews/${textReview.reviewId}`)).revision.revisionKind,'TEXT');assert.equal(await approve(textReview),slug)
const oldVersion=(await owner.call('/api/v1/problems/'+slug)).judgeVersion;assert.equal(oldVersion,publicDetail.judgeVersion)
// Multiple learners merge feedback, see only their own report, and closed cases never revive.
const feedbackPath='/api/v1/problems/'+slug+'/feedbacks',one={clientRequestId:randomUUID(),category:'AMBIGUITY',body:'M4_OWNER_FEEDBACK_SENTINEL'}
const [f1,f2]=await Promise.all([owner.call(feedbackPath,'POST',one,201),other.call(feedbackPath,'POST',{clientRequestId:randomUUID(),category:'TEST_ERROR',body:'M4_OTHER_FEEDBACK_SENTINEL'},201)])
assert.equal(f1.caseId,f2.caseId);assert.ok(!JSON.stringify(await owner.call(feedbackPath)).includes('M4_OTHER_FEEDBACK_SENTINEL'));assert.equal((await reviewer.call(root+'/feedback-cases/'+f1.caseId)).reports.total,2)
await reviewer.call(`${root}/feedback-cases/${f1.caseId}/close`,'POST',{expectedVersion:1,reason:'合并处理，继续保留题目'})
assert.equal((await owner.call(feedbackPath,'POST',one,201)).caseStatus,'CLOSED');const nextFeedback=await owner.call(feedbackPath,'POST',{...one,clientRequestId:randomUUID(),body:'新的独立问题'},201);assert.notEqual(nextFeedback.caseId,f1.caseId)
// A real AC tied to an assignment remains a historical fact when the old basis is invalidated.
const room=await owner.call('/api/v1/classrooms','POST',{title:'M4 公共纠错验收班级',clientRequestId:randomUUID()},201)
const invite=await owner.call(`/api/v1/classrooms/${room.id}/invite`,'POST',{expectedVersion:room.version,enabled:true})
await other.call('/api/v1/classrooms/join','POST',{inviteCode:invite.inviteCode})
const assignmentRoot=`/api/v1/classrooms/${room.id}/assignments`,assignment=await owner.call(assignmentRoot,'POST',{clientRequestId:randomUUID(),definition:{title:'公共纠错作业',description:'仅一次性验收',deadlineAt:new Date(Date.now()+3600000).toISOString(),acceptExistingAc:false,allowLate:true,solutionPolicy:'AFTER_AC',problemSlugs:[slug]}},201)
await owner.call(`${assignmentRoot}/${assignment.assignment.id}/publish`,'POST',{expectedVersion:1,startsAt:null})
const oldQueued=await other.call(`${assignmentRoot}/${assignment.assignment.id}/problems/${slug}/submissions`,'POST',{clientRequestId:randomUUID(),language:'JAVA_21',sourceCode:code},202);await waitFormal(other,oldQueued,'AC')
assert.equal((await other.call(`${assignmentRoot}/${assignment.assignment.id}`)).problems[0].grade.state,'ON_TIME_AC')
const governed=(await reviewer.call(root+'/public-problems')).items.find(p=>p.slug===slug);assert.equal(governed.version,2)
await reviewer.call(`${root}/public-problems/${governed.id}/archive`,'POST',{expectedVersion:2,reason:'普通下架验收'})
await other.call(`/api/v1/problems/${slug}/submissions`,'POST',{language:'JAVA_21',sourceCode:code},404,{'Idempotency-Key':randomUUID()})
await other.call(`/api/v1/problems/${slug}/self-tests`,'POST',{requestId:randomUUID(),language:'JAVA_21',sourceCode:code,input:'20 25\n'},404)
await reviewer.call(`${root}/public-problems/${governed.id}/restore`,'POST',{expectedVersion:3,reason:'普通恢复验收'})
await reviewer.call(`${root}/public-problems/${governed.id}/invalidate`,'POST',{expectedVersion:4,reason:'严重错误判题依据验收'})
await reviewer.call(`${root}/public-problems/${governed.id}/restore`,'POST',{expectedVersion:5,reason:'不能恢复已作废依据'},409)
const oldStatus=await other.call('/api/v1/submissions/'+oldQueued.submissionId);assert.equal(oldStatus.verdict,'AC');assert.equal(oldStatus.judgeDataWarning,'判题数据存在问题，此历史结果来自作废版本')
assert.equal((await other.call(`${assignmentRoot}/${assignment.assignment.id}`)).problems[0].grade.state,'INVALID')
const teaching=(await owner.call(`${assignmentRoot}/${assignment.assignment.id}/teaching/grades`)).items.find(p=>p.userId===2);assert.equal(teaching.completed,0);assert.equal(teaching.problems[0].state,'INVALID');assert.equal(teaching.problems[0].attempts,1)
revision=await copy(slug,5,'CORRECTION');revision.content.metadata.statement='重新验证并审核的关联修正版。';revision=await owner.call(`${author}/${revision.draft.id}`,'PUT',{expectedVersion:1,content:revision.content})
revision=await owner.call(`${author}/${revision.draft.id}/tests`,'PUT',{expectedVersion:revision.draft.version,tests:[{input:'111 222\n',expectedOutput:'333\n'},{input:'-7 9\n',expectedOutput:'2\n'},{input:'1000 -2\n',expectedOutput:'998\n'}]})
const correction=await submitReview(revision),correctedSlug=await approve(correction);assert.notEqual(correctedSlug,slug)
assert.equal((await other.call('/api/v1/problems/'+correctedSlug)).attribution.correctionOfSlug,slug)
assert.equal((await other.call(`/api/v1/me/problems/${correctedSlug}/solution`)).access,'LOCKED')
const formalBeforeSelf=(await other.call('/api/v1/me/submissions')).total
const self=await other.call(`/api/v1/problems/${correctedSlug}/self-tests`,'POST',{requestId:randomUUID(),language:'JAVA_21',sourceCode:code,input:'20 25\n'},202)
let selfResult
for(let i=0;i<240;i++){selfResult=await other.call('/api/v1/self-tests/'+self.runId,'GET',undefined,200,{},false);if(['FINISHED','SYSTEM_ERROR'].includes(selfResult.run.processingStatus))break;await new Promise(r=>setTimeout(r,1000))}
assert.equal(selfResult.run.processingStatus,'FINISHED');assert.equal(selfResult.run.executionResult,'SUCCESS');assert.equal(selfResult.output,'45\n')
assert.equal((await other.call('/api/v1/me/submissions')).total,formalBeforeSelf);assert.equal((await other.call(`/api/v1/me/problems/${correctedSlug}/solution`)).access,'LOCKED')
const wrong=await other.call(`/api/v1/problems/${correctedSlug}/submissions`,'POST',{language:'JAVA_21',sourceCode:'public class Main {public static void main(String[] a){System.out.println(0);}}'},202,{'Idempotency-Key':randomUUID()});await waitFormal(other,wrong,'WA')
const accepted=await other.call(`/api/v1/problems/${correctedSlug}/submissions`,'POST',{language:'JAVA_21',sourceCode:code},202,{'Idempotency-Key':randomUUID()});await waitFormal(other,accepted,'AC');assert.equal((await other.call(`/api/v1/me/problems/${correctedSlug}/solution`)).access,'AC')
const browserFeedbackPath='/api/v1/problems/'+correctedSlug+'/feedbacks'
const browserFeedback=await owner.call(browserFeedbackPath,'POST',{...one,clientRequestId:randomUUID()},201)
await other.call(browserFeedbackPath,'POST',{clientRequestId:randomUUID(),category:'OTHER',body:'M4_OTHER_FEEDBACK_SENTINEL'},201)
assert.ok(!JSON.stringify(await owner.call(browserFeedbackPath)).includes('M4_OTHER_FEEDBACK_SENTINEL'))
// Leave a fresh immutable pending case for real browser reading/authorization acceptance.
const browserDraft=await copy(correctedSlug,1,'TEXT'),browserReview=await submitReview(browserDraft)
const report={checkedAt:new Date().toISOString(),allPassed:true,checks,jobs,formal,reviews,initialReview:initial.reviewId,recheckId:recheck.jobId,oldSlug:slug,correctedSlug,oldProblemId:governed.id,oldSubmissionId:oldQueued.submissionId,selfTestId:self.runId,classroomId:room.id,assignmentId:assignment.assignment.id,feedbackCaseId:f1.caseId,browserFeedbackCaseId:browserFeedback.caseId,browserReviewId:browserReview.reviewId,browserDraftId:browserDraft.draft.id,copyDoesNotInheritPassed:true,textJudgePreserved:true,oldAcIsolated:true,invalidAssignmentPreservesAttempts:true}
await writeFile('/reports/public-review-http.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({allPassed:true,httpChecks:checks.length,jobs:jobs.length,formal:formal.length,browserReviewId:browserReview.reviewId,correctedSlug}))
