# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$BuildDirectory,
  [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ApiSha256,
  [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$WorkerSha256
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build=(Resolve-Path (Join-Path $repo $BuildDirectory)).Path
if(-not $build.StartsWith((Join-Path $repo 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Build must be in target'}
$api=Join-Path $build 'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar'
$worker=Join-Path $build 'forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar'
if((Get-FileHash $api).Hash.ToLower() -ne $ApiSha256 -or (Get-FileHash $worker).Hash.ToLower() -ne $WorkerSha256){throw 'Accepted artifact mismatch'}
$inputs=(Get-Content -LiteralPath (Join-Path $repo 'docs/evidence/m4-monitoring/linux-source-inputs.json') -Raw | ConvertFrom-Json).inputs
foreach($input in $inputs){
  if($input.path -notmatch '^(pom.xml$|forgeoj-(api|judge-worker)/|frontend/|contracts/)'){throw 'Unexpected compiled input path'}
  if((Get-FileHash -LiteralPath (Join-Path $repo $input.path)).Hash.ToLower() -ne $input.sha256){throw "Compiled input changed: $($input.path); build again before packaging"}
}
$name='forgeoj-deployment-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$out=Join-Path $repo ('target/'+$name)
New-Item -ItemType Directory -Path $out | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'deploy/production') -Destination (Join-Path $out 'package') -Recurse
$package=Join-Path $out 'package'
New-Item -ItemType Directory -Path (Join-Path $package 'artifacts/frontend'),(Join-Path $package 'notices') | Out-Null
Copy-Item -LiteralPath $api -Destination (Join-Path $package 'artifacts/api.jar')
Copy-Item -LiteralPath $worker -Destination (Join-Path $package 'artifacts/worker.jar')
Get-ChildItem -LiteralPath (Join-Path $build 'frontend-dist') | Copy-Item -Destination (Join-Path $package 'artifacts/frontend') -Recurse
Copy-Item -LiteralPath (Join-Path $repo 'deploy/mysql/init/01-create-users.sh') -Destination (Join-Path $package 'scripts/create-users.sh')
foreach($file in @('datasources.yml','dashboards.yml','forgeoj.json')){Copy-Item -LiteralPath (Join-Path $repo ('deploy/monitoring/'+$file)) -Destination (Join-Path $package ('monitoring/'+$file))}
foreach($file in @('LICENSE','NOTICE','THIRD_PARTY_NOTICES.md')){Copy-Item -LiteralPath (Join-Path $repo $file) -Destination (Join-Path $package ('notices/'+$file))}
foreach($file in @('DIRECT-DEPENDENCY-LICENSES.md','UPSTREAM-AND-LICENSE.md','M5-DEPLOYMENT-DESIGN.md')){Copy-Item -LiteralPath (Join-Path $repo ('docs/'+$file)) -Destination (Join-Path $package ('notices/'+$file))}
Copy-Item -LiteralPath (Join-Path $repo 'docs/licenses') -Destination (Join-Path $package 'notices/upstream') -Recurse
# Windows checkout line endings must not break Linux deployment scripts.
Get-ChildItem -LiteralPath $package -Filter '*.sh' -Recurse | ForEach-Object {
  $body=[IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n")
  [IO.File]::WriteAllText($_.FullName,$body,[Text.UTF8Encoding]::new($false))
}
$manifest=[ordered]@{deploymentRevision=2;createdAt=(Get-Date -Format o);sourceCommit=(git -C $repo rev-parse HEAD);compiledInputs=$inputs.Count;apiInputSha256=$ApiSha256;workerInputSha256=$WorkerSha256;mode='private deployment preparation; not a Release';files=@()}
$manifest.files=@(Get-ChildItem -LiteralPath $package -Recurse -File | Sort-Object FullName | ForEach-Object {[ordered]@{path=[IO.Path]::GetRelativePath($package,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLower()}})
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $package 'package-manifest.json') -Encoding utf8
Write-Output $package
