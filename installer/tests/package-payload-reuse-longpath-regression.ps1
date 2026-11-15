#Requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$SigningPrivateKey,
      [Parameter(Mandatory=$true)][string]$TrustedPublicKey)
$ErrorActionPreference='Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Actual Windows NTFS test required' }
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'scripts/offline-native.ps1')
. (Join-Path $repo 'scripts/offline-payload-reuse.ps1')
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts/package-offline-installer.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Producer AST invalid' }
foreach ($name in @('Test-PathInside','Get-OfflineInstallerRelativePath','Get-OfflineInstallerManifestLines')) {
  $fn=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)|Where-Object Name -eq $name)
  if ($fn.Count-ne1) { throw 'Missing producer helper' }
  Invoke-Expression $fn[0].Extent.Text
}
function Assert([bool]$Condition,[string]$Message) { if(-not$Condition){throw $Message} }
function FileId([string]$Path) {
  $r=Invoke-OfflineNative 'fsutil.exe' @('file','queryfileid',(ConvertTo-OfflineHardLinkPath $Path))
  $id=[regex]::Match($r.Stdout,'0x[0-9a-fA-F]+').Value
  Assert ([bool]$id) 'No file identity returned'
  return $id
}
$base=Join-Path $repo ('.work/artifacts/payload-longpath-fixture-'+[Guid]::NewGuid().ToString('N'))
$old=Join-Path $base 'old';$source=Join-Path $base 'source';$output=Join-Path $base 'output'
$long='bundles/common/java-ci/seed/'+(('segment-'*9)+'/')*3+'dependency.pom.sha1'
$relatives=@('bundles/images/short.tar',$long)
try {
  foreach($relative in $relatives) {
    foreach($root in @((Join-Path $old 'installer'),$source)) {
      $path=Join-Path $root $relative
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path)|Out-Null
      [IO.File]::WriteAllText($path,'nonsecret-fixed-hardlink-fixture')
    }
  }
  $manifest=Join-Path $old 'INSTALLER-MANIFEST.sha256'
  [IO.File]::WriteAllText($manifest,((Get-OfflineInstallerManifestLines (Join-Path $old 'installer'))-join "`n")+"`n",[Text.Encoding]::ASCII)
  $null=Invoke-OfflineNative 'C:/Program Files/Git/usr/bin/openssl.exe' @('dgst','-sha256','-sign',$SigningPrivateKey,'-out',($manifest+'.sig'),$manifest)
  $context=New-OfflinePayloadReuseContext $old $output $source $TrustedPublicKey 'C:/Program Files/Git/usr/bin/openssl.exe'
  foreach($relative in $relatives) {
    $sourceFile=Join-Path $source $relative;$oldFile=Join-Path $old ('installer/'+$relative);$target=Join-Path $output ('installer/'+$relative)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)|Out-Null
    Assert (Copy-OfflineReusePayload $sourceFile $target $relative $context) 'Actual eligible hardlink not created'
    Assert ((FileId $oldFile)-eq(FileId $target)) 'Actual file identity differs: copied instead of linked'
    Assert ((Get-FileHash -LiteralPath $target).Hash-eq(Get-FileHash -LiteralPath $oldFile).Hash) 'Hardlink bytes differ'
    if($relative-eq$long) { Assert ($sourceFile.Length-gt260-and$oldFile.Length-gt260-and$target.Length-gt260) 'Long source/target fixture too short' }
  }
  Assert ($context.Linked.Count-eq2) 'Expected short and long hardlinks'
  Assert-OfflinePayloadReuseUnchanged $context
  Assert ((ConvertTo-OfflineHardLinkPath '\\server\share\payload.tar')-ceq'\\?\UNC\server\share\payload.tar') 'UNC lexical conversion changed'
  Assert ((ConvertTo-OfflineHardLinkPath '\\?\D:\payload.tar')-ceq'\\?\D:\payload.tar') 'Extended path double-prefixed'
  Assert ((ConvertTo-OfflineHardLinkPath 'D:\payload.tar')-ceq'\\?\D:\payload.tar') 'Local path conversion changed'
  Write-Output 'PASS: actual signed NTFS short/long source+target hardlinks share file IDs and hashes; original audit and lexical UNC/idempotent conversion passed'
} finally {
  $resolved=[IO.Path]::GetFullPath($base)
  $parent=[IO.Path]::GetFullPath((Join-Path $repo '.work/artifacts'))
  if([IO.Path]::GetDirectoryName($resolved)-cne$parent-or[IO.Path]::GetFileName($resolved)-cnotmatch'^payload-longpath-fixture-[a-f0-9]{32}$'){throw 'Unsafe fixture cleanup path'}
  if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
