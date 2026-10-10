# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$MavenRepository,[Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$MavenRepository=(Resolve-Path -LiteralPath $MavenRepository).Path
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory=(Resolve-Path -LiteralPath $OutputDirectory).Path
function Pom-Path([string]$Group,[string]$Artifact,[string]$Version){Join-Path $MavenRepository ($Group.Replace('.','/')+'/'+$Artifact+'/'+$Version+'/'+$Artifact+'-'+$Version+'.pom')}
function Licenses([xml]$Pom,[int]$Depth=0){
 if($Depth -gt 8){throw 'Unexpected parent depth'}
 $direct=@($Pom.project.licenses.license | Where-Object {$_} | ForEach-Object {@{name=[string]$_.name;url=[string]$_.url}})
 if($direct.Count){return $direct}
 if($Pom.project.parent){$p=$Pom.project.parent;return Licenses ([xml](Get-Content -LiteralPath (Pom-Path $p.groupId $p.artifactId $p.version) -Raw)) ($Depth+1)}
 return @()
}
$coordinates=@(
 @('org.springframework.boot','spring-boot-starter-data-redis','4.1.1'),
 @('org.springframework.boot','spring-boot-data-redis','4.1.1'),
 @('org.springframework.boot','spring-boot-data-commons','4.1.1'),
 @('org.springframework.data','spring-data-redis','4.1.1'),
 @('org.springframework.data','spring-data-keyvalue','4.1.1'),
 @('org.springframework.data','spring-data-commons','4.1.1'),
 @('io.lettuce','lettuce-core','7.5.2.RELEASE'),
 @('redis.clients.authentication','redis-authx-core','0.1.1-beta2'),
 @('io.projectreactor','reactor-core','3.8.7'),
 @('org.reactivestreams','reactive-streams','1.0.4')
)
foreach($name in @('common','handler','resolver','buffer','transport-native-unix-common','codec-base','transport','resolver-dns','codec-dns','codec','codec-compression','codec-protobuf','codec-marshalling')){$coordinates+=,@('io.netty',('netty-'+$name),'4.2.17.Final')}
$records=@()
foreach($coordinate in $coordinates){
 $pomPath=Pom-Path $coordinate[0] $coordinate[1] $coordinate[2]
 $jarPath=[IO.Path]::ChangeExtension($pomPath,'.jar')
 $pom=[xml](Get-Content -LiteralPath $pomPath -Raw)
 $jar=[IO.Compression.ZipFile]::OpenRead($jarPath)
 try{$resources=@($jar.Entries | Where-Object {$_.FullName -match '(?i)(license|notice|copying|third.party)' -and $_.Length -gt 0} | ForEach-Object { $stream=$_.Open();try{$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLower()}finally{$stream.Dispose()};@{path=$_.FullName;sha256=$digest}})}
 finally{$jar.Dispose()}
 $records+=@{group=$coordinate[0];artifact=$coordinate[1];version=$coordinate[2];pomSha256=(Get-FileHash -LiteralPath $pomPath -Algorithm SHA256).Hash.ToLower();jarSha256=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLower();licenses=@(Licenses $pom);resources=$resources}
}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'redis-dependencies.json'),(@{checkedAt=(Get-Date -Format o);basis='Boot 4.1.1 managed runtime coordinates, actual resolved POM/JAR and retained resource digests';artifacts=$records} | ConvertTo-Json -Depth 10)+"`n",[Text.UTF8Encoding]::new($false))
$lines=@('# M4 Redis 构件事实','','2026-10-09；直接使用 Boot 4.1.1 BOM，记录实际解析的 POM/JAR 与其原始告知资源；不复制上游源码，不把客户端、协议或序列化实现算作 ForgeOJ 自有代码。此表不是整个 Release 分发组合的许可验收。','','| 构件 | 版本 | POM 许可声明 | JAR SHA-256 |','| --- | --- | --- | --- |')
foreach($record in $records){$lines+='| '+$record.artifact+' | '+$record.version+' | '+(($record.licenses.name) -join '; ')+' | '+$record.jarSha256+' |'}
$lines+=@('','完整 POM/JAR 与 LICENSE/NOTICE 路径及摘要见 redis-dependencies.json。Spring 项保留 Apache-2.0；本版本 Lettuce 和 Redis Authx 的实际 POM 为 MIT，不能根据旧版本记忆写成 Apache。Reactor/Netty 的上游及内嵌告知仍各自保留；运行包保留原始 JAR。','Redis 服务端使用官方 7.2.16 BSD-3-Clause 镜像；固定镜像摘要及实际 redis-server --version 见设计和最终运行证据；服务端不内嵌 API JAR。')
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'redis-dependencies.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Write-Host ("Captured "+$records.Count+" fixed Redis dependency artifacts")
