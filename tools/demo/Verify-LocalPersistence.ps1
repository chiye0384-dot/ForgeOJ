# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$DockerCommand)
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDirectory=(Resolve-Path $RunDirectory).Path
if(-not $RunDirectory.StartsWith((Join-Path $repository 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned target directory required'}
$state=Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
if($state.ProductDemo -ne $true -or (Split-Path -Leaf $RunDirectory) -ne $state.Project -or $state.Project -notmatch '^forgeoj-e2e-\d{8}-\d{6}-[a-f0-9]{8}$'){throw 'Dedicated demo required'}
$managed=@(& $DockerCommand ps -q --filter 'label=com.forgeoj.managed=true')
if($LASTEXITCODE -ne 0){throw 'Cannot check active judging'}
if($managed.Count){throw 'Wait for active judging to finish before testing stop/start'}
$mysql=$state.Project+'-mysql-1'
function Facts {
    $sql=@'
SELECT CONCAT('problem:',id,':',slug,':',COALESCE(difficulty,'')) FROM problem ORDER BY id;
SELECT CONCAT('submission:',id,':',processing_status,':',COALESCE(verdict,''),':',SHA2(source_code,256)) FROM submission ORDER BY id;
SELECT CONCAT('draft:',user_id,':',problem_id,':',version,':',SHA2(source_code,256)) FROM user_code_draft ORDER BY user_id,problem_id;
SELECT CONCAT('review:',id,':',review_status,':',version) FROM content_review ORDER BY id;
SELECT CONCAT('list:',list_id,':',position,':',problem_id) FROM official_problem_list_item ORDER BY list_id,position;
'@
    $result=$sql | & $DockerCommand exec -i $mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" --batch --skip-column-names forgeoj'
    if($LASTEXITCODE -ne 0){throw 'Cannot capture persistent facts'}
    return @($result)
}
$before=Facts
$before | Set-Content (Join-Path $RunDirectory 'persistence-before.txt') -Encoding utf8
$volumeBefore=& $DockerCommand inspect --format '{{json .Mounts}}' $mysql
if($LASTEXITCODE -ne 0){throw 'Cannot inspect data volume'}
& (Join-Path $repository 'ForgeOJ.ps1') -Action Stop -RunDirectory $RunDirectory -DockerCommand $DockerCommand
& (Join-Path $repository 'ForgeOJ.ps1') -Action Start -RunDirectory $RunDirectory -DockerCommand $DockerCommand -SkipFrontendBuild
$after=Facts
$after | Set-Content (Join-Path $RunDirectory 'persistence-after.txt') -Encoding utf8
$volumeAfter=& $DockerCommand inspect --format '{{json .Mounts}}' $mysql
if($LASTEXITCODE -ne 0){throw 'Cannot inspect restarted data volume'}
if(Compare-Object $before $after){throw 'Persistent facts changed across stop/start'}
if($volumeBefore -ne $volumeAfter){throw 'MySQL data mounts changed'}
@{status='VERIFIED';project=$state.Project;checkedAt=(Get-Date -Format o);rows=$before.Count;dataMountsUnchanged=$true;url='http://127.0.0.1:8088/problems'} |
    ConvertTo-Json | Set-Content (Join-Path $RunDirectory 'persistence-verification.json') -Encoding utf8
Write-Host 'Persistent problems/submissions/drafts/reviews/list positions unchanged across stop/start.'
