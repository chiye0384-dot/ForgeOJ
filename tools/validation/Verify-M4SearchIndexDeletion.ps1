# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([ValidateSet('Prepare','Run','Verify')][string]$Action,[Parameter(Mandatory)][string]$RunDirectory,[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
$state=Get-Content -LiteralPath (Join-Path $run 'state.json') -Raw | ConvertFrom-Json
if(-not $run.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar) -or (Split-Path -Leaf $run) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$' -or -not $state.SearchEnabled){throw 'Owned supplemental replay required'}
function Save([string]$Name,[object]$Value){[IO.File]::WriteAllText((Join-Path $run $Name),($Value | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))}
if($Action -eq 'Verify'){
  $facts=Get-Content -LiteralPath (Join-Path $run 'search-index-deletion-facts.json') -Raw | ConvertFrom-Json
  $http=Get-Content -LiteralPath (Join-Path $run 'search-index-deletion-http.json') -Raw | ConvertFrom-Json
  $cleanup=Get-Content -LiteralPath (Join-Path $run 'search-cleanup.json') -Raw | ConvertFrom-Json
  foreach($key in @('ownedContainers','ownedVolumes','ownedNetworks','ownedImages','builders','testcontainers','managedSandboxes')){if($cleanup.$key -ne 0){throw 'Supplemental resources remain'}}
  foreach($key in @('deletedIndexFallsBack','titleFallbackStillWorks','replayedSameId','rebuildSucceeded','fullTextRestored','allPassed')){if($http.$key -ne $true){throw 'Supplemental HTTP assertion missing'}}
  if(-not $facts.deleteAcknowledged -or $facts.deletedIndexStatus -ne '404' -or $facts.before.indexName -eq $facts.after.indexName -or $facts.before.uuid -eq $facts.after.uuid -or $facts.after.job -ne $http.rebuildId -or $facts.after.status -ne 'SUCCEEDED' -or $facts.after.queuedAudit -ne 1 -or $facts.after.terminalAudit -ne 1 -or $facts.before.publicRows -ne $facts.after.publicRows){throw 'Supplemental deletion/rebuild facts mismatch'}
  foreach($entry in $facts.jars){$expected=if($entry.service -eq 'worker'){$state.WorkerHash}else{$state.ApiHash};if($entry.sha256 -ne $expected -or -not $entry.readOnly){throw 'Supplemental JAR mismatch'}}
  foreach($line in Get-Content -LiteralPath (Join-Path $run 'search-index-deletion-inputs.sha256')){if($line -notmatch '^([a-f0-9]{64})  (tools/validation/[A-Za-z0-9.-]+)$'){throw 'Invalid supplemental helper manifest'};if((Get-FileHash -LiteralPath (Join-Path $repository $Matches[2])).Hash.ToLower() -ne $Matches[1]){throw 'Supplemental helper changed'}}
  $proof=@{status='VERIFIED';scope='same-JAR whole owned index deletion and managed rebuild only';build=$state.BuildDirectory;replay=$state.Project;apiSha256=$state.ApiHash;workerSha256=$state.WorkerHash;facts=$facts;http=$http;cleanup=$cleanup;checkedAt=(Get-Date -Format o)}
  Save 'search-index-deletion-verification.json' $proof
  $proof | ConvertTo-Json -Depth 2 | Out-Host
  exit
}
$env:FORGEOJ_E2E_SOURCE=$repository;$env:FORGEOJ_E2E_PROJECT=$state.Project;$env:FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory;$env:FORGEOJ_E2E_REPORTS=$run
$files=@('-f',(Join-Path $PSScriptRoot 'compose.replay.yml'),'-f',(Join-Path $PSScriptRoot 'compose.search-replay.yml'))
function Docker([string[]]$Arguments){$result=& $DockerCommand @Arguments;if($LASTEXITCODE -ne 0){throw 'Supplemental owned Docker operation failed'};return $result}
function Compose([string[]]$Arguments){Docker (@('compose','--project-name',$state.Project)+$files+$Arguments)}
function Owned([string]$service){$id=(Compose @('ps','-q',$service)).Trim();$c=(Docker @('inspect',$id) | ConvertFrom-Json)[0];if($c.Config.Labels.'com.docker.compose.project' -ne $state.Project -or $c.Config.Labels.'com.docker.compose.service' -ne $service){throw 'Supplemental ownership mismatch'};return $c}
function Probe([string]$Phase){
  $network=$state.Project+'_default';$n=(Docker @('network','inspect',$network) | ConvertFrom-Json)[0]
  if($n.Labels.'com.docker.compose.project' -ne $state.Project){throw 'Supplemental probe network ownership mismatch'}
  Docker @('run','--rm','--pull=never','--name',($state.Project+'-index-probe'),'--label',('com.docker.compose.project='+$state.Project),'--network',$network,'--user','65534:65534','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/tmp:size=16m,mode=1777','--mount',('type=bind,source='+$repository+',target=/source,readonly'),'--mount',('type=bind,source='+$run+',target=/reports'),'--env','FORGEOJ_INDEX_DELETION_DIRECT_API=true','node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c','node','/source/tools/validation/replay-search-index-deletion.mjs',$Phase) | Out-Host
}
function Es([string]$Path,[string]$Method='GET'){((Compose @('exec','-T','search','curl','--fail','--silent','--cacert','/usr/share/elasticsearch/config/certs/server.crt','--user','elastic:m4-search-admin-fixture-only','-X',$Method,('https://localhost:9200'+$Path))) -join "`n") | ConvertFrom-Json}
function Control(){
  $sql="SELECT JSON_OBJECT('indexName',c.index_name,'uuid',c.index_uuid,'job',j.id,'status',j.status,'publicRows',(SELECT COUNT(*) FROM problem WHERE scope='PUBLIC'),'queuedAudit',(SELECT COUNT(*) FROM admin_audit_event a WHERE a.action='SEARCH_REBUILD_QUEUED' AND a.target_id=j.id),'terminalAudit',(SELECT COUNT(*) FROM admin_audit_event a WHERE a.action='SEARCH_REBUILD_SUCCEEDED' AND a.target_id=j.id)) FROM public_search_control c JOIN public_search_rebuild j ON j.target_index=c.index_name WHERE c.id=1"
  $result=$sql | & $DockerCommand compose --project-name $state.Project @files exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --batch --skip-column-names forgeoj'
  if($LASTEXITCODE -ne 0){throw 'Supplemental SQL read failed'}
  return ($result -join "`n") | ConvertFrom-Json
}
foreach($service in @('mysql','search','api','worker')){Owned $service | Out-Null}
if($Action -eq 'Prepare'){Probe 'prepare';exit}
if(Test-Path -LiteralPath (Join-Path $run 'search-index-deletion-facts.json')){throw 'Do not replay deletion against an already recorded generation'}
$helpers=@('Verify-M4SearchIndexDeletion.ps1','replay-search-index-deletion.mjs','Verify-M4SearchReplay.ps1','replay-search.mjs','Replay-FixedLinux.ps1','compose.replay.yml','compose.search-replay.yml','New-SearchReplayCertificates.ps1','Verify-M4RedisReplay.ps1','Verify-M4AdminReplay.ps1','replay-admin-identity.mjs')
[IO.File]::WriteAllLines((Join-Path $run 'search-index-deletion-inputs.sha256'),@($helpers | Sort-Object | ForEach-Object {(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $_)).Hash.ToLower()+'  tools/validation/'+$_}),[Text.UTF8Encoding]::new($false))
$jars=@();foreach($service in @('api','worker')){$c=Owned $service;$file=if($service -eq 'api'){'/app/api.jar'}else{'/app/worker.jar'};$hash=((Docker @('exec',$c.Id,'sha256sum',$file)) -split ' ')[0];$expected=if($service -eq 'api'){$state.ApiHash}else{$state.WorkerHash};$readOnly=@($c.Mounts | Where-Object {$_.Destination -eq $file -and -not $_.RW}).Count -eq 1;if($hash -ne $expected -or -not $readOnly){throw 'Supplemental running JAR drift'};$jars+=@{service=$service;sha256=$hash;readOnly=$readOnly;containerId=$c.Id}}
$before=Control
if($before.indexName -notmatch '^forgeoj-public-[a-f0-9]{32}$' -or $before.status -ne 'SUCCEEDED'){throw 'Supplemental managed generation missing'}
$mapping=Es ('/'+$before.indexName+'/_mapping');$settings=Es ('/'+$before.indexName+'/_settings')
if(@($mapping.PSObject.Properties).Count -ne 1 -or $mapping.PSObject.Properties[$before.indexName].Value.mappings._meta.forgeojRebuildId -ne $before.job -or $settings.PSObject.Properties[$before.indexName].Value.settings.index.uuid -ne $before.uuid){throw 'Exact supplemental generation ownership not proven'}
$deleted=Es ('/'+$before.indexName) 'DELETE';if(-not $deleted.acknowledged){throw 'Whole index deletion unconfirmed'}
$deletedStatus=(Compose @('exec','-T','search','curl','--silent','--output','/dev/null','--write-out','%{http_code}','--cacert','/usr/share/elasticsearch/config/certs/server.crt','--user','elastic:m4-search-admin-fixture-only',('https://localhost:9200/'+$before.indexName+'/_settings'))).Trim()
if($deletedStatus -ne '404'){throw 'Deleted generation still exists'}
Probe 'recover'
$after=Control
Save 'search-index-deletion-facts.json' @{before=$before;after=$after;deleteAcknowledged=[bool]$deleted.acknowledged;deletedIndexStatus=$deletedStatus;jars=$jars}
