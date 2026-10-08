# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Setup','ReadyBrowsers','Revoke','HttpAudit','CaptureRuntime','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
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
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'assignment-cleanup.json') -Destination (Join-Path $RunDirectory 'admin-cleanup.json');exit
}
if($Action -in @('Setup','ReadyBrowsers','Revoke','HttpAudit')){
  if($Action -eq 'Setup'){
    $lines=foreach($name in @('tools/validation/Verify-M4AdminReplay.ps1','tools/validation/replay-admin-identity.mjs','tools/validation/replay-admin-audit.mjs','tools/validation/Verify-M3TeacherReplay.ps1','tools/validation/Verify-M3AssignmentReplay.ps1')){(Get-FileHash (Join-Path $repository $name)).Hash.ToLower()+'  '+$name}
    Save 'admin-tool-inputs.sha256' $lines
  }
  $phase=if($Action -eq 'HttpAudit'){'audit'}else{$Action.ToLower()}
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-admin-identity.mjs',$phase) | Out-Host;exit
}
if($Action -eq 'CaptureRuntime'){
  & (Join-Path $PSScriptRoot 'Verify-M3TeacherReplay.ps1') -Action CaptureRuntime -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'teacher-runtime.json') -Destination (Join-Path $RunDirectory 'admin-runtime.json');exit
}
$lines=foreach($name in @('tools/validation/Verify-M4AdminReplay.ps1','tools/validation/replay-admin-identity.mjs','tools/validation/replay-admin-audit.mjs','tools/validation/Verify-M3TeacherReplay.ps1','tools/validation/Verify-M3AssignmentReplay.ps1')){(Get-FileHash (Join-Path $repository $name)).Hash.ToLower()+'  '+$name}
Save 'admin-tool-inputs.sha256' $lines
Save 'admin-account-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',id,'username',username,'status',status,'role',role,'mustChangePassword',must_change_password,'version',version) FROM admin_account ORDER BY id;")
Save 'admin-audit-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',id,'occurredAt',occurred_at,'actorType',actor_type,'actorAdminId',actor_admin_id,'action',action,'targetId',target_id,'outcome',outcome,'reason',reason,'before',before_state,'after',after_state,'correlationId',correlation_id) FROM admin_audit_event ORDER BY occurred_at,id;")
Save 'admin-policy-facts.jsonl' (Sql "SELECT JSON_OBJECT('bootstrapped',bootstrapped,'schema18',(SELECT COUNT(*) FROM flyway_schema_history WHERE version='18' AND success=TRUE),'activeSupers',(SELECT COUNT(*) FROM admin_account WHERE status='ACTIVE' AND role='SUPER_ADMIN')) FROM admin_policy_fence;")
$denials=0
foreach($table in @('admin_account','admin_policy_fence','admin_login_session','admin_refresh_token','admin_creation_request','admin_audit_event')){
  $result="SELECT * FROM $table LIMIT 0;" | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_worker --password="$FORGEOJ_WORKER_DB_PASSWORD" forgeoj' 2>&1
  if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142'){throw 'Expected Worker admin denial missing'};$denials++
}
foreach($query in @('DELETE FROM admin_account','UPDATE admin_policy_fence SET bootstrapped=FALSE','DELETE FROM admin_audit_event',"UPDATE admin_audit_event SET reason='overwritten'")){
  $result=$query | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_api --password="$FORGEOJ_API_DB_PASSWORD" forgeoj' 2>&1
  if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142|ERROR 1143'){throw 'Expected API immutable fact denial missing'};$denials++
}
$apiId=Compose @('ps','-q','api');$api=(Docker @('inspect',$apiId) | ConvertFrom-Json)[0]
if(@($api.Mounts | Where-Object {$_.Destination -like '*docker.sock*'}).Count -or @($api.Config.Env | Where-Object {$_ -match '(WORKER_DB|MIGRATOR_PASSWORD|ADMIN_CLI_DB|DOCKER_HOST)'}).Count){throw 'API credential/execution boundary violated'}
@{checkedAt=(Get-Date -Format o);allDenied=$true;denials=$denials;apiBoundary=$true} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'admin-denials.json') -Encoding utf8
Save 'admin-api.log' (Compose @('logs','--no-log-prefix','api'))
Save 'admin-worker.log' (Compose @('logs','--no-log-prefix','worker'))
Save 'admin-formal-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',s.id,'status',s.processing_status,'verdict',s.verdict,'taskId',t.id,'taskStatus',t.task_status,'statusVersion',s.status_version,'taskVersion',t.status_version,'attemptCount',t.attempt_count,'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL,'closedAttempts',(SELECT COUNT(*) FROM judge_task_attempt a WHERE a.judge_task_id=t.id AND a.attempt_status='SUCCEEDED' AND a.finished_at IS NOT NULL),'outboxId',(SELECT id FROM outbox_event o WHERE o.aggregate_id=t.id AND o.aggregate_type='JUDGE_TASK'),'published',(SELECT published_at IS NOT NULL AND failed_at IS NULL FROM outbox_event o WHERE o.aggregate_id=t.id AND o.aggregate_type='JUDGE_TASK')) FROM submission s JOIN judge_task t ON t.submission_id=s.id;")
Save 'admin-queues.tsv' (Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','messages_ready','messages_unacknowledged','--no-table-headers'))
Compose @('exec','-T','frontend','node','/source/tools/validation/replay-admin-audit.mjs') | Out-Host
Write-Host "Admin facts exported; $denials SQL permission refusals verified."
