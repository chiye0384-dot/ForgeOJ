# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([string]$DockerCommand='docker',[string]$OutputFile='target/m3-gate-cleanup.json')
$ErrorActionPreference='Stop'
$taskRepository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput=[IO.Path]::GetFullPath((Join-Path $taskRepository $OutputFile))
if(-not $taskOutput.StartsWith((Join-Path $taskRepository 'target')+[IO.Path]::DirectorySeparatorChar)) {throw 'Cleanup proof must stay inside target'}
function Read-Docker([string[]]$Arguments) {
  $taskResult=& $DockerCommand @Arguments
  if($LASTEXITCODE -ne 0) {throw 'Cannot read Docker daemon state'}
  return $taskResult
}
$taskServer=Read-Docker @('version','--format','{{.Server.Version}}')
if(-not $taskServer) {throw 'Docker server unavailable'}
$taskCounts=[ordered]@{}
$taskCounts.builders=@(Read-Docker @('ps','-aq','--filter','name=forgeoj-linux-')).Count
$taskCounts.testcontainers=@(Read-Docker @('ps','-aq','--filter','label=org.testcontainers=true')).Count
$taskCounts.managedSandboxes=@(Read-Docker @('ps','-aq','--filter','label=com.forgeoj.managed=true')).Count
$taskCounts.replayContainers=@(Read-Docker @('ps','-a','--format','{{.Names}}') | Where-Object {$_ -match '^forgeoj-e2e-'}).Count
$taskCounts.replayVolumes=@(Read-Docker @('volume','ls','--format','{{.Name}}') | Where-Object {$_ -match '^forgeoj-e2e-'}).Count
$taskCounts.replayNetworks=@(Read-Docker @('network','ls','--format','{{.Name}}') | Where-Object {$_ -match '^forgeoj-e2e-'}).Count
$taskCounts.ownedImages=@(Read-Docker @('image','ls','--format','{{.Repository}}:{{.Tag}}') | Where-Object {$_ -match '^forgeoj-(e2e|linux)-'}).Count
if(@($taskCounts.Values | Where-Object {$_ -ne 0}).Count) {throw 'Test resources remain; this tool never stops or deletes them'}
$taskFacts=[ordered]@{checkedAt=[DateTimeOffset]::UtcNow.ToString('o');dockerAvailable=$true;readOnlyInspection=$true;counts=$taskCounts;collectorSha256=(Get-FileHash $PSCommandPath).Hash.ToLower()}
[IO.File]::WriteAllText($taskOutput,($taskFacts | ConvertTo-Json -Depth 4)+"`n",[Text.UTF8Encoding]::new($false))
$taskFacts | ConvertTo-Json -Depth 4
