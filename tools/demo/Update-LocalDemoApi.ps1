# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Replace only this owned loopback demo API; never bootstrap or replace its data services.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$BuildDirectory,[Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ApiSha256,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$target=(Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$BuildDirectory=(Resolve-Path -LiteralPath $BuildDirectory).Path
if(-not $RunDirectory.StartsWith($target,[StringComparison]::OrdinalIgnoreCase) -or -not $BuildDirectory.StartsWith($target,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned target paths required'}
$statePath=Join-Path $RunDirectory 'state.json'
$oldText=Get-Content -LiteralPath $statePath -Raw
$state=$oldText | ConvertFrom-Json
if(-not $state.ProductDemo -or $state.Project -ne (Split-Path -Leaf $RunDirectory) -or $state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Owned product demo required'}
function Docker-Checked([string[]]$Arguments){$out=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Exact demo Docker operation failed'};return $out}
foreach($service in @('api','mysql','worker','frontend','mailbox')){
 $labels=Docker-Checked @('inspect','--format','{{json .Config.Labels}}',"$($state.Project)-$service-1") | ConvertFrom-Json
 if($labels.'com.docker.compose.project' -ne $state.Project -or -not [IO.Path]::GetFullPath($labels.'com.docker.compose.project.working_dir').StartsWith($repository+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Container ownership mismatch'}
}
$mysqlId=Docker-Checked @('inspect','--format','{{.Id}}',"$($state.Project)-mysql-1")
$mysqlMount=Docker-Checked @('inspect','--format','{{json .Mounts}}',$mysqlId)
$actualOld=(Docker-Checked @('exec',"$($state.Project)-api-1",'sha256sum','/app/api.jar')).Split(' ')[0]
if($actualOld -ne $state.ApiHash){throw 'Existing runtime does not match saved API state'}
$jar='forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar'
if((Get-FileHash (Join-Path $BuildDirectory $jar)).Hash.ToLowerInvariant() -ne $ApiSha256){throw 'New API artifact mismatch'}
$worker='forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar'
if((Get-FileHash (Join-Path $state.BuildDirectory $worker)).Hash.ToLowerInvariant() -ne $state.WorkerHash){throw 'Original Worker artifact mismatch'}
if(-not (Test-Path (Join-Path $BuildDirectory $worker))){Copy-Item -LiteralPath (Join-Path $state.BuildDirectory $worker) -Destination (Join-Path $BuildDirectory $worker)}
if((Get-FileHash (Join-Path $BuildDirectory $worker)).Hash.ToLowerInvariant() -ne $state.WorkerHash){throw 'Worker bytes changed'}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $RunDirectory "state.before-api-$stamp.json"
Set-Content -LiteralPath $backup -Value $oldText -Encoding utf8
$values=@{FORGEOJ_E2E_SOURCE=$repository;FORGEOJ_E2E_PROJECT=$state.Project;FORGEOJ_E2E_ARTIFACTS=$BuildDirectory;FORGEOJ_E2E_REPORTS=$RunDirectory;FORGEOJ_E2E_REDIS_ENABLED=($(if($state.RedisEnabled){'true'}else{'false'}));FORGEOJ_E2E_MAIL_APP_URL='http://127.0.0.1:8088';FORGEOJ_DEMO_DIST=(Join-Path $repository 'frontend/dist');FORGEOJ_DEMO_PORT='8088'}
$previous=@{}
foreach($name in $values.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
$compose=@('compose','--project-name',$state.Project,'-f',(Join-Path $repository 'tools/validation/compose.replay.yml'))
if($state.SearchEnabled){$compose+=@('-f',(Join-Path $repository 'tools/validation/compose.search-replay.yml'))}
$compose+=@('-f',(Join-Path $repository 'tools/demo/compose.local-demo.yml'))
try{
 Docker-Checked ($compose+@('up','--detach','--no-deps','--force-recreate','--no-build','--pull','never','--wait','--wait-timeout','90','api','mailbox')) | Out-Host
 if((Docker-Checked @('exec',"$($state.Project)-api-1",'sha256sum','/app/api.jar')).Split(' ')[0] -ne $ApiSha256){throw 'New runtime artifact mismatch'}
 if((Docker-Checked @('inspect','--format','{{.Id}}',"$($state.Project)-mysql-1")) -ne $mysqlId -or (Docker-Checked @('inspect','--format','{{json .Mounts}}',$mysqlId)) -ne $mysqlMount){throw 'Original MySQL identity changed'}
 $response=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8088/api/v1/auth/session' -TimeoutSec 10
 if($response.StatusCode -ne 200){throw 'API not reachable'}
 $state.BuildDirectory=$BuildDirectory;$state.ApiHash=$ApiSha256
 $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding utf8
 @{status='VERIFIED';checkedAt=(Get-Date -Format o);project=$state.Project;previousApi=$actualOld;api=$ApiSha256;worker=$state.WorkerHash;mysqlId=$mysqlId;mysqlMountUnchanged=$true;stateBackup=(Split-Path -Leaf $backup)} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $RunDirectory "api-update-$stamp.json") -Encoding utf8
}catch{
 # Original artifact still exists. Restore only API and its network-sharing mail bridge.
 [Environment]::SetEnvironmentVariable('FORGEOJ_E2E_ARTIFACTS',($oldText | ConvertFrom-Json).BuildDirectory,'Process')
 Docker-Checked ($compose+@('up','--detach','--no-deps','--force-recreate','--no-build','--pull','never','--wait','--wait-timeout','90','api','mailbox')) | Out-Host
 throw
}finally{foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
