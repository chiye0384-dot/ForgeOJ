# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Setup','Revoke','Archive','CaptureRuntime','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar) -or (Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-[0-9-]+-[a-f0-9]{8}$'){throw 'Owned replay directory mismatch'}
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$RunDirectory
$compose=Join-Path $PSScriptRoot 'compose.replay.yml'
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned replay Docker command failed'};return $result}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project,'-f',$compose)+$Arguments)}
function Save([string]$Name,[object]$Lines){[IO.File]::WriteAllText((Join-Path $RunDirectory $Name),(@($Lines)-join "`n"),[Text.UTF8Encoding]::new($false))}
function Sql([string]$Statement){$result=$Statement | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Owned fixture SQL failed'};return $result}
if($Action -eq 'CleanupSnapshot'){
  & (Join-Path $PSScriptRoot 'Verify-M3AssignmentReplay.ps1') -Action CleanupSnapshot -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'assignment-cleanup.json') -Destination (Join-Path $RunDirectory 'teacher-cleanup.json')
  exit
}
if($Action -in @('Setup','Revoke','Archive')){
  if($Action -eq 'Setup'){
    $lines=foreach($name in @('tools/validation/Verify-M3TeacherReplay.ps1','tools/validation/replay-teacher-records.mjs','tools/validation/replay-teacher-audit.mjs')){(Get-FileHash (Join-Path $repository $name)).Hash.ToLower()+'  '+$name}
    Save 'teacher-tool-inputs.sha256' $lines
  }
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-teacher-records.mjs',$Action.ToLower()) | Out-Host
  exit
}
if($Action -eq 'CaptureRuntime'){
  $frontendId=Compose @('ps','-q','frontend');$frontend=(Docker @('inspect',$frontendId) | ConvertFrom-Json)[0]
  if(@($frontend.Mounts | Where-Object {$_.Destination -eq '/source' -and -not $_.RW}).Count -ne 1){throw 'Expected read-only browser runtime source mount'}
  $capture=@'
const fs=require('node:fs'),crypto=require('node:crypto');const result=[];function walk(p){for(const e of fs.readdirSync('/source/'+p,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){if(['node_modules','dist','target','.git','.idea'].includes(e.name)||e.name.endsWith('.local')||e.name.startsWith('.env'))continue;const f=p+'/'+e.name;if(e.isDirectory())walk(f);else result.push(crypto.createHash('sha256').update(fs.readFileSync('/source/'+f)).digest('hex')+'  '+f)}}walk('frontend');result.sort();fs.writeFileSync('/reports/frontend-runtime.sha256',result.join('\n')+'\n');fs.writeFileSync('/reports/teacher-runtime.json',JSON.stringify({capturedAt:new Date().toISOString(),readOnlySourceMount:true,files:result.length})+'\n');console.log(JSON.stringify({capturedInputs:result.length}));
'@
  Compose @('exec','-T','frontend','node','-e',$capture) | Out-Host
  exit
}
Save 'teacher-attempt-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('submissionId',s.id,'assignmentId',a.assignment_id,'userId',s.user_id,'ordinal',p.ordinal,'status',s.processing_status,'verdict',s.verdict,'sourceSha256',s.source_sha256,'sourceDigestMatches',SHA2(s.source_code,256)=s.source_sha256,
 'bindingsMatch',s.user_id=a.user_id AND s.problem_id=a.problem_id AND s.judge_version_id=a.judge_version_id,'resourcesMatch',s.time_limit_ms=v.time_limit_ms AND s.memory_limit_mb=v.memory_limit_mb AND s.test_dataset_sha256=v.test_dataset_sha256,'taskStatus',t.task_status,'attemptCount',t.attempt_count,
 'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL,'attempts',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',j.id,'status',j.attempt_status,'finished',j.finished_at IS NOT NULL)) FROM judge_task_attempt j WHERE j.judge_task_id=t.id),JSON_ARRAY()),
 'events',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('published',o.published_at IS NOT NULL,'failed',o.failed_at IS NOT NULL)) FROM outbox_event o WHERE o.aggregate_id=t.id),JSON_ARRAY()))
FROM assignment_attempt a JOIN assignment_problem p ON p.assignment_id=a.assignment_id AND p.problem_id=a.problem_id AND p.judge_version_id=a.judge_version_id JOIN submission s ON s.id=a.submission_id JOIN judge_task t ON t.submission_id=s.id JOIN problem_judge_version v ON v.id=s.judge_version_id ORDER BY a.accepted_at,s.id;
'@)
Save 'teacher-participant-facts.jsonl' (Sql "SELECT JSON_OBJECT('assignmentId',p.assignment_id,'userId',p.user_id,'memberStatus',m.status,'role',m.role,'classroomStatus',c.status) FROM assignment_participant p JOIN classroom_member m ON m.classroom_id=p.classroom_id AND m.user_id=p.user_id JOIN classroom c ON c.id=p.classroom_id ORDER BY p.assignment_id,p.user_id;")
Save 'teacher-pre-facts.jsonl' (Sql "SELECT JSON_OBJECT('submissionId',s.id,'assignmentId',p.assignment_id,'userId',p.user_id,'realOwnerVersionAc',s.user_id=p.user_id AND s.problem_id=p.problem_id AND s.judge_version_id=p.judge_version_id AND s.processing_status='FINISHED' AND s.verdict='AC') FROM assignment_precompletion p JOIN submission s ON s.id=p.submission_id ORDER BY p.assignment_id,p.user_id;")
Save 'teacher-self-facts.jsonl' (Sql "SELECT JSON_OBJECT('runId',p.run_id,'assignmentId',p.assignment_id,'userId',p.user_id,'status',j.processing_status,'result',j.execution_result,'basisMatches',s.owner_id=p.user_id AND s.problem_id=p.problem_id AND s.judge_version_id=p.judge_version_id,'outputSha256',o.output_sha256) FROM assignment_self_test p JOIN self_test_job j ON j.id=p.run_id JOIN self_test_snapshot s ON s.id=j.snapshot_id LEFT JOIN self_test_output o ON o.job_id=j.id;")
Save 'teacher-queues.tsv' (Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','messages_ready','messages_unacknowledged','--no-table-headers'))
Save 'teacher-api.log' (Compose @('logs','--no-log-prefix','api'))
Save 'teacher-worker.log' (Compose @('logs','--no-log-prefix','worker'))
$denials=0
foreach($table in @('classroom_assignment','assignment_problem','assignment_draft_problem','assignment_participant','assignment_precompletion','assignment_attempt','assignment_self_test','assignment_policy_fence')){
  $query="SELECT * FROM $table LIMIT 0;";$result=$query | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_worker --password="$FORGEOJ_WORKER_DB_PASSWORD" forgeoj' 2>&1
  if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142'){throw 'Expected Worker assignment denial missing'};$denials++
}
$apiId=Compose @('ps','-q','api');$api=(Docker @('inspect',$apiId) | ConvertFrom-Json)[0]
if(@($api.Mounts | Where-Object {$_.Destination -like '*docker.sock*'}).Count -or @($api.Config.Env | Where-Object {$_ -match '(WORKER_DB|MIGRATOR_PASSWORD|DOCKER_HOST)'}).Count){throw 'API credential/execution boundary violated'}
@{checkedAt=(Get-Date -Format o);allDenied=$true;denials=$denials;apiBoundary=$true} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'teacher-denials.json') -Encoding utf8
Compose @('exec','-T','frontend','node','/source/tools/validation/replay-teacher-audit.mjs') | Out-Host
