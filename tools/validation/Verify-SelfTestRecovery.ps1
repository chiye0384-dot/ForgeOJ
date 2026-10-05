# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Disposable replay only. Arm before clicking Run in the actual browser.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path $RunDirectory).Path
if (-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Recovery requires repository target replay'}
$state=Get-Content -LiteralPath (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if ($state.Project -ne (Split-Path -Leaf $RunDirectory) -or $state.Project -notmatch '^forgeoj-e2e-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}$') {throw 'Replay ownership mismatch'}
$composeFile=Join-Path $PSScriptRoot 'compose.replay.yml'
$env:FORGEOJ_E2E_SOURCE=$repository
$env:FORGEOJ_E2E_PROJECT=$state.Project
$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory
$env:FORGEOJ_E2E_REPORTS=$RunDirectory
$env:FORGEOJ_E2E_MAIL_APP_URL=$state.FrontendUrl
function Checked([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Owned Docker operation failed'};return $result}
function Compose([string[]]$Arguments){Checked (@('compose','--project-name',$state.Project,'-f',$composeFile)+$Arguments)}
function Sql([string]$Query){$result=$Query | & $DockerCommand compose --project-name $state.Project -f $composeFile exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj';if($LASTEXITCODE -ne 0){throw 'Disposable recovery fact read failed'};return ($result | Where-Object {$_ -match '^\{'} | ForEach-Object {$_ | ConvertFrom-Json})}
$baseline=@(Sql "SELECT JSON_OBJECT('id',id) FROM self_test_job;").id
$worker=(Compose @('ps','-q','worker')).Trim()
$owned=(Checked @('inspect',$worker)|ConvertFrom-Json)[0]
if ($owned.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $owned.Config.Labels.'com.docker.compose.service' -ne 'worker') {throw 'Worker ownership mismatch'}
Write-Host 'RECOVERY_ARMED: waiting for a new actual UI self-test and its managed sandbox'
$deadline=(Get-Date).AddSeconds(180);$claimed=$null;$sandbox=$null
while((Get-Date) -lt $deadline){
 foreach($row in @(Sql "SELECT JSON_OBJECT('id',j.id,'attemptId',a.id,'leaseValid',j.lease_expires_at>CURRENT_TIMESTAMP(6),'leaseExpiresAt',CAST(j.lease_expires_at AS CHAR)) FROM self_test_job j JOIN self_test_attempt a ON a.job_id=j.id AND a.lease_token=j.lease_token WHERE j.processing_status='RUNNING' AND a.attempt_status='RUNNING' AND j.lease_expires_at>CURRENT_TIMESTAMP(6);")) {
  if($baseline -contains $row.id){continue}
  if($row.id -notmatch '^[a-f0-9-]{36}$' -or $row.attemptId -notmatch '^[a-f0-9-]{36}$' -or -not $row.leaseValid){throw 'Invalid authoritative claim'}
  $candidate=Checked @('ps','-aq','--filter','label=com.forgeoj.managed=true','--filter',"label=com.forgeoj.task-id=$($row.id)",'--filter',"label=com.forgeoj.attempt-id=$($row.attemptId)")
  if($candidate){$claimed=$row;$sandbox=($candidate|Select-Object -First 1).Trim();break}
 }
 if($claimed){break};Start-Sleep -Milliseconds 150
}
if(-not $claimed){throw 'No new UI self-test sandbox observed; no Worker was killed'}
$labels=(Checked @('inspect',$sandbox)|ConvertFrom-Json)[0].Config.Labels
if($labels.'com.forgeoj.task-id' -ne $claimed.id -or $labels.'com.forgeoj.attempt-id' -ne $claimed.attemptId -or $labels.'com.forgeoj.managed' -ne 'true'){throw 'Sandbox ownership mismatch'}
Checked @('kill','--signal=KILL',$worker) | Out-Null
$killedAt=(Get-Date).ToUniversalTime().ToString('o')
Write-Host "SIGKILL_SENT: self-test $($claimed.id), attempt $($claimed.attemptId)"
Compose @('up','--detach','worker') | Out-Null
$deadline=(Get-Date).AddSeconds(150);$result=$null
while((Get-Date) -lt $deadline){
 $facts=@(Sql "SELECT JSON_OBJECT('id',j.id,'status',j.processing_status,'result',j.execution_result,'attempts',j.attempt_count,'leaseCleared',j.lease_token IS NULL,'statuses',(SELECT JSON_ARRAYAGG(a.attempt_status) FROM self_test_attempt a WHERE a.job_id=j.id)) FROM self_test_job j WHERE j.id='$($claimed.id)';")
 if($facts.Count -eq 1 -and $facts[0].status -eq 'FINISHED'){$result=$facts[0];break}
 Start-Sleep -Milliseconds 500
}
if(-not $result -or $result.result -ne 'SUCCESS' -or $result.attempts -ne 2 -or -not $result.leaseCleared -or ((@($result.statuses | Sort-Object) -join ',') -ne 'LEASE_EXPIRED,SUCCEEDED')){throw 'Natural self-test recovery incomplete'}
if(Checked @('ps','-aq','--filter',"label=com.forgeoj.task-id=$($claimed.id)")){throw 'Recovered self-test sandbox remains'}
$proof=[ordered]@{verifiedAt=(Get-Date).ToUniversalTime().ToString('o');runId=$claimed.id;oldAttemptId=$claimed.attemptId;oldSandboxId=$sandbox;killedWorkerId=$worker;killedAt=$killedAt;leaseExpiresAt=$claimed.leaseExpiresAt;realWorkerSigkill=$true;naturalLeaseExpiry=$true;oldSandboxRemoved=$true;attemptStatuses=@('LEASE_EXPIRED','SUCCEEDED');result='SUCCESS'}
$proof|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $RunDirectory 'self-test-crash.json') -Encoding utf8
Write-Host 'SELF_TEST_NATURAL_RECOVERY_VERIFIED'
