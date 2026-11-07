# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'State must be inside repository target.'}
$state=Get-Content -LiteralPath (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if($state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$' -or (Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.ProductDemo -ne $true){throw 'Dedicated product demo ownership required.'}
$receiptPath=Join-Path $RunDirectory 'starter-metadata.json'
if(Test-Path -LiteralPath $receiptPath){throw 'Metadata receipt already exists. Inspect it and actual state instead of reapplying automatically.'}
$env:FORGEOJ_E2E_SOURCE=$repository
$env:FORGEOJ_E2E_PROJECT=$state.Project
$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory
$env:FORGEOJ_E2E_REPORTS=$RunDirectory
$env:FORGEOJ_E2E_REDIS_ENABLED=($(if($state.RedisEnabled){'true'}else{'false'}))
$compose=@('compose','--project-name',$state.Project,'-f',(Join-Path $repository 'tools/validation/compose.replay.yml'))
if($state.SearchEnabled){$compose+=@('-f',(Join-Path $repository 'tools/validation/compose.search-replay.yml'))}
foreach($service in @('mysql','frontend')){
    $ids=@(& $DockerCommand ps -q --filter "label=com.docker.compose.project=$($state.Project)" --filter "label=com.docker.compose.service=$service")
    if($LASTEXITCODE -ne 0 -or $ids.Count -ne 1){throw 'Exact running demo service missing.'}
    $labels=& $DockerCommand inspect --format '{{json .Config.Labels}}' $ids[0] | ConvertFrom-Json
    if($LASTEXITCODE -ne 0 -or $labels.'com.docker.compose.project' -ne $state.Project){throw 'Container ownership mismatch.'}
}
& $DockerCommand @compose exec -T frontend node /source/tools/demo/build-starter-metadata.mjs $state.Project
if($LASTEXITCODE -ne 0){throw 'Metadata generation refused; no SQL executed.'}
$sql=Get-Content -LiteralPath (Join-Path $RunDirectory 'starter-metadata.sql') -Raw
$result=$sql | & $DockerCommand @compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --skip-column-names --raw forgeoj'
if($LASTEXITCODE -ne 0){throw 'Demo metadata transaction failed; retain its SQL and inspect before retrying.'}
$facts=$result | ConvertFrom-Json
if($facts.project -ne $state.Project -or $facts.published -ne 20 -or $facts.classified -ne 20 -or $facts.listItems -ne 20){throw 'Metadata result mismatch; do not mark verified.'}
@{status='VERIFIED';checkedAt=(Get-Date -Format o);facts=$facts;sqlSha256=(Get-FileHash -LiteralPath (Join-Path $RunDirectory 'starter-metadata.sql')).Hash.ToLowerInvariant()} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding utf8
Write-Host '20 reviewed problems classified and added to the official practice list.'
