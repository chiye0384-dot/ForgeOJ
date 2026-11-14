# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run=(Resolve-Path -LiteralPath (Join-Path $repo $RunDirectory)).Path
if(-not $run.StartsWith((Join-Path $repo 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $run -Leaf) -notmatch '^forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Owned benchmark directory required'}
$state=Get-Content (Join-Path $run 'validation-state.json') -Raw | ConvertFrom-Json
$summary=Get-Content (Join-Path $run 'judge-performance/summary.json') -Raw | ConvertFrom-Json
$probe=Get-Content (Join-Path $run 'judge-performance/probe-image.json') -Raw | ConvertFrom-Json
if($summary.status -ne 'VERIFIED' -or $state.project -notmatch '^forgeoj-m5check-[a-f0-9]{12}$' -or $probe.tag -ne "$($state.project)-judge-probe"){throw 'Verified owned benchmark required; failed jobs must first be inspected'}
$actual=& $DockerCommand image inspect --format '{{.Id}}' $probe.tag
if($LASTEXITCODE -ne 0 -or $actual -ne $probe.id){throw 'Instrumentation image identity mismatch'}
$managed=@(& $DockerCommand ps -aq --filter 'label=com.forgeoj.managed=true')
if($LASTEXITCODE -ne 0 -or $managed.Count){throw 'Active/retained sandbox exists; inspect ownership, do not delete it blindly'}
foreach($name in @('api','worker','mysql')){
 $owner=& $DockerCommand inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$($state.project)-$name-1"
 if($LASTEXITCODE -ne 0 -or $owner -ne $state.project){throw 'Container owner mismatch'}
}
& $DockerCommand compose --project-name $state.project --env-file $state.instance -f (Join-Path $run 'package/compose.yml') down --volumes *> (Join-Path $run 'judge-performance/cleanup.log')
if($LASTEXITCODE -ne 0){throw 'Exact project cleanup failed'}
& $DockerCommand image rm $probe.tag | Out-Null
if($LASTEXITCODE -ne 0){throw 'Instrumentation image removal failed; no force used'}
& (Join-Path $repo 'tools/deployment/Stop-ProductionPackageCheck.ps1') -RunDirectory $run -DockerCommand $DockerCommand
if(-not (Test-Path (Join-Path $run 'cleanup-result.json'))){throw 'Base cleanup not verified'}
@{status='VERIFIED';project=$state.project;instrumentationTagRemoved=$probe.tag;instrumentationImageId=$probe.id;managedSandboxes=0;originalDemoTargeted=$false;checkedAt=(Get-Date -Format o)} | ConvertTo-Json | Set-Content (Join-Path $run 'judge-performance/judge-cleanup.json') -Encoding utf8
