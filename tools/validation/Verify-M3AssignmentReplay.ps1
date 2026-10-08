# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Setup','Queue','Finish','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if((Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-[0-9-]+-[a-f0-9]{8}$'){throw 'Owned replay directory mismatch'}
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$RunDirectory
$compose=Join-Path $PSScriptRoot 'compose.replay.yml'
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned replay Docker command failed'};return $result}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project,'-f',$compose)+$Arguments)}
function Save([string]$Name,[object]$Lines){[IO.File]::WriteAllText((Join-Path $RunDirectory $Name),(@($Lines)-join "`n"),[Text.UTF8Encoding]::new($false))}
function Sql([string]$Statement){$result=$Statement | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Owned fixture SQL failed'};return $result}
if($Action -eq 'CleanupSnapshot'){
  $facts=@{checkedAt=(Get-Date -Format o)}
  $facts.ownedContainers=@(Docker @('ps','-aq','--filter',"label=com.docker.compose.project=$($state.Project)")).Count
  $facts.ownedVolumes=@(Docker @('volume','ls','-q','--filter',"label=com.docker.compose.project=$($state.Project)")).Count
  $facts.ownedNetworks=@(Docker @('network','ls','-q','--filter',"label=com.docker.compose.project=$($state.Project)")).Count
  $facts.ownedImages=@(Docker @('image','ls','-q',"$($state.Project)-worker")).Count
  $facts.builders=@(Docker @('ps','-aq','--filter','name=forgeoj-linux-')).Count
  $facts.testcontainers=@(Docker @('ps','-aq','--filter','label=org.testcontainers=true')).Count
  $facts.managedSandboxes=@(Docker @('ps','-aq','--filter','label=com.forgeoj.managed=true')).Count
  if(@($facts.GetEnumerator() | Where-Object {$_.Key -ne 'checkedAt' -and $_.Value -ne 0}).Count){throw 'Test resources remain'}
  $facts | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'assignment-cleanup.json') -Encoding utf8
  exit
}
if($Action -eq 'Queue'){Compose @('stop','-t','10','worker') | Out-Host}
if($Action -eq 'Finish'){
  $report=Get-Content (Join-Path $RunDirectory 'assignments-http.json') -Raw | ConvertFrom-Json
  if([DateTimeOffset]::UtcNow -le [DateTimeOffset]::Parse($report.hard.deadlineAt)){throw 'Restart only after actual deadline'}
  Compose @('up','--detach','--no-recreate','worker') | Out-Host
}
if($Action -in @('Setup','Queue','Finish')){Compose @('exec','-T','frontend','node','/source/tools/validation/replay-assignments.mjs',$Action.ToLower()) | Out-Host;exit}
Save 'assignment-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('id',a.id,'classroomId',a.classroom_id,'status',a.status,'version',a.version,'deadlineAt',a.deadline_at,'startedAt',a.started_at,'endedAt',a.ended_at,'reason',a.close_reason,
 'participants',(SELECT COUNT(*) FROM assignment_participant m WHERE m.assignment_id=a.id),'frozenProblems',(SELECT COUNT(*) FROM assignment_problem p WHERE p.assignment_id=a.id),
 'precompleted',(SELECT COUNT(*) FROM assignment_precompletion p WHERE p.assignment_id=a.id),'attempts',(SELECT COUNT(*) FROM assignment_attempt t WHERE t.assignment_id=a.id)) FROM classroom_assignment a ORDER BY a.created_at,a.id;
'@)
Save 'assignment-attempt-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('submissionId',s.id,'assignmentId',a.id,'userId',s.user_id,'acceptedAt',x.accepted_at,'finishedAt',s.finished_at,'deadlineAt',a.deadline_at,'judgeVersionId',s.judge_version_id,
 'status',s.processing_status,'verdict',s.verdict,'taskStatus',t.task_status,'attemptCount',t.attempt_count,'bindingsMatch',s.problem_id=x.problem_id AND s.judge_version_id=x.judge_version_id AND s.user_id=x.user_id,
 'resourcesMatch',s.time_limit_ms=v.time_limit_ms AND s.memory_limit_mb=v.memory_limit_mb AND s.output_limit_bytes=v.output_limit_bytes AND s.test_dataset_sha256=v.test_dataset_sha256,
 'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL,
 'attempts',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',j.id,'status',j.attempt_status,'finished',j.finished_at IS NOT NULL)) FROM judge_task_attempt j WHERE j.judge_task_id=t.id),JSON_ARRAY()),
 'events',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('published',o.published_at IS NOT NULL,'failed',o.failed_at IS NOT NULL)) FROM outbox_event o WHERE o.aggregate_id=t.id),JSON_ARRAY()))
