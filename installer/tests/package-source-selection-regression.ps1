#Requires -Version 7.3
# Tiny real filesystem copy/manifest test. No package producer, bundles, keys or Docker operations.
$ErrorActionPreference = 'Stop'
$producer = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../scripts/package-offline-installer.ps1'))
. (Join-Path $PSScriptRoot '../../scripts/offline-component-selection.ps1')
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($producer,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Producer AST invalid' }
foreach ($name in @('Get-OfflineInstallerRelativePath','Test-OfflineInstallerSourcePath','Copy-OfflineInstallerSource','Get-OfflineInstallerManifestLines','Get-OfflineInstallerPrivateMaterial')) {
  $definition=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
  if ($definition.Count -ne 1) { throw "Function absent/nonunique: $name" }
  . ([ScriptBlock]::Create($definition[0].Extent.Text))
}
$taskTemp=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('office-package-source-' + [Guid]::NewGuid().ToString('N'))))
$source=Join-Path $taskTemp 'input'
$stage=Join-Path $taskTemp 'output/installer'
function Write-Fixture([string]$Relative, [string]$Text='fixture') {
  $file=Join-Path $source $Relative
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
  [IO.File]::WriteAllText($file,$Text,[Text.UTF8Encoding]::new($false))
}
try {
  New-Item -ItemType Directory -Force -Path $stage | Out-Null
  $retained=@('install.sh','lib/release-metadata.sh','components/docker.sh','answers.example.env',
    'profiles/target-was.env','bundles/common/java-ci/CI-PAYLOAD.json',
    'bundles/common/java-ci/inputs/seed/.mvn/wrapper/maven-wrapper.properties',
    'bundles/common/java-ci/inputs/seed/.npmrc',
    'bundles/common/java-ci/inputs/seed/declared-build.log',
    'bundles/common/java-ci/inputs/seed/declared-cache.tmp',
    'bundles/common/java-ci/inputs/seed/declared-image.tar.part',
    'bundles/common/java-ci/inputs/seed/declared-fixture.bak',
    'bundles/common/java-ci/inputs/seed/declared-fixture.old',
    'bundles/common/java-ci/inputs/seed/declared-fixture~',
    'bundles/rocky8/docker/repository/repodata/repomd.xml',
    'sim/local-lab.ps1','sim/lab-node-boot.sh','sim/Dockerfile.rocky-systemd')
  foreach ($file in $retained) { Write-Fixture $file "source-$file" }
  $hiddenPayload=Join-Path $source 'bundles/common/java-ci/inputs/seed/.npmrc'
  if ($IsWindows) {
    [IO.File]::SetAttributes($hiddenPayload, ([IO.File]::GetAttributes($hiddenPayload) -bor [IO.FileAttributes]::Hidden))
  }
  $excluded=@('sim/.offlineQA-current-v29/installer/bundles/common/gitlab/large-image.tar',
    'sim/.offlineQA-current-v29/private-canary.pem','sim/.offlineQA-trusted-public.pem',
    'sim/answers/prdwas1.env','sim/keys/private/deploy_id_rsa','sim/random-lab/bundles/image.tar',
    'sim/PROD_SIM_RESULT.md','.work/state/secret.json','lib/__pycache__/helper.pyc',
    'profiles/node.local.env','components/debug.log','components/cache.tmp','components/image.tar.part',
    'bundles/common/deploy-system/deploy-ops.jar.bak-20261019',
    'bundles/common/worksystem-server/worksystem.jar',
    'bundles/common/runtime-private/connection.env','bundles/common/qa.private.yml',
    'bundles/common/qa-token.secret',
    'answers.env','answers.env.bak','answers.env.old','answers.env~','answers.env.20261020',
    'components/docker.sh.bak','components/docker.sh.old','components/docker.sh~',
    'deploy-system/application-install.yml.bak','deploy-system/application-install.yml.old',
    'deploy-system/application-install.yml~')
  foreach ($file in $excluded) { Write-Fixture $file 'DO-NOT-PACK-QA-CANARY' }
  if ((Get-OfflineInstallerRelativePath 'C:\fixture\sim\local-lab.ps1' 'C:\fixture') -cne 'sim/local-lab.ps1') {
    throw 'Windows separator normalization failed'
  }
  if (Test-OfflineInstallerSourcePath 'sim\.offlineQA-current-v29\private-canary.pem') {
    throw 'Windows hidden QA path accepted'
  }
  foreach ($path in @('bundles\common\java-ci\inputs\seed\.mvn\settings.xml',
      'bundles/common/java-ci/inputs/seed/.npmrc','bundles/common/declared-build.log')) {
    if (-not (Test-OfflineInstallerSourcePath $path)) { throw "Signed bundle payload excluded: $path" }
  }
  Copy-OfflineInstallerSource $source $stage
  $integratedStage = Join-Path $taskTemp 'integrated/installer'
  New-Item -ItemType Directory -Force -Path $integratedStage | Out-Null
  Copy-OfflineInstallerSource $source $integratedStage $null $true
  if (-not (Test-Path -LiteralPath (Join-Path $integratedStage 'bundles/common/worksystem-server/worksystem.jar'))) {
    throw 'Explicitly included Worksystem was excluded from source copy'
  }
  if ($IsWindows -and
      (([IO.File]::GetAttributes((Join-Path $stage 'bundles/common/java-ci/inputs/seed/.npmrc')) -band [IO.FileAttributes]::Hidden) -eq 0)) {
    throw 'Windows Hidden attribute was not preserved in the real copy fixture'
  }
  $actual=@(Get-ChildItem $stage -Recurse -File -Force | ForEach-Object { Get-OfflineInstallerRelativePath $_.FullName $stage })
  if (@(Compare-Object ($retained | Sort-Object) ($actual | Sort-Object)).Count) { throw 'Copied source file set mismatch' }
  $manifest=@(Get-OfflineInstallerManifestLines $stage)
  if ($manifest.Count -ne $retained.Count -or ($manifest -join ' ') -match 'offlineQA|canary|random-lab|\\') {
    throw 'Manifest leaked QA paths or Windows separators'
  }
  foreach ($line in $manifest) {
    if ($line -notmatch '^([a-f0-9]{64})  installer/(.+)$') { throw 'Malformed manifest line' }
    $expected=$Matches[1]; $relative=$Matches[2]
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $stage $relative)).Hash.ToLowerInvariant() -cne $expected) {
      throw 'Manifest hash mismatched copied bytes'
    }
    if ([IO.File]::ReadAllText((Join-Path $stage $relative)) -match 'DO-NOT-PACK-QA-CANARY') { throw 'Canary content leaked' }
  }
  if (@(Get-OfflineInstallerPrivateMaterial $stage).Count) { throw 'Ordinary hidden signed input was falsely classified as a private key' }
  # This is deliberately invalid, non-secret canary text, not a generated key.
  Write-Fixture 'bundles/common/hidden-private-canary.pem' '-----BEGIN PRIVATE KEY-----'
  $canaryInput=Join-Path $source 'bundles/common/hidden-private-canary.pem'
  if ($IsWindows) {
    [IO.File]::SetAttributes($canaryInput, ([IO.File]::GetAttributes($canaryInput) -bor [IO.FileAttributes]::Hidden))
  }
  Copy-OfflineInstallerSource $source $stage
  $privateCandidates=@(Get-OfflineInstallerPrivateMaterial $stage)
  if ($privateCandidates.Count -ne 1 -or $privateCandidates[0].Name -cne 'hidden-private-canary.pem') {
    throw 'Actual private-material scanner failed to detect the hidden canary'
  }
  $manifestWithCanary=@(Get-OfflineInstallerManifestLines $stage)
  if ($manifestWithCanary.Count -ne ($retained.Count + 1) -or
      -not ($manifestWithCanary -match '  installer/bundles/common/hidden-private-canary\.pem$')) {
    throw 'Manifest does not cover the same hidden file set as copy and private-material scan'
  }
  $sourceText=[IO.File]::ReadAllText($producer)
  if ($sourceText -notmatch 'Copy-OfflineInstallerSource \$sourceInstaller \$stageInstaller' -or
      $sourceText -notmatch 'Get-OfflineInstallerManifestLines \$stageInstaller' -or
      $sourceText -notmatch 'Get-OfflineInstallerPrivateMaterial \$stageInstaller') {
    throw 'Producer does not use the tested copy and manifest helpers'
  }
  Write-Output 'PASS: real tiny copy excludes outside-bundle QA state; bundle payload bytes and Windows Hidden files remain manifested; actual private-material scanner detects hidden non-secret canary'
} finally {
  $resolved=[IO.Path]::GetFullPath($taskTemp)
  $tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char]92,[char]47)
  if ([IO.Path]::GetDirectoryName($resolved) -cne $tempParent -or
      [IO.Path]::GetFileName($resolved) -cnotmatch '^office-package-source-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup target' }
  Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
