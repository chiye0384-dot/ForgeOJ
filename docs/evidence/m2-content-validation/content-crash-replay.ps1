# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Test-only process control for an explicitly named disposable Replay stack.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][ValidatePattern('^[a-f0-9-]{36}$')][string]$JobId)
$ErrorActionPreference='Stop'
$cursor=Get-Item -LiteralPath $PSScriptRoot
while($cursor -and -not (Test-Path -LiteralPath (Join-Path $cursor.FullName 'tools/validation/compose.replay.yml'))) {$cursor=$cursor.Parent}
if(-not $cursor) {throw 'Cannot locate the ForgeOJ repository'}
$repo=$cursor.FullName
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
if(-not $run.StartsWith((Join-Path $repo 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Replay must be inside this repository target'}
$state=Get-Content -LiteralPath (Join-Path $run 'state.json') -Raw | ConvertFrom-Json
if($state.Project -notmatch '^forgeoj-e2e-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}$') {throw 'Invalid disposable project'}
$env:FORGEOJ_E2E_SOURCE=$repo;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$run
$compose=Join-Path $repo 'tools/validation/compose.replay.yml'
function Checked([string[]]$Arguments) {$value=& docker @Arguments;if($LASTEXITCODE -ne 0){throw 'Docker control failed'};return $value}
function Sql([string]$Query) {
    $value=$Query | & docker compose --project-name $state.Project -f $compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names forgeoj' 2>$null
    if($LASTEXITCODE -ne 0){throw 'Disposable fact query failed'};return $value
}
function Fact {
    Sql "SELECT JSON_OBJECT('jobId',id,'processingStatus',processing_status,'attemptCount',attempt_count,'validationStatus',validation_status,'referenceResult',reference_result,'solutionResult',solution_result,'leaseValid',lease_expires_at>CURRENT_TIMESTAMP(6),'leaseCleared',lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL) FROM content_validation_job WHERE id='$JobId';" | ConvertFrom-Json
}
function Sandboxes { @(Checked @('ps','-aq','--no-trunc','--filter','label=com.forgeoj.managed=true','--filter',"label=com.forgeoj.task-id=$JobId")) }
$beforeCount=[int](Sql 'SELECT COUNT(*) FROM submission;')
$worker=(Checked @('compose','--project-name',$state.Project,'-f',$compose,'ps','-q','worker')).Trim()
if($worker -notmatch '^[a-f0-9]{64}$') {throw 'No exact Worker'}
$labels=Checked @('inspect','--format','{{json .Config.Labels}}',$worker) | ConvertFrom-Json
if($labels.'com.docker.compose.project' -ne $state.Project -or $labels.'com.docker.compose.service' -ne 'worker') {throw 'Worker ownership mismatch'}
$deadline=(Get-Date).AddSeconds(45)
do {
    $before=Fact;$old=Sandboxes
    if($before.processingStatus -eq 'RUNNING' -and $before.attemptCount -eq 1 -and $before.leaseValid -and $old.Count -eq 1) {
        if((Checked @('inspect','--format','{{.State.Running}}',$old[0])).Trim() -eq 'true') {break}
    }
    Start-Sleep -Milliseconds 100
} while((Get-Date) -lt $deadline)
if($before.processingStatus -ne 'RUNNING' -or $before.attemptCount -ne 1 -or -not $before.leaseValid -or $old.Count -ne 1) {throw 'No live first attempt/sandbox to interrupt'}
$attempt=(Sql "SELECT id FROM content_validation_attempt WHERE job_id='$JobId' AND attempt_no=1;").Trim()
$killedAt=(Get-Date).ToUniversalTime().ToString('o')
Checked @('kill','--signal','KILL',$worker) | Out-Null
if((Checked @('inspect','--format','{{.State.Running}}',$worker)).Trim() -ne 'false') {throw 'Worker was not killed'}
if((Fact).processingStatus -ne 'RUNNING') {throw 'No durable RUNNING fact after kill'}
Checked @('start',$worker) | Out-Null
$deadline=(Get-Date).AddSeconds(150)
do {$after=Fact;if($after.processingStatus -in @('FINISHED','SYSTEM_ERROR')){break};Start-Sleep -Milliseconds 250} while((Get-Date) -lt $deadline)
if($after.processingStatus -ne 'FINISHED' -or $after.validationStatus -ne 'PASSED' -or $after.attemptCount -ne 2 -or -not $after.leaseCleared) {throw 'Automatic content recovery did not finish both programs'}
$statuses=Sql "SELECT attempt_status FROM content_validation_attempt WHERE job_id='$JobId' ORDER BY attempt_no;"
if(($statuses -join ',') -ne 'LEASE_EXPIRED,SUCCEEDED') {throw 'Attempt closure history mismatch'}
$deadline=(Get-Date).AddSeconds(15)
do {$remaining=Sandboxes;if(-not $remaining.Count){break};Start-Sleep -Milliseconds 250} while((Get-Date) -lt $deadline)
if($remaining.Count) {throw 'Old/new content sandbox remains'}
$afterCount=[int](Sql 'SELECT COUNT(*) FROM submission;');if($beforeCount -ne $afterCount){throw 'Content validation manufactured a formal Submission'}
$events=[int](Sql "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='CONTENT_VALIDATION' AND aggregate_id='$JobId' AND published_at IS NOT NULL AND failed_at IS NULL;")
if($events -ne 2){throw 'Recovery Outbox was not actually confirmed/published'}
$proof=[ordered]@{verifiedAt=(Get-Date).ToUniversalTime().ToString('o');jobId=$JobId;workerContainerId=$worker;oldAttemptId=$attempt;oldSandboxIds=$old;killedAt=$killedAt;killConfirmed=$true;before=$before;after=$after;attemptStatuses=@($statuses);oldSandboxRemoved=$true;formalSubmissionCountBefore=$beforeCount;formalSubmissionCountAfter=$afterCount;publishedContentOutbox=$events;naturalExpiry=$true}
$proof | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'content-crash.json') -Encoding utf8
Write-Host 'REAL_CONTENT_WORKER_KILL_NATURAL_EXPIRY_RECOVERY_VERIFIED'
