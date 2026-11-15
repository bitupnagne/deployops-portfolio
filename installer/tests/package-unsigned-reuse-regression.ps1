#Requires -Version 7.3
# Two-byte NTFS fixture only. No package producer, signature, keys or OpenSSL.
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'scripts/package-offline-installer.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Producer syntax is invalid' }
foreach ($name in @('Test-PathInside')) {
    $definition = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true))
    if ($definition.Count -ne 1) { throw 'Required actual producer helper is absent' }
    . ([scriptblock]::Create($definition[0].Extent.Text))
}
. (Join-Path $root 'scripts/offline-payload-reuse.ps1')
function Invoke-OfflineNative { throw 'No-key reuse unexpectedly invoked an external signature tool' }
$artifact = Join-Path $root '.work/artifacts/simple-offline-installer-20261018'
$fixture = [IO.Path]::GetFullPath((Join-Path $artifact ('unsigned-reuse-' + [Guid]::NewGuid().ToString('N'))))
if (-not $fixture.StartsWith([IO.Path]::GetFullPath($artifact) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture path escaped artifact directory' }
try {
    $old = Join-Path $fixture 'old'
    $source = Join-Path $fixture 'source'
    $output = Join-Path $fixture 'new'
    $relative = 'bundles/common/tiny.bin'
    foreach ($base in @((Join-Path $old 'installer'), $source)) {
        $file = Join-Path $base $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
        [IO.File]::WriteAllBytes($file, [byte[]]@(65, 66))
    }
    $member = Join-Path $old ('installer/' + $relative)
    $hash = (Get-FileHash -LiteralPath $member -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $old 'INSTALLER-MANIFEST.sha256'), "$hash  installer/$relative`n", [Text.Encoding]::ASCII)
    $context = New-OfflinePayloadReuseContext -Root $old -OutputRoot $output -SourceInstaller $source -TrustedPublicKey '' -OpenSsl ''
    $target = Join-Path $output ('installer/' + $relative)
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    if (-not (Copy-OfflineReusePayload (Join-Path $source $relative) $target $relative $context)) { throw 'Tiny payload was copied instead of hardlinked' }
    if ($context.Linked.Count -ne 1 -or (Get-Item -LiteralPath $target).LinkType -ne 'HardLink') { throw 'Expected one actual hardlink' }
    # Derived Java CI seed metadata changes legitimately; keep it independent while
    # all ordinary binary reuse mismatches still fail closed.
    $seedRelative = 'bundles/common/java-ci/maven-seed/seed.lock.json'
    if (Test-OfflineReusePayload $seedRelative) { throw 'Derived seed metadata must not reuse an old hardlink' }
    $seedSource = Join-Path $source $seedRelative
    $seedTarget = Join-Path $output ('installer/' + $seedRelative)
    New-Item -ItemType Directory -Path (Split-Path -Parent $seedSource) -Force | Out-Null
    New-Item -ItemType Directory -Path (Split-Path -Parent $seedTarget) -Force | Out-Null
    [IO.File]::WriteAllText($seedSource, '{"projects":[{"name":"deploy"}]}')
    if (Copy-OfflineReusePayload $seedSource $seedTarget $seedRelative $context) { throw 'Seed metadata unexpectedly reused' }
    Copy-Item -LiteralPath $seedSource -Destination $seedTarget
    if ((Get-Item -LiteralPath $seedTarget).LinkType -eq 'HardLink') { throw 'Seed metadata is not independent' }
    if ((Get-Content -LiteralPath $seedTarget -Raw) -cne (Get-Content -LiteralPath $seedSource -Raw)) { throw 'Derived seed metadata was lost' }
    [IO.File]::WriteAllBytes((Join-Path $source $relative), [byte[]]@(67, 68))
    $rejected = $false
    try { $null = Copy-OfflineReusePayload (Join-Path $source $relative) ($target + '.changed') $relative $context } catch { $rejected = $true }
    if (-not $rejected) { throw 'Changed binary was allowed after metadata exclusion' }
    Assert-OfflinePayloadReuseUnchanged $context
    if (Test-Path -LiteralPath (Join-Path $old 'INSTALLER-MANIFEST.sha256.sig')) { throw 'Unsigned fixture unexpectedly has a signature' }
    Write-Host 'PASS: unsigned two-byte payload reused by actual NTFS hardlink without verification key, signing key or OpenSSL; old payload unchanged'
} finally {
    if ((Test-Path -LiteralPath $fixture) -and $fixture.StartsWith([IO.Path]::GetFullPath($artifact) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $fixture -Recurse -Force
    }
}
