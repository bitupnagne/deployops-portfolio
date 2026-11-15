$ErrorActionPreference = 'Stop'

$installerRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$prepareScript = (Resolve-Path (Join-Path $installerRoot '..\scripts\prepare-offline-bundle.ps1')).Path
. (Join-Path $installerRoot '..\scripts\offline-native.ps1')
. (Join-Path $installerRoot '..\scripts\java-ci-payload.ps1')
$tempRoot = Join-Path (Join-Path ([System.IO.Path]::GetTempPath()) ("office-prepare-test-" + [Guid]::NewGuid().ToString('N'))) '.work'
$bundle = Join-Path $tempRoot 'bundles'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Remove-TestFixtureTree([string]$Path) {
  $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
  $fixtureRoot = [IO.Path]::GetFullPath($tempRoot).TrimEnd('\', '/')
  $fixtureParent = [IO.Path]::GetDirectoryName($fixtureRoot)
  if ([IO.Path]::GetFileName($fixtureRoot) -cne '.work' -or
      [IO.Path]::GetFileName($fixtureParent) -cnotmatch '^office-prepare-test-[0-9a-f]{32}$' -or
      -not [IO.Path]::GetDirectoryName($fixtureParent).Equals($systemTemp, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe fixture root for recursive cleanup: $fixtureRoot"
  }
  $target = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
  $rootPrefix = $fixtureRoot + [IO.Path]::DirectorySeparatorChar
  if (-not $target.Equals($fixtureRoot, [StringComparison]::OrdinalIgnoreCase) -and
      -not $target.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Recursive cleanup target is outside fixture root: $target"
  }
  # A junction inside the fixture must not redirect cleanup to another tree.
  for ($checked = $target; ; $checked = [IO.Path]::GetDirectoryName($checked)) {
    $entry = Get-Item -LiteralPath $checked -Force -ErrorAction SilentlyContinue
    if ($entry -and ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
      throw "Reparse point is not allowed in fixture cleanup: $checked"
    }
    if ($checked.Equals($fixtureRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
  }
  Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue
}

function Write-TextNoBom([string]$Path, [string]$Text) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
  [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Write-TestJar([string]$Path, [string]$Version, [string[]]$Migrations, [string]$Marker) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
  Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
  $zip = [System.IO.Compression.ZipFile]::Open($Path, 'Create')
  try {
    $manifest = $zip.CreateEntry('META-INF/MANIFEST.MF')
    $writer = [System.IO.StreamWriter]::new($manifest.Open(), $utf8NoBom)
    try { $writer.Write("Manifest-Version: 1.0`nImplementation-Version: $Version`n") } finally { $writer.Dispose() }
    foreach ($migration in $Migrations) {
      $entry = $zip.CreateEntry("BOOT-INF/classes/db/migration/$migration")
      $writer = [System.IO.StreamWriter]::new($entry.Open(), $utf8NoBom)
      try { $writer.Write("-- $Marker") } finally { $writer.Dispose() }
    }
  } finally { $zip.Dispose() }
}

function New-TestImageArchive([string]$Archive, [string[]]$Tags, [switch]$Compressed, [switch]$WrongDiff) {
  $build = Join-Path $tempRoot ("image-" + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force -Path $build, (Split-Path -Parent $Archive) | Out-Null
  try {
    $layerText = 'fixture-layer'
    $layerTemp = Join-Path $build 'layer.tmp'
    Write-TextNoBom $layerTemp $layerText
    $layerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $layerTemp).Hash.ToLowerInvariant()
    $layerRelative = "$layerHash/layer.tar"
    New-Item -ItemType Directory -Force -Path (Join-Path $build $layerHash) | Out-Null
    Move-Item -LiteralPath $layerTemp -Destination (Join-Path $build $layerRelative)
    if ($Compressed) {
      $compressedPath = Join-Path $build 'compressed.tmp'
      $outputStream = [System.IO.File]::Create($compressedPath)
      $gzip = [System.IO.Compression.GZipStream]::new($outputStream, [System.IO.Compression.CompressionMode]::Compress)
      try {
        $compressedText = $layerText
        if ($WrongDiff) { $compressedText += '-wrong-diff' }
        $bytes = $utf8NoBom.GetBytes($compressedText); $gzip.Write($bytes, 0, $bytes.Length)
      }
      finally { $gzip.Dispose(); $outputStream.Dispose() }
      $blobHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $compressedPath).Hash.ToLowerInvariant()
      $layerRelative = "blobs/sha256/$blobHash"
      New-Item -ItemType Directory -Force -Path (Join-Path $build 'blobs/sha256') | Out-Null
      Move-Item -LiteralPath $compressedPath -Destination (Join-Path $build $layerRelative)
    }
    $configText = '{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":["sha256:' + $layerHash + '"]}}'
    $configTemp = Join-Path $build 'config.tmp'
    Write-TextNoBom $configTemp $configText
    $configHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $configTemp).Hash.ToLowerInvariant()
    Move-Item -LiteralPath $configTemp -Destination (Join-Path $build "$configHash.json")
    $tagJson = ($Tags | ForEach-Object { '"' + $_ + '"' }) -join ','
    $dockerManifest = '[{{"Config":"{0}.json","RepoTags":[{1}],"Layers":["{2}"]}}]' -f $configHash, $tagJson, $layerRelative
    Write-TextNoBom (Join-Path $build 'manifest.json') $dockerManifest
    & tar -czf $Archive -C $build manifest.json "$configHash.json" $layerRelative
    if ($LASTEXITCODE -ne 0) { throw "tar creation failed: $Archive" }
    return "sha256:$configHash"
  } finally {
    Remove-TestFixtureTree $build
  }
}

function New-OciEvidence([string]$Name, [string]$ConfigDigest) {
  $text = '{{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{{"digest":"{0}","size":1}},"layers":[{{"digest":"sha256:{1}","size":1}}]}}' -f $ConfigDigest, ('a' * 64)
  $source = Join-Path $bundle "common\release-evidence\$Name-source.json"
  $platform = Join-Path $bundle "common\release-evidence\$Name-linux-amd64.json"
  Write-TextNoBom $source $text
  Write-TextNoBom $platform $text
  return 'sha256:' + (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()
}

try {
  New-Item -ItemType Directory -Force -Path $bundle | Out-Null
  Copy-Item -LiteralPath (Join-Path $installerRoot 'bundles\RELEASE-BOM.env') -Destination (Join-Path $bundle 'RELEASE-BOM.env')
  $bomText = Get-Content -Raw -LiteralPath (Join-Path $bundle 'RELEASE-BOM.env')

  $javaArchive = [regex]::Match($bomText, '(?m)^BOM_JAVA_ARCHIVE=(.+)$').Groups[1].Value.Trim()
  $javaPath = Join-Path $bundle "common\java\$javaArchive"
  Write-TextNoBom $javaPath 'fixture-java'
  $javaSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $javaPath).Hash.ToLowerInvariant()
  $jqPath = Join-Path $bundle 'common\jq\jq-linux-amd64'
  Write-TextNoBom $jqPath 'fixture-jq'
  $jqSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $jqPath).Hash.ToLowerInvariant()
  $bomText = $bomText -replace '(?m)^BOM_JAVA_SHA256=.*$', "BOM_JAVA_SHA256=$javaSha"
  $bomText = $bomText -replace '(?m)^BOM_JQ_SHA256=.*$', "BOM_JQ_SHA256=$jqSha"
  Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $bomText

  $values = @{}
  foreach ($line in $bomText -split "`r?`n") {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.+)$') { $values[$Matches[1]] = $Matches[2] }
  }
  $migrations = $values.BOM_DEPLOYOPS_REQUIRED_MIGRATIONS.Split(',')
  $deployJar = Join-Path $bundle $values.BOM_DEPLOYOPS_JAR.Replace('/', '\')
  $worksystemJar = Join-Path $bundle $values.BOM_WORKSYSTEM_JAR.Replace('/', '\')
  Write-TestJar $deployJar $values.BOM_DEPLOYOPS_VERSION $migrations 'deploy-v1'
  Write-TestJar $worksystemJar $values.BOM_WORKSYSTEM_VERSION @() 'work-v1'

  $gitlabId = New-TestImageArchive (Join-Path $bundle 'common\gitlab\gitlab.tar.gz') @($values.BOM_GITLAB_IMAGE)
  $runnerId = New-TestImageArchive (Join-Path $bundle 'common\gitlab-runner\gitlab-runner.tar.gz') @($values.BOM_RUNNER_IMAGE,$values.BOM_RUNNER_HELPER_IMAGE,$values.BOM_RUNNER_BUILD_IMAGE) -Compressed
  $nexusId = New-TestImageArchive (Join-Path $bundle 'common\nexus\nexus.tar.gz') @($values.BOM_NEXUS_IMAGE)
  $parseErrors = $null; $tokens = $null
  $prepareAst = [System.Management.Automation.Language.Parser]::ParseFile($prepareScript, [ref]$tokens, [ref]$parseErrors)
  if ($parseErrors.Count) { throw 'Prepare script has parsing errors' }
  $archiveFunction = $prepareAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-DockerArchiveImageId' }, $true)
  . ([scriptblock]::Create($archiveFunction.Extent.Text))
  $TarExecutable = if ($env:WINDIR -and (Test-Path -LiteralPath (Join-Path $env:WINDIR 'System32\tar.exe'))) {
    Join-Path $env:WINDIR 'System32\tar.exe'
  } else {
    (Get-Command tar -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
  }
  $wrongDiffArchive = Join-Path $tempRoot 'wrong-diff.tar.gz'
  $null = New-TestImageArchive $wrongDiffArchive @('fixture:wrong') -Compressed -WrongDiff
  $rejected = $false
  $rejectionReason = ''
  try { $null = Get-DockerArchiveImageId -Archive $wrongDiffArchive -Tag 'fixture:wrong' }
  catch {
    $rejectionReason = $_.Exception.Message
    $rejected = $rejectionReason -like '*do not match config diff_id*'
  }
  if (-not $rejected) { throw "Compressed layer with valid blob SHA but wrong diffID was not rejected: $rejectionReason" }
  $contracts = @(
    @{ Name='GITLAB'; Image=$values.BOM_GITLAB_IMAGE; Evidence='gitlab'; Id=$gitlabId },
    @{ Name='RUNNER'; Image=$values.BOM_RUNNER_IMAGE; Evidence='runner'; Id=$runnerId },
    @{ Name='RUNNER_HELPER'; Image=$values.BOM_RUNNER_HELPER_IMAGE; Evidence='runner-helper'; Id=$runnerId },
    @{ Name='RUNNER_BUILD'; Image=$values.BOM_RUNNER_BUILD_IMAGE; Evidence='runner-build'; Id=$runnerId },
    @{ Name='NEXUS'; Image=$values.BOM_NEXUS_IMAGE; Evidence='nexus'; Id=$nexusId }
  )
  $lockLines = [System.Collections.Generic.List[string]]::new()
  $lockLines.Add('LOCK_SCHEMA_VERSION=2')
  # A changed application release may retain the same approved images. The
  # prepare step must revalidate every image/evidence contract and then refresh
  # the lock's release label instead of failing before it can do so.
  $lockLines.Add('LOCK_BOM_RELEASE=2026.09')
  foreach ($contract in $contracts) {
    $digest = New-OciEvidence $contract.Evidence $contract.Id
    $repo = $contract.Image.Substring(0, $contract.Image.LastIndexOf(':'))
    $lockLines.Add("LOCK_$($contract.Name)_IMAGE=$($contract.Image)")
    $lockLines.Add("LOCK_$($contract.Name)_DIGEST=$digest")
    $lockLines.Add("LOCK_$($contract.Name)_PINNED_REF=$repo@$digest")
    $lockLines.Add("LOCK_$($contract.Name)_PLATFORM_DIGEST=$digest")
    $lockLines.Add("LOCK_$($contract.Name)_CONFIG_DIGEST=$($contract.Id)")
    $lockLines.Add("LOCK_$($contract.Name)_ARCHIVE_ID=$($contract.Id)")
  }
  $lockLines.Add("LOCK_DEPLOYOPS_JAR=$($values.BOM_DEPLOYOPS_JAR)")
  $lockLines.Add("LOCK_DEPLOYOPS_VERSION=$($values.BOM_DEPLOYOPS_VERSION)")
  $lockLines.Add("LOCK_DEPLOYOPS_REQUIRED_MIGRATIONS=$($values.BOM_DEPLOYOPS_REQUIRED_MIGRATIONS)")
  $lockLines.Add('LOCK_DEPLOYOPS_SHA256=PENDING')
  $lockLines.Add("LOCK_WORKSYSTEM_JAR=$($values.BOM_WORKSYSTEM_JAR)")
  $lockLines.Add("LOCK_WORKSYSTEM_VERSION=$($values.BOM_WORKSYSTEM_VERSION)")
  $lockLines.Add('LOCK_WORKSYSTEM_SHA256=PENDING')
  [System.IO.File]::WriteAllLines((Join-Path $bundle 'RELEASE-LOCK.env'), $lockLines, [System.Text.Encoding]::ASCII)

  # Keep the preparer fixture small while satisfying its mandatory Rocky package contract.
  $targetPrerequisites = Join-Path $bundle 'rocky8\target-prerequisites'
  $packageNames = @('openssh-server','openssh-clients','sudo','policycoreutils',
    'policycoreutils-python-utils','iproute','procps-ng','shadow-utils','util-linux','tar','gzip','curl')
  $packageLock = [System.Collections.Generic.List[string]]::new()
  foreach ($name in $packageNames) {
    $filename = "$name-1.0-1.x86_64.rpm"
    $packagePath = Join-Path $targetPrerequisites $filename
    Write-TextNoBom $packagePath "fixture package $name"
    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $packagePath).Hash.ToLowerInvariant()
    $packageLock.Add("$name|1.0|x86_64|$sha|$filename")
  }
  Write-TextNoBom (Join-Path $targetPrerequisites 'PACKAGES.lock') ($packageLock -join "`n")
  $repodata = Join-Path $targetPrerequisites 'repodata\repomd.xml'
  Write-TextNoBom $repodata 'fixture repository metadata'
  $repodataSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $repodata).Hash.ToLowerInvariant()
  Write-TextNoBom (Join-Path $targetPrerequisites 'REPODATA.sha256') "$repodataSha  repodata/repomd.xml"

  # A collection interrupted before atomic promotion can leave this hidden staging tree.
  # It is not part of the approved package set and must never enter a signed manifest.
  $staleCollector = Join-Path $bundle 'rocky8\.target-prerequisites.collect.fixture'
  New-Item -ItemType Directory -Force -Path $staleCollector | Out-Null
  Write-TextNoBom (Join-Path $staleCollector 'partial.rpm') 'unpromoted staging artifact'

  $staleRejected = $false
  $staleReason = ''
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar }
  catch {
    $staleReason = $_.Exception.Message
    $staleRejected = $staleReason -like 'Unpromoted target prerequisite staging directory must be removed or recollected before release:*'
  }
  if (-not $staleRejected) { throw "prepare did not fail closed for a stale target prerequisite collector: $staleReason" }
  if (Test-Path -LiteralPath (Join-Path $bundle 'MANIFEST.sha256')) {
    throw 'prepare wrote a signed-package manifest despite a stale prerequisite collector'
  }
  Remove-Item -LiteralPath $staleCollector -Recurse -Force

  & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar
  if (-not (Test-Path -LiteralPath (Join-Path $bundle 'MANIFEST.sha256'))) { throw 'prepare did not create MANIFEST.sha256' }
  if ((Select-String -Path (Join-Path $bundle 'RELEASE-LOCK.env') -Pattern '^LOCK_BOM_RELEASE=').Line -ne "LOCK_BOM_RELEASE=$($values.BOM_RELEASE)") {
    throw 'prepare did not refresh stale lock release after validating image contracts'
  }
  $manifestText = Get-Content -Raw -LiteralPath (Join-Path $bundle 'MANIFEST.sha256')
  if ($manifestText -notmatch '(?m)^[0-9a-f]{64}  RELEASE-BOM\.env\r?$') {
    throw 'prepare manifest omitted RELEASE-BOM.env'
  }
  if ($manifestText -match '\.target-prerequisites\.collect\.') {
    throw 'prepare manifest included an unpromoted target prerequisite staging directory'
  }
  $bomText = Get-Content -Raw -LiteralPath (Join-Path $bundle 'RELEASE-BOM.env')
  if ($bomText -notmatch '(?m)^BOM_WORKSYSTEM_INCLUDED=true\r?$' -or
      (Get-Content -Raw -LiteralPath (Join-Path $bundle 'RELEASE-LOCK.env')) -notmatch '(?m)^LOCK_WORKSYSTEM_INCLUDED=true\r?$') {
    throw 'Integrated media must declare Worksystem inclusion explicitly'
  }
  $baseline = @{}
  foreach ($target in @($deployJar,$worksystemJar,(Join-Path $bundle 'RELEASE-BOM.env'),(Join-Path $bundle 'RELEASE-LOCK.env'),(Join-Path $bundle 'MANIFEST.sha256'))) {
    $baseline[$target] = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
  }

  # A stale release label must not bypass the image pin/evidence checks.
  $lockPath = Join-Path $bundle 'RELEASE-LOCK.env'
  $validLockText = Get-Content -Raw -LiteralPath $lockPath
  $tamperedLockText = $validLockText -replace '(?m)^LOCK_BOM_RELEASE=.*$', 'LOCK_BOM_RELEASE=2026.09'
  $tamperedLockText = $tamperedLockText -replace '(?m)^LOCK_GITLAB_PINNED_REF=.*$',
    'LOCK_GITLAB_PINNED_REF=gitlab/gitlab-ce@sha256:0000000000000000000000000000000000000000000000000000000000000000'
  Write-TextNoBom $lockPath $tamperedLockText
  $rejected = $false
  $rejectionReason = ''
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar }
  catch {
    $rejectionReason = $_.Exception.Message
    $rejected = $rejectionReason -ceq 'GITLAB pinned ref does not match digest'
  } finally {
    Write-TextNoBom $lockPath $validLockText
  }
  if (-not $rejected) { throw "prepare accepted a stale release with a mismatched image pin or failed for an unrelated reason: $rejectionReason" }
  foreach ($target in $baseline.Keys) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash -ne $baseline[$target]) {
      throw "stale-release image-pin rejection changed a valid bundle target: $target"
    }
  }

  # A valid version must not bless a JAR without the MR reviewer identity lineage.
  $reviewerMigration = 'V22__merge_request_reviewer_gitlab_identity.sql'
  $missingV22Jar = Join-Path $tempRoot 'deploy-without-v22.jar'
  $withoutV22 = @($migrations | Where-Object { $_ -cne $reviewerMigration })
  Write-TestJar $missingV22Jar $values.BOM_DEPLOYOPS_VERSION $withoutV22 'missing-reviewer-identity'
  $rejected = $false
  $rejectionReason = ''
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $missingV22Jar -WorksystemJar $worksystemJar }
  catch {
    $rejectionReason = $_.Exception.Message
    $rejected = $rejectionReason -ceq "DeployOps JAR missing required migration: $reviewerMigration"
  }
  if (-not $rejected) { throw "prepare accepted missing V22 or failed for an unrelated reason: $rejectionReason" }

  # Dropping V22 from a new BOM must not downgrade the release contract either.
  $bomWithoutV22 = $bomText -replace '(?m)^BOM_DEPLOYOPS_REQUIRED_MIGRATIONS=.*$',
    ('BOM_DEPLOYOPS_REQUIRED_MIGRATIONS=' + ($withoutV22 -join ','))
  $rejected = $false
  $rejectionReason = ''
  try {
    Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $bomWithoutV22
    try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $missingV22Jar -WorksystemJar $worksystemJar }
    catch {
      $rejectionReason = $_.Exception.Message
      $rejected = $rejectionReason -ceq "DeployOps BOM missing required reviewer identity migration: $reviewerMigration"
    }
  } finally {
    Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $bomText
  }
  if (-not $rejected) { throw "prepare accepted BOM without V22 or failed for an unrelated reason: $rejectionReason" }
  $auditCapacityMigration = 'V26__audit_target_description_capacity.sql'
  $withoutV26 = @($migrations | Where-Object { $_ -cne $auditCapacityMigration })
  $missingV26Jar = Join-Path $tempRoot 'deploy-without-v26.jar'
  Write-TestJar $missingV26Jar $values.BOM_DEPLOYOPS_VERSION $withoutV26 'missing-audit-capacity'
  $rejected = $false
  $rejectionReason = ''
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $missingV26Jar -WorksystemJar $worksystemJar }
  catch {
    $rejectionReason = $_.Exception.Message
    $rejected = $rejectionReason -ceq "DeployOps JAR missing required migration: $auditCapacityMigration"
  }
  if (-not $rejected) { throw "prepare accepted missing V26 or failed for an unrelated reason: $rejectionReason" }

  $bomWithoutV26 = $bomText -replace '(?m)^BOM_DEPLOYOPS_REQUIRED_MIGRATIONS=.*$',
    ('BOM_DEPLOYOPS_REQUIRED_MIGRATIONS=' + ($withoutV26 -join ','))
  $rejected = $false
  $rejectionReason = ''
  try {
    Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $bomWithoutV26
    try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $missingV26Jar -WorksystemJar $worksystemJar }
    catch {
      $rejectionReason = $_.Exception.Message
      $rejected = $rejectionReason -ceq "DeployOps BOM missing required audit target capacity migration: $auditCapacityMigration"
    }
  } finally {
    Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $bomText
  }
  if (-not $rejected) { throw "prepare accepted BOM without V26 or failed for an unrelated reason: $rejectionReason" }
  foreach ($target in $baseline.Keys) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash -ne $baseline[$target]) {
      throw "migration contract failure changed a valid bundle target: $target"
    }
  }

  # A layer mutation must be rejected by the Windows preparer before it can bless a new manifest.
  $gitlabArchive = Join-Path $bundle 'common\gitlab\gitlab.tar.gz'
  $safeArchive = Join-Path $tempRoot 'gitlab-safe.tar.gz'
  Copy-Item -LiteralPath $gitlabArchive -Destination $safeArchive
  $tamperDir = Join-Path $tempRoot 'tamper-layer'
  New-Item -ItemType Directory -Force -Path $tamperDir | Out-Null
  & tar -xzf $gitlabArchive -C $tamperDir
  if ($LASTEXITCODE -ne 0) { throw 'fixture archive extraction failed' }
  $archiveManifest = Get-Content -Raw -LiteralPath (Join-Path $tamperDir 'manifest.json') | ConvertFrom-Json
  $layerRelative = [string]$archiveManifest[0].Layers[0]
  $configRelative = [string]$archiveManifest[0].Config
  [System.IO.File]::AppendAllText((Join-Path $tamperDir $layerRelative), 'tamper', $utf8NoBom)
  & tar -czf $gitlabArchive -C $tamperDir manifest.json $configRelative $layerRelative
  if ($LASTEXITCODE -ne 0) { throw 'tampered fixture archive creation failed' }
  $failed = $false
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar } catch { $failed = $true }
  if (-not $failed) { throw 'prepare accepted Docker layer bytes that differ from config diff_id' }
  Copy-Item -LiteralPath $safeArchive -Destination $gitlabArchive -Force

  # Force a failure after commit has started. Every earlier target must be restored.
  $nextWorkJar = Join-Path $tempRoot 'worksystem-next.jar'
  Write-TestJar $nextWorkJar $values.BOM_WORKSYSTEM_VERSION @() 'work-v2'
  $lockedStream = [System.IO.File]::Open($worksystemJar, 'Open', 'Read', 'Read')
  $failed = $false
  try {
    try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $nextWorkJar } catch { $failed = $true }
  } finally {
    $lockedStream.Dispose()
  }
  if (-not $failed) { throw 'prepare did not fail when a commit target was write-locked' }
  foreach ($target in $baseline.Keys) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash -ne $baseline[$target]) {
      throw "mid-commit failure did not restore valid bundle target: $target"
    }
  }

  $invalidKey = Join-Path $tempRoot 'invalid-private.pem'
  Write-TextNoBom $invalidKey 'not a private key'
  $failed = $false
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar -SigningPrivateKey $invalidKey } catch { $failed = $true }
  if (-not $failed) { throw 'prepare accepted an invalid signing key' }
  foreach ($target in $baseline.Keys) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash -ne $baseline[$target]) {
      throw "pre-commit signing failure changed a valid bundle target: $target"
    }
  }

  if (Get-Command openssl -ErrorAction SilentlyContinue) {
    $inBundlePrivateKey = Join-Path $bundle 'leaked-signing-private.pem'
    & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $inBundlePrivateKey 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'test signing key generation failed' }
    $failed = $false
    try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar -SigningPrivateKey $inBundlePrivateKey } catch { $failed = $true }
    if (-not $failed) { throw 'prepare accepted a signing private key inside BundleRoot' }
    Remove-Item -LiteralPath $inBundlePrivateKey -Force
  }

  $safeBom = Get-Content -Raw -LiteralPath (Join-Path $bundle 'RELEASE-BOM.env')
  $escapedBom = $safeBom -replace '(?m)^BOM_DEPLOYOPS_JAR=.*$', 'BOM_DEPLOYOPS_JAR=../../victim.jar'
  Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $escapedBom
  $victim = Join-Path $tempRoot 'victim.jar'
  Write-TextNoBom $victim 'do-not-overwrite'
  $failed = $false
  try { & $prepareScript -IncludeWorksystem -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar } catch { $failed = $true }
  if (-not $failed -or (Get-Content -Raw -LiteralPath $victim) -ne 'do-not-overwrite') {
    throw 'BOM path escape was accepted or wrote outside BundleRoot'
  }

  Write-TextNoBom (Join-Path $bundle 'RELEASE-BOM.env') $safeBom
  $failed = $false
  try { & $prepareScript -BundleRoot $bundle -DeployJar $deployJar -WorksystemJar $worksystemJar }
  catch { $failed = $_.Exception.Message -like '*requires explicit -IncludeWorksystem*' }
  if (-not $failed) { throw 'Explicit WorksystemJar was accepted without component opt-in' }

  $failed = $false
  try { & $prepareScript -BundleRoot $bundle -DeployJar $deployJar }
  catch { $failed = $_.Exception.Message -like '*must not contain the Worksystem payload directory*' }
  if (-not $failed) { throw 'Deploy-only prepare accepted retained Worksystem payload' }

  # Tiny fresh fixture clone: default mode strips the legacy Work metadata and contains no Work payload.
  $deployOnly = Join-Path $tempRoot 'deploy-only'
  New-Item -ItemType Directory -Path $deployOnly | Out-Null
  foreach ($file in Get-ChildItem -LiteralPath $bundle -File -Recurse -Force) {
    $relative = $file.FullName.Substring($bundle.Length + 1)
    if ($relative.Replace('\','/') -like 'common/worksystem-server/*') { continue }
    $destination = Join-Path $deployOnly $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $destination
  }
  & $prepareScript -BundleRoot $deployOnly -DeployJar $deployJar
  foreach ($prefix in @('BOM','LOCK')) {
    $text = Get-Content -Raw -LiteralPath (Join-Path $deployOnly "RELEASE-$prefix.env")
    if ($text -notmatch "(?m)^${prefix}_WORKSYSTEM_INCLUDED=false\r?$" -or
        $text -match "(?m)^${prefix}_WORKSYSTEM_(JAR|VERSION|SHA256)=") {
      throw "Deploy-only $prefix retained optional Worksystem metadata"
    }
  }
  if (Test-Path -LiteralPath (Join-Path $deployOnly 'common/worksystem-server')) { throw 'Deploy-only output contains Worksystem' }
  $deployOnlyManifest = Get-Content -Raw -LiteralPath (Join-Path $deployOnly 'MANIFEST.sha256')
  if ($deployOnlyManifest -match 'worksystem-server') { throw 'Deploy-only manifest contains Worksystem' }
  # Re-preparing the already excluded media is supported without old component metadata.
  & $prepareScript -BundleRoot $deployOnly -DeployJar $deployJar
  Write-Output 'prepare-offline-bundle PowerShell regression checks passed (legacy opt-in and Deploy-only)'
} finally {
  Remove-TestFixtureTree $tempRoot
}
