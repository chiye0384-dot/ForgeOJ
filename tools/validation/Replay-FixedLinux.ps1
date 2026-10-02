# Copyright 2026 池也
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [ValidateSet('Start','Status','Matrix','WorkerStart','WorkerStop','Permissions','Audit','Stop')]
    [string]$Action = 'Start',
    [string]$RunDirectory,
    [ValidatePattern('^$|^[a-f0-9-]{36}$')]
    [string]$SubmissionId = '',
    [string]$BuildDirectory = 'target/forgeoj-linux-20261002-084610-f76a1ccc',
    [string]$ApiSha256 = '236e2352d2d67d1885dbf8905b0e6adb82479c5732c44b64efd326d32b4de650',
    [string]$WorkerSha256 = '60d9f0aa42d143e95a3036984b48096967548d61bb52b0e391ccfa3d3fe46d5b',
    [string]$DockerCommand = 'docker'
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composeFile = Join-Path $PSScriptRoot 'compose.replay.yml'
function Docker-Checked([string[]]$Arguments) {
    $output = & $DockerCommand @Arguments
    if ($LASTEXITCODE -ne 0) {
        $output | Out-Host
        throw "Docker command failed ($LASTEXITCODE)"
    }
    return $output
}
function Compose-Checked([string[]]$Arguments) {
    Docker-Checked (@('compose','--project-name',$state.Project,'-f',$composeFile) + $Arguments)
}
function Set-ReplayEnvironment {
    $env:FORGEOJ_E2E_SOURCE = $repository
    $env:FORGEOJ_E2E_PROJECT = $state.Project
    $env:FORGEOJ_E2E_ARTIFACTS = $state.BuildDirectory
    $env:FORGEOJ_E2E_REPORTS = $RunDirectory
}
function Save-State { $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $RunDirectory 'state.json') -Encoding utf8 }
function Sql-Fixture([string]$Sql) {
    $output = $Sql | & $DockerCommand compose --project-name $state.Project -f $composeFile exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --batch --skip-column-names forgeoj'
    if ($LASTEXITCODE -ne 0) { throw 'Disposable fixture SQL failed' }
    return $output
}

if ($Action -eq 'Start') {
    $build = (Resolve-Path (Join-Path $repository $BuildDirectory)).Path
    $apiHash = (Get-FileHash "$build/forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar").Hash.ToLower()
    $workerHash = (Get-FileHash "$build/forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar").Hash.ToLower()
    if ($ApiSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $WorkerSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $apiHash -ne $ApiSha256.ToLower() -or $workerHash -ne $WorkerSha256.ToLower()) {
        throw 'This replay requires the recorded Linux artifacts; review new hashes before accepting a rebuild.'
    }
    if (Docker-Checked @('ps','-aq','--filter','label=com.forgeoj.managed=true')) { throw 'Another managed sandbox exists; do not remove it or run concurrently.' }
    $project = 'forgeoj-e2e-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $RunDirectory = (New-Item -ItemType Directory -Path (Join-Path $repository "target/$project")).FullName
    $state = [pscustomobject]@{Project=$project;BuildDirectory=$build;ApiHash=$apiHash;WorkerHash=$workerHash;SourceCommit=(git -C $repository rev-parse HEAD);StartedAt=(Get-Date -Format o);FrontendUrl='';FallbackUrl=''}
    Set-ReplayEnvironment
    Save-State
    Write-Host "Replay state: $RunDirectory"
    Compose-Checked @('up','--detach','--wait','--wait-timeout','180','mysql','rabbitmq','bootstrap')
    # V1-V5 + dev seed via real Flyway, then remove the migrator-credential process.
    Compose-Checked @('stop','-t','10','bootstrap')
    Compose-Checked @('rm','--force','bootstrap')
    Sql-Fixture (Get-Content (Join-Path $PSScriptRoot 'replay-fixture.sql') -Raw)
    Compose-Checked @('up','--detach','--build','--wait','--wait-timeout','180','api','frontend','worker')
    $state.FrontendUrl = 'http://' + (Compose-Checked @('port','frontend','5173')).Trim()
    $state.FallbackUrl = 'http://' + (Compose-Checked @('port','frontend','5174')).Trim()
    Save-State
    $state | ConvertTo-Json
    exit
}

$RunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
$allowedParent = Join-Path $repository 'target'
if (-not $RunDirectory.StartsWith($allowedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'State directory must be inside this repository target' }
$state = Get-Content -LiteralPath (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if ($state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$') { throw 'Invalid test project identity' }
if ((Split-Path -Leaf $RunDirectory) -ne $state.Project) { throw 'State directory/project ownership mismatch' }
Set-ReplayEnvironment
switch ($Action) {
    'Status' {
        $state.FrontendUrl = 'http://' + (Compose-Checked @('port','frontend','5173')).Trim()
        $state.FallbackUrl = 'http://' + (Compose-Checked @('port','frontend','5174')).Trim()
        Save-State
        $state | ConvertTo-Json
    }
    'Matrix' { Compose-Checked @('exec','-T','frontend','node','/source/tools/validation/replay-probe.mjs','matrix') }
    'Permissions' { Compose-Checked (@('exec','-T','frontend','node','/source/tools/validation/replay-probe.mjs','permissions') + $(if($SubmissionId) { @($SubmissionId) } else { @() })) }
    'WorkerStop' { Compose-Checked @('stop','-t','10','worker') }
    'WorkerStart' { Compose-Checked @('up','--detach','worker') }
    'Audit' {
        # Export only platform identifiers/states: never source, tests, hashes or diagnostics.
        $facts = Sql-Fixture @'
SELECT JSON_OBJECT('submissionId',s.id,'processingStatus',s.processing_status,'verdict',s.verdict,
 'statusVersion',s.status_version,'judgeTaskId',jt.id,'taskStatus',jt.task_status,
 'taskVersion',jt.status_version,'attemptCount',jt.attempt_count,
 'leaseCleared',jt.lease_owner IS NULL AND jt.lease_token IS NULL AND jt.lease_expires_at IS NULL,
 'attempts',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('attemptId',a.id,'attemptNo',a.attempt_no,
 'status',a.attempt_status,'finished',a.finished_at IS NOT NULL)) FROM judge_task_attempt a
 WHERE a.judge_task_id=jt.id),JSON_ARRAY()))
 FROM submission s JOIN judge_task jt ON jt.submission_id=s.id ORDER BY s.created_at;
'@
        $facts | Set-Content -LiteralPath (Join-Path $RunDirectory 'database.jsonl') -Encoding utf8
        $outbox = Sql-Fixture @'
SELECT JSON_OBJECT('outboxEventId',id,'judgeTaskId',aggregate_id,'published',published_at IS NOT NULL,
 'failed',failed_at IS NOT NULL,'sequenceNo',sequence_no,'keys',JSON_KEYS(payload)) FROM outbox_event;
'@
        $outbox | Set-Content -LiteralPath (Join-Path $RunDirectory 'outbox.jsonl') -Encoding utf8
        $queues = Compose-Checked @('exec','-T','rabbitmq','rabbitmqctl','--quiet','list_queues','-p','/forgeoj','name','messages_ready','messages_unacknowledged','--formatter','json')
        $queues | Set-Content -LiteralPath (Join-Path $RunDirectory 'queues.json') -Encoding utf8
        foreach($service in @('api','worker','frontend')) {
            $id = (Compose-Checked @('ps','-q',$service)).Trim()
            $logs = Docker-Checked @('logs',$id)
            $logs | Set-Content -LiteralPath (Join-Path $RunDirectory "$service.log") -Encoding utf8
            Write-Host "$service log captured"
        }
        $apiId = (Compose-Checked @('ps','-q','api')).Trim()
        $api = (Docker-Checked @('inspect',$apiId) | ConvertFrom-Json)[0]
        if ($api.Mounts.Destination -contains '/var/run/docker.sock' -or $api.Config.Env -match 'MIGRATOR|FORGEOJ_WORKER|DOCKER_') { throw 'Runtime API boundary violation' }
        if ($api.Config.User -ne '65534:65534' -or -not $api.HostConfig.ReadonlyRootfs -or
            $api.HostConfig.PortBindings.PSObject.Properties.Count -gt 0) { throw 'Runtime API deployment boundary violation' }
        if (Compose-Checked @('ps','-aq','bootstrap')) { throw 'Migrator-credential process remains' }
        $denials = @(
            @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','SELECT id FROM problem_test_case LIMIT 0;'),
            @('forgeoj_api','FORGEOJ_API_DB_PASSWORD','SELECT id FROM judge_task_attempt LIMIT 0;'),
            @('forgeoj_worker','FORGEOJ_WORKER_DB_PASSWORD','SELECT id FROM user_account LIMIT 0;')
        )
        foreach($denial in $denials) {
            # Inputs are the fixed roles/queries above, not user-controlled shell text.
            $command = 'exec mysql -h127.0.0.1 -u' + $denial[0] + ' --password="$' + $denial[1] + '" forgeoj'
            $result = $denial[2] | & $DockerCommand compose --project-name $state.Project -f $composeFile exec -T mysql sh -c $command 2>&1
            if ($LASTEXITCODE -eq 0 -or "$result" -notmatch 'ERROR 1142') { throw 'Expected database privilege denial missing' }
        }
        if (Docker-Checked @('ps','-aq','--filter','label=com.forgeoj.managed=true')) { throw 'Managed sandbox residue remains' }
        Write-Host 'API has no Docker/Worker/migrator configuration or socket mount.'
        Compose-Checked @('exec','-T','frontend','node','/source/tools/validation/replay-audit.mjs')
    }
    'Stop' {
        # Resolve and inspect exact project resources before deleting this disposable DB/queue.
        foreach($id in (Docker-Checked @('ps','-aq','--filter',"label=com.docker.compose.project=$($state.Project)"))) {
            $owned = (Docker-Checked @('inspect',$id) | ConvertFrom-Json)[0]
            if ($owned.Config.Labels.'com.docker.compose.project' -ne $state.Project) { throw 'Project ownership mismatch' }
        }
        if (Docker-Checked @('ps','-aq','--filter','label=com.forgeoj.managed=true')) { throw 'Managed sandboxes remain; resolve exact ownership before removing the database.' }
        Compose-Checked @('--profile','worker','--profile','bootstrap','down','--volumes','--remove-orphans')
        $image = Docker-Checked @('image','ls','-q',"$($state.Project)-worker")
        if ($image) { Docker-Checked @('image','rm',"$($state.Project)-worker") }
        if (Docker-Checked @('ps','-aq','--filter',"label=com.docker.compose.project=$($state.Project)")) { throw 'Owned containers remain' }
        if (Docker-Checked @('volume','ls','-q','--filter',"label=com.docker.compose.project=$($state.Project)")) { throw 'Owned volumes remain' }
        if (Docker-Checked @('network','ls','-q','--filter',"label=com.docker.compose.project=$($state.Project)")) { throw 'Owned network remains' }
        Write-Host 'Only this disposable stack/data/worker image removed; local reports retained.'
    }
}
