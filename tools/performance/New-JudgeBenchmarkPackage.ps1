# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build=(Resolve-Path -LiteralPath (Join-Path $repo $BuildDirectory)).Path
if(-not $build.StartsWith((Join-Path $repo 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned fixed Linux build required'}
if((Split-Path $build -Leaf) -notmatch '^forgeoj-linux-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Fixed Linux run identity required'}
# A JAR alone is not acceptance: Maven can leave artifacts after a later failed phase.
foreach($module in @('forgeoj-api','forgeoj-judge-worker')){
 $reports=@(Get-ChildItem -LiteralPath (Join-Path $build "$module/surefire-reports") -Filter 'TEST-*.xml' -ErrorAction Stop)
 if(-not $reports.Count){throw "Missing full regression reports: $module"}
 $tests=0
 foreach($report in $reports){
  [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
  $suite=$xml.testsuite
  if([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0){throw "Regression did not pass: $module"}
  $tests += [int]$suite.tests
 }
 if($tests -lt $(if($module -eq 'forgeoj-api'){341}else{139})){throw "Filtered/incomplete regression is not accepted: $module"}
}
$frontend=Get-Content -LiteralPath (Join-Path $build 'frontend.log') -Raw
$frontend=[regex]::Replace($frontend,([string][char]27+'\[[0-9;]*m'),'')
if($frontend -notmatch 'Tests\s+159 passed \(159\)' -or $frontend -notmatch 'built in'){throw 'Complete frontend verification required'}
$inputs=@{}
foreach($spec in @(@('backend','^\./(pom\.xml|forgeoj-(api|judge-worker)/(pom\.xml|src/.*)|contracts/.*)$'),@('frontend','^\./frontend/(src/.*|package(-lock)?\.json|[^/]*config[^/]*)$'))){
 foreach($line in Get-Content (Join-Path $build "source-files.$($spec[0]).sha256")){
  if($line -match '^([a-f0-9]{64})  (.+)$'){
   $hash=$Matches[1];$path=$Matches[2]
   if($path -match $spec[1]){
    $path=$path.Substring(2)
    if((Get-FileHash -LiteralPath (Join-Path $repo $path)).Hash.ToLowerInvariant() -ne $hash){throw ('Changed compilation input: '+$path)}
    $inputs[$path]=@{path=$path;sha256=$hash}
   }
  }
 }
}
if($inputs.Count -lt 400){throw 'Incomplete backend/frontend input manifest'}
@{inputs=@($inputs.Values | Sort-Object path)} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $build 'judge-compiled-inputs.json') -Encoding utf8
$api=(Get-FileHash (Join-Path $build 'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar')).Hash.ToLowerInvariant()
$worker=(Get-FileHash (Join-Path $build 'forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar')).Hash.ToLowerInvariant()
$relative=[IO.Path]::GetRelativePath($repo,$build).Replace('\','/')
& (Join-Path $repo 'tools/deployment/Build-ProductionPackage.ps1') -BuildDirectory $relative -ApiSha256 $api -WorkerSha256 $worker -CompiledInputManifest "$relative/judge-compiled-inputs.json"
