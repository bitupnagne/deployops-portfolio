#Requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$SigningPrivateKey,
      [Parameter(Mandatory=$true)][string]$TrustedPublicKey)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'scripts/offline-native.ps1')
. (Join-Path $repo 'scripts/offline-payload-reuse.ps1')
. (Join-Path $repo 'scripts/offline-component-selection.ps1')
$tokens=$null; $errors=$null
$producer=Join-Path $repo 'scripts/package-offline-installer.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($producer,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Producer AST invalid' }
foreach($name in @('Test-PathInside','Get-OfflineInstallerRelativePath','Test-OfflineInstallerSourcePath','Copy-OfflineInstallerSource','Copy-OfflineJavaCiPayload','Get-OfflineInstallerManifestLines')) {
  $fn=$ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true) | Where-Object Name -eq $name
  if (@($fn).Count -ne 1) { throw "Missing helper $name" }
  Invoke-Expression $fn.Extent.Text
}
$openssl='C:/Program Files/Git/usr/bin/openssl.exe'
$base=Join-Path $repo ('.work/artifacts/payload-reuse-fixture-'+[Guid]::NewGuid().ToString('N'))
$old=Join-Path $base 'old'; $source=Join-Path $base 'source'; $output=Join-Path $base 'output'
function Assert([bool]$Condition,[string]$Message){ if(-not $Condition){throw $Message} }
function Reject([scriptblock]$Action){$rejected=$false;try{&$Action|Out-Null}catch{$rejected=$true};Assert $rejected 'Expected fail-closed rejection'}
function FileId([string]$Path){
  $r=Invoke-OfflineNative 'fsutil.exe' @('file','queryfileid',$Path)
  $id=[regex]::Match($r.Stdout,'0x[0-9a-fA-F]+').Value
  Assert ([bool]$id) 'No filesystem identity returned'
  $id
}
try {
  $files=@('bundles/images/image.tar','bundles/rpms/test.rpm','bundles/common/java-ci/source/.mvn/settings.xml','bundles/common/java-ci/seed/dependency.jar','bundles/common/java-ci/CI-PAYLOAD.json',
    'bundles/common/deploy-system/deploy-ops.jar','bundles/common/worksystem-server/worksystem.jar',
    'bundles/RELEASE-BOM.env','bundles/RELEASE-LOCK.env','bundles/MANIFEST.sha256','bundles/MANIFEST.sha256.sig',
    'bundles/MANIFEST-PUBLIC-KEY.pem','install.sh','RELEASE-CANDIDATE.json')
  foreach($relative in $files){
    foreach($root in @((Join-Path $old 'installer'),$source)){
      $target=Join-Path $root $relative
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)|Out-Null
      [IO.File]::WriteAllText($target,"fixture:$relative")
    }
  }
  $hidden=Join-Path $old 'installer/bundles/common/java-ci/source/.mvn/settings.xml'
  [IO.File]::SetAttributes($hidden,[IO.FileAttributes]::Hidden)
  $manifest=Join-Path $old 'INSTALLER-MANIFEST.sha256'
  [IO.File]::WriteAllText($manifest,((Get-OfflineInstallerManifestLines (Join-Path $old 'installer')) -join "`n")+"`n",[Text.Encoding]::ASCII)
  $null=Invoke-OfflineNative $openssl @('dgst','-sha256','-sign',$SigningPrivateKey,'-out',($manifest+'.sig'),$manifest)
  $context=New-OfflinePayloadReuseContext $old $output $source $TrustedPublicKey $openssl
  $signatureBytes=[IO.File]::ReadAllBytes($manifest+'.sig')
  [IO.File]::WriteAllText($manifest+'.sig','invalid fixture signature')
  Reject {New-OfflinePayloadReuseContext $old (Join-Path $base 'bad-signature-output') $source $TrustedPublicKey $openssl}
  [IO.File]::WriteAllBytes($manifest+'.sig',$signatureBytes)
  New-Item -ItemType Directory -Path (Join-Path $output 'installer')|Out-Null
  Copy-OfflineInstallerSource $source (Join-Path $output 'installer') $context
  Assert ($context.Linked.Count -eq 3) 'Expected only image/RPM/hidden JavaCI payload hardlinks'
  foreach($relative in $files){
    $oldFile=Join-Path $old "installer/$relative"; $newFile=Join-Path $output "installer/$relative"
    $same=(FileId $oldFile) -eq (FileId $newFile)
    Assert ($same -eq (Test-OfflineReusePayload $relative)) "Wrong hardlink/copy identity: $relative"
  }
  Assert (@(Get-OfflineInstallerManifestLines (Join-Path $output 'installer')).Count -eq $files.Count) 'Hidden payload omitted from manifest'
  $normalLines=Get-OfflineInstallerManifestLines (Join-Path $old 'installer')
  $reuseLines=Get-OfflineInstallerManifestLines (Join-Path $output 'installer')
  Assert (($normalLines -join "`n") -ceq ($reuseLines -join "`n")) 'Hardlinked/copy manifest differs'
  $reuseManifest=Join-Path $output 'INSTALLER-MANIFEST.sha256'
  [IO.File]::WriteAllText($reuseManifest,($reuseLines -join "`n")+"`n",[Text.Encoding]::ASCII)
  Copy-Item -LiteralPath ($manifest+'.sig') -Destination ($reuseManifest+'.sig')
  Assert ((Get-FileHash -LiteralPath $manifest).Hash -ceq (Get-FileHash -LiteralPath $reuseManifest).Hash) 'Full manifest bytes changed'
  $null=Invoke-OfflineNative $openssl @('dgst','-sha256','-verify',$TrustedPublicKey,'-signature',($reuseManifest+'.sig'),$reuseManifest)
  Assert-OfflinePayloadReuseUnchanged $context
  # External -JavaCiPayload uses exact signed bundles/common/java-ci paths too.
  $external=Join-Path $base 'external-ci'
  Copy-Item -LiteralPath (Join-Path $source 'bundles/common/java-ci') -Destination $external -Recurse
  $externalOutput=Join-Path $base 'external-output'
  $externalContext=New-OfflinePayloadReuseContext $old $externalOutput $source $TrustedPublicKey $openssl
  $externalTarget=Join-Path $externalOutput 'installer/bundles/common/java-ci'
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $externalTarget)|Out-Null
  Copy-OfflineJavaCiPayload $external $externalTarget $externalContext
  foreach($entry in Get-ChildItem -LiteralPath $external -Recurse -File -Force){
    $relative=Get-OfflineInstallerRelativePath $entry.FullName $external
    $signedRelative='bundles/common/java-ci/'+$relative
    $original=Join-Path $old ('installer/'+$signedRelative)
    $target=Join-Path $externalTarget $relative
    Assert ((Get-FileHash -LiteralPath $entry.FullName).Hash -ceq (Get-FileHash -LiteralPath $target).Hash) 'External CI bytes changed'
    Assert (((FileId $original) -eq (FileId $target)) -eq (Test-OfflineReusePayload $signedRelative)) 'External CI link/copy identity incorrect'
  }
  Assert ($externalContext.Linked.Count -eq 1) 'External hidden CI input not linked or mutable CI file linked'
  $externalJar=Join-Path $externalTarget 'seed/dependency.jar'
  [IO.File]::WriteAllText($externalJar,'updated independent CI JAR')
  Copy-Item -LiteralPath (Join-Path $external 'seed/dependency.jar') -Destination $externalJar -Force
  Assert-OfflinePayloadReuseUnchanged $externalContext
  # Default external import remains full independent copy.
  $defaultTarget=Join-Path $base 'default-ci'
  Copy-OfflineJavaCiPayload $external $defaultTarget
  Assert ((FileId (Join-Path $external 'source/.mvn/settings.xml')) -ne (FileId (Join-Path $defaultTarget 'source/.mvn/settings.xml'))) 'Default external import unexpectedly linked'
  $externalHidden=Join-Path $external 'source/.mvn/settings.xml'
  $hiddenBytes=[IO.File]::ReadAllBytes($externalHidden)
  [IO.File]::WriteAllText($externalHidden,'tampered external CI input')
  Reject {Copy-OfflineReusePayload $externalHidden (Join-Path $externalTarget 'source/.mvn/tampered.xml') 'bundles/common/java-ci/source/.mvn/settings.xml' $externalContext}
  Reject {Copy-OfflineJavaCiPayload $external (Join-Path $externalOutput 'tampered-import') $externalContext}
  [IO.File]::WriteAllBytes($externalHidden,$hiddenBytes)
  $unsignedExternal=Join-Path $external 'unsigned.xml'; [IO.File]::WriteAllText($unsignedExternal,'unsigned external CI input')
  Reject {Copy-OfflineReusePayload $unsignedExternal (Join-Path $externalTarget 'unsigned.xml') 'bundles/common/java-ci/unsigned.xml' $externalContext}
  # The producer validates the new complete JavaCI inventory before this
  # transport helper. Additive members copy independently; direct general
  # reuse above still refuses a member missing from the old signed manifest.
  $lockOutput=Join-Path $base 'lock-output'
  $additiveTarget=Join-Path $lockOutput 'additive-import'
  $externalLock=Join-Path $external 'maven-seed/seed.lock.json'
  [IO.Directory]::CreateDirectory((Split-Path -Parent $externalLock))|Out-Null
  [IO.File]::WriteAllText($externalLock,'regenerated exact seed inventory')
  # Include the old lock in a separate signed baseline: changed metadata must
  # copy independently, while direct general reuse must still reject it.
  $lockOld=Join-Path $base 'lock-old'
  Copy-Item -LiteralPath $old -Destination $lockOld -Recurse -Force
  $oldLock=Join-Path $lockOld 'installer/bundles/common/java-ci/maven-seed/seed.lock.json'
  [IO.Directory]::CreateDirectory((Split-Path -Parent $oldLock))|Out-Null
  [IO.File]::WriteAllText($oldLock,'prior seed inventory')
  $lockManifest=Join-Path $lockOld 'INSTALLER-MANIFEST.sha256'
  [IO.File]::WriteAllText($lockManifest,((Get-OfflineInstallerManifestLines (Join-Path $lockOld 'installer')) -join "`n")+"`n",[Text.Encoding]::ASCII)
  $null=Invoke-OfflineNative $openssl @('dgst','-sha256','-sign',$SigningPrivateKey,'-out',($lockManifest+'.sig'),$lockManifest)
  $lockContext=New-OfflinePayloadReuseContext $lockOld $lockOutput $source $TrustedPublicKey $openssl
  Reject {Copy-OfflineReusePayload $externalLock (Join-Path $base 'general-lock.json') 'bundles/common/java-ci/maven-seed/seed.lock.json' $lockContext}
  [IO.File]::WriteAllText((Join-Path $external 'CI-PAYLOAD.json'),'regenerated complete CI inventory')
  Copy-OfflineJavaCiPayload $external $additiveTarget $lockContext
  foreach($metadata in @('maven-seed/seed.lock.json','CI-PAYLOAD.json')) {
    Assert ((Get-FileHash (Join-Path $additiveTarget $metadata)).Hash -ceq (Get-FileHash (Join-Path $external $metadata)).Hash) 'Regenerated inventory bytes not copied'
    Assert ((FileId (Join-Path $additiveTarget $metadata)) -ne (FileId (Join-Path $lockOld ('installer/bundles/common/java-ci/'+$metadata)))) 'Regenerated inventory must not hardlink old metadata'
  }
  $additiveFile=Join-Path $additiveTarget 'unsigned.xml'
  Assert ((Get-FileHash $additiveFile).Hash -ceq (Get-FileHash $unsignedExternal).Hash) 'Additive CI bytes changed'
  Assert ((FileId $additiveFile) -ne (FileId $unsignedExternal)) 'Additive CI member must be an independent copy'
  Assert ((FileId (Join-Path $additiveTarget 'source/.mvn/settings.xml')) -eq (FileId (Join-Path $lockOld 'installer/bundles/common/java-ci/source/.mvn/settings.xml'))) 'Existing immutable CI member lost signed hardlink reuse'
  Assert-OfflinePayloadReuseUnchanged $lockContext
  [IO.File]::WriteAllText($additiveFile,'updated independent additive member')
  Assert ([IO.File]::ReadAllText($unsignedExternal) -ceq 'unsigned external CI input') 'Additive copy changed its source'
  Assert-OfflinePayloadReuseUnchanged $externalContext
  Reject {New-OfflinePayloadReuseContext $old (Join-Path ([IO.Path]::GetTempPath()) ('cross-volume-'+[Guid]::NewGuid().ToString('N'))) $source $TrustedPublicKey $openssl}
  $junction=Join-Path $base 'junction'
  New-Item -ItemType Junction -Path $junction -Target $old | Out-Null
  Reject {New-OfflinePayloadReuseContext $junction (Join-Path $base 'junction-output') $source $TrustedPublicKey $openssl}
  # Native link failure cannot silently fall back to a payload copy.
  $linkFailure=Join-Path $output 'installer/bundles/images/link-failure.tar'
  Reject { & {
    function New-Item { throw 'injected hardlink creation failure' }
    Copy-OfflineReusePayload (Join-Path $source 'bundles/images/image.tar') $linkFailure 'bundles/images/image.tar' $context
  } }
  Assert (-not (Test-Path -LiteralPath $linkFailure)) 'Link failure silently copied payload'
  Assert-OfflinePayloadReuseUnchanged $context
  # Normal write and injected commit failure/Copy-Item -Force rollback only touch independent copies.
  foreach($relative in $files | Where-Object {-not (Test-OfflineReusePayload $_)}){
    $oldFile=Join-Path $old "installer/$relative"; $newFile=Join-Path $output "installer/$relative"
    [IO.File]::WriteAllText($newFile,'new mutable content')
    try { throw 'injected producer commit failure' } catch { Copy-Item -LiteralPath $oldFile -Destination $newFile -Force }
  }
  Assert-OfflinePayloadReuseUnchanged $context
  $oldPayload=Join-Path $old 'installer/bundles/rpms/test.rpm'
  $savedBytes=[IO.File]::ReadAllBytes($oldPayload)
  [IO.File]::WriteAllText($oldPayload,'tampered original fixture')
  Reject {Assert-OfflinePayloadReuseUnchanged $context}
  Reject {New-OfflinePayloadReuseContext $old (Join-Path $base 'tampered-output') $source $TrustedPublicKey $openssl}
  [IO.File]::WriteAllBytes($oldPayload,$savedBytes)
  Assert-OfflinePayloadReuseUnchanged $context
  Reject {New-OfflinePayloadReuseContext $old $output $source $TrustedPublicKey $openssl}
  Reject {New-OfflinePayloadReuseContext $old (Join-Path $old 'child') $source $TrustedPublicKey $openssl}
  $badKey=Join-Path $old 'embedded-public.pem'; Copy-Item -LiteralPath $TrustedPublicKey -Destination $badKey
  Reject {New-OfflinePayloadReuseContext $old (Join-Path $base 'other') $source $badKey $openssl}
  $changed=Join-Path $source 'bundles/images/image.tar'; [IO.File]::WriteAllText($changed,'changed source')
  Reject {Copy-OfflineReusePayload $changed (Join-Path $output 'installer/bundles/images/changed.tar') 'bundles/images/image.tar' $context}
  $missing=Join-Path $source 'bundles/images/new.tar'; [IO.File]::WriteAllText($missing,'not manifested')
  Reject {Copy-OfflineReusePayload $missing (Join-Path $output 'installer/bundles/images/new.tar') 'bundles/images/new.tar' $context}
  Assert-OfflinePayloadReuseUnchanged $context
  Write-Output 'PASS: real NTFS hardlink identity, signed source, hidden payload, independent mutable files and rollback preserve original candidate'
} finally {
  $resolved=[IO.Path]::GetFullPath($base)
  $parent=[IO.Path]::GetFullPath((Join-Path $repo '.work/artifacts'))
  if([IO.Path]::GetDirectoryName($resolved) -cne $parent -or [IO.Path]::GetFileName($resolved) -cnotmatch '^payload-reuse-fixture-[a-f0-9]{32}$'){throw 'Unsafe fixture cleanup path'}
  if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
