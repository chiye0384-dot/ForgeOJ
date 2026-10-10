# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[string]$Keytool='D:/JDK21/bin/keytool.exe')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
$parent=Join-Path $repository 'target'
if(-not $run.StartsWith($parent+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Certificates must belong to an owned replay directory'}
$name=Split-Path -Leaf $run
if($name -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Invalid replay identity'}
$certs=Join-Path $run 'search-certs'
if(Test-Path -LiteralPath $certs){throw 'Do not replace existing replay certificates'}
New-Item -ItemType Directory -Path $certs | Out-Null
& $Keytool -genkeypair -alias search-fixture -keyalg RSA -keysize 2048 -validity 7 -dname 'CN=search-fixture' -ext 'SAN=dns:search,dns:localhost,ip:127.0.0.1' -storetype PKCS12 -keystore (Join-Path $certs 'server.p12') -storepass m4-search-tls-fixture -keypass m4-search-tls-fixture
if($LASTEXITCODE -ne 0){throw 'Fixture server certificate generation failed'}
& $Keytool -exportcert -rfc -alias search-fixture -keystore (Join-Path $certs 'server.p12') -storepass m4-search-tls-fixture -file (Join-Path $certs 'server.crt')
if($LASTEXITCODE -ne 0){throw 'Fixture public certificate export failed'}
& $Keytool -importcert -noprompt -alias search-fixture -file (Join-Path $certs 'server.crt') -storetype PKCS12 -keystore (Join-Path $certs 'trust.p12') -storepass m4-search-tls-fixture
if($LASTEXITCODE -ne 0){throw 'Fixture public-only truststore generation failed'}
& $Keytool '-J-Duser.language=en' '-J-Duser.country=US' -list -keystore (Join-Path $certs 'trust.p12') -storepass m4-search-tls-fixture | Set-Content -LiteralPath (Join-Path $certs 'trust-summary.txt') -Encoding utf8
if($LASTEXITCODE -ne 0){throw 'Fixture truststore inspection failed'}
if(-not (Select-String -LiteralPath (Join-Path $certs 'trust-summary.txt') -Pattern 'trustedCertEntry' -Quiet)){throw 'API truststore must contain only a trusted public certificate'}
Write-Host 'Owned fixture certificates generated; API receives only the public truststore.'
