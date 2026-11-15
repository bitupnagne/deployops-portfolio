$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../scripts/target-prerequisites.ps1')
$taskRoot = Join-Path ([IO.Path]::GetTempPath()) ('target-prerequisites-test-' + [Guid]::NewGuid().ToString('N'))
$dir = Join-Path $taskRoot 'rocky8/target-prerequisites'
$utf8 = [Text.UTF8Encoding]::new($false)
function Expect-Failure([scriptblock]$Action) {
  $failed = $false
  try { & $Action } catch { $failed = $true }
  if (-not $failed) { throw 'Invalid target prerequisite contract was accepted' }
}
try {
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
  Expect-Failure { Assert-TargetPrerequisiteBundle $taskRoot }
  $rows = @()
  foreach ($name in @('openssh-server','openssh-clients','sudo','policycoreutils','policycoreutils-python-utils','iproute','procps-ng','shadow-utils','util-linux','tar','gzip','curl')) {
    $file = Join-Path $dir ($name + '.rpm')
    [IO.File]::WriteAllText($file, $name, $utf8)
    $sha = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $rows += "$name|0:1.0-1|x86_64|$sha|$name.rpm"
  }
  $lock = Join-Path $dir 'PACKAGES.lock'
  [IO.File]::WriteAllLines($lock, $rows, $utf8)
  New-Item -ItemType Directory -Path (Join-Path $dir 'repodata') | Out-Null
  [IO.File]::WriteAllText((Join-Path $dir 'repodata/repomd.xml'), 'metadata', $utf8)
  $metadataSha = (Get-FileHash -LiteralPath (Join-Path $dir 'repodata/repomd.xml') -Algorithm SHA256).Hash.ToLowerInvariant()
  [IO.File]::WriteAllText((Join-Path $dir 'REPODATA.sha256'), "$metadataSha  repodata/repomd.xml", $utf8)
  Assert-TargetPrerequisiteBundle $taskRoot
  [IO.File]::WriteAllText((Join-Path $dir 'repodata/repomd.xml'), 'tampered', $utf8)
  Expect-Failure { Assert-TargetPrerequisiteBundle $taskRoot }
  [IO.File]::WriteAllText((Join-Path $dir 'repodata/repomd.xml'), 'metadata', $utf8)
  [IO.File]::WriteAllLines($lock, @($rows + $rows[0]), $utf8)
  Expect-Failure { Assert-TargetPrerequisiteBundle $taskRoot }
  [IO.File]::WriteAllLines($lock, @($rows | Where-Object { $_ -notlike 'sudo|*' }), $utf8)
  Expect-Failure { Assert-TargetPrerequisiteBundle $taskRoot }
  [IO.File]::WriteAllLines($lock, @($rows | ForEach-Object { $_.Replace('|x86_64|','|aarch64|') }), $utf8)
  Expect-Failure { Assert-TargetPrerequisiteBundle $taskRoot }
  [IO.File]::WriteAllLines($lock, $rows, $utf8)
  [IO.File]::WriteAllText((Join-Path $dir 'sudo.rpm'), 'tampered', $utf8)
  Expect-Failure { Assert-TargetPrerequisiteBundle $taskRoot }
  Write-Output 'target prerequisite Windows integrity regression: PASS (synthetic bytes, no RPM install)'
} finally {
  $resolved = [IO.Path]::GetFullPath($taskRoot)
  $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
  if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or
      [IO.Path]::GetFileName($resolved) -notmatch '^target-prerequisites-test-[0-9a-f]{32}$') { throw 'Unsafe test cleanup path' }
  Remove-Item -LiteralPath $resolved -Recurse -Force
}
