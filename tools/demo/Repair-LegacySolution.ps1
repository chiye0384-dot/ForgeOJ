# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned repository target required'}
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if(-not $state.ProductDemo -or $state.Project -ne (Split-Path -Leaf $RunDirectory) -or $state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Local product demo required'}
$receipt=Join-Path $RunDirectory 'legacy-sum-solution-repair.json'
if(Test-Path -LiteralPath $receipt){throw 'Receipt exists; inspect actual state instead of repeating a repair'}
foreach($service in @('frontend','mysql')){
  $owner=& $DockerCommand inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$($state.Project)-$service-1"
  if($LASTEXITCODE -ne 0 -or $owner -ne $state.Project){throw 'Container owner mismatch'}
}
& $DockerCommand exec "$($state.Project)-frontend-1" node /source/tools/demo/build-legacy-solution-repair.mjs $state.Project
if($LASTEXITCODE -ne 0){throw 'Proof rejected; no SQL executed'}
$sql=Get-Content (Join-Path $RunDirectory 'legacy-sum-solution-repair.sql') -Raw
$result=$sql | & $DockerCommand exec -i "$($state.Project)-mysql-1" sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names --raw forgeoj'
if($LASTEXITCODE -ne 0){throw 'Repair transaction rejected; preserve SQL and diagnose before retrying'}
$facts=$result | ConvertFrom-Json
if($facts.status -ne 'VERIFIED' -or $facts.project -ne $state.Project -or $facts.officialSolutions -ne 21){throw 'Unexpected repair facts'}
@{status='VERIFIED';checkedAt=(Get-Date -Format o);facts=$facts;sqlSha256=(Get-FileHash (Join-Path $RunDirectory 'legacy-sum-solution-repair.sql')).Hash.ToLowerInvariant()} | ConvertTo-Json -Depth 5 | Set-Content $receipt -Encoding utf8
Write-Host 'Repaired the missing local seed solution; other official solutions unchanged.'
