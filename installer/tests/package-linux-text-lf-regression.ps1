#Requires -Version 7.5
# Actual copy/writer functions only. No producer, signing key, Docker or installer execution.
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'scripts/offline-component-selection.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('package-linux-text-lf-'+[Guid]::NewGuid().ToString('N'))
$fixtureInput=Join-Path $fixture 'input';$output=Join-Path $fixture 'output'
try {
New-Item -ItemType Directory -Path $fixtureInput,$output | Out-Null
$tokens=$null;$errors=$null
$producer=Join-Path $repo 'scripts/package-offline-installer.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($producer,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Producer AST invalid'}
foreach($name in @('Test-PathInside','Get-OfflineInstallerRelativePath','Test-OfflineInstallerSourcePath','Copy-OfflineInstallerSource','Get-OfflineInstallerManifestLines')){
 $fn=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true))
 if($fn.Count -ne 1){throw 'Actual function missing'}
 Invoke-Expression $fn[0].Extent.Text
}
. (Join-Path $repo 'scripts/offline-payload-reuse.ps1')
$cases=0
function Assert($value,$message){if(!$value){throw $message};$script:cases++}
function Put($relative,[byte[]]$bytes){$p=Join-Path $fixtureInput $relative;New-Item -ItemType Directory -Force -Path (Split-Path -Parent $p)|Out-Null;[IO.File]::WriteAllBytes($p,$bytes)}
foreach($name in @('lib/ui.sh','lib/nexus-backup.sh')){Put $name ([IO.File]::ReadAllBytes((Join-Path $repo ('installer/'+$name))))}
foreach($name in @('nexus-backup-broker.py','nexus-local-backup-restore.py','nexus-isolated-restore-worker.py')){Put ('nexus-backup/'+$name) ([IO.File]::ReadAllBytes((Join-Path $repo ('installer/nexus-backup/'+$name))))}
foreach($name in @('lib/bom.sh','profiles/fixture.env','nexus-backup/fixture.py','deploy-system/fixture.service','deploy-system/fixture.timer')){
 $text=if($name.EndsWith('.sh')){"#!/bin/bash`r`necho '한글'`r`n"}else{"fixture=한글`r`n"}
 Put $name ([byte[]](@(239,187,191)+[Text.Encoding]::UTF8.GetBytes($text)))
}
$lf=[Text.Encoding]::UTF8.GetBytes("#!/bin/bash`necho fixed`n")
Put 'lib/already-lf.sh' $lf
Put 'bundles/fetch-bundles.sh' ([byte[]](@(239,187,191)+[Text.Encoding]::UTF8.GetBytes("#!/bin/bash`r`ncase x in x) :;; esac`r`n")))
$fetchInputHash=(Get-FileHash (Join-Path $fixtureInput 'bundles/fetch-bundles.sh')).Hash
$binary=[byte[]]@(0,255,13,10,239,187,191,1)
foreach($name in @('bundles/fixture.jar','bundles/fixture.rpm','bundles/fixture.tar')){Put $name $binary}
Put 'bundles/locked.env' ([Text.Encoding]::ASCII.GetBytes("LOCKED=unchanged`r`n"))
$package=Join-Path $fixture 'old-package'
$oldShell=Join-Path $package 'installer/lib/ui.sh'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $oldShell)|Out-Null
# Deliberately preserve CRLF in this independent old-generation fixture. The
# destination hardlink must be replaced, never normalized through the alias.
[IO.File]::WriteAllBytes($oldShell,[Text.Encoding]::UTF8.GetBytes("#!/bin/bash`r`nprintf 'old-generation\n'`r`n"))
$oldShellHash=(Get-FileHash $oldShell).Hash
$manifest=Join-Path $package 'INSTALLER-MANIFEST.sha256'
$signature=$manifest+'.sig'
# Opaque non-secret sentinel only: this test checks byte preservation, not signing.
[IO.File]::WriteAllBytes($signature,[byte[]]@(1,2,3,4))
# A pre-existing destination alias to an old source must be replaced, never written through.
New-Item -ItemType Directory -Path (Join-Path $output 'lib') | Out-Null
New-Item -ItemType HardLink -Path (Join-Path $output 'lib/ui.sh') -Target $oldShell | Out-Null
$relative='bundles/common/fixture/tiny.bin'
$oldPayload=Join-Path $package ('installer/'+$relative)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $oldPayload)|Out-Null
[IO.File]::WriteAllBytes($oldPayload,[byte[]]@(65,66,13,10,255))
$payloadHash=(Get-FileHash $oldPayload).Hash
[IO.File]::WriteAllText($manifest,($payloadHash.ToLowerInvariant()+"  installer/$relative`n"),[Text.Encoding]::ASCII)
$manifestHash=(Get-FileHash $manifest).Hash;$signatureHash=(Get-FileHash $signature).Hash
$signedRow=@([IO.File]::ReadAllLines($manifest)|Where-Object{$_ -match ('  installer/'+[regex]::Escape($relative)+'$')})
Assert ($signedRow.Count -eq 1 -and $signedRow[0].StartsWith($payloadHash.ToLowerInvariant()+'  ')) 'Old fixture member not bound to manifest'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent (Join-Path $fixtureInput $relative))|Out-Null
New-Item -ItemType HardLink -Path (Join-Path $fixtureInput $relative) -Target $oldPayload|Out-Null
$members=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::OrdinalIgnoreCase)
$members.Add('installer/'+$relative,[pscustomobject]@{Path=$oldPayload;Hash=$payloadHash.ToLowerInvariant();Length=(Get-Item $oldPayload).Length})
$context=[pscustomobject]@{Output=$output;Members=$members;Linked=[Collections.Generic.List[string]]::new()}
# Only an eligible immutable binary uses reuse. Derived seed.lock.json is
# intentionally excluded by the current producer and is covered in unsigned-reuse.
function Copy-OfflineReusePayload {
 param($Source,$Destination,$Relative,$Context)
 if($Relative -cne 'bundles/common/fixture/tiny.bin'){return $false}
 & $actualReuse $Source $Destination $Relative $Context
}
$reuseAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts/offline-payload-reuse.ps1'),[ref]$tokens,[ref]$errors)
$reuseFn=@($reuseAst.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Copy-OfflineReusePayload'},$true))[0]
$actualReuse=[scriptblock]::Create($reuseFn.Body.Extent.Text.TrimStart('{').TrimEnd('}'))
Copy-OfflineInstallerSource $fixtureInput $output $context
Assert ([IO.File]::ReadAllText((Join-Path $output 'bundles/fetch-bundles.sh')) -ceq "#!/bin/bash`ncase x in x) :;; esac`n") 'Installer-owned bundle fetch script not LF normalized'
Assert ((Get-FileHash (Join-Path $fixtureInput 'bundles/fetch-bundles.sh')).Hash -ceq $fetchInputHash) 'Fetch source bytes changed'
$pinnedSourceResult=& py -X utf8 (Join-Path $PSScriptRoot 'test_nexus_broker_pinned_sources.py') (Join-Path $output 'nexus-backup')
Assert ($LASTEXITCODE -eq 0) 'Actual copied broker/helper/worker exact pins failed'
$pinnedSourceChecks=$pinnedSourceResult|ConvertFrom-Json -DateKind String
Assert ($pinnedSourceChecks.cases -eq 6 -and $pinnedSourceChecks.copiedHelperAndWorkerLoad -eq $true -and $pinnedSourceChecks.tamperedBytesRejected -eq $true) 'Pinned source loader checks incomplete'
foreach($p in Get-ChildItem $output -Recurse -File|Where-Object{$_.FullName -notmatch '[\\/]bundles[\\/]'}){
 $bytes=[IO.File]::ReadAllBytes($p.FullName)
 Assert (-not ($bytes -contains 13) -and -not ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191)) 'Linux source framing remains CR/BOM'
}
Assert ([Convert]::ToHexString([IO.File]::ReadAllBytes((Join-Path $output 'lib/bom.sh'))) -ceq [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes("#!/bin/bash`necho '한글'`n"))) 'UTF8 content changed beyond BOM/newline normalization'
Assert ([IO.File]::ReadAllText((Join-Path $output 'profiles/fixture.env')) -ceq "fixture=한글`n") 'Sourced environment values changed'
$bash='C:/Program Files/Git/bin/bash.exe'
foreach($name in @('lib/ui.sh','lib/nexus-backup.sh','lib/bom.sh','lib/already-lf.sh','bundles/fetch-bundles.sh')){& $bash -n (Join-Path $output $name);Assert ($LASTEXITCODE -eq 0) 'Copied shell syntax failed'}
Assert ((Get-FileHash (Join-Path $output 'lib/already-lf.sh')).Hash -ceq (Get-FileHash (Join-Path $fixtureInput 'lib/already-lf.sh')).Hash) 'Existing LF bytes changed'
foreach($name in @('bundles/fixture.jar','bundles/fixture.rpm','bundles/fixture.tar','bundles/locked.env')){Assert ((Get-FileHash (Join-Path $fixtureInput $name)).Hash -ceq (Get-FileHash (Join-Path $output $name)).Hash) 'Immutable payload bytes changed'}
Assert ($context.Linked.Count -eq 1 -and (Get-FileHash $oldPayload).Hash -ceq $payloadHash) 'Actual reused payload changed'
Assert ((Get-FileHash $oldShell).Hash -ceq $oldShellHash) 'Old shell was changed through destination hardlink'
Assert ((Get-FileHash $manifest).Hash -ceq $manifestHash -and (Get-FileHash $signature).Hash -ceq $signatureHash) 'Old fixture manifest/signature sentinel changed'
$prepare=Join-Path $repo 'scripts/prepare-offline-bundle.ps1'
$prepareAst=[Management.Automation.Language.Parser]::ParseFile($prepare,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Prepare AST invalid'}
$writer=@($prepareAst.FindAll({param($n)$n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and $n.Extent.Text -match '^\[System.IO.File\]::WriteAllText\(\$lockTemp,'},$true))
Assert ($writer.Count -eq 1) 'Actual LOCK writer absent'
$lockTemp=Join-Path $fixture 'RELEASE-LOCK.env';$lockLines=@('LOCK_SCHEMA=2','LOCK_FIXED=value')
Invoke-Expression $writer[0].Extent.Text
Assert ([Convert]::ToHexString([IO.File]::ReadAllBytes($lockTemp)) -ceq [Convert]::ToHexString([Text.Encoding]::ASCII.GetBytes("LOCK_SCHEMA=2`nLOCK_FIXED=value`n"))) 'Normal LOCK writer did not produce exact LF values'
@{status='PASS';cases=$cases;sourceSha256=(Get-FileHash $producer).Hash;prepareSha256=(Get-FileHash $prepare).Hash;selfContainedFixture=$true;oldFixtureBytesPreserved=$true;signatureVerificationTested=$false;actualReusableMemberUnchanged=$true;producerExecuted=$false;keysRead=$false;dockerCalls=0} | ConvertTo-Json
} finally {
 $full=[IO.Path]::GetFullPath($fixture)
 $tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char]92,[char]47)
 if([IO.Path]::GetDirectoryName($full) -cne $tempParent -or [IO.Path]::GetFileName($full) -cnotmatch '^package-linux-text-lf-[a-f0-9]{32}$'){throw 'Unsafe LF fixture cleanup target'}
 if(Test-Path -LiteralPath $full){Remove-Item -LiteralPath $full -Recurse -Force}
}
