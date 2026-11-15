$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $project 'scripts/package-offline-installer.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Packager parse failed' }
$function = $ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Copy-OfflineRecoveryManual'}, $true)
if (-not $function) { throw 'Missing recovery manual packaging function' }
. ([scriptblock]::Create($function.Extent.Text))
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('deployops-manual-package-' + [guid]::NewGuid().ToString('N'))
try {
  $repository = Join-Path $fixture 'repository'
  $destination = Join-Path $fixture 'installer'
  New-Item -ItemType Directory -Path (Join-Path $repository 'docs'), $destination -Force | Out-Null
  Copy-OfflineRecoveryManual $repository $destination
  if (Test-Path -LiteralPath (Join-Path $destination 'RECOVERY-MIGRATION-UPGRADE.md')) { throw 'Missing source must not create a placeholder' }
  $source = Join-Path $repository 'docs/10-recovery-migration-upgrade.md'
  [IO.File]::WriteAllText($source, "# Recovery`nfirst generation`n")
  Copy-OfflineRecoveryManual $repository $destination
  $copied = Join-Path $destination 'RECOVERY-MIGRATION-UPGRADE.md'
  if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $copied).Hash) { throw 'Manual bytes differ' }
  [IO.File]::WriteAllText($source, "# Recovery`nsecond generation`n")
  Copy-OfflineRecoveryManual $repository $destination
  if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $copied).Hash) { throw 'Stale manual packaged' }
  'RECOVERY_MANUAL_PACKAGING_PASS 3'
} finally {
  $resolved = [IO.Path]::GetFullPath($fixture)
  $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
  if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'deployops-manual-package-*') { throw 'Unsafe fixture cleanup path' }
  if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
