# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('FaultInstall','FaultRestore','Fault','Recover','DeliveryCreate','DeliveryRecover','BrowserAudit','RevokeOps','CaptureRuntime','Audit','CleanupSnapshot')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
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
function OwnedWorker {
 $id=(Compose @('ps','-q','worker')).Trim();$container=(Docker @('inspect',$id) | ConvertFrom-Json)[0]
 if($container.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $container.Config.Labels.'com.docker.compose.service' -ne 'worker'){throw 'Exact Worker ownership mismatch'}
 $hash=((Compose @('exec','-T','worker','sha256sum','/app/worker.jar')) -split '\s+')[0]
 if($hash -ne $state.WorkerHash){throw 'Owned Worker JAR mismatch'}
 return $id
}
function Frozen {
 Sql @'
SELECT JSON_OBJECT('kind','FORMAL','taskId',j.id,'binding',s.id,'fingerprint',SHA2(CAST(JSON_OBJECT('source',s.source_sha256,'sourceDigest',SHA2(s.source_code,256),'version',s.judge_version_id,'problem',s.problem_id,'created',s.created_at,'assignmentAcceptedAt',(SELECT accepted_at FROM assignment_attempt a WHERE a.submission_id=s.id),'image',s.java_image_digest,'dataset',s.test_dataset_sha256,'time',s.time_limit_ms,'memory',s.memory_limit_mb,'output',s.output_limit_bytes,'comparison',s.comparison_rule_version,'sandbox',s.sandbox_policy_version) AS CHAR),256)) FROM judge_task j JOIN submission s ON s.id=j.submission_id
UNION ALL SELECT JSON_OBJECT('kind',j.execution_kind,'taskId',j.id,'binding',s.id,'fingerprint',SHA2(CAST(JSON_OBJECT('snapshot',s.snapshot_sha256,'reference',SHA2(s.reference_code,256),'solution',SHA2(s.solution_code,256),'metadata',SHA2(s.metadata_text,256),'draft',s.draft_id,'version',s.draft_version,'image',s.java_image_digest,'dataset',s.test_dataset_sha256,'time',s.time_limit_ms,'memory',s.memory_limit_mb,'output',s.output_limit_bytes,'comparison',s.comparison_rule_version,'sandbox',s.sandbox_policy_version) AS CHAR),256)) FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id
UNION ALL SELECT JSON_OBJECT('kind','SELF_TEST','taskId',j.id,'binding',s.id,'fingerprint',SHA2(CAST(JSON_OBJECT('snapshot',s.snapshot_sha256,'source',s.source_sha256,'input',s.input_sha256,'version',s.judge_version_id,'problem',s.problem_id,'image',s.java_image_digest,'time',s.time_limit_ms,'memory',s.memory_limit_mb,'output',s.output_limit_bytes,'comparison',s.comparison_rule_version,'sandbox',s.sandbox_policy_version) AS CHAR),256)) FROM self_test_job j JOIN self_test_snapshot s ON s.id=j.snapshot_id;
'@
}
if($Action -in @('FaultInstall','FaultRestore')){
 $id=OwnedWorker
 if($Action -eq 'FaultInstall'){
  Compose @('exec','-T','worker','sh','-c','test ! -e /tmp/forgeoj-operations-real-docker && mv /usr/local/bin/docker /tmp/forgeoj-operations-real-docker') | Out-Null
  Get-Content (Join-Path $PSScriptRoot 'operations-docker-fault.sh') -Raw | & $DockerCommand exec -i $id sh -c 'tr -d "\r" > /usr/local/bin/docker && chmod 755 /usr/local/bin/docker'
  if($LASTEXITCODE -ne 0){throw 'Owned Worker fault installation failed'}
 }else{Compose @('exec','-T','worker','sh','-c','test -f /tmp/forgeoj-operations-real-docker && mv /tmp/forgeoj-operations-real-docker /usr/local/bin/docker') | Out-Null}
 @{at=(Get-Date -Format o);action=$Action;containerId=$id;workerJar=$state.WorkerHash;wrapperHash=(Get-FileHash (Join-Path $PSScriptRoot 'operations-docker-fault.sh')).Hash.ToLower()} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory ("operations-"+$Action.ToLower()+'.json')) -Encoding utf8
 exit
}
if($Action -eq 'DeliveryCreate'){
 $apiId=(Compose @('ps','-q','api')).Trim();$api=(Docker @('inspect',$apiId) | ConvertFrom-Json)[0]
 if($api.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $api.Config.Labels.'com.docker.compose.service' -ne 'api'){throw 'API ownership mismatch'}
 @{apiId=$apiId;capturedAt=(Get-Date -Format o)} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'operations-delivery-ownership.json') -Encoding utf8
 # Only this owned broker queue; baseline Matrix and permission replay must finish first.
 $id=(Compose @('ps','-q','rabbitmq')).Trim();$container=(Docker @('inspect',$id) | ConvertFrom-Json)[0]
 if($container.Config.Labels.'com.docker.compose.project' -ne $state.Project){throw 'Broker ownership mismatch'}
 # Stop only the owned Worker so it cannot redeclare during the exhausted publisher experiment.
 $workerId=OwnedWorker;Docker @('stop','--time','10',$workerId) | Out-Null
 Compose @('exec','-T','rabbitmq','rabbitmqctl','delete_queue','--vhost','/forgeoj','forgeoj.judge.submission.v1') | Out-Host
}
if($Action -eq 'DeliveryRecover'){
 # Restart only the stopped owned Worker; dependency recreation would discard API logs/CSRF state.
 Compose @('up','--detach','--no-deps','--no-recreate','worker') | Out-Null
 $deliveryOwnership=Get-Content (Join-Path $RunDirectory 'operations-delivery-ownership.json') -Raw | ConvertFrom-Json
 if((Compose @('ps','-q','api')).Trim() -ne $deliveryOwnership.apiId){throw 'Delivery replay must preserve the running API and its logs'}
 # Startup declares only the disposable stack's topology; old startup logs are not readiness proof.
 $ready=$false
 for($i=0;$i -lt 60;$i++) { $queues=Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','--no-table-headers');if(@($queues | Where-Object {$_ -eq 'forgeoj.judge.submission.v1'}).Count){$ready=$true;break};Start-Sleep -Seconds 1 }
 if(-not $ready){throw 'Owned formal queue was not redeclared'}
}
if($Action -in @('Fault','Recover','DeliveryCreate','DeliveryRecover','BrowserAudit','RevokeOps')){
 $phase=@{Fault='fault';Recover='recover';DeliveryCreate='delivery-create';DeliveryRecover='delivery-recover';BrowserAudit='browser-audit';RevokeOps='revoke-ops'}[$Action]
 Compose @('exec','-T','frontend','node','/source/tools/validation/replay-operations.mjs',$phase) | Out-Host
 if($Action -eq 'Fault'){Save 'operations-frozen-before.jsonl' (Frozen)}
 exit
}
if($Action -eq 'CaptureRuntime'){
 & (Join-Path $PSScriptRoot 'Verify-M4PublicReviewReplay.ps1') -Action CaptureRuntime -RunDirectory $RunDirectory -DockerCommand $DockerCommand
 Copy-Item -LiteralPath (Join-Path $RunDirectory 'public-review-runtime.json') -Destination (Join-Path $RunDirectory 'operations-runtime.json')
 Copy-Item -LiteralPath (Join-Path $RunDirectory 'public-review-jar-runtime.json') -Destination (Join-Path $RunDirectory 'operations-jar-runtime.json')
 $capture=@'
const fs=require('node:fs'),crypto=require('node:crypto'),lines=[];function walk(p){for(const e of fs.readdirSync('/workspace/ForgeOJ/'+p,{withFileTypes:true})){if(['node_modules','dist','target','.git','.idea','replay-vite.mjs'].includes(e.name)||e.name.endsWith('.local')||e.name.startsWith('.env'))continue;const f=p+'/'+e.name;if(e.isDirectory())walk(f);else lines.push(crypto.createHash('sha256').update(fs.readFileSync('/workspace/ForgeOJ/'+f)).digest('hex')+'  '+f)}}walk('frontend');fs.writeFileSync('/reports/frontend-served-runtime.sha256',lines.sort().join('\n')+'\n');console.log(JSON.stringify({actualServedInputs:lines.length}));
'@
 Compose @('exec','-T','frontend','node','-e',$capture) | Out-Host;exit
}
if($Action -eq 'CleanupSnapshot'){
 & (Join-Path $PSScriptRoot 'Verify-M3AssignmentReplay.ps1') -Action CleanupSnapshot -RunDirectory $RunDirectory -DockerCommand $DockerCommand
 Copy-Item -LiteralPath (Join-Path $RunDirectory 'assignment-cleanup.json') -Destination (Join-Path $RunDirectory 'operations-cleanup.json');exit
}
$lines=foreach($name in @('tools/validation/Verify-M4OperationsReplay.ps1','tools/validation/replay-operations.mjs','tools/validation/operations-docker-fault.sh','tools/validation/Replay-FixedLinux.ps1','tools/validation/Verify-M4AdminReplay.ps1','tools/validation/replay-admin-identity.mjs','tools/validation/Verify-M4PublicReviewReplay.ps1','tools/validation/Verify-M3TeacherReplay.ps1','tools/validation/Verify-M3AssignmentReplay.ps1','tools/validation/compose.replay.yml','tools/validation/start-replay-frontend.sh','tools/validation/replay-fixture.sql','tools/validation/replay-probe.mjs','tools/validation/replay-classroom.mjs')){(Get-FileHash (Join-Path $repository $name)).Hash.ToLower()+'  '+$name}
Save 'operations-tool-inputs.sha256' $lines
Save 'operations-frozen-after.jsonl' (Frozen)
Save 'operations-task-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('kind','FORMAL','id',j.id,'binding',s.id,'status',j.task_status,'userStatus',s.processing_status,'verdict',s.verdict,'attempts',j.attempt_count,'max',j.max_attempts,'leaseCleared',j.lease_token IS NULL AND j.lease_owner IS NULL AND j.lease_expires_at IS NULL,'sourceMatches',SHA2(s.source_code,256)=s.source_sha256) FROM judge_task j JOIN submission s ON s.id=j.submission_id
UNION ALL SELECT JSON_OBJECT('kind',execution_kind,'id',id,'binding',snapshot_id,'status',processing_status,'validation',validation_status,'reference',reference_result,'solution',solution_result,'attempts',attempt_count,'max',max_attempts,'leaseCleared',lease_token IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL) FROM content_validation_job
UNION ALL SELECT JSON_OBJECT('kind','SELF_TEST','id',id,'binding',snapshot_id,'status',processing_status,'result',execution_result,'attempts',attempt_count,'max',max_attempts,'leaseCleared',lease_token IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL) FROM self_test_job;
'@)
Save 'operations-receipt-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('id',id,'scope',recovery_scope,'kind',task_kind,'taskId',task_id,'targetId',target_id,'eventId',event_id,'oldStatus',previous_status,'oldVersion',previous_version,'oldAttempts',previous_attempts,'oldMax',previous_max_attempts,'oldFailure',previous_failure_code,'oldFinished',previous_finished_at,'newVersion',resulting_version,'createdAt',created_at,'actorId',actor_admin_id) FROM operations_recovery_request ORDER BY created_at,id;
'@)
Save 'operations-attempt-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('kind','FORMAL','taskId',judge_task_id,'id',id,'number',attempt_no,'status',attempt_status,'failureCode',failure_code,'finished',finished_at IS NOT NULL) FROM judge_task_attempt UNION ALL SELECT JSON_OBJECT('kind','CONTENT','taskId',job_id,'id',id,'number',attempt_no,'status',attempt_status,'failureCode',failure_code,'finished',finished_at IS NOT NULL) FROM content_validation_attempt UNION ALL SELECT JSON_OBJECT('kind','SELF_TEST','taskId',job_id,'id',id,'number',attempt_no,'status',attempt_status,'failureCode',failure_code,'finished',finished_at IS NOT NULL) FROM self_test_attempt;
'@)
Save 'operations-outbox-facts.jsonl' (Sql @'
SELECT JSON_OBJECT('id',id,'taskId',aggregate_id,'type',event_type,'sequence',sequence_no,'attempts',publish_attempts,'published',published_at IS NOT NULL,'failed',failed_at IS NOT NULL,'boundFields',JSON_LENGTH(payload)=4,'taskBinding',JSON_UNQUOTE(JSON_EXTRACT(payload,'$.taskId'))=aggregate_id,'contractVersion',JSON_EXTRACT(payload,'$.contractVersion')) FROM outbox_event ORDER BY created_at,id;
'@)
Save 'operations-audit-facts.jsonl' (Sql "SELECT JSON_OBJECT('id',id,'action',action,'actorId',actor_admin_id,'targetId',target_id,'correlationId',correlation_id,'outcome',outcome) FROM admin_audit_event ORDER BY occurred_at,id;")
$denials=0
foreach($sql in @("UPDATE submission SET verdict='AC' WHERE FALSE;","UPDATE submission SET source_code='changed' WHERE FALSE;","UPDATE judge_task SET attempt_count=0 WHERE FALSE;","UPDATE judge_task SET lease_token=NULL WHERE FALSE;","UPDATE content_validation_snapshot SET reference_code='changed' WHERE FALSE;","UPDATE content_validation_job SET validation_status='PASSED' WHERE FALSE;","UPDATE self_test_job SET execution_result='SUCCESS' WHERE FALSE;","UPDATE judge_task_attempt SET failure_code=NULL WHERE FALSE;","UPDATE operations_recovery_request SET reason='changed' WHERE FALSE;","DELETE FROM operations_recovery_request WHERE FALSE;","SELECT lease_token FROM judge_task_attempt LIMIT 0;","SELECT lease_token FROM content_validation_attempt LIMIT 0;","SELECT lease_token FROM self_test_attempt LIMIT 0;")){
 $result=$sql | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_api --password="$FORGEOJ_API_DB_PASSWORD" forgeoj' 2>&1
 if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142|ERROR 1143'){throw 'Expected API recovery privilege denial missing'};$denials++
}
$result='SELECT * FROM operations_recovery_request LIMIT 0;' | & $DockerCommand compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -h127.0.0.1 -uforgeoj_worker --password="$FORGEOJ_WORKER_DB_PASSWORD" forgeoj' 2>&1
if($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142'){throw 'Expected Worker receipt denial missing'};$denials++
Save 'operations-api.log' (Compose @('logs','--no-log-prefix','api'));Save 'operations-worker.log' (Compose @('logs','--no-log-prefix','worker'))
Save 'operations-queues.tsv' (Compose @('exec','-T','rabbitmq','rabbitmqctl','list_queues','--vhost','/forgeoj','name','messages_ready','messages_unacknowledged','--no-table-headers'))
$apiId=(Compose @('ps','-q','api')).Trim();$api=(Docker @('inspect',$apiId) | ConvertFrom-Json)[0]
if($api.Config.Labels.'com.docker.compose.project' -ne $state.Project -or @($api.Mounts | Where-Object {$_.Destination -like '*docker.sock*'}).Count -or @($api.Config.Env | Where-Object {$_ -match '(WORKER_DB|MIGRATOR_PASSWORD|ADMIN_CLI_DB|DOCKER_HOST)'}).Count){throw 'API execution/credential boundary violated'}
@{at=(Get-Date -Format o);allDenied=$true;denials=$denials;apiBoundary=$true} | ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'operations-denials.json') -Encoding utf8
