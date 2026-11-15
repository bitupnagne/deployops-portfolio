#Requires -Version 7.3
# Exercise the actual preparation guard without running the producer or reading credentials.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$producer = Join-Path $repo 'scripts/prepare-offline-bundle.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($producer,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Producer syntax invalid' }
$functions = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-OfflineBundleShellFraming'},$true))
if ($functions.Count -ne 1) { throw 'Actual bundle shell guard missing' }
Invoke-Expression $functions[0].Extent.Text
$text = [IO.File]::ReadAllText($producer)
$guardIndex = $text.IndexOf('Assert-OfflineBundleShellFraming $BundleRoot')
$anchorIndex = $text.IndexOf('$DeployJar = (Resolve-Path')
if ($guardIndex -lt 0 -or $anchorIndex -lt 0) { throw 'Guard call or staging anchor missing' }
if ($guardIndex -gt $anchorIndex) { throw 'Guard must execute before staging inputs' }
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('bundle-shell-framing-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$path = Join-Path $fixture 'fixture.sh'
$cases = 0
function Check-Rejected([byte[]]$Bytes) {
  [IO.File]::WriteAllBytes($path,$Bytes)
  $before = (Get-FileHash -LiteralPath $path).Hash
  $rejected = $false
  try { Assert-OfflineBundleShellFraming $fixture } catch { $rejected = $true }
  if (-not $rejected) { throw 'Invalid shell accepted' }
  if ((Get-FileHash -LiteralPath $path).Hash -ne $before) { throw 'Immutable input changed' }
  $script:cases++
}
try {
  Check-Rejected ([Text.Encoding]::UTF8.GetBytes("#!/bin/bash`r`necho ok`r`n"))
  Check-Rejected ([Text.Encoding]::UTF8.GetBytes("#!/bin/bash`necho ok`r"))
  Check-Rejected ([byte[]](@(239,187,191)+[Text.Encoding]::UTF8.GetBytes("#!/bin/bash`ntrue`n")))
  [IO.File]::WriteAllBytes($path,[Text.Encoding]::UTF8.GetBytes("#!/bin/bash`nprintf '한글\n'`n"))
  $before = (Get-FileHash -LiteralPath $path).Hash
  Assert-OfflineBundleShellFraming $fixture
  if ((Get-FileHash -LiteralPath $path).Hash -ne $before) { throw 'LF UTF-8 changed' }
  $cases++
  [IO.File]::WriteAllBytes((Join-Path $fixture 'fixture.jar'),[byte[]]@(0,13,10,255))
  Assert-OfflineBundleShellFraming $fixture
  $cases++
  @{status='PASS';cases=$cases;producerExecuted=$false;dockerExecuted=$false} | ConvertTo-Json
} finally {
  $resolved = (Resolve-Path -LiteralPath $fixture).Path
  if ((Split-Path $resolved -Parent).TrimEnd('\') -ne ([IO.Path]::GetTempPath()).TrimEnd('\')) { throw 'Unexpected fixture path' }
  Remove-Item -LiteralPath $resolved -Recurse -Force
}