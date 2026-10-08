# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Http','RevokeReviewer','CaptureRuntime','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar) -or (Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-[0-9-]+-[a-f0-9]{8}$'){throw 'Owned disposable replay directory mismatch'}
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$RunDirectory
$compose=Join-Path $PSScriptRoot 'compose.replay.yml'
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned replay Docker command failed'};return $result}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project,'-f',$compose)+$Arguments)}
function Save([string]$Name,[object]$Lines){[IO.File]::WriteAllText((Join-Path $RunDirectory $Name),(@($Lines)-join "`n"),[Text.UTF8Encoding]::new($false))}
function Sql([string]$Statement){$result=$Statement | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Owned fixture SQL export failed'};return $result}
if($Action -eq 'CleanupSnapshot'){
  & (Join-Path $PSScriptRoot 'Verify-M3AssignmentReplay.ps1') -Action CleanupSnapshot -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'assignment-cleanup.json') -Destination (Join-Path $RunDirectory 'public-review-cleanup.json');exit
}
if($Action -eq 'CaptureRuntime'){
  & (Join-Path $PSScriptRoot 'Verify-M3TeacherReplay.ps1') -Action CaptureRuntime -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'teacher-runtime.json') -Destination (Join-Path $RunDirectory 'public-review-runtime.json')
  $jars=@()
  foreach($item in @(@('api','/app/api.jar',$state.ApiHash),@('worker','/app/worker.jar',$state.WorkerHash))){
    $id=(Compose @('ps','-q',$item[0])).Trim();$container=(Docker @('inspect',$id) | ConvertFrom-Json)[0]
    if($container.Config.Labels.'com.docker.compose.project' -ne $state.Project -or @($container.Mounts | Where-Object {$_.Destination -eq $item[1] -and -not $_.RW}).Count -ne 1){throw 'Expected owned read-only JAR mount'}
    $hash=((Compose @('exec','-T',$item[0],'sha256sum',$item[1])) -split '\s+')[0]
    if($hash -ne $item[2]){throw 'Running JAR differs from accepted artifact'}
    $jars+=@{service=$item[0];containerId=$id;path=$item[1];sha256=$hash;readOnly=$true}
  }
  @{capturedAt=(Get-Date -Format o);allMatch=$true;jars=$jars} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $RunDirectory 'public-review-jar-runtime.json') -Encoding utf8
  exit
}
if($Action -eq 'Http'){
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-public-review.mjs') | Out-Host;exit
}
if($Action -eq 'RevokeReviewer'){
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-public-review.mjs','revoke-reviewer') | Out-Host;exit
}
$lines=foreach($name in @('tools/validation/Verify-M4PublicReviewReplay.ps1','tools/validation/replay-public-review.mjs','tools/validation/replay-public-review-audit.mjs','tools/validation/Verify-M3TeacherReplay.ps1','tools/validation/Verify-M3AssignmentReplay.ps1')){(Get-FileHash (Join-Path $repository $name)).Hash.ToLower()+'  '+$name}
Save 'public-review-tool-inputs.sha256' $lines
Save 'public-review-job-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('id',j.id,'snapshotId',j.snapshot_id,'ownerMatches',j.owner_id=s.owner_id,'kind',j.execution_kind,'status',j.processing_status,'validation',j.validation_status,'reference',j.reference_result,'solution',j.solution_result,'version',j.status_version,'attemptCount',j.attempt_count,'leaseCleared',j.lease_owner IS NULL AND j.lease_token IS NULL AND j.lease_expires_at IS NULL,'closedAttempts',(SELECT COUNT(*) FROM content_validation_attempt a WHERE a.job_id=j.id AND a.attempt_status='SUCCEEDED' AND a.finished_at IS NOT NULL),'outboxId',(SELECT o.id FROM outbox_event o WHERE o.aggregate_id=j.id),'published',(SELECT o.published_at IS NOT NULL AND o.failed_at IS NULL FROM outbox_event o WHERE o.aggregate_id=j.id),'payloadBound',(SELECT JSON_LENGTH(o.payload)=4 AND JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.taskId'))=j.id AND JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.snapshotId'))=s.id AND JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.taskType'))='CONTENT_VALIDATE' AND JSON_EXTRACT(o.payload,'$.contractVersion')=1 FROM outbox_event o WHERE o.aggregate_id=j.id),'snapshotHash',s.snapshot_sha256,'datasetHash',s.test_dataset_sha256) FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id ORDER BY j.created_at,j.id;
'@)
Save 'public-review-case-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('id',r.id,'status',r.review_status,'version',r.version,'ownerMatches',r.owner_id=s.owner_id AND r.draft_id=s.draft_id AND r.draft_version=s.draft_version,'snapshotId',r.snapshot_id,'originalJobId',r.validation_job_id,'latestJobId',COALESCE(r.latest_recheck_id,r.validation_job_id),'decision',d.decision,'problemId',d.problem_id,'actor',d.actor_admin_id,'reason',d.reason,'validationPassed',j.processing_status='FINISHED' AND j.validation_status='PASSED' AND j.reference_result='ACCEPTED' AND j.solution_result='ACCEPTED' AND j.snapshot_id=r.snapshot_id) FROM content_review r JOIN content_validation_snapshot s ON s.id=r.snapshot_id JOIN content_validation_job j ON j.id=COALESCE(r.latest_recheck_id,r.validation_job_id) LEFT JOIN public_review_decision d ON d.review_id=r.id ORDER BY r.submitted_at,r.id;
'@)
Save 'public-review-problem-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('id',p.id,'slug',p.slug,'status',p.status,'version',g.version,'author',g.author_id,'invalid',g.data_invalid,'correctionOf',g.correction_of_id,'judgeId',v.id,'datasetHash',v.test_dataset_sha256,'snapshotId',g.snapshot_id,'ownerMatches',g.author_id=s.owner_id,'basisMatches',v.test_dataset_sha256=s.test_dataset_sha256 AND v.java_image_digest=s.java_image_digest AND v.time_limit_ms=s.time_limit_ms AND v.memory_limit_mb=s.memory_limit_mb AND v.output_limit_bytes=s.output_limit_bytes AND v.comparison_rule_version=s.comparison_rule_version AND v.sandbox_policy_version=s.sandbox_policy_version,'testCount',(SELECT COUNT(*) FROM problem_test_case t WHERE t.judge_version_id=v.id),'frozenTests',(SELECT COUNT(*) FROM content_validation_test_case t WHERE t.snapshot_id=s.id),'samples',JSON_LENGTH(p.public_samples_json),'officialSolution',(SELECT COUNT(*) FROM official_problem_solution o WHERE o.judge_version_id=v.id)) FROM public_problem_governance g JOIN problem p ON p.id=g.problem_id JOIN problem_judge_version v ON v.id=p.current_judge_version_id JOIN content_validation_snapshot s ON s.id=g.snapshot_id WHERE g.author_id IS NOT NULL;
'@)
Save 'public-review-revision-facts.jsonl' (Sql "SELECT JSON_OBJECT('draftId',r.draft_id,'problemId',r.problem_id,'ownerMatches',r.owner_id=d.owner_id AND r.owner_id=g.author_id,'kind',r.revision_kind,'expectedVersion',r.expected_version,'draftVersion',d.version,'draftStatus',d.status,'validationJobs',(SELECT COUNT(*) FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id WHERE s.draft_id=d.id)) FROM public_revision_draft r JOIN authored_problem_draft d ON d.id=r.draft_id JOIN public_problem_governance g ON g.problem_id=r.problem_id;")
Save 'public-review-feedback-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',c.id,'problemId',c.problem_id,'status',c.status,'version',c.version,'reports',(SELECT COUNT(*) FROM public_problem_feedback f WHERE f.case_id=c.id),'uniqueUsers',(SELECT COUNT(DISTINCT f.user_id) FROM public_problem_feedback f WHERE f.case_id=c.id),'closedBy',c.resolved_by,'reason',c.resolution) FROM public_problem_feedback_case c;")
Save 'public-review-recheck-facts.jsonl' (Sql "SELECT JSON_OBJECT('reviewId',c.review_id,'jobId',c.job_id,'actor',c.actor_admin_id,'sameSnapshot',j.snapshot_id=r.snapshot_id,'originalJobDifferent',j.id<>r.validation_job_id) FROM public_review_recheck c JOIN content_review r ON r.id=c.review_id JOIN content_validation_job j ON j.id=c.job_id;")
Save 'public-review-self-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',j.id,'status',j.processing_status,'result',j.execution_result,'attemptCount',j.attempt_count,'leaseCleared',j.lease_owner IS NULL AND j.lease_token IS NULL AND j.lease_expires_at IS NULL,'ownerMatches',j.owner_id=s.owner_id,'problemSlug',s.problem_slug,'basisMatches',s.judge_version_id=p.current_judge_version_id AND s.time_limit_ms=v.time_limit_ms AND s.memory_limit_mb=v.memory_limit_mb AND s.output_limit_bytes=LEAST(v.output_limit_bytes,1048576),'closedAttempts',(SELECT COUNT(*) FROM self_test_attempt a WHERE a.job_id=j.id AND a.attempt_status='SUCCEEDED' AND a.finished_at IS NOT NULL),'outboxId',(SELECT id FROM outbox_event o WHERE o.aggregate_id=j.id),'published',(SELECT published_at IS NOT NULL AND failed_at IS NULL FROM outbox_event o WHERE o.aggregate_id=j.id),'outputHash',o.output_sha256) FROM self_test_job j JOIN self_test_snapshot s ON s.id=j.snapshot_id JOIN problem p ON p.id=s.problem_id JOIN problem_judge_version v ON v.id=s.judge_version_id JOIN self_test_output o ON o.job_id=j.id;")
Save 'public-review-audit-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',id,'action',action,'actor',actor_admin_id,'targetId',target_id,'outcome',outcome,'reason',reason,'correlationId',correlation_id) FROM admin_audit_event ORDER BY occurred_at,id;")
Save 'public-review-formal-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',s.id,'status',s.processing_status,'verdict',s.verdict,'taskId',t.id,'taskStatus',t.task_status,'statusVersion',s.status_version,'taskVersion',t.status_version,'attemptCount',t.attempt_count,'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL,'closedAttempts',(SELECT COUNT(*) FROM judge_task_attempt a WHERE a.judge_task_id=t.id AND a.attempt_status='SUCCEEDED' AND a.finished_at IS NOT NULL),'outboxId',(SELECT id FROM outbox_event o WHERE o.aggregate_id=t.id AND o.aggregate_type='JUDGE_TASK'),'published',(SELECT published_at IS NOT NULL AND failed_at IS NULL FROM outbox_event o WHERE o.aggregate_id=t.id AND o.aggregate_type='JUDGE_TASK')) FROM submission s JOIN judge_task t ON t.submission_id=s.id;")
Save 'public-review-queues.tsv' (Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','messages_ready','messages_unacknowledged','--no-table-headers'))
Save 'public-review-api.log' (Compose @('logs','--no-log-prefix','api'))
Save 'public-review-worker.log' (Compose @('logs','--no-log-prefix','worker'))
$denials=0
foreach($table in @('public_problem_governance','public_revision_draft','public_review_decision','public_review_recheck','public_problem_feedback_case','public_problem_feedback')){
  $result="SELECT * FROM $table LIMIT 0;" | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_worker --password="$FORGEOJ_WORKER_DB_PASSWORD" forgeoj' 2>&1
  if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142'){throw 'Expected Worker governance SQL denial missing'};$denials++
}
foreach($table in @('public_revision_draft','public_review_decision','public_review_recheck','public_problem_feedback')){
  foreach($query in @("DELETE FROM $table WHERE 1=0;","UPDATE $table SET client_request_id=client_request_id WHERE 1=0;")){
    $result=$query | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_api --password="$FORGEOJ_API_DB_PASSWORD" forgeoj' 2>&1
    if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142|ERROR 1143'){throw 'Expected API immutable SQL denial missing'};$denials++
  }
}
$baselineDenials=@(
 @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','SELECT id FROM problem_test_case LIMIT 0;','ERROR 1142'),
 @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE problem_judge_version SET time_limit_ms=time_limit_ms WHERE 1=0;','ERROR 1142'),
 @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE official_problem_solution SET judge_version_id=judge_version_id WHERE 1=0;','ERROR 1143'),
 @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','DELETE FROM official_problem_solution WHERE 1=0;','ERROR 1142'),
 @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE content_review SET snapshot_id=snapshot_id WHERE 1=0;','ERROR 1143'),
 @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE content_review SET validation_job_id=validation_job_id WHERE 1=0;','ERROR 1143'),
 @('forgeoj_worker','FORGEOJ_WORKER_DB_PASSWORD','SELECT id FROM admin_account LIMIT 0;','ERROR 1142'),
 @('forgeoj_worker','FORGEOJ_WORKER_DB_PASSWORD','SELECT id FROM classroom LIMIT 0;','ERROR 1142')
)
foreach($denial in $baselineDenials){
 $command='exec mysql -h127.0.0.1 -u'+$denial[0]+' --password="$'+$denial[1]+'" forgeoj'
 $result=$denial[2] | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c $command 2>&1
 if($LASTEXITCODE -eq 0 -or "$result" -notmatch $denial[3]){throw ('Expected existing privilege boundary denial missing: '+$denial[2])}
}
$apiId=Compose @('ps','-q','api');$api=(Docker @('inspect',$apiId) | ConvertFrom-Json)[0]
if(@($api.Mounts | Where-Object {$_.Destination -like '*docker.sock*'}).Count -or @($api.Config.Env | Where-Object {$_ -match '(WORKER_DB|MIGRATOR_PASSWORD|ADMIN_CLI_DB|DOCKER_HOST)'}).Count){throw 'API execution/credential boundary violated'}
@{checkedAt=(Get-Date -Format o);allDenied=$true;denials=$denials;baselineDenials=$baselineDenials.Count;apiBoundary=$true} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'public-review-denials.json') -Encoding utf8
Compose @('exec','-T','frontend','node','/source/tools/validation/replay-public-review-audit.mjs') | Out-Host
