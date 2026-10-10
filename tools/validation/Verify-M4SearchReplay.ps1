# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Setup','Healthy','Pause','Outage','Pending','Resume','Recovered','Clear','RevokeBrowser','CaptureRuntime','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar) -or (Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-[0-9-]+-[a-f0-9]{8}$' -or -not $state.SearchEnabled){throw 'Owned search replay directory/state mismatch'}
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$RunDirectory;$env:FORGEOJ_E2E_REDIS_ENABLED=[string]$state.RedisEnabled
$compose=Join-Path $PSScriptRoot 'compose.replay.yml';$overlay=Join-Path $PSScriptRoot 'compose.search-replay.yml'
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned search replay Docker command failed'};return $result}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project,'-f',$compose,'-f',$overlay)+$Arguments)}
function Save-Lines([string]$Name,[object]$Lines){[IO.File]::WriteAllText((Join-Path $RunDirectory $Name),(@($Lines)-join "`n"),[Text.UTF8Encoding]::new($false))}
function Save-Json([string]$Name,[object]$Value){Save-Lines $Name ($Value | ConvertTo-Json -Depth 15)}
function Sql([string]$Statement){$result=$Statement | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Owned search fixture SQL failed'};return $result}
function Owned([string]$Service){$id=(Compose @('ps','-q',$Service)).Trim();if($id -notmatch '^[a-f0-9]{64}$'){throw 'Missing exact owned container'};$container=(Docker @('inspect',$id) | ConvertFrom-Json)[0];if($container.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $container.Config.Labels.'com.docker.compose.service' -ne $Service){throw 'Container ownership mismatch'};return $container}
function Es([string]$Path,[string]$Method='GET',[string]$Body='', [string]$Identity='elastic:m4-search-admin-fixture-only'){
  Owned 'search' | Out-Null
  $arguments=@('exec','-T','search','curl','--fail','--silent','--cacert','/usr/share/elasticsearch/config/certs/server.crt','--user',$Identity,'-X',$Method,'-H','Content-Type: application/json')
  if($Body){$arguments+=@('--data-binary',$Body)}
  return ((Compose ($arguments+@('https://localhost:9200'+$Path))) -join "`n") | ConvertFrom-Json
}
if($Action -eq 'Setup'){
  Owned 'search' | Out-Null
  $paths=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object {$_.Extension -in @('.ps1','.mjs','.yml','.sql','.sh')})
  Save-Lines 'search-tool-inputs.sha256' ($paths | Sort-Object Name | ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash.ToLower()+'  tools/validation/'+$_.Name})
  exit
}
if($Action -eq 'Pause'){$owned=Owned 'search';if($owned.State.Paused){throw 'Search is already paused'};Docker @('pause',$owned.Id) | Out-Null;Save-Json 'search-pause.json' @{id=$owned.Id;project=$state.Project;at=(Get-Date -Format o)};exit}
if($Action -eq 'Resume'){$owned=Owned 'search';if(-not $owned.State.Paused){throw 'Search is not paused'};Docker @('unpause',$owned.Id) | Out-Null;exit}
if($Action -eq 'Clear'){
  # Maintenance belongs only to this owned disposable generation, never to a caller-selected index.
  $control=((Sql "SELECT JSON_OBJECT('indexName',c.index_name,'uuid',c.index_uuid,'job',j.id,'status',j.status) FROM public_search_control c JOIN public_search_rebuild j ON j.target_index=c.index_name WHERE c.id=1") -join "`n") | ConvertFrom-Json
  if($control.indexName -notmatch '^forgeoj-public-[a-f0-9]{32}$' -or $control.status -ne 'SUCCEEDED'){throw 'Managed generation ownership not proven'}
  $mapping=Es ('/'+$control.indexName+'/_mapping');if(@($mapping.PSObject.Properties).Count -ne 1 -or $mapping.PSObject.Properties[$control.indexName].Value.mappings._meta.forgeojRebuildId -ne $control.job){throw 'Search generation metadata mismatch'}
  $settings=Es ('/'+$control.indexName+'/_settings');if(@($settings.PSObject.Properties).Count -ne 1 -or $settings.PSObject.Properties[$control.indexName].Value.settings.index.uuid -ne $control.uuid){throw 'Search generation UUID mismatch'}
  $before=Es ('/'+$control.indexName+'/_count');$receiptPath=Join-Path $RunDirectory 'search-clear.json'
  if(Test-Path -LiteralPath $receiptPath){
    $receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    if($receipt.index -ne $control.indexName -or $receipt.uuid -ne $control.uuid -or $receipt.job -ne $control.job -or $receipt.before -lt 1 -or $receipt.deleted -ne $receipt.before -or $receipt.after -ne 0 -or $before.count -ne 0){throw 'Existing clear receipt does not match this still-empty owned generation'}
  }else{
    if($before.count -lt 1){throw 'Owned generation must contain documents before its first clear'}
    $cleared=Es ('/'+$control.indexName+'/_delete_by_query?refresh=true') 'POST' '{"query":{"match_all":{}}}';$after=Es ('/'+$control.indexName+'/_count')
    if($after.count -ne 0 -or $cleared.deleted -ne $before.count){throw 'Owned index clear was incomplete'}
    Save-Json 'search-clear.json' @{index=$control.indexName;uuid=$control.uuid;job=$control.job;before=$before.count;deleted=$cleared.deleted;after=$after.count}
  }
}
if($Action -in @('Healthy','Outage','Recovered','Clear','RevokeBrowser')){$phase=if($Action -eq 'RevokeBrowser'){'revoke-browser'}else{$Action.ToLower()};Compose @('exec','-T','frontend','node','/source/tools/validation/replay-search.mjs',$phase) | Out-Host;exit}
if($Action -eq 'Pending'){
  Save-Lines 'search-pending.jsonl' (Sql "SELECT JSON_OBJECT('eventId',d.event_id,'status',d.status,'attempts',d.attempts,'errorCode',d.error_code,'deadPublished',(SELECT o.published_at IS NOT NULL FROM public_search_dead_outbox o WHERE o.event_id=d.event_id)) FROM public_search_delivery d WHERE d.status<>'SUCCEEDED'")
  exit
}
if($Action -eq 'CaptureRuntime'){
  & (Join-Path $PSScriptRoot 'Verify-M4RedisReplay.ps1') -Action CaptureRuntime -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  $runtime=@()
  foreach($service in @('api','api-replica','worker','search','redis')){
    $c=Owned $service;$socket=[bool]($c.Mounts | Where-Object Destination -eq '/var/run/docker.sock');$names=@($c.Config.Env | ForEach-Object {($_ -split '=',2)[0]});$jar=$null
    if($service -in @('api','api-replica','worker')){
      $file=if($service -eq 'worker'){'/app/worker.jar'}else{'/app/api.jar'}
      $jar=((Docker @('exec',$c.Id,'sha256sum',$file)) -split ' ')[0]
      if($jar -ne $(if($service -eq 'worker'){$state.WorkerHash}else{$state.ApiHash})){throw 'Search runtime JAR drift'}
      if(@($c.Mounts | Where-Object {$_.Destination -eq $file -and -not $_.RW}).Count -ne 1){throw 'Search runtime JAR must be read-only'}
    }
    $trust=[bool]($c.Mounts | Where-Object {$_.Destination -eq '/run/search-trust.p12' -and -not $_.RW});$privateKey=[bool]($c.Mounts | Where-Object {$_.Source -like '*server.p12'})
    if($service -in @('api','api-replica') -and ($socket -or -not $trust -or $privateKey -or $c.Config.Env -notcontains 'FORGEOJ_SEARCH_URL=https://search:9200')){throw 'API search TLS/privilege boundary invalid'}
    if($service -eq 'worker' -and @($names | Where-Object {$_ -match 'SEARCH|ELASTIC|REDIS|MIGRATOR|ADMIN_CLI'}).Count){throw 'Worker received a forbidden environment key'}
    $runtime+=@{service=$service;id=$c.Id;project=$state.Project;image=$c.Image;configuredImage=$c.Config.Image;jarSha256=$jar;hasDockerSocket=$socket;publicTrustReadOnly=$trust;serverKeyMounted=$privateKey;envNames=$names}
  }
  $version=Es '/';$license=Es '/_license';$nodes=Es '/_nodes/plugins';$plugins=@($nodes.nodes.PSObject.Properties | ForEach-Object {$_.Value.plugins})
  if($version.version.number -ne '9.5.3' -or $license.license.type -ne 'basic' -or $plugins.Count -ne 0){throw 'Unexpected ES runtime/version/license/plugin'}
  $unauth=(Compose @('exec','-T','search','curl','--silent','--output','/dev/null','--write-out','%{http_code}','--cacert','/usr/share/elasticsearch/config/certs/server.crt','https://localhost:9200/')).Trim()
  $unrelated=(Compose @('exec','-T','search','curl','--silent','--output','/dev/null','--write-out','%{http_code}','--cacert','/usr/share/elasticsearch/config/certs/server.crt','--user','forgeoj_search:m4-search-public-fixture-only','https://localhost:9200/unrelated-private-index/_search')).Trim()
  if($unauth -ne '401' -or $unrelated -ne '403'){throw 'Search authentication/namespace boundary failed'}
  Save-Json 'search-runtime.json' @{containers=$runtime;server=@{version=$version.version.number;license=$license.license.type;plugins=$plugins.Count;unauthenticatedStatus=$unauth;unrelatedIndexStatus=$unrelated;tls=$true};capturedAt=(Get-Date -Format o)}
  exit
}
if($Action -eq 'Audit'){
  $confirmed=@(Sql "SELECT d.event_id FROM public_search_delivery d WHERE d.status='DEAD_LETTER' AND (EXISTS(SELECT 1 FROM public_search_dead_outbox o WHERE o.event_id=d.event_id AND o.published_at IS NOT NULL) OR EXISTS(SELECT 1 FROM public_search_dead_recovery r WHERE r.event_id=d.event_id AND r.published_at IS NOT NULL))")
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-search-dead.mjs',([string](ConvertTo-Json -InputObject $confirmed -Compress))) | Out-Host
  Save-Lines 'search-events.jsonl' (Sql "SELECT JSON_OBJECT('id',e.id,'problemId',e.problem_id,'scope',p.scope,'version',e.data_version,'epoch',e.public_epoch,'published',e.published_at IS NOT NULL,'failed',e.failed_at IS NOT NULL,'publishAttempts',e.publish_attempts,'status',d.status,'attempts',d.attempts,'errorCode',d.error_code,'leaseCleared',d.lease_token IS NULL AND d.lease_expires_at IS NULL,'deadConfirmed',(SELECT o.published_at IS NOT NULL FROM public_search_dead_outbox o WHERE o.event_id=e.id)) FROM public_search_outbox e JOIN problem p ON p.id=e.problem_id LEFT JOIN public_search_delivery d ON d.event_id=e.id ORDER BY e.created_at,e.id")
  Save-Lines 'search-jobs.jsonl' (Sql "SELECT JSON_OBJECT('id',j.id,'status',j.status,'attempts',j.attempts,'epoch',j.target_epoch,'leaseCleared',j.lease_token IS NULL AND j.lease_expires_at IS NULL,'queueAudit',(SELECT COUNT(*) FROM admin_audit_event a WHERE a.action='SEARCH_REBUILD_QUEUED' AND a.target_id=j.id),'terminalAudit',(SELECT COUNT(*) FROM admin_audit_event a WHERE a.action='SEARCH_REBUILD_SUCCEEDED' AND a.target_id=j.id)) FROM public_search_rebuild j ORDER BY j.created_at")
  Save-Lines 'search-control.jsonl' (Sql "SELECT JSON_OBJECT('version',c.version,'publicEpoch',(SELECT revision FROM cache_epoch WHERE namespace='public'),'readableEpoch',c.readable_epoch,'activeJob',c.active_job,'privateVersions',(SELECT COUNT(*) FROM public_search_version v JOIN problem p ON p.id=v.problem_id WHERE p.scope<>'PUBLIC')) FROM public_search_control c")
  Save-Lines 'search-outage-submission.jsonl' (Sql "SELECT JSON_OBJECT('id',s.id,'status',s.processing_status,'verdict',s.verdict,'taskCount',(SELECT COUNT(*) FROM judge_task t WHERE t.submission_id=s.id),'sourceDigestMatches',s.source_sha256=SHA2(s.source_code,256)) FROM submission s JOIN user_account u ON u.id=s.user_id WHERE u.username='learner' ORDER BY s.created_at")
  Save-Lines 'search-formal-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',s.id,'taskId',t.id,'taskStatus',t.task_status,'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL,'closedAttempts',(SELECT COUNT(*) FROM judge_task_attempt a WHERE a.judge_task_id=t.id AND a.finished_at IS NOT NULL),'published',(SELECT COUNT(*) FROM outbox_event o WHERE o.aggregate_type='JUDGE_TASK' AND o.aggregate_id=t.id AND o.published_at IS NOT NULL)) FROM submission s JOIN judge_task t ON t.submission_id=s.id JOIN user_account u ON u.id=s.user_id WHERE u.username IN ('learner','redis_learner') ORDER BY s.created_at")
  Save-Json 'search-queues.json' (Compose @('exec','-T','rabbitmq','rabbitmqctl','-q','list_queues','-p','/forgeoj','name','messages_ready','messages_unacknowledged','--formatter=json') | ConvertFrom-Json)
  $denials=@()
  foreach($table in @('public_search_version','public_search_outbox','public_search_delivery','public_search_dead_outbox','public_search_dead_recovery','public_search_control','public_search_rebuild')){
    foreach($probe in @(@('forgeoj_worker','FORGEOJ_WORKER_DB_PASSWORD',"SELECT * FROM $table LIMIT 0;"),@('forgeoj_api','FORGEOJ_API_DB_PASSWORD',"DELETE FROM $table WHERE 1=0;"))){
      $command='exec mysql -h127.0.0.1 -u'+$probe[0]+' --password="$'+$probe[1]+'" forgeoj'
      $result=$probe[2] | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c $command 2>&1
      if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142'){throw 'Expected search database denial missing'}
      $denials+=@{table=$table;account=$probe[0];denied=$true}
    }
  }
  Save-Json 'search-sql-denials.json' $denials
  foreach($service in @('api','api-replica','worker','search')){Save-Lines ('search-'+$service+'.log') (Docker @('logs',(Owned $service).Id))}
  exit
}
if($Action -eq 'CleanupSnapshot'){
  & (Join-Path $PSScriptRoot 'Verify-M3AssignmentReplay.ps1') -Action CleanupSnapshot -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'assignment-cleanup.json') -Destination (Join-Path $RunDirectory 'search-cleanup.json')
}
