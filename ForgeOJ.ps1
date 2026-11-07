# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [ValidateSet('Start','Status','Stop')][string]$Action = 'Status',
    [string]$RunDirectory,
    [ValidateRange(1024,65535)][int]$Port = 8088,
    [string]$DockerCommand,
    [switch]$SkipFrontendBuild,
    [switch]$OpenBrowser
)
$ErrorActionPreference = 'Stop'
$repository = $PSScriptRoot
$targetRoot = [IO.Path]::GetFullPath((Join-Path $repository 'target'))
$settingsPath = Join-Path $targetRoot 'local-demo.json'
if (-not $DockerCommand) {
    $foundDocker = Get-Command docker -ErrorAction SilentlyContinue
    if ($foundDocker) { $DockerCommand = $foundDocker.Source }
    else { $DockerCommand = Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe' }
}
if (-not (Test-Path -LiteralPath $DockerCommand)) { throw 'Docker CLI not found. Install/open Docker Desktop, then retry.' }
function Docker-Checked([string[]]$Arguments) {
    $result = & $DockerCommand @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker command failed. Check that Docker Desktop is running; no application restart was requested.' }
    return $result
}
if (-not $RunDirectory) {
    if (Test-Path -LiteralPath $settingsPath) {
        $settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
        $RunDirectory = $settings.RunDirectory
        if (-not $PSBoundParameters.ContainsKey('Port')) { $Port = [int]$settings.Port }
    } else { $RunDirectory = Join-Path $targetRoot 'forgeoj-e2e-20261011-145312-83d835a3' }
}
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid local-demo port.' }
if (-not [IO.Path]::IsPathRooted($RunDirectory)) { $RunDirectory = Join-Path $repository $RunDirectory }
$RunDirectory = [IO.Path]::GetFullPath($RunDirectory)
if (-not $RunDirectory.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Demo state must be inside this repository target directory.'
}
$state = Get-Content -LiteralPath (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if ($state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$' -or (Split-Path -Leaf $RunDirectory) -ne $state.Project) {
    throw 'Unrecognized demo ownership. Refusing to operate on this project.'
}
$containers = @(Docker-Checked @('ps','-aq','--filter',"label=com.docker.compose.project=$($state.Project)"))
if (-not $containers.Count) { throw 'Initialized demo containers are missing. Follow docs/LOCAL-DEMO.md; this command never initializes or replaces a database.' }
foreach ($containerId in $containers) {
    $labels = Docker-Checked @('inspect','--format','{{json .Config.Labels}}',$containerId) | ConvertFrom-Json
    if ($labels.'com.docker.compose.project' -ne $state.Project -or -not [IO.Path]::GetFullPath($labels.'com.docker.compose.project.working_dir').StartsWith($repository + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Container ownership or repository directory mismatch.'
    }
}
if ($Action -eq 'Start') {
    $mysqlIds = @(Docker-Checked @('ps','-aq','--filter',"label=com.docker.compose.project=$($state.Project)",'--filter','label=com.docker.compose.service=mysql'))
    if ($mysqlIds.Count -ne 1) { throw 'The original MySQL container is missing; refusing to initialize an empty replacement.' }
    $mysqlMounts = Docker-Checked @('inspect','--format','{{json .Mounts}}',$mysqlIds[0]) | ConvertFrom-Json
    $dataMount = @($mysqlMounts | Where-Object { $_.Type -eq 'volume' -and $_.Destination -eq '/var/lib/mysql' })
    if ($dataMount.Count -ne 1) { throw 'Expected persistent MySQL data volume missing.' }
    $volume = Docker-Checked @('volume','inspect','--format','{{json .Labels}}',$dataMount[0].Name) | ConvertFrom-Json
    if ($volume.'com.docker.compose.project' -ne $state.Project) { throw 'MySQL data volume ownership mismatch.' }
}
$url = "http://127.0.0.1:$Port"
if ($Action -eq 'Status') {
    Docker-Checked @('ps','-a','--filter',"label=com.docker.compose.project=$($state.Project)",'--format','table {{.Names}}\t{{.Status}}\t{{.Ports}}') | Out-Host
    Write-Host "Local URL: $url"
    try {
        $session = Invoke-WebRequest -UseBasicParsing -Uri "$url/api/v1/auth/session" -TimeoutSec 5
        if ($session.StatusCode -eq 200) { Write-Host 'Website/API reachable. Demo login: learner / forgeoj-dev-only' }
    } catch { Write-Host 'Website unavailable. Run: .\ForgeOJ.ps1 -Action Start' }
    return
}
if ($Action -eq 'Stop') {
    # Only the exact inspected project IDs; never remove containers or data volumes.
    Docker-Checked (@('stop','--time','20') + $containers) | Out-Host
    Write-Host 'Demo stopped; containers, submissions and database volumes retained.'
    return
}
foreach ($item in @(@('forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar','ApiHash'),@('forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar','WorkerHash'))) {
    $artifact = Join-Path $state.BuildDirectory $item[0]
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $artifact).Hash.ToLowerInvariant() -ne $state.($item[1])) {
        throw 'Backend artifact does not match the recorded demo state.'
    }
}
$dist = Join-Path $repository 'frontend/dist'
if (-not $SkipFrontendBuild) {
    Push-Location (Join-Path $repository 'frontend')
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed; no demo containers were changed.' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw 'Built frontend is missing. Run Start without -SkipFrontendBuild.' }
$demoEnvironment = @{
    FORGEOJ_E2E_SOURCE=$repository; FORGEOJ_E2E_PROJECT=$state.Project
    FORGEOJ_E2E_ARTIFACTS=$state.BuildDirectory; FORGEOJ_E2E_REPORTS=$RunDirectory
    FORGEOJ_E2E_REDIS_ENABLED=($(if($state.RedisEnabled){'true'}else{'false'}))
    FORGEOJ_E2E_MAIL_APP_URL=$url; FORGEOJ_DEMO_DIST=$dist; FORGEOJ_DEMO_PORT="$Port"
}
$previousEnvironment = @{}
foreach ($name in $demoEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
    [Environment]::SetEnvironmentVariable($name,$demoEnvironment[$name],'Process')
}
try {
    $compose = @('compose','--project-name',$state.Project,'-f',(Join-Path $repository 'tools/validation/compose.replay.yml'))
    if ($state.SearchEnabled) { $compose += @('-f',(Join-Path $repository 'tools/validation/compose.search-replay.yml')) }
    $compose += @('-f',(Join-Path $repository 'tools/demo/compose.local-demo.yml'))
    $dependencies = @('mysql','rabbitmq')
    if ($state.RedisEnabled) { $dependencies += 'redis' }
    if ($state.SearchEnabled) { $dependencies += 'search' }
    Docker-Checked ($compose + @('up','--detach','--no-deps','--no-recreate','--no-build','--pull','never','--wait','--wait-timeout','180') + $dependencies) | Out-Host
    Docker-Checked ($compose + @('up','--detach','--no-deps','--no-recreate','--no-build','--pull','never','--wait','--wait-timeout','180','api','worker')) | Out-Host
    Docker-Checked ($compose + @('up','--detach','--no-deps','--force-recreate','--no-build','--pull','never','--wait','--wait-timeout','90','frontend')) | Out-Host
    $session = Invoke-WebRequest -UseBasicParsing -Uri "$url/api/v1/auth/session" -TimeoutSec 10
    $page = Invoke-WebRequest -UseBasicParsing -Uri "$url/problems" -TimeoutSec 10
    if ($session.StatusCode -ne 200 -or $page.StatusCode -ne 200 -or $page.Content -notmatch 'id="app"') {
        throw 'Website/API checks did not pass; no ready result was recorded.'
    }
    @{RunDirectory=$RunDirectory;Port=$Port;Url=$url;BackendBuild=$state.BuildDirectory;FrontendDist=$dist;CheckedAt=(Get-Date -Format o)} |
        ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding utf8
    Write-Host "Ready: $url/problems"
    Write-Host 'Demo login: learner / forgeoj-dev-only'
    Write-Host 'Keep Docker Desktop running while using this local website.'
    if ($OpenBrowser) { Start-Process -FilePath "$url/problems" }
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process') }
}
