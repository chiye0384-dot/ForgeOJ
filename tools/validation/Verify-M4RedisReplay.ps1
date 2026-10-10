# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Bootstrap','Setup','Healthy','GuardFailure','Pause','Outage','Pending','ResumeFlush','Recovered','Warm','Audit','CaptureRuntime','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar) -or (Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-[0-9-]+-[a-f0-9]{8}$' -or -not $state.RedisEnabled){throw 'Owned Redis replay directory/state mismatch'}
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$RunDirectory;$env:FORGEOJ_E2E_REDIS_ENABLED='true'
$compose=Join-Path $PSScriptRoot 'compose.replay.yml'
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned Redis replay Docker command failed'};return $result}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project,'-f',$compose)+$Arguments)}
function Save-Lines([string]$Name,[object]$Lines){[IO.File]::WriteAllText((Join-Path $RunDirectory $Name),(@($Lines)-join "`n"),[Text.UTF8Encoding]::new($false))}
function Save-Json([string]$Name,[object]$Value){Save-Lines $Name ($Value | ConvertTo-Json -Depth 12)}
function Sql([string]$Statement){$result=$Statement | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Owned Redis fixture SQL failed'};return $result}
function Owned([string]$Service){$id=(Compose @('ps','-q',$Service)).Trim();if($id -notmatch '^[a-f0-9]{64}$'){throw 'Missing exact owned container'};$container=(Docker @('inspect',$id) | ConvertFrom-Json)[0];if($container.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $container.Config.Labels.'com.docker.compose.service' -ne $Service){throw 'Container ownership mismatch'};return $container}
if($Action -eq 'Bootstrap'){
  # Interactive CLI routes before Spring. This public maintenance credential belongs only to this disposable fixture.
  Owned 'mysql' | Out-Null;$api=Owned 'api';$network=$state.Project+'_default'
  $networkInfo=(Docker @('network','inspect',$network) | ConvertFrom-Json)[0]
  if($networkInfo.Labels.'com.docker.compose.project' -ne $state.Project){throw 'CLI network ownership mismatch'}
  $jar=Join-Path $state.BuildDirectory 'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar'
  if((Get-FileHash -LiteralPath $jar).Hash.ToLower() -ne $state.ApiHash){throw 'CLI JAR drift'}
  & $DockerCommand run --rm --interactive --tty --name "$($state.Project)-admin-cli" --label "com.docker.compose.project=$($state.Project)" --network $network --user '65534:65534' --read-only --tmpfs '/tmp:size=16m,mode=1777' --mount "type=bind,source=$jar,target=/app/api.jar,readonly" --env 'FORGEOJ_ADMIN_CLI_DB_URL=jdbc:mysql://mysql:3306/forgeoj' --env 'FORGEOJ_ADMIN_CLI_DB_USERNAME=forgeoj_migrator' --env 'FORGEOJ_ADMIN_CLI_DB_PASSWORD=m1-e2e-migrator-test-secret' $api.Config.Image java -jar /app/api.jar admin-bootstrap
  if($LASTEXITCODE -ne 0){throw 'Disposable interactive bootstrap failed'}
  Save-Lines 'redis-bootstrap.jsonl' (Sql "SELECT JSON_OBJECT('fixtureOnly',TRUE,'administrators',(SELECT COUNT(*) FROM admin_account),'bootstrapEvents',(SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_BOOTSTRAP'),'bootstrapped',bootstrapped) FROM admin_policy_fence")
  exit
}
if($Action -eq 'Setup'){
  $helpers=@('tools/validation/Replay-FixedLinux.ps1','tools/validation/compose.replay.yml','tools/validation/Verify-M4RedisReplay.ps1','tools/validation/replay-redis.mjs','tools/validation/redis-fixture.sql','tools/validation/Verify-M3TeacherReplay.ps1','tools/validation/replay-teacher-records.mjs','tools/validation/Verify-M3AssignmentReplay.ps1','tools/validation/replay-assignments.mjs','tools/validation/Verify-M4RedisEvidence.mjs')
  Save-Lines 'redis-tool-inputs.sha256' ($helpers | ForEach-Object {(Get-FileHash (Join-Path $repository $_)).Hash.ToLower()+'  '+$_})
  $before=(Owned 'api').Id
  Sql (Get-Content (Join-Path $PSScriptRoot 'redis-fixture.sql') -Raw) | Out-Null
  Compose @('up','--detach','--no-deps','--no-recreate','--wait','--wait-timeout','120','api-replica') | Out-Null
  if((Owned 'api').Id -ne $before){throw 'Replica setup recreated the primary API'}
  Save-Json 'redis-ownership.json' @{primary=$before;replica=(Owned 'api-replica').Id;redis=(Owned 'redis').Id;at=(Get-Date -Format o)}
  exit
}
if($Action -eq 'Pause'){
  $owned=Owned 'redis';if($owned.State.Paused){throw 'Fixture Redis is already paused'}
  Docker @('pause',$owned.Id) | Out-Null
  Save-Json 'redis-pause.json' @{id=$owned.Id;project=$state.Project;service='redis';at=(Get-Date -Format o)}
  exit
}
if($Action -eq 'GuardFailure'){
  Owned 'mysql' | Out-Null
  $ordinaryId=(Sql "SELECT id FROM user_account WHERE username='redis_learner'").Trim()
  $cached=@(Compose @('exec','-T','redis','redis-cli','-a','m4-redis-public-fixture-only','--no-auth-warning','--scan','--pattern',"forgeoj:v1:session:ordinary:$($ordinaryId):*"))
  if($cached.Count -eq 0){throw 'The positive ordinary identity cache must exist before the DB guard failure'}
  Sql "REVOKE SELECT ON forgeoj.login_session FROM 'forgeoj_api'@'%'" | Out-Null
  try {Compose @('exec','-T','frontend','node','/source/tools/validation/replay-redis.mjs','guard-failure') | Out-Host}
  finally {Sql "GRANT SELECT ON forgeoj.login_session TO 'forgeoj_api'@'%'" | Out-Null}
  Save-Json 'redis-guard-failure.json' @{scope='only-owned-test-db-login_session-table-select';positiveIdentityCacheKeys=$cached.Count;restored=$true;at=(Get-Date -Format o)}
  exit
}
if($Action -eq 'ResumeFlush'){
  $owned=Owned 'redis';$paused=Get-Content (Join-Path $RunDirectory 'redis-pause.json') -Raw | ConvertFrom-Json
  if($owned.Id -ne $paused.id -or -not $owned.State.Paused){throw 'Paused fixture ownership mismatch'}
  Docker @('unpause',$owned.Id) | Out-Null
  Compose @('exec','-T','redis','redis-cli','-a','m4-redis-public-fixture-only','--no-auth-warning','FLUSHDB') | Out-Null
  Save-Json 'redis-resume-flush.json' @{id=$owned.Id;at=(Get-Date -Format o);flush='only-owned-fixture'}
  exit
}
if($Action -in @('Healthy','Outage','Recovered','Warm')){
  Owned 'api' | Out-Null;Owned 'api-replica' | Out-Null
  Compose @('exec','-T','frontend','node','/source/tools/validation/replay-redis.mjs',$Action.ToLower()) | Out-Host
  exit
}
if($Action -eq 'Pending'){
  for($wait=0;$wait -lt 10;$wait++){
    $failed=[int](Sql "SELECT COUNT(*) FROM cache_invalidation_outbox WHERE delivered_at IS NULL AND attempts>=1 AND error_code='REDIS_UNAVAILABLE'")
    if($failed -ge 2){break};Start-Sleep -Seconds 1
  }
  if($failed -lt 2){throw 'Two durable Redis outage invalidations were not observed'}
  Save-Lines 'redis-pending.jsonl' (Sql "SELECT JSON_OBJECT('id',id,'namespace',namespace,'oldRevision',old_revision,'attempts',attempts,'errorCode',error_code,'delivered',delivered_at IS NOT NULL) FROM cache_invalidation_outbox ORDER BY id")
  exit
}
if($Action -eq 'CaptureRuntime'){
  $runtime=@()
  foreach($name in @('api','api-replica','worker','redis')){
    $owned=Owned $name;$jar=$null
    if($name -in @('api','api-replica','worker')){
      $file=if($name -eq 'worker'){'/app/worker.jar'}else{'/app/api.jar'}
      if(@($owned.Mounts | Where-Object {$_.Destination -eq $file -and -not $_.RW}).Count -ne 1){throw 'Runtime JAR is not mounted read-only'}
      $jar=((Docker @('exec',$owned.Id,'sha256sum',$file)) -split ' ')[0]
      if($jar -ne $(if($name -eq 'worker'){$state.WorkerHash}else{$state.ApiHash})){throw 'Runtime JAR differs from reviewed Linux input'}
    }
    $version=if($name -eq 'redis'){(Docker @('exec',$owned.Id,'redis-server','--version')) -join ' '}else{$null}
    $runtime+=@{service=$name;id=$owned.Id;image=$owned.Image;configuredImage=$owned.Config.Image;serverVersion=$version;project=$owned.Config.Labels.'com.docker.compose.project';jarSha256=$jar;hasDockerSocket=[bool]($owned.Mounts | Where-Object Destination -eq '/var/run/docker.sock');envNames=@($owned.Config.Env | ForEach-Object {($_ -split '=',2)[0]})}
  }
  if(($runtime | Where-Object service -in @('api','api-replica','redis')).hasDockerSocket -contains $true){throw 'Redis/API cannot receive Docker socket'}
  if(($runtime | Where-Object service -eq 'worker').envNames -match 'REDIS'){throw 'Worker cannot receive Redis configuration'}
  Save-Json 'redis-runtime.json' $runtime
  & (Join-Path $PSScriptRoot 'Verify-M3TeacherReplay.ps1') -Action CaptureRuntime -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  $servedCapture=@'
const fs=require('node:fs'),crypto=require('node:crypto'),lines=[];function walk(p){for(const e of fs.readdirSync('/workspace/ForgeOJ/'+p,{withFileTypes:true})){if(['node_modules','dist','target','.git','.idea','replay-vite.mjs'].includes(e.name)||e.name.endsWith('.local')||e.name.startsWith('.env'))continue;const f=p+'/'+e.name;if(e.isDirectory())walk(f);else lines.push(crypto.createHash('sha256').update(fs.readFileSync('/workspace/ForgeOJ/'+f)).digest('hex')+'  '+f)}}walk('frontend');fs.writeFileSync('/reports/frontend-served-runtime.sha256',lines.sort().join('\n')+'\n');console.log(JSON.stringify({actualServedInputs:lines.length}));
'@
  Compose @('exec','-T','frontend','node','-e',$servedCapture) | Out-Host
  foreach($name in @('api','api-replica','worker')){Save-Lines ("redis-"+$name+".log") (Docker @('logs',(Owned $name).Id))}
  exit
}
if($Action -eq 'CleanupSnapshot'){
  & (Join-Path $PSScriptRoot 'Verify-M3AssignmentReplay.ps1') -Action CleanupSnapshot -RunDirectory $RunDirectory -DockerCommand $DockerCommand
  Copy-Item -LiteralPath (Join-Path $RunDirectory 'assignment-cleanup.json') -Destination (Join-Path $RunDirectory 'redis-cleanup.json')
  exit
}
if($Action -eq 'Audit'){
  $owned=Owned 'redis';if($owned.State.Paused){throw 'Cannot accept a paused Redis fixture'}
  $pending=[int](Sql "SELECT COUNT(*) FROM cache_invalidation_outbox WHERE delivered_at IS NULL")
  if($pending -ne 0){throw 'Cache invalidation replay is still pending'}
  $keys=@(Compose @('exec','-T','redis','redis-cli','-a','m4-redis-public-fixture-only','--no-auth-warning','--scan','--pattern','forgeoj:v1:*'))
  if($keys.Count -gt 2000){throw 'Unexpected fixture key count'}
  $facts=@()
  foreach($key in $keys){
    $ttl=[int](Compose @('exec','-T','redis','redis-cli','-a','m4-redis-public-fixture-only','--no-auth-warning','PTTL',$key))
    # SCAN is a live snapshot; an entry that actually expired before inspection is absent.
    if($ttl -eq -2){continue}
    if($key -match '^forgeoj:v1:public:[0-9]+:[a-f0-9]{64}$'){
      $entry=(Compose @('exec','-T','redis','redis-cli','-a','m4-redis-public-fixture-only','--no-auth-warning','GET',$key)) -join "`n"
      $parsed=$entry | ConvertFrom-Json;$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($parsed.body))).ToLower()
      if($parsed.requestDigest -ne ($key -split ':')[-1] -or $digest -ne $parsed.bodySha256 -or $ttl -le 0 -or $ttl -gt 300000){throw 'Public cache binding/TTL invalid'}
      $body=$parsed.body | ConvertFrom-Json
      if($body -and $body.PSObject.Properties.Name -contains 'slug'){
        if(@($body.PSObject.Properties.Name | Where-Object {$_ -notin @('slug','title','statement','inputDescription','outputDescription','publicSamples','judgeVersion','resourceLimits','attribution')}).Count){throw 'Unexpected public detail field'}
      }elseif($body -and $body.PSObject.Properties.Name -contains 'items'){
        if($parsed.body -match '"(completed|completedCount|sourceCode|solutionCode|idea|passwordHash|testCases)"'){throw 'Private fields entered official cache'}
      }elseif($null -ne $body){throw 'Unexpected public cache type'}
      $facts+=@{kind='public';key=$key;ttlMs=$ttl;bodySha256=$digest}
    }elseif($key -like 'forgeoj:v1:session:*'){
      $identity=((Compose @('exec','-T','redis','redis-cli','-a','m4-redis-public-fixture-only','--no-auth-warning','GET',$key)) -join "`n") | ConvertFrom-Json
      if(@($identity.PSObject.Properties.Name).Count -ne 1 -or $identity.PSObject.Properties.Name -ne 'username' -or $ttl -le 0 -or $ttl -gt 60000){throw 'Session cache whitelist/TTL invalid'}
      $facts+=@{kind='session';ttlMs=$ttl}
    }
  }
  if(-not ($facts | Where-Object kind -eq 'public') -or -not ($facts | Where-Object kind -eq 'session')){throw 'Expected reconstructed public and session caches'}
  Save-Json 'redis-cache-facts.json' @{keyCount=$keys.Count;facts=$facts;pendingInvalidations=$pending;checkedAt=(Get-Date -Format o)}
  Save-Lines 'redis-outbox.jsonl' (Sql "SELECT JSON_OBJECT('id',id,'namespace',namespace,'oldRevision',old_revision,'attempts',attempts,'errorCode',error_code,'delivered',delivered_at IS NOT NULL) FROM cache_invalidation_outbox ORDER BY id")
  Save-Lines 'redis-submissions.jsonl' (Sql "SELECT JSON_OBJECT('id',s.id,'request',s.client_request_id,'status',s.processing_status,'verdict',s.verdict,'sourceSha256',s.source_sha256,'taskCount',(SELECT COUNT(*) FROM judge_task j WHERE j.submission_id=s.id),'eventCount',(SELECT COUNT(*) FROM outbox_event o JOIN judge_task j ON j.id=o.aggregate_id WHERE j.submission_id=s.id)) FROM submission s JOIN user_account u ON u.id=s.user_id WHERE u.username='redis_learner' ORDER BY s.created_at")
  Save-Lines 'redis-formal-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',s.id,'taskId',t.id,'taskStatus',t.task_status,'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL,'closedAttempts',(SELECT COUNT(*) FROM judge_task_attempt a WHERE a.judge_task_id=t.id AND a.attempt_status='SUCCEEDED' AND a.finished_at IS NOT NULL),'published',(SELECT published_at IS NOT NULL AND failed_at IS NULL FROM outbox_event o WHERE o.aggregate_id=t.id AND o.aggregate_type='JUDGE_TASK')) FROM submission s JOIN judge_task t ON t.submission_id=s.id JOIN user_account u ON u.id=s.user_id WHERE u.username='redis_learner' ORDER BY s.created_at")
  Save-Lines 'redis-assignment-facts.jsonl' (Sql "SELECT JSON_OBJECT('submissionId',s.id,'assignmentId',a.id,'userId',s.user_id,'acceptedAt',x.accepted_at,'finishedAt',s.finished_at,'deadlineAt',a.deadline_at,'status',s.processing_status,'verdict',s.verdict,'bindingsMatch',s.problem_id=x.problem_id AND s.judge_version_id=x.judge_version_id AND s.user_id=x.user_id,'resourcesMatch',s.time_limit_ms=v.time_limit_ms AND s.memory_limit_mb=v.memory_limit_mb AND s.output_limit_bytes=v.output_limit_bytes AND s.test_dataset_sha256=v.test_dataset_sha256,'taskStatus',t.task_status,'attempts',t.attempt_count,'leaseCleared',t.lease_owner IS NULL AND t.lease_token IS NULL AND t.lease_expires_at IS NULL) FROM assignment_attempt x JOIN classroom_assignment a ON a.id=x.assignment_id JOIN submission s ON s.id=x.submission_id JOIN judge_task t ON t.submission_id=s.id JOIN problem_judge_version v ON v.id=x.judge_version_id ORDER BY x.accepted_at,s.id")
  Save-Lines 'redis-queues.tsv' (Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','messages_ready','messages_unacknowledged','--no-table-headers'))
  $denials=@()
  foreach($probe in @(@{user='forgeoj_api';pass='m1-e2e-api-test-secret';sql='DELETE FROM cache_epoch'},@{user='forgeoj_api';pass='m1-e2e-api-test-secret';sql='DELETE FROM cache_invalidation_outbox'},@{user='forgeoj_worker';pass='m1-e2e-worker-test-secret';sql='SELECT * FROM cache_epoch'},@{user='forgeoj_worker';pass='m1-e2e-worker-test-secret';sql='SELECT * FROM cache_invalidation_outbox'})){
    $sql=$probe.sql
    $sql | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql mysql "-u$($probe.user)" "--password=$($probe.pass)" forgeoj 2> (Join-Path $RunDirectory 'redis-sql-denial.tmp') | Out-Null
    $exit=$LASTEXITCODE;$errorText=Get-Content (Join-Path $RunDirectory 'redis-sql-denial.tmp') -Raw
    if($exit -eq 0 -or $errorText -notmatch '1142|1143'){throw 'Required Redis SQL boundary was not denied'}
    $denials+=@{user=$probe.user;sql=$sql;exitCode=$exit;denied=$true}
  }
  Save-Json 'redis-sql-denials.json' $denials
  Write-Host 'Redis cache, durable invalidation, same-key facts and four SQL denials passed'
}