FROM assignment_attempt x JOIN classroom_assignment a ON a.id=x.assignment_id JOIN submission s ON s.id=x.submission_id JOIN judge_task t ON t.submission_id=s.id JOIN problem_judge_version v ON v.id=x.judge_version_id ORDER BY x.accepted_at,s.id;
'@)
Save 'assignment-pre-facts.jsonl' (Sql "SELECT JSON_OBJECT('assignmentId',p.assignment_id,'userId',p.user_id,'submissionId',s.id,'realOwnerVersionAc',s.user_id=p.user_id AND s.problem_id=p.problem_id AND s.judge_version_id=p.judge_version_id AND s.processing_status='FINISHED' AND s.verdict='AC') FROM assignment_precompletion p JOIN submission s ON s.id=p.submission_id ORDER BY p.assignment_id,p.user_id;")
Save 'assignment-self-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',s.run_id,'assignmentId',s.assignment_id,'userId',s.user_id,'status',j.processing_status,'result',j.execution_result,'basisMatches',v.owner_id=s.user_id AND v.problem_id=s.problem_id AND v.judge_version_id=s.judge_version_id,'outputSha256',o.output_sha256) FROM assignment_self_test s JOIN self_test_job j ON j.id=s.run_id JOIN self_test_snapshot v ON v.id=j.snapshot_id LEFT JOIN self_test_output o ON o.job_id=j.id ORDER BY s.run_id;")
Save 'assignment-queues.tsv' (Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','messages_ready','messages_unacknowledged','--no-table-headers'))
Save 'assignment-api.log' (Compose @('logs','--no-log-prefix','api'))
Save 'assignment-worker.log' (Compose @('logs','--no-log-prefix','worker'))
$denials=@()
foreach($table in @('classroom_assignment','assignment_problem','assignment_draft_problem','assignment_participant','assignment_precompletion','assignment_attempt','assignment_self_test','assignment_policy_fence')){$denials+=,@('forgeoj_worker','FORGEOJ_WORKER_DB_PASSWORD',"SELECT * FROM $table LIMIT 0;",'ERROR 1142')}
foreach($table in @('classroom_assignment','assignment_problem','assignment_participant','assignment_precompletion','assignment_attempt','assignment_self_test')){$denials+=,@('forgeoj_api','FORGEOJ_API_DB_PASSWORD',"DELETE FROM $table WHERE 1=0;",'ERROR 1142')}
$denials+=,@('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE classroom_assignment SET created_by=created_by WHERE 1=0;','ERROR 1143')
$denials+=,@('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE assignment_problem SET metadata_text=metadata_text WHERE 1=0;','ERROR 1142')
$denials+=,@('forgeoj_api','FORGEOJ_API_DB_PASSWORD','UPDATE assignment_self_test SET user_id=user_id WHERE 1=0;','ERROR 1142')
foreach($denial in $denials){$command='exec mysql -h127.0.0.1 -u'+$denial[0]+' --password="$'+$denial[1]+'" forgeoj';$result=$denial[2] | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c $command 2>&1;if($LASTEXITCODE -eq 0 -or "$result" -notmatch $denial[3]){throw 'Expected assignment database denial missing'}}
@{checkedAt=(Get-Date -Format o);allDenied=$true;denials=$denials.Count} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'assignment-denials.json') -Encoding utf8
$apiId=Compose @('ps','-q','api');$apiConfig=(Docker @('inspect',$apiId) | ConvertFrom-Json)[0]
if(@($apiConfig.Mounts | Where-Object {$_.Destination -like '*docker.sock*'}).Count -or @($apiConfig.Config.Env | Where-Object {$_ -match '(WORKER_DB|MIGRATOR_PASSWORD|DOCKER_HOST)'}).Count){throw 'API execution/credential boundary violated'}
Compose @('exec','-T','frontend','node','/source/tools/validation/replay-assignment-audit.mjs') | Out-Host
