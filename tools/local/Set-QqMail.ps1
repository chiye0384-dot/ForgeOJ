# Copyright 2026 池也
# SPDX-License-Identifier: Apache-2.0
# Dot-source this script so configuration applies to the current PowerShell process.
[CmdletBinding()]
param(
    [string]$Email,
    [switch]$Load,
    [string]$ApplicationUrl = 'http://localhost:5173'
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This setup requires Windows current-user DPAPI encryption.' }
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$configurationPath = Join-Path $repository '.smtp.qq.local'
if ($MyInvocation.InvocationName -ne '.') { throw 'Run with a leading dot: . ./tools/local/Set-QqMail.ps1 -Email your@qq.com' }
if ($Load) {
    $configuration = Get-Content -LiteralPath $configurationPath -Raw | ConvertFrom-Json
    $Email = $configuration.email
    $secureAuthorization = ConvertTo-SecureString -String $configuration.encryptedAuthorization
} else {
    if ($Email -notmatch '^\d{5,12}@qq\.com$') { throw 'Enter the QQ email address, for example 123456@qq.com.' }
    $secureAuthorization = Read-Host 'QQ SMTP authorization code (hidden; NOT your QQ login password)' -AsSecureString
    if ($secureAuthorization.Length -eq 0) { throw 'Authorization code cannot be empty.' }
    $configuration = [ordered]@{
        email = $Email
        encryptedAuthorization = ConvertFrom-SecureString -SecureString $secureAuthorization
    }
    # *.local is ignored by Git and excluded from fixed-Linux source copies.
    $configuration | ConvertTo-Json | Set-Content -LiteralPath $configurationPath -Encoding UTF8
}
if ($Email -notmatch '^\d{5,12}@qq\.com$') { throw 'Stored QQ email configuration is invalid.' }
$address = [uri]$ApplicationUrl
if (-not $address.IsAbsoluteUri -or $address.Scheme -notin @('http','https') -or
    ($address.Scheme -eq 'http' -and $address.Host -notin @('localhost','127.0.0.1','[::1]'))) {
    throw 'Use an HTTPS application URL, or a local HTTP development URL.'
}
$authorizationPointer = [IntPtr]::Zero
try {
    $authorizationPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureAuthorization)
    $env:FORGEOJ_AUTH_MAIL_SMTP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($authorizationPointer)
} finally {
    if ($authorizationPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($authorizationPointer) }
    $secureAuthorization.Dispose()
}
$env:FORGEOJ_AUTH_MAIL_MODE = 'smtp'
$env:FORGEOJ_AUTH_MAIL_SMTP_HOST = 'smtp.qq.com'
$env:FORGEOJ_AUTH_MAIL_SMTP_PORT = '465'
$env:FORGEOJ_AUTH_MAIL_SMTP_TLS = 'implicit'
$env:FORGEOJ_AUTH_MAIL_SMTP_FROM = $Email
$env:FORGEOJ_AUTH_MAIL_SMTP_USERNAME = $Email
$env:FORGEOJ_AUTH_MAIL_APP_URL = $ApplicationUrl
Write-Host 'QQ SMTP configured for this PowerShell process. No email has been sent.'
Write-Host 'The saved authorization code is encrypted for your Windows user; the local file is ignored by Git.'
