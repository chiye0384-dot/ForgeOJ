# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Setup','Start','Check','Probe','Business','Snapshot','PauseRabbit','ResumeRabbit','PauseWorker','ResumeWorker','RabbitPending','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[ValidateSet('healthy','jwt-denied','redis-outage','redis-recovered','es-outage','es-recovered','rabbit-outage','rabbit-recovered','rabbit-accept','rabbit-rearm','rabbit-recover')][string]$Phase='healthy',[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content -LiteralPath (Join-Path $run 'state.json') -Raw | ConvertFrom-Json
if(-not $run.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar) -or (Split-Path -Leaf $run) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$' -or -not $state.SearchEnabled -or -not $state.RedisEnabled){throw 'Exact owned search/Redis replay required'}
function Save([string]$Name,[object]$Value){[IO.File]::WriteAllText((Join-Path $run $Name),($Value | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned monitoring Docker operation failed'};return $result}
$files=@('-f',(Join-Path $PSScriptRoot 'compose.replay.yml'),'-f',(Join-Path $PSScriptRoot 'compose.search-replay.yml'),'-f',(Join-Path $PSScriptRoot 'compose.monitoring-replay.yml'))
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$run;$env:FORGEOJ_E2E_REDIS_ENABLED='true';$env:FORGEOJ_E2E_MAIL_APP_URL=$state.FrontendUrl
$private=Join-Path $run 'monitoring-private'
if($Action -eq 'Setup'){
 if(Test-Path -LiteralPath $private){throw 'Monitoring private credentials already exist; use Check, not Setup'}
 New-Item -ItemType Directory -Path $private | Out-Null
 # Restrict host directory to its owner. Linux containers get read-only bind files.
 $owner=[Security.Principal.WindowsIdentity]::GetCurrent().Name
 & icacls.exe $private /inheritance:r /grant:r "${owner}:(OI)(CI)F" | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Cannot restrict private fixture credential directory'}
 foreach($name in @('token','grafana-password','grafana-key')){
  $bytes=[byte[]]::new(32);[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [IO.File]::WriteAllText((Join-Path $private $name),[Convert]::ToHexString($bytes).ToLower(),[Text.UTF8Encoding]::new($false))
 }
}
if(-not (Test-Path (Join-Path $private 'token'))){throw 'Owned monitoring credentials missing'}
$env:FORGEOJ_E2E_MONITORING_TOKEN=[IO.File]::ReadAllText((Join-Path $private 'token'))
# Freeze runtime helpers/config without reading private credentials or user handoffs.
if($Action -ne 'CleanupSnapshot'){
 $inputPaths=@(Get-ChildItem -LiteralPath $PSScriptRoot -File;Get-ChildItem -LiteralPath (Join-Path $repository 'deploy/monitoring') -File)
 $inputText=($inputPaths | Sort-Object FullName | ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash.ToLower()+'  '+$_.FullName.Substring($repository.Length+1).Replace('\','/')}) -join "`n"
 $history=Join-Path $run 'monitoring-tool-history';New-Item -ItemType Directory -Force -Path $history | Out-Null
 $inputPath=Join-Path $history ((Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+$Action+'-'+$Phase+'.sha256')
 [IO.File]::WriteAllText($inputPath,$inputText,[Text.UTF8Encoding]::new($false))
 [IO.File]::WriteAllText((Join-Path $run 'monitoring-tool-inputs.sha256'),$inputText,[Text.UTF8Encoding]::new($false))
}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project)+$files+$Arguments)}
function Owned([string]$service){$id=(Compose @('ps','-q',$service)).Trim();if($id -notmatch '^[a-f0-9]{64}$'){throw 'Exact owned container absent'};$c=(Docker @('inspect',$id) | ConvertFrom-Json)[0];if($c.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $c.Config.Labels.'com.docker.compose.service' -ne $service){throw 'Monitoring ownership mismatch'};return $c}
function Sql([string]$statement){Owned 'mysql' | Out-Null;$result=$statement | & $DockerCommand compose --project-name $state.Project @files exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Owned monitoring readonly facts query failed'};return $result}
if($Action -in @('Setup','Start')){
 Owned 'rabbitmq' | Out-Null
 Compose @('exec','-T','rabbitmq','rabbitmq-plugins','enable','rabbitmq_prometheus') | Out-Null
 Compose @('up','--detach','--no-deps','--wait','--wait-timeout','180','api','api-replica','worker') | Out-Null
 Compose @('up','--detach','--no-deps','prometheus','grafana') | Out-Null
 Compose @('up','--detach','--no-deps','mailbox') | Out-Null
 $state | Add-Member -NotePropertyName MonitoringEnabled -NotePropertyValue $true -Force
 $state | Add-Member -NotePropertyName GrafanaUrl -NotePropertyValue ('http://'+(Compose @('port','grafana','3000')).Trim()) -Force
 Save 'state.json' $state
}
if($Action -in @('Setup','Start','Check','Probe')){
 Compose @('exec','-T','prometheus','promtool','check','config','/etc/prometheus/prometheus.yml') | Out-Host
 $network=$state.Project+'_default';$n=(Docker @('network','inspect',$network)|ConvertFrom-Json)[0];if($n.Labels.'com.docker.compose.project' -ne $state.Project){throw 'Monitoring probe network mismatch'}
 # The original disposable frontend writes its synthetic cookies as root-owned 0600.
 # Only the JWT boundary reader shares that fixture UID; do not broaden cookie file modes.
 $probeUser=if($Phase -eq 'jwt-denied'){'0:0'}else{'65534:65534'}
 Docker @('run','--rm','--pull=never','--name',($state.Project+'-monitor-probe'),'--label',('com.docker.compose.project='+$state.Project),'--network',$network,'--user',$probeUser,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/tmp:size=16m,mode=1777','--mount',('type=bind,source='+$repository+',target=/source,readonly'),'--mount',('type=bind,source='+$run+',target=/reports'),'--mount',('type=bind,source='+$private+',target=/private,readonly'),'node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c','node','/source/tools/validation/replay-monitoring.mjs',$Phase) | Out-Host
 if($Action -eq 'Probe'){exit}
 foreach($service in @('api','api-replica','worker')){
  # PowerShell's string-to-native pipe appends CRLF after this LF file. Read exact bytes in Linux.
  Docker @('run','--rm','--pull=never','--name',($state.Project+'-promtool'),'--label',('com.docker.compose.project='+$state.Project),'--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--mount',('type=bind,source='+$run+',target=/reports,readonly'),'--entrypoint','/bin/sh','prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e','-c',('exec /bin/promtool check metrics < /reports/monitoring-'+$service+'.prom')) | Out-Host
 }
 Save 'monitoring-promtool.json' @{allPassed=$true;services=@('api','api-replica','worker');checkedAt=(Get-Date -Format o)}
}
if($Action -eq 'Business'){
 if($Phase -notin @('rabbit-accept','rabbit-rearm','rabbit-recover')){throw 'Monitoring business probe requires a Rabbit phase'}
 Owned 'frontend' | Out-Null
 Compose @('exec','-T','frontend','node','/source/tools/validation/replay-monitoring-rabbit.mjs',$Phase.Substring(7)) | Out-Host
}
if($Action -eq 'Snapshot'){
 $containers=@()
 foreach($service in @('api','api-replica','worker','rabbitmq','prometheus','grafana')){
  $c=Owned $service;$ports=@($c.HostConfig.PortBindings.PSObject.Properties | Where-Object {$null -ne $_.Value})
  if($service -ne 'grafana' -and $ports.Count -ne 0){throw 'Unexpected monitoring/infra published port'}
  if($service -eq 'grafana' -and ($ports.Count -ne 1 -or @($ports[0].Value | Where-Object HostIp -ne '127.0.0.1').Count)){throw 'Grafana must bind only loopback'}
  $socket=[bool]($c.Mounts | Where-Object Destination -eq '/var/run/docker.sock')
  if($service -ne 'worker' -and $socket){throw 'Non-worker service received Docker socket'}
  $jar=$null;if($service -in @('api','api-replica','worker')){$path=if($service -eq 'worker'){'/app/worker.jar'}else{'/app/api.jar'};$jar=((Docker @('exec',$c.Id,'sha256sum',$path)) -split ' ')[0];if($jar -ne $(if($service -eq 'worker'){$state.WorkerHash}else{$state.ApiHash})){throw 'Monitoring runtime JAR drift'}}
  $containers+=@{service=$service;id=$c.Id;image=$c.Image;configuredImage=$c.Config.Image;jarSha256=$jar;dockerSocket=$socket;publishedPortCount=$ports.Count;readOnlyRoot=$c.HostConfig.ReadonlyRootfs;environmentNames=@($c.Config.Env|ForEach-Object {($_ -split '=',2)[0]})}
 }
 Save 'monitoring-runtime.json' @{containers=$containers;capturedAt=(Get-Date -Format o)}
}
if($Action -eq 'PauseRabbit'){$c=Owned 'rabbitmq';if($c.State.Paused){throw 'Rabbit already paused'};Docker @('pause',$c.Id) | Out-Null;Save 'monitoring-rabbit-pause.json' @{id=$c.Id;project=$state.Project;at=(Get-Date -Format o)}}
if($Action -eq 'ResumeRabbit'){$c=Owned 'rabbitmq';if(-not $c.State.Paused){throw 'Rabbit not paused'};Docker @('unpause',$c.Id) | Out-Null;Save 'monitoring-rabbit-resume.json' @{id=$c.Id;project=$state.Project;at=(Get-Date -Format o)}}
if($Action -eq 'PauseWorker'){$c=Owned 'worker';if($c.State.Paused){throw 'Worker already paused'};if(-not (Owned 'rabbitmq').State.Paused){throw 'Pause only after Rabbit outage observation'};Docker @('pause',$c.Id) | Out-Null;Save 'monitoring-worker-pause.json' @{id=$c.Id;project=$state.Project;at=(Get-Date -Format o)}}
if($Action -eq 'ResumeWorker'){$c=Owned 'worker';$p=Get-Content (Join-Path $run 'monitoring-worker-pause.json') -Raw | ConvertFrom-Json;if(-not $c.State.Paused -or $p.id -ne $c.Id){throw 'Paused Worker ownership mismatch'};Docker @('unpause',$c.Id) | Out-Null;Save 'monitoring-worker-resume.json' @{id=$c.Id;project=$state.Project;at=(Get-Date -Format o)}}
if($Action -in @('RabbitPending','Audit')){
 $http=Get-Content (Join-Path $run 'monitoring-rabbit-http.json') -Raw | ConvertFrom-Json
 if($http.submissionId -notmatch '^[a-f0-9-]{36}$' -or $http.taskId -notmatch '^[a-f0-9-]{36}$'){throw 'Invalid owned HTTP identifiers'}
 $facts=((Sql @"
SELECT JSON_OBJECT('submission',s.id,'task',t.id,'status',s.processing_status,'verdict',s.verdict,'taskStatus',t.task_status,'attempts',t.attempt_count,'closedAttempts',(SELECT COUNT(*) FROM judge_task_attempt a WHERE a.judge_task_id=t.id AND a.finished_at IS NOT NULL),'submissionCount',(SELECT COUNT(*) FROM submission x WHERE x.id=s.id),'taskCount',(SELECT COUNT(*) FROM judge_task x WHERE x.submission_id=s.id),'eventCount',(SELECT COUNT(*) FROM outbox_event o WHERE o.aggregate_id=t.id),'digestMatches',s.source_sha256=SHA2(s.source_code,256),'sourceSha256',s.source_sha256,'quotaSlots',(SELECT COUNT(*) FROM submission active WHERE active.user_id=s.user_id AND active.processing_status IN ('QUEUED','RETRYING','RUNNING')),'events',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',o.id,'attempts',o.publish_attempts,'failed',o.failed_at IS NOT NULL,'published',o.published_at IS NOT NULL)) FROM outbox_event o WHERE o.aggregate_id=t.id),JSON_ARRAY()),'recoveries',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('scope',r.recovery_scope,'attempts',r.previous_attempts,'reason',r.reason,'target',r.target_id)) FROM operations_recovery_request r WHERE r.task_id=t.id),JSON_ARRAY())) FROM submission s JOIN judge_task t ON t.submission_id=s.id JOIN user_judge_quota_lock q ON q.user_id=s.user_id WHERE s.id='$($http.submissionId)' AND t.id='$($http.taskId)';
"@) -join "`n") | ConvertFrom-Json
 if($facts.submissionCount -ne 1 -or $facts.taskCount -ne 1 -or $facts.eventCount -ne 1 -or -not $facts.digestMatches){throw 'Durable task binding changed'}
 if($Action -eq 'RabbitPending'){
  if(-not (Owned 'rabbitmq').State.Paused -or $facts.status -ne 'QUEUED' -or $facts.taskStatus -ne 'QUEUED' -or $facts.attempts -ne 0 -or $facts.events[0].published -or $facts.quotaSlots -ne 1){throw 'Rabbit outage lost accepted task/quota or executed before delivery'}
  Save 'monitoring-rabbit-pending-facts.json' $facts
 }else{
  $before=Get-Content (Join-Path $run 'monitoring-rabbit-pending-facts.json') -Raw | ConvertFrom-Json
  if($facts.status -ne 'FINISHED' -or $facts.verdict -ne 'AC' -or $facts.taskStatus -ne 'FINISHED' -or $facts.attempts -ne 1 -or $facts.closedAttempts -ne 1 -or -not $facts.events[0].published -or $facts.events[0].id -ne $before.events[0].id -or $facts.sourceSha256 -ne $before.sourceSha256 -or $facts.quotaSlots -ne 0){throw 'Rabbit real completion or frozen facts mismatch'}
  Save 'monitoring-rabbit-final-facts.json' $facts
  [IO.File]::WriteAllText((Join-Path $run 'monitoring-admin-audit.jsonl'),(@(Sql "SELECT JSON_OBJECT('id',id,'actorAdminId',actor_admin_id,'action',action,'targetType',target_type,'targetId',target_id,'reason',reason,'occurredAt',CAST(occurred_at AS CHAR)) FROM admin_audit_event ORDER BY id") -join "`n"),[Text.UTF8Encoding]::new($false))
  foreach($service in @('api','api-replica','worker')){[IO.File]::WriteAllText((Join-Path $run ('monitoring-'+$service+'.log')),((Docker @('logs',(Owned $service).Id)) -join "`n"),[Text.UTF8Encoding]::new($false))}
  Save 'monitoring-queues.json' (Compose @('exec','-T','rabbitmq','rabbitmqctl','-q','list_queues','-p','/forgeoj','name','messages_ready','messages_unacknowledged','--formatter=json') | ConvertFrom-Json)
  # No purge/ACK deletion. Every diagnostic copy is requeued before the final queue snapshot.
  Copy-Item -LiteralPath (Join-Path $run 'monitoring-queues.json') -Destination (Join-Path $run 'monitoring-queues-before-dlq.json')
  [IO.File]::WriteAllText((Join-Path $run 'monitoring-dead-outbox.jsonl'),(@(Sql "SELECT JSON_OBJECT('id',id,'taskId',aggregate_id,'type',event_type,'published',published_at IS NOT NULL,'failed',failed_at IS NOT NULL,'payload',payload) FROM outbox_event WHERE event_type IN ('JUDGE_TASK_DEAD_LETTERED','CONTENT_VALIDATION_DEAD_LETTERED','SELF_TEST_DEAD_LETTERED') ORDER BY id") -join "`n"),[Text.UTF8Encoding]::new($false))
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-monitoring-dead-letters.mjs',$state.Project) | Out-Host
  $queuesAfter=Compose @('exec','-T','rabbitmq','rabbitmqctl','-q','list_queues','-p','/forgeoj','name','messages_ready','messages_unacknowledged','--formatter=json') | ConvertFrom-Json
  $queuesBefore=Get-Content (Join-Path $run 'monitoring-queues-before-dlq.json') -Raw | ConvertFrom-Json
  foreach($q in $queuesBefore){$after=@($queuesAfter | Where-Object name -EQ $q.name);if($after.Count -ne 1 -or $after[0].messages_ready -ne $q.messages_ready -or $after[0].messages_unacknowledged -ne $q.messages_unacknowledged){throw 'Diagnostic changed queue retention or execution drain'}}
  Save 'monitoring-queues.json' $queuesAfter
 }
}
if($Action -eq 'CleanupSnapshot'){
 & (Join-Path $PSScriptRoot 'Verify-M3AssignmentReplay.ps1') -Action CleanupSnapshot -RunDirectory $run -DockerCommand $DockerCommand
 Copy-Item -LiteralPath (Join-Path $run 'assignment-cleanup.json') -Destination (Join-Path $run 'monitoring-cleanup.json')
}
