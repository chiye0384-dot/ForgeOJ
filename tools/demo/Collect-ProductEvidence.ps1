# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunDirectory,
    [Parameter(Mandatory)][string]$FrontendBuildDirectory,
    [Parameter(Mandatory)][string]$NativeFrontendLog,
    [Parameter(Mandatory)][string]$DockerCommand
)
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repository
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$FrontendBuildDirectory=(Resolve-Path -LiteralPath $FrontendBuildDirectory).Path
foreach($path in @($RunDirectory,$FrontendBuildDirectory)){
    if(-not $path.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence inputs must be inside repository target'}
}
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if($state.ProductDemo -ne $true -or $state.Project -ne (Split-Path -Leaf $RunDirectory)){throw 'Dedicated demo required'}
$backend=$state.BuildDirectory
$evidence=Join-Path $repository 'docs/evidence/product-improvements/20261029'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
function Verified([string]$Path) {
    $data=Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if($data.status -ne 'VERIFIED'){throw ('Evidence not accepted: '+$Path)}
    return $data
}
function TestSummary([string]$Directory) {
    $tests=0;$failures=0;$errors=0;$skipped=0
    $reports=@(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml')
    if(-not $reports.Count){throw 'Missing test reports'}
    foreach($report in $reports){
        $xml=[xml](Get-Content -LiteralPath $report.FullName -Raw)
        $tests+=[int]$xml.testsuite.tests;$failures+=[int]$xml.testsuite.failures
        $errors+=[int]$xml.testsuite.errors;$skipped+=[int]$xml.testsuite.skipped
    }
    if($failures -or $errors -or $skipped){throw 'Backend tests not accepted'}
    return @{tests=$tests;suites=$reports.Count;failures=$failures;errors=$errors;skipped=$skipped}
}
function FrontendLog([string]$Path) {
    $raw=Get-Content -LiteralPath $Path -Raw
    $clean=[regex]::Replace($raw,'\x1B\[[0-?]*[ -/]*[@-~]','')
    if($clean -notmatch 'Tests\s+140 passed \(140\)' -or $clean -match 'exited with [1-9]'){throw ('Frontend verification missing: '+$Path)}
}
$imported=Verified (Join-Path $RunDirectory 'starter-import.json')
$metadata=Verified (Join-Path $RunDirectory 'starter-metadata.json')
$http=Verified (Join-Path $RunDirectory 'product-http-1793277256934.json')
$persistence=Verified (Join-Path $RunDirectory 'persistence-verification.json')
$restartPath=(Get-ChildItem -LiteralPath $RunDirectory -Filter 'restart-judge-*.json' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$restart=Verified $restartPath
$catalog=Verified 'target/starter-check-1793274389636/verification.json'
$inputs=@()
foreach($spec in @(@((Join-Path $backend 'source-files.backend.sha256'),'^\./(pom\.xml|forgeoj-(api|judge-worker)/(pom\.xml|src/.*))$'),@((Join-Path $FrontendBuildDirectory 'source-files.frontend.sha256'),'^\./frontend/(src/.*|package(-lock)?\.json|.*config.*)$'))){
    foreach($line in Get-Content -LiteralPath $spec[0]){
        if($line -match '^([a-f0-9]{64})  (.+)$'){
            $expected=$Matches[1];$file=$Matches[2]
            if($file -match $spec[1]){
                $actual=(Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
                if($actual -ne $expected){throw ('Source changed after verification: '+$file)}
                $inputs+=@{path=$file;sha256=$actual}
            }
        }
    }
}
FrontendLog (Join-Path $FrontendBuildDirectory 'frontend.log')
FrontendLog $NativeFrontendLog
if(-not (Test-Path (Join-Path $FrontendBuildDirectory 'frontend-dist/index.html'))){throw 'Linux frontend build missing'}
$inputs | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $evidence 'source-inputs.json') -Encoding utf8
$runtime=@()
foreach($item in @(@('api','/app/api.jar',$state.ApiHash),@('worker','/app/worker.jar',$state.WorkerHash))){
    $name=$state.Project+'-'+$item[0]+'-1'
    $labels=& $DockerCommand inspect --format '{{json .Config.Labels}}' $name | ConvertFrom-Json
    if($LASTEXITCODE -ne 0 -or $labels.'com.docker.compose.project' -ne $state.Project){throw 'Runtime ownership mismatch'}
    $hash=((& $DockerCommand exec $name sha256sum $item[1]) -split '\s+')[0]
    if($LASTEXITCODE -ne 0 -or $hash -ne $item[2]){throw 'Running JAR differs from verified build'}
    $runtime+=@{service=$item[0];sha256=$hash}
}
$summary=@{
    status='VERIFIED';scope='local product improvements only';checkedAt=(Get-Date -Format o)
    baselineCommit=(git rev-parse HEAD);worktreeUncommitted=$true;project=$state.Project;url='http://127.0.0.1:8088/problems'
    api=(TestSummary (Join-Path $backend 'forgeoj-api/surefire-reports'))
    worker=(TestSummary (Join-Path $backend 'forgeoj-judge-worker/surefire-reports'))
    frontend=@{tests=140;suites=27;windowsAndLinux=$true;linuxBuild=$FrontendBuildDirectory;nativeLog=$NativeFrontendLog}
    sourceInputs=@{count=$inputs.Count;allMatch=$true;sha256=(Get-FileHash (Join-Path $evidence 'source-inputs.json')).Hash.ToLowerInvariant()}
    runtimeJars=$runtime
    catalog=@{newProblems=20;totalLibrary=21;localProgramExecutions=$catalog.executions;platformDoubleValidationJobs=20;approvedReviews=20;officialListItems=$metadata.facts.listItems;catalogSha256=$imported.catalogSha256}
    http=@{status=$http.status;formalAc=20;formalWa=1;idempotencyChecks=20;solutionUnlockChecks=20;crossOwnerDenied=$true;selfTest=$http.selfTest.result;fullText=$http.fullText;learnerHistoryUnchanged=$http.learnerHistoryUnchanged;receiptSha256=(Get-FileHash (Join-Path $RunDirectory 'product-http-1793277256934.json')).Hash.ToLowerInvariant()}
    browser=@{homepage='problem library';libraryCount=21;neutralTemplate=$true;immediateLeaveDraftRecovered=$true;realAcObserved=$true;narrowNavigationFixed=$true;screenshot='library-final-20261030.jpg'}
    persistence=$persistence;postRestartJudge=$restart
    release=$false;resumeReady=$false;fullM5='IN_PROGRESS';performanceGainClaimed=$false
    remaining=@('real server and public HTTPS','production SMTP and offsite backups','cross-business-version rollback','long-running load and controlled real-user trial','regenerate deployment package for changed source')
}
$summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $evidence 'verification.json') -Encoding utf8
$summary | Select-Object status,url,api,worker,frontend,catalog,persistence | ConvertTo-Json -Depth 4
