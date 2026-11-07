# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackageDirectory,
 [string]$DockerCommand='docker',
 [string]$PythonCommand='python',
 [string]$NodeCommand='node',
 [switch]$Lifecycle)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$package=(Resolve-Path -LiteralPath $PackageDirectory).Path
if(-not $package.StartsWith((Join-Path $repo 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $package -Leaf) -ne 'package'){throw 'Owned target package required'}
$run=Split-Path $package -Parent
$project='forgeoj-m5check-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$private=Join-Path $run 'private'
if(Test-Path -LiteralPath $private){throw 'Do not overwrite private validation files'}
New-Item -ItemType Directory -Path (Join-Path $private 'secrets'),(Join-Path $private 'tls'),(Join-Path $private 'search') | Out-Null
foreach($name in @('root-password','migrator-password','api-password','worker-password','rabbit-password','redis-password','jwt-secret','admin-jwt-secret','search-admin-password','search-password','monitoring-token','grafana-password','grafana-key')){
  $bytes=[Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
  [IO.File]::WriteAllText((Join-Path $private ('secrets/'+$name)),[Convert]::ToHexString($bytes).ToLower())
}
[IO.File]::WriteAllText((Join-Path $private 'secrets/smtp-password'),'')
$instance=Join-Path $run 'instance.env'
$state=[ordered]@{project=$project;package=$package;instance=$instance;privateDirectory=$private;status='IN_PROGRESS';images=@()}
$state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $run 'validation-state.json')
function Checked([string[]]$Arguments,[string]$Log){
  if($Log){& $DockerCommand @Arguments *> (Join-Path $run $Log)}else{& $DockerCommand @Arguments | Out-Null}
  if($LASTEXITCODE -ne 0){throw "Docker validation failed; inspect safe diagnostics from $Log"}
}
foreach($component in @('api','worker','web')){
  Checked @('build','--platform','linux/amd64','-f',(Join-Path $package "Dockerfile.$component"),'-t',"$project-$component",$package) "build-$component.log"
  $id=(& $DockerCommand image inspect --format '{{.Id}}' "$project-$component").Trim()
  if($LASTEXITCODE -ne 0 -or $id -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Image identity unavailable'}
  $state.images+=@([ordered]@{component=$component;id=$id;tag="$project-$component"})
  $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $run 'validation-state.json')
}
$apiImage=$state.images[0].id
$socketGid=(& $DockerCommand run --rm --network none --label "com.forgeoj.m5check=$project" --mount type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock --entrypoint stat $apiImage -c '%g' /var/run/docker.sock).Trim()
if($LASTEXITCODE -ne 0 -or $socketGid -notmatch '^\d+$'){throw 'Cannot determine actual daemon socket GID'}
$certScript=@'
set -eu
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=oj.test -addext subjectAltName=DNS:oj.test -keyout /work/tls/privkey.pem -out /work/tls/fullchain.pem 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=search -addext subjectAltName=DNS:search,DNS:localhost -keyout /work/search/search.key -out /work/search/search.crt 2>/dev/null
keytool -importcert -noprompt -alias search -file /work/search/search.crt -keystore /work/search/trust.p12 -storetype PKCS12 -storepass changeit
chmod 0444 /work/search/* /work/tls/fullchain.pem /work/tls/privkey.pem
'@
Checked @('run','--rm','--network','none','--user','0:0','--label',"com.forgeoj.m5check=$project",'--mount',"type=bind,src=$private,dst=/work",'--entrypoint','bash',$apiImage,'-c',$certScript) 'certificates.log'
$config=@"
COMPOSE_PROJECT_NAME=$project
FORGEOJ_DOMAIN=oj.test
FORGEOJ_HTTPS_PORT=18443
FORGEOJ_HTTPS_SUFFIX=:18443
FORGEOJ_HTTP_PORT=18080
FORGEOJ_BIND_IP=127.0.0.1
FORGEOJ_API_IMAGE=$($state.images[0].id)
FORGEOJ_WORKER_IMAGE=$($state.images[1].id)
FORGEOJ_WEB_IMAGE=$($state.images[2].id)
FORGEOJ_PRIVATE_DIR=$($private.Replace('\','/'))
FORGEOJ_DOCKER_SOCKET_GID=$socketGid
FORGEOJ_MAIL_MODE=disabled
"@
[IO.File]::WriteAllText($instance,$config,[Text.UTF8Encoding]::new($false))
& $PythonCommand (Join-Path $package 'scripts/preflight.py') $instance --isolated *> (Join-Path $run 'preflight-isolated.log')
if($LASTEXITCODE -ne 0){throw 'Isolated preflight failed'}
& $PythonCommand (Join-Path $package 'scripts/preflight.py') $instance *> (Join-Path $run 'preflight-production-refused.log')
if($LASTEXITCODE -eq 0){throw 'Production accepted test config'}
$compose=@('compose','--env-file',$instance,'-f',(Join-Path $package 'compose.yml'))
$resolved=(& $DockerCommand @compose config --format json | ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Compose parse failed'}
foreach($property in $resolved.services.PSObject.Properties){
  $service=$property.Value
  if($property.Name -notin @('web','grafana') -and $service.ports){throw 'Infrastructure port exposure'}
}
if($resolved.services.api.volumes | Where-Object {$_.target -eq '/var/run/docker.sock'}){throw 'API gained Docker access'}
if($resolved.services.api.environment.SPRING_PROFILES_ACTIVE){throw 'Unexpected profile'}
Checked ($compose+@('up','-d','--wait','--wait-timeout','180','mysql','rabbitmq','redis','search')) 'infrastructure.log'
Checked ($compose+@('run','--rm','--no-deps','migrate')) 'migrate.log'
Checked ($compose+@('run','--rm','--no-deps','migrate')) 'migrate-repeat.log'
Checked ($compose+@('run','--rm','--no-deps','search-setup')) 'search-setup.log'
Checked ($compose+@('up','-d','--wait','--wait-timeout','180','api','worker','web','prometheus','grafana')) 'runtime.log'
& $NodeCommand (Join-Path $PSScriptRoot 'smoke-https.mjs') $private 18443 18080 (Join-Path $run 'https-smoke.json') *> (Join-Path $run 'https-smoke.log')
if($LASTEXITCODE -ne 0){throw 'HTTPS smoke failed'}
$state.status='RUNTIME_SMOKE_PASS'
if($Lifecycle){
  & $PythonCommand (Join-Path $PSScriptRoot 'audit-package.py') $run $DockerCommand *> (Join-Path $run 'package-audit.log')
  if($LASTEXITCODE -ne 0){throw 'Read-only package audit failed'}
  & $PythonCommand (Join-Path $PSScriptRoot 'check-package-lifecycle.py') $run $DockerCommand *> (Join-Path $run 'package-lifecycle.log')
  if($LASTEXITCODE -ne 0){throw 'Owned package lifecycle check failed'}
  $state.status='LIFECYCLE_CHECKS_PASS'
}
$state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $run 'validation-state.json')
Write-Output "Validation run: $run; owned project: $project. Audit and clean this exact project before final delivery."
