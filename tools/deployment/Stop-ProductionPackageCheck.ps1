# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
if(-not $run.StartsWith((Join-Path $repo 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
   (Split-Path $run -Leaf) -notmatch '^forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Owned run directory required'}
$state=Get-Content -LiteralPath (Join-Path $run 'validation-state.json') -Raw | ConvertFrom-Json
$project=$state.project
if($project -notmatch '^forgeoj-m5check-[a-f0-9]{12}$' -or
  [IO.Path]::GetFullPath($state.package) -ne (Join-Path $run 'package') -or
  [IO.Path]::GetFullPath($state.instance) -ne (Join-Path $run 'instance.env')){throw 'Ownership paths mismatch'}
# Do not trust arbitrary image names from state.json.
foreach($image in $state.images){
  if($image.component -notin @('api','worker','web') -or $image.tag -ne ($project+'-'+$image.component)){throw 'Unexpected owned image tag'}
  $actual=& $DockerCommand image inspect --format '{{.Id}}' ($image.tag+':latest') 2>$null
  if($LASTEXITCODE -eq 0 -and $actual.Trim() -ne $image.id){throw 'Owned image identity changed'}
}
& $DockerCommand compose --project-name $project --env-file $state.instance -f (Join-Path $run 'package/compose.yml') down --volumes *> (Join-Path $run 'cleanup.log')
if($LASTEXITCODE -ne 0){throw 'Owned project cleanup failed'}
foreach($image in $state.images){
  & $DockerCommand image inspect --format '{{.Id}}' ($image.tag+':latest') 2>$null | Out-Null
  if($LASTEXITCODE -eq 0){
    & $DockerCommand image rm ($image.tag+':latest') | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Owned image cleanup refused; no force used'}
  }
}
$containers=@(& $DockerCommand ps -aq --filter ('label=com.docker.compose.project='+$project))
$volumes=@(& $DockerCommand volume ls -q --filter ('label=com.docker.compose.project='+$project))
$networks=@(& $DockerCommand network ls -q --filter ('label=com.docker.compose.project='+$project))
if($containers.Count+$volumes.Count+$networks.Count -ne 0){throw 'Owned runtime resources remain'}
$probe=@(& $DockerCommand volume ls -q --filter ('label=com.forgeoj.m5check='+$project))
if($probe.Count -ne 0){throw 'A Linux probe volume remains; inspect exact ownership before cleanup'}
$result=[ordered]@{project=$project;containers=0;volumes=0;networks=0;ownedApplicationTagsRemoved=$state.images.Count;unrelatedServices='not targeted';verifiedAt=(Get-Date -Format o)}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'cleanup-result.json') -Encoding utf8
$result | ConvertTo-Json -Compress
