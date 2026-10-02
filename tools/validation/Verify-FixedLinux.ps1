# Copyright 2026 池也
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [ValidateSet('All', 'Backend', 'Frontend')]
    [string]$Scope = 'All',
    [string]$DockerCommand = 'docker'
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runId = 'forgeoj-linux-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$artifacts = Join-Path $repository "target/$runId"
New-Item -ItemType Directory -Path $artifacts | Out-Null

function Invoke-DockerChecked {
    param([string[]]$Arguments)
    & $DockerCommand @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed with exit code $LASTEXITCODE" }
}

# Each run creates only disposable named containers, no volumes/caches or real DB writes.
# --rm cleans the named builder; integration fixtures have their own Testcontainers cleanup.
Write-Host "Linux verification artifacts: $artifacts"
if ($Scope -in @('All', 'Backend')) {
    # Fault tests intentionally require no concurrent managed sandbox test run.
    $managed = & $DockerCommand container ls -aq --filter 'label=com.forgeoj.managed=true'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker sandbox precondition' }
    if ($managed) { throw 'Stop: another managed sandbox exists; do not delete or run tests concurrently.' }
    $backendImage = "$runId-backend-image"
    try {
        Invoke-DockerChecked -Arguments @('build', '--platform', 'linux/amd64', '-f',
            "$PSScriptRoot/Dockerfile.backend", '-t', $backendImage, $PSScriptRoot)
        Invoke-DockerChecked -Arguments @('run', '--rm', '--name', "$runId-backend", '--platform', 'linux/amd64',
            '--mount', "type=bind,source=$repository,target=/source,readonly",
            '--mount', "type=bind,source=$artifacts,target=/artifacts",
            '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
            '-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal',
            $backendImage, '/source/tools/validation/verify-linux.sh', 'backend')
    } finally {
        # Only remove the exact image tag created by this invocation, never prune shared images.
        & $DockerCommand image rm $backendImage
    }
}
if ($Scope -in @('All', 'Frontend')) {
    Invoke-DockerChecked -Arguments @('run', '--rm', '--name', "$runId-frontend", '--platform', 'linux/amd64',
        '--mount', "type=bind,source=$repository,target=/source,readonly",
        '--mount', "type=bind,source=$artifacts,target=/artifacts",
        '--entrypoint', '/bin/bash',
        'node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c',
        '/source/tools/validation/verify-linux.sh', 'frontend')
}
Write-Host "Verification complete. Reports: $artifacts"
