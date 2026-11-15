$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$TarExecutable = (Get-Command tar -CommandType Application).Source
. (Join-Path $repo 'scripts/java-ci-payload.ps1')
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts/prepare-offline-bundle.ps1'),[ref]$tokens,[ref]$errors)
if ($errors) { throw 'Prepare script parse failure' }
$function=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-DockerArchiveImageId'},$true)
. ([scriptblock]::Create($function.Extent.Text))
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('java-ci-test-'+[Guid]::NewGuid().ToString('N'))
$root=Join-Path $fixture 'payload'
$utf8=[Text.UTF8Encoding]::new($false)
function Text([string]$Path,[string]$Value) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path)|Out-Null
  [IO.File]::WriteAllText($Path,$Value,$utf8)
}
function Json([string]$Path,$Value) {
  function SortValue($v) {
    if($v -is [Collections.IDictionary]) {$x=[ordered]@{};foreach($k in @($v.Keys|Sort-Object -CaseSensitive)){$x[$k]=SortValue $v[$k]};return $x}
    if($v -is [array]) {return ,@($v|ForEach-Object {SortValue $_})}
    return $v
  }
  Text $Path ((SortValue $Value|ConvertTo-Json -Depth 100 -Compress)+"`n")
}
function Hash([string]$Path){(Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()}
function Inventory([string]$Directory,[string]$Exclude){
  return ,@(Get-ChildItem -LiteralPath $Directory -Recurse -File|Where-Object {$_.Name -cne $Exclude}|ForEach-Object {
    @{path=$_.FullName.Substring($Directory.Length).TrimStart('\','/').Replace('\','/');size=$_.Length;sha256=Hash $_.FullName}
  }|Sort-Object {$_.path})
}
function Reject([scriptblock]$Action,[string]$Name,[string]$Reason=''){
  $rejected=$false
  try{& $Action}catch{$rejected=$true;if($Reason -and -not $_.Exception.Message.Contains($Reason)){throw "Wrong rejection for ${Name}: $($_.Exception.Message)"}}
  if(-not $rejected){throw "Unexpected acceptance: $Name"};Write-Host "Rejected: $Name"
}
function RepackBuilder {
  & $TarExecutable -czf (Join-Path $root 'builder.tar.gz') -C (Join-Path $fixture 'builder') manifest.json blobs
  if($LASTEXITCODE -ne 0){throw 'Malformed fixture repack failed'}
  $updated=Read-JavaCiJson (Join-Path $root 'CI-PAYLOAD.json')
  $updated.images.builder.archiveSha256=Hash (Join-Path $root 'builder.tar.gz')
  $updated.files=Inventory $root 'CI-PAYLOAD.json'
  Json (Join-Path $root 'CI-PAYLOAD.json') $updated
}
try {
  New-Item -ItemType Directory -Path $root|Out-Null
  $images=@{}; $inputs=@{}
  foreach($role in @('builder','runtime')) {
    $build=Join-Path $fixture $role
    New-Item -ItemType Directory -Path $build|Out-Null
    $layer='fixture-'+$role
    Text (Join-Path $build 'layer') $layer
    $layerHash=Hash (Join-Path $build 'layer')
    Text (Join-Path $build "blobs/sha256/$layerHash") $layer
    $config=@{architecture='amd64';os='linux';rootfs=@{type='layers';diff_ids=@("sha256:$layerHash")}}
    Json (Join-Path $build 'config') $config
    $configHash=Hash (Join-Path $build 'config')
    Copy-Item -LiteralPath (Join-Path $build 'config') -Destination (Join-Path $build "blobs/sha256/$configHash")
    $oci=@{schemaVersion=2;mediaType='application/vnd.oci.image.manifest.v1+json';config=@{digest="sha256:$configHash";size=(Get-Item (Join-Path $build 'config')).Length};layers=@(@{digest="sha256:$layerHash";size=$layer.Length})}
    $manifest=Join-Path $root "evidence/$role-manifest.json"
    Json $manifest $oci
    $manifestHash=Hash $manifest
    Copy-Item -LiteralPath $manifest -Destination (Join-Path $build "blobs/sha256/$manifestHash")
    $index=Join-Path $root "evidence/$role-index.json"
    Json $index @{schemaVersion=2;manifests=@(@{digest="sha256:$manifestHash";platform=@{os='linux';architecture='amd64'}})}
    $indexHash=Hash $index
    Copy-Item -LiteralPath $index -Destination (Join-Path $build "blobs/sha256/$indexHash")
    Json (Join-Path $build 'manifest.json') @(@{Config="blobs/sha256/$configHash";RepoTags=@("fixture-$($role):v1");Layers=@("blobs/sha256/$layerHash")})
    & $TarExecutable -czf (Join-Path $root "$role.tar.gz") -C $build manifest.json blobs
    if($LASTEXITCODE -ne 0){throw 'Fixture archive failure'}
    $directory=Join-Path $root "inputs/$role"
    Text (Join-Path $directory 'Dockerfile') "FROM scratch`n"
    Json (Join-Path $directory 'INPUTS.lock.json') @{schemaVersion=1;files=(Inventory $directory 'INPUTS.lock.json')}
    $inputs[$role]=@{directory="inputs/$role";lockSha256=Hash (Join-Path $directory 'INPUTS.lock.json')}
    $images[$role]=@{tag="fixture-$($role):v1";archive="$role.tar.gz";archiveSha256=Hash (Join-Path $root "$role.tar.gz");index="evidence/$role-index.json";indexSha256=$indexHash;manifest="evidence/$role-manifest.json";manifestSha256=$manifestHash;configDigest="sha256:$configHash";input=$role}
  }
  $seedRoot=Join-Path $root 'maven-seed'
  Text (Join-Path $seedRoot 'sources/example/pom.xml') '<project/>'
  Text (Join-Path $seedRoot 'repository/example/library/1/library-1.pom') '<project/>'
  Json (Join-Path $seedRoot 'seed.lock.json') @{schema=1;identity=@{java='fixture'};projects=@(@{name='example';pom_sha256=Hash (Join-Path $seedRoot 'sources/example/pom.xml');files=@('pom.xml')});files=(Inventory $seedRoot 'seed.lock.json')}
  $metadata=@{schemaVersion=1;origin='local-build-candidate-not-publisher-authenticated';images=$images;inputs=$inputs;seed=@{directory='maven-seed';lockSha256=Hash (Join-Path $seedRoot 'seed.lock.json')};files=(Inventory $root 'CI-PAYLOAD.json')}
  Json (Join-Path $root 'CI-PAYLOAD.json') $metadata
  Assert-JavaCiPayload $root
  $baseline=[IO.File]::ReadAllText((Join-Path $root 'CI-PAYLOAD.json'))
  Text (Join-Path $root 'unexpected.txt') 'extra'
  Reject {Assert-JavaCiPayload $root} 'extra file'
  Remove-Item -LiteralPath (Join-Path $root 'unexpected.txt')
  Text (Join-Path $seedRoot 'repository/example/library/1/library-1.pom') 'tampered'
  Reject {Assert-JavaCiPayload $root} 'seed bytes mismatch'
  Text (Join-Path $seedRoot 'repository/example/library/1/library-1.pom') '<project/>'
  $metadata.files += $metadata.files[0]
  Json (Join-Path $root 'CI-PAYLOAD.json') $metadata
  Reject {Assert-JavaCiPayload $root} 'duplicate path'
  Text (Join-Path $root 'CI-PAYLOAD.json') $baseline
  $duplicate=$baseline.Replace('"schemaVersion":1','"schemaVersion":1,"schemaVersion":1')
  Text (Join-Path $root 'CI-PAYLOAD.json') $duplicate
  Reject {Assert-JavaCiPayload $root} 'duplicate JSON property'
  Text (Join-Path $root 'CI-PAYLOAD.json') $baseline
  $metadata=Read-JavaCiJson (Join-Path $root 'CI-PAYLOAD.json')
  $metadata.files[0].path='../escape'
  Json (Join-Path $root 'CI-PAYLOAD.json') $metadata
  Reject {Assert-JavaCiPayload $root} 'path traversal'
  Text (Join-Path $root 'CI-PAYLOAD.json') $baseline
  $metadata=Read-JavaCiJson (Join-Path $root 'CI-PAYLOAD.json')
  $metadata.images.runtime.configDigest='sha256:'+('a'*64)
  Json (Join-Path $root 'CI-PAYLOAD.json') $metadata
  Reject {Assert-JavaCiPayload $root} 'wrong OCI config'
  Text (Join-Path $root 'CI-PAYLOAD.json') $baseline
  Assert-JavaCiPayload $root
  # Keep every outer hash/pin consistent so rejection must occur inside the archive parser.
  $build=Join-Path $fixture 'builder'
  $dockerManifest=[IO.File]::ReadAllText((Join-Path $build 'manifest.json'))
  Text (Join-Path $build 'manifest.json') ($dockerManifest.Replace('"Config":','"Config":"ignored","Config":'))
  RepackBuilder
  Reject {Assert-JavaCiPayload $root} 'duplicate archived manifest Config with consistent outer hashes' 'Duplicate JSON property'
  Text (Join-Path $build 'manifest.json') $dockerManifest
  RepackBuilder
  Assert-JavaCiPayload $root
  & $TarExecutable -czf (Join-Path $root 'builder.tar.gz') -C $build manifest.json blobs manifest.json
  if($LASTEXITCODE -ne 0){throw 'Duplicate member fixture creation failed'}
  $updated=Read-JavaCiJson (Join-Path $root 'CI-PAYLOAD.json')
  $updated.images.builder.archiveSha256=Hash (Join-Path $root 'builder.tar.gz')
  $updated.files=Inventory $root 'CI-PAYLOAD.json'
  Json (Join-Path $root 'CI-PAYLOAD.json') $updated
  Reject {Assert-JavaCiPayload $root} 'duplicate regular archive manifest member with consistent outer hashes' 'one regular member'
  RepackBuilder
  $duplicateConfig=[IO.File]::ReadAllText((Join-Path $build 'config')).Replace('"os":','"os":"ignored","os":')
  Text (Join-Path $build 'config') $duplicateConfig
  $newConfig=Hash (Join-Path $build 'config')
  Copy-Item -LiteralPath (Join-Path $build 'config') -Destination (Join-Path $build "blobs/sha256/$newConfig")
  $mapping=@(Read-JavaCiJson (Join-Path $build 'manifest.json'))
  $mapping[0].Config="blobs/sha256/$newConfig"
  Json (Join-Path $build 'manifest.json') $mapping
  $oci=Read-JavaCiJson (Join-Path $root 'evidence/builder-manifest.json')
  $oci.config.digest="sha256:$newConfig"; $oci.config.size=(Get-Item (Join-Path $build 'config')).Length
  Json (Join-Path $root 'evidence/builder-manifest.json') $oci
  $newPlatform=Hash (Join-Path $root 'evidence/builder-manifest.json')
  Copy-Item -LiteralPath (Join-Path $root 'evidence/builder-manifest.json') -Destination (Join-Path $build "blobs/sha256/$newPlatform")
  $index=Read-JavaCiJson (Join-Path $root 'evidence/builder-index.json')
  $index.manifests[0].digest="sha256:$newPlatform"
  Json (Join-Path $root 'evidence/builder-index.json') $index
  $newIndex=Hash (Join-Path $root 'evidence/builder-index.json')
  Copy-Item -LiteralPath (Join-Path $root 'evidence/builder-index.json') -Destination (Join-Path $build "blobs/sha256/$newIndex")
  $updated=Read-JavaCiJson (Join-Path $root 'CI-PAYLOAD.json')
  $updated.images.builder.configDigest="sha256:$newConfig"
  $updated.images.builder.manifestSha256=$newPlatform; $updated.images.builder.indexSha256=$newIndex
  Json (Join-Path $root 'CI-PAYLOAD.json') $updated
  RepackBuilder
  Reject {Assert-JavaCiPayload $root} 'duplicate archived config os with consistent OCI/outer pins' 'Duplicate JSON property'
  Write-Host 'Java CI PowerShell contract regressions: PASS (synthetic fixtures only)'
} finally {
  $full=[IO.Path]::GetFullPath($fixture)
  $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
  if(-not [IO.Path]::GetDirectoryName($full).Equals($parent,[StringComparison]::OrdinalIgnoreCase) -or
      [IO.Path]::GetFileName($full) -cnotmatch '^java-ci-test-[0-9a-f]{32}$'){throw 'Unsafe fixture cleanup'}
  Remove-Item -LiteralPath $full -Recurse -Force
}
