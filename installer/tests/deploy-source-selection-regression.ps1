#Requires -Version 7.3
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../../scripts/offline-component-selection.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('deploy-source-' + [Guid]::NewGuid().ToString('N'))
$inputRoot = Join-Path $fixture 'input'
$outputRoot = Join-Path $fixture 'output'
$allowed = @('pom.xml','src/main/java/example/Demo.java','src/main/resources/application.yml',
  'src/test/java/example/DemoTest.java','.mvn/wrapper/maven-wrapper.properties',
  'scripts/offline-component-selection.ps1','installer/lib/release-metadata.sh',
  'config/application-install.example.yml','installer/answers.example.env','installer/profiles/target-was.env')
$excluded = @('worksystem-server/pom.xml','worksystem-server-0.2/target/worksystem.jar',
  'src/main/resources/application-install.yml','src/main/resources/application-qa.yml',
  'config/private/real.yml','.work/private/secret.json','dist/old.tar.gz',
  'installer/bundles/common/deploy-system/deploy-ops.jar','installer/sim/answers/dev.env',
  'src/main/resources/connection.private.yml','src/main/resources/debug.log',
  'src/main/resources/id_rsa','scripts/token.secret','scripts/old.ps1.bak-20261019',
  'src/main/resources/application-install.yml.bak','src/main/resources/application-install.yml.old',
  'src/main/resources/application-install.yml~','src/main/resources/application-qa.yml.BAK',
  'src/main/resources/application-install.yml.old.20261020',
  'installer/answers.env','installer/answers.env.bak','installer/answers.env.old',
  'installer/answers.env~','installer/answers.env.20261020','installer/components/common.sh.bak')
try {
  foreach($relative in $allowed + $excluded) {
    $path=Join-Path $inputRoot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path,$relative)
  }
  Copy-OfflineDeploySource $inputRoot $outputRoot
  $actual=@(Get-ChildItem -LiteralPath $outputRoot -File -Force -Recurse | ForEach-Object {
    $_.FullName.Substring($outputRoot.Length+1).Replace('\','/')
  })
  if (@(Compare-Object ($allowed | Sort-Object) ($actual | Sort-Object)).Count) {
    throw "Source allowlist mismatch: $($actual -join ',')"
  }
  $legacy=[ordered]@{BOM_WORKSYSTEM_JAR='common/worksystem-server/worksystem.jar';BOM_WORKSYSTEM_VERSION='1.0.0'}
  Assert-OfflineWorksystemMetadata $legacy 'BOM'
  if ($legacy.BOM_WORKSYSTEM_INCLUDED -cne 'true') { throw 'Legacy include lost' }
  Set-OfflineWorksystemMetadata $legacy 'BOM' $false
  Assert-OfflineWorksystemMetadata $legacy 'BOM'
  if ($legacy.Count -ne 1 -or $legacy.BOM_WORKSYSTEM_INCLUDED -cne 'false') { throw 'Excluded metadata retained' }
  Write-Output 'PASS: real Deploy-only source copy excludes Work projects, embedded install/QA config, payloads and operational files'
} finally {
  $full=[IO.Path]::GetFullPath($fixture)
  $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
  if ([IO.Path]::GetDirectoryName($full) -cne $parent -or [IO.Path]::GetFileName($full) -cnotmatch '^deploy-source-[a-f0-9]{32}$') {
    throw 'Unsafe fixture cleanup'
  }
  if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Force -Recurse }
}
