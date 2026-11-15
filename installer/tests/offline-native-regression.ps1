[CmdletBinding()]
param([string]$EvidencePath = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $repoRoot 'scripts/offline-native.ps1')
$gitExe = (Get-Command git -CommandType Application | Select-Object -First 1).Source
$tarExe = Join-Path $env:WINDIR 'System32/tar.exe'
$opensslExe = 'C:/Program Files/Git/usr/bin/openssl.exe'
$pwshExe = (Get-Process -Id $PID).Path
$cases = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Action) {
  & $Action
  $cases.Add([ordered]@{ name=$Name; passed=$true })
}
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Reject([scriptblock]$Action) {
  $rejected = $false
  try { & $Action | Out-Null } catch { $rejected = $true }
  Assert $rejected 'Expected fail-closed rejection'
}
# Execute in a function's inherited local scope: this is the actual failed packaging invocation shape.
function Test-InheritedScope([int]$Sentinel) {
  $LASTEXITCODE = $Sentinel
  Check "native-success-parent-$Sentinel" {
    $r = Invoke-OfflineNative $gitExe @('--version')
    Assert ($r.ExitCode -eq 0 -and $r.Stdout.StartsWith('git version')) 'Actual success lost to parent sentinel'
    Assert ($LASTEXITCODE -eq $Sentinel) 'Caller sentinel must not be used or rewritten'
  }
  Check "git-failure-parent-$Sentinel" {
    $r = Invoke-OfflineNative $gitExe @('-C',$PSScriptRoot,'rev-parse','--verify','refs/heads/does-not-exist-native-test') -AllowFailure
    Assert ($r.ExitCode -ne 0) 'Actual Git failure was hidden'
    Reject { Invoke-OfflineNative $gitExe @('-C',$PSScriptRoot,'rev-parse','--verify','refs/heads/does-not-exist-native-test') }
  }
  Check "openssl-failure-parent-$Sentinel" {
    $r = Invoke-OfflineNative $opensslExe @('invalid-offline-regression-command') -AllowFailure
    Assert ($r.ExitCode -ne 0) 'Actual OpenSSL failure was hidden'
    Reject { Invoke-OfflineNative $opensslExe @('invalid-offline-regression-command') }
  }
  Check "tar-failure-parent-$Sentinel" {
    $missing = Join-Path $repoRoot 'definitely-absent-offline-regression.tar'
    Assert (-not (Test-Path -LiteralPath $missing)) 'Missing archive fixture unexpectedly exists'
    Reject { Invoke-OfflineNative $tarExe @('-tvf',$missing,'manifest.json') }
  }
  Check "real-source-provenance-parent-$Sentinel" {
    $p = Get-OfflineSourceProvenance $repoRoot
    if ($p.sourceProvenance -ceq 'git') {
      Assert ($p.sourceCommit -cmatch '\A[0-9a-f]{40}\z' -and $p.sourceDirty -is [bool]) 'Repository provenance must expose a commit and actual dirty flag'
    } else {
      Assert ($p.sourceCommit -ceq 'unavailable' -and $p.sourceDirty -eq $true) 'A source export must not claim repository provenance'
    }
  }
  Check "nongit-source-provenance-parent-$Sentinel" {
    $nonGitFixture = Join-Path ([IO.Path]::GetTempPath()) ('portfolio-nongit-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $nonGitFixture | Out-Null
    try {
      $p = Get-OfflineSourceProvenance $nonGitFixture
      Assert ($p.sourceCommit -ceq 'unavailable' -and $p.sourceDirty -eq $true -and $p.sourceProvenance -ceq 'unavailable') 'Unavailable provenance must never claim clean/null'
      Reject { Get-OfflineSourceProvenance $nonGitFixture -RequireRepository }
    } finally { [IO.Directory]::Delete($nonGitFixture) }
  }
  Check "nonroot-source-parent-$Sentinel" { Reject { Get-OfflineSourceProvenance $PSScriptRoot -RequireRepository } }
}
Test-InheritedScope 0
Test-InheritedScope 17
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('office-provenance-' + [Guid]::NewGuid().ToString('N'))))
try {
  $fixtureRepo = Join-Path $fixtureRoot 'repo'
  $emptyTemplate = Join-Path $fixtureRoot 'empty-template'
  New-Item -ItemType Directory -Path $fixtureRepo,$emptyTemplate -Force | Out-Null
  $null = Invoke-OfflineNative $gitExe @('init',"--template=$emptyTemplate",$fixtureRepo)
  [IO.File]::WriteAllText((Join-Path $fixtureRepo '.gitignore'), "ignored/`n")
  [IO.File]::WriteAllText((Join-Path $fixtureRepo 'tracked.txt'), 'original')
  $null = Invoke-OfflineNative $gitExe @('-C',$fixtureRepo,'add','--','.gitignore','tracked.txt')
  $null = Invoke-OfflineNative $gitExe @('-C',$fixtureRepo,'-c','user.name=Offline Fixture','-c','user.email=offline-fixture@example.invalid','-c','commit.gpgsign=false','-c',"core.hooksPath=$emptyTemplate",'commit','-m','fixture baseline')
  function Assert-FixtureDirty([bool]$Expected) {
    $normal = Invoke-OfflineNative $gitExe @('-C',$fixtureRepo,'status','--porcelain=v1','--untracked-files=normal')
    $all = Invoke-OfflineNative $gitExe @('-C',$fixtureRepo,'status','--porcelain=v1','--untracked-files=all')
    $normalDirty = -not [string]::IsNullOrWhiteSpace($normal.Stdout)
    $allDirty = -not [string]::IsNullOrWhiteSpace($all.Stdout)
    Assert ($normalDirty -eq $Expected -and $allDirty -eq $Expected) 'Normal/all dirty booleans differ from expected state'
    $provenance = Get-OfflineSourceProvenance $fixtureRepo -RequireRepository
    Assert ($provenance.sourceDirty -eq $Expected -and $provenance.sourceProvenance -ceq 'git') 'Producer provenance differs from actual Git dirty state'
  }
  Check 'provenance-clean-normal-equals-all' { Assert-FixtureDirty $false }
  Check 'provenance-ignored-only-remains-clean' {
    New-Item -ItemType Directory -Path (Join-Path $fixtureRepo 'ignored') | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixtureRepo 'ignored/artifact.bin'), 'ignored')
    Assert-FixtureDirty $false
  }
  Check 'provenance-tracked-modified-normal-equals-all' {
    [IO.File]::WriteAllText((Join-Path $fixtureRepo 'tracked.txt'), 'modified')
    Assert-FixtureDirty $true
    [IO.File]::WriteAllText((Join-Path $fixtureRepo 'tracked.txt'), 'original')
  }
  Check 'provenance-untracked-directory-normal-equals-all' {
    $nested = Join-Path $fixtureRepo 'artifacts/nested'
    New-Item -ItemType Directory -Path $nested -Force | Out-Null
    foreach ($i in 1..40) { [IO.File]::WriteAllText((Join-Path $nested "file-$i.txt"), 'artifact') }
    Assert-FixtureDirty $true
    $normal = Invoke-OfflineNative $gitExe @('-C',$fixtureRepo,'status','--porcelain=v1','--untracked-files=normal')
    $all = Invoke-OfflineNative $gitExe @('-C',$fixtureRepo,'status','--porcelain=v1','--untracked-files=all')
    Assert ($normal.Stdout.Trim() -ceq '?? artifacts/') 'Untracked directory must remain dirty without child enumeration'
    Assert ($all.Stdout.Length -gt $normal.Stdout.Length) 'Fixture must exercise directory collapsing'
  }
} finally {
  $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
  $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot).TrimEnd('\','/')
  if (-not [IO.Path]::GetDirectoryName($resolvedFixture).Equals($tempParent,[StringComparison]::OrdinalIgnoreCase) -or
      [IO.Path]::GetFileName($resolvedFixture) -cnotmatch '^office-provenance-[0-9a-f]{32}$') { throw 'Unsafe provenance fixture cleanup target' }
  if (Test-Path -LiteralPath $resolvedFixture) { Remove-Item -LiteralPath $resolvedFixture -Recurse -Force }
}
Check 'native-output-budget' {
  Reject { Invoke-OfflineNative $pwshExe @('-NoProfile','-Command',"[Console]::Write('x' * 4096)") -OutputBudgetBytes 128 }
}
Check 'native-deadline' {
  Reject { Invoke-OfflineNative $pwshExe @('-NoProfile','-Command','Start-Sleep -Seconds 5') -DeadlineSeconds 1 }
}
Check 'production-native-status-no-automatic-variable' {
  foreach ($relative in @('scripts/package-offline-installer.ps1','scripts/prepare-offline-bundle.ps1','scripts/java-ci-payload.ps1')) {
    $path = Join-Path $repoRoot $relative
    $tokens=$null; $parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$parseErrors)
    Assert ($parseErrors.Count -eq 0) "AST errors: $relative"
    $reads=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -match '(^|:)LASTEXITCODE$'},$true))
    Assert ($reads.Count -eq 0) "Automatic native status still used: $relative"
  }
}
$report = [ordered]@{ schemaVersion=1; createdUtc=[DateTime]::UtcNow.ToString('o'); status='AUTHOR_LOCAL_NATIVE_BOUNDARY_REGRESSION_PASSED'; tests=@($cases); sourcePins=[ordered]@{}; heavyRuntimeExecuted=$false; signingOrPrivateKeysUsed=$false; candidateModified=$false }
foreach ($relative in @('scripts/offline-native.ps1','scripts/package-offline-installer.ps1','scripts/prepare-offline-bundle.ps1','scripts/java-ci-payload.ps1','installer/tests/offline-native-regression.ps1')) {
  $report.sourcePins[$relative]=(Get-FileHash -LiteralPath (Join-Path $repoRoot $relative)).Hash.ToLowerInvariant()
}
if ($EvidencePath) {
  if (Test-Path -LiteralPath $EvidencePath) { throw 'EvidencePath must be new' }
  $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $EvidencePath -Encoding utf8NoBOM
}
Write-Host "Offline native regression passed: $($cases.Count) cases, no signing, packaging, or heavy runtime."
