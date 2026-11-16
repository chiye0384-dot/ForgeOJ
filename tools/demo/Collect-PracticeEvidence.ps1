# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$FrontendLog,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repository
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned target required'}
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if(-not $state.ProductDemo -or $state.Project -ne (Split-Path -Leaf $RunDirectory)){throw 'Product demo required'}
$evidence=Join-Path $repository 'docs/archive/evidence/practice-continuity/20261030'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$files=@('practice-continuity-http.json','teaching-revision-validation.json','teaching-revision-maintenance.json')
foreach($file in $files){
 $fact=Get-Content (Join-Path $RunDirectory $file) -Raw | ConvertFrom-Json
 if($fact.status -ne 'VERIFIED'){throw 'Unaccepted receipt'}
 Copy-Item -LiteralPath (Join-Path $RunDirectory $file) -Destination (Join-Path $evidence $file)
}
$proof=Get-Content (Join-Path $RunDirectory $files[0]) -Raw | ConvertFrom-Json
$retry=$proof.programs.retry
if($retry.submissionId -notmatch '^[a-f0-9-]{36}$' -or $retry.requestId -notmatch '^[a-f0-9-]{36}$'){throw 'Invalid proof binding'}
$sql="SELECT JSON_OBJECT('retrySubmissions',(SELECT COUNT(*) FROM submission WHERE user_id=2 AND client_request_id='$($retry.requestId)'),'retryTasks',(SELECT COUNT(*) FROM judge_task WHERE submission_id='$($retry.submissionId)'),'retryEvents',(SELECT COUNT(*) FROM outbox_event e JOIN judge_task t ON t.id=e.aggregate_id WHERE t.submission_id='$($retry.submissionId)'),'officialSolutions',(SELECT COUNT(*) FROM official_problem_solution),'publicProblems',(SELECT COUNT(*) FROM problem WHERE scope='PUBLIC' AND status='ACTIVE'));"
$output=$sql | & $DockerCommand exec -i "$($state.Project)-mysql-1" sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --batch --skip-column-names --raw forgeoj'
if($LASTEXITCODE -ne 0){throw 'Read-only fact query failed'}
$db=$output | ConvertFrom-Json
if($db.retrySubmissions -ne 1 -or $db.retryTasks -ne 1 -or $db.retryEvents -ne 1 -or $db.officialSolutions -ne 21 -or $db.publicProblems -ne 21){throw 'Unexpected persisted facts'}
$tests=0;$suites=0
foreach($file in Get-ChildItem (Join-Path $state.BuildDirectory 'forgeoj-api/surefire-reports/TEST-*.xml')){
 $report=[xml](Get-Content -LiteralPath $file.FullName -Raw)
 if([int]$report.testsuite.failures -or [int]$report.testsuite.errors -or [int]$report.testsuite.skipped){throw 'Rejected API report'}
 $tests+=[int]$report.testsuite.tests;$suites++
}
if($tests -ne 58 -or $suites -ne 4){throw 'Unexpected focused API suite set'}
$frontend=Get-Content -LiteralPath $FrontendLog -Raw
if($frontend -notmatch 'Tests\s+159 passed \(159\)' -or $frontend -match 'ERROR:|FAIL |exited with [1-9]' -or $frontend -notmatch 'built in'){throw 'Frontend gates not accepted'}
$inputs=@()
foreach($line in Get-Content (Join-Path $state.BuildDirectory 'source-files.api.sha256')){
 if($line -match '^([a-f0-9]{64})  (\./(?:pom\.xml|forgeoj-api/(?:pom\.xml|src/.*)))$'){
  $expected=$Matches[1];$path=$Matches[2]
  if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ne $expected){throw ('API input drift: '+$path)}
  $inputs+=@{path=$path;sha256=$expected;verifiedBy='fixed Linux'}
 }
}
foreach($path in & rg --files frontend/src frontend -g '*.json' -g '*config*' -g '*.vue' -g '*.ts' -g '!node_modules' -g '!dist'){
 $path=$path.Replace('\','/')
 if($inputs.path -contains $path){continue}
 $inputs+=@{path=$path.Replace('\','/');sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant();verifiedBy='Windows frontend gates'}
}
$runtime=@()
foreach($service in @(@('api','/app/api.jar',$state.ApiHash),@('worker','/app/worker.jar',$state.WorkerHash))){
 $name="$($state.Project)-$($service[0])-1"
 $labels=& $DockerCommand inspect --format '{{json .Config.Labels}}' $name | ConvertFrom-Json
 if($LASTEXITCODE -ne 0 -or $labels.'com.docker.compose.project' -ne $state.Project){throw 'Runtime ownership mismatch'}
 $hash=((& $DockerCommand exec $name sha256sum $service[1]) -split '\s+')[0]
 if($LASTEXITCODE -ne 0 -or $hash -ne $service[2]){throw 'Runtime JAR mismatch'}
 $runtime+=@{service=$service[0];sha256=$hash}
}
$assets=@()
$assetDir=Join-Path $RunDirectory 'practice-static-assets'
New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
foreach($file in Get-ChildItem frontend/dist -Recurse -File){
 $relative=$file.FullName.Substring((Resolve-Path frontend/dist).Path.Length+1).Replace('\','/')
 $download=Join-Path $assetDir ($relative.Replace('/','_'))
 Invoke-WebRequest -UseBasicParsing -Uri ('http://127.0.0.1:8088/'+$relative) -OutFile $download -TimeoutSec 10
 $hash=(Get-FileHash -LiteralPath $file.FullName).Hash
 if($relative -eq 'index.html'){
  $expected=(Get-Content -LiteralPath $file.FullName -Raw).Replace('</head>','<meta name="forgeoj-local-demo" content="true"><script src="/demo-compat.js"></script></head>')
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($expected)))
 }
 if((Get-FileHash -LiteralPath $download).Hash -ne $hash){throw ('Served asset mismatch: '+$relative)}
 $assets+=@{path=$relative;sha256=$hash.ToLowerInvariant()}
}
$protected=@()
foreach($spec in @(@('docs/M2-NEXT-CHAT-HANDOFF.md','e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054'),@('.smtp.qq.local','6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907'))){
 $hash=(Get-FileHash -LiteralPath $spec[0]).Hash.ToLowerInvariant()
 if($hash -ne $spec[1]){throw 'Protected file digest changed'}
 $protected+=@{path=$spec[0];sha256=$hash;method='Get-FileHash only'}
}
$inputs | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $evidence 'source-inputs.json') -Encoding utf8
$assets | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $evidence 'served-assets.json') -Encoding utf8
@{status='VERIFIED';checkedAt=(Get-Date -Format o);scope='local practice continuity only';project=$state.Project;baselineCommit=(git rev-parse HEAD);api=@{tests=$tests;suites=$suites;fullSuiteRerun=$false;build=$state.BuildDirectory};worker=@{unchanged=$true;testsRerun=$false};frontend=@{tests=159;suites=30;platform='Windows';allGates=$true;log=$FrontendLog};inputCount=$inputs.Count;servedAssets=$assets.Count;runtime=$runtime;persisted=$db;protected=$protected;publicRelease=$false;committed=$false;remaining=@('production deployment and full M5 gates unchanged','rebuild deployment package before deploying new source','teaching quality not yet evaluated by human learners','pending request key retained only in current page with leave warning')} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $evidence 'verification.json') -Encoding utf8
Write-Host "VERIFIED: $tests API tests, 159 frontend tests, $($assets.Count) served assets, exact runtime and protected digests."
