$ErrorActionPreference = 'Stop'
$deployRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workspaceRoot = Split-Path $deployRoot -Parent
$taskRoot = Join-Path ([IO.Path]::GetTempPath()) ('release-context-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRoot | Out-Null
# 실패 시에도 이 작은 fixture를 남겨 실제 전송 결과를 조사할 수 있게 한다.
foreach ($app in @('deploy-system', 'worksystem-server')) {
    $source = Join-Path $workspaceRoot $app
    $context = Join-Path $taskRoot "$app/context"
    $result = Join-Path $taskRoot "$app/result"
    New-Item -ItemType Directory -Path "$context/target", "$context/src", "$context/.git" -Force | Out-Null
    Copy-Item -LiteralPath "$source/.dockerignore" -Destination "$context/.dockerignore"
    Copy-Item -LiteralPath "$source/Dockerfile.release.dockerignore" -Destination "$context/Dockerfile.release.dockerignore"
    Set-Content -LiteralPath "$context/Dockerfile.release" -Value "FROM scratch`nCOPY . /context/" -Encoding utf8NoBOM
    Set-Content -LiteralPath "$context/target/app.jar" -Value 'verified-jar-fixture' -Encoding utf8NoBOM
    Set-Content -LiteralPath "$context/target/build.log" -Value 'excluded' -Encoding utf8NoBOM
    Set-Content -LiteralPath "$context/src/private.txt" -Value 'excluded' -Encoding utf8NoBOM
    Set-Content -LiteralPath "$context/.git/config" -Value 'excluded' -Encoding utf8NoBOM
    Set-Content -LiteralPath "$context/.env" -Value 'excluded' -Encoding utf8NoBOM
    & docker build --network=none --pull=false --no-cache --file "$context/Dockerfile.release" --output "type=local,dest=$result" $context
    if ($LASTEXITCODE -ne 0) { throw "Docker context build failed: $app" }
    $files = @(Get-ChildItem -LiteralPath "$result/context" -File -Recurse -Force)
    if ($files.Count -ne 1 -or $files[0].Name -ne 'app.jar' -or $files[0].Directory.Name -ne 'target') {
        throw "Unexpected release build context: $app"
    }
    if ((Get-FileHash "$context/target/app.jar").Hash -ne (Get-FileHash "$result/context/target/app.jar").Hash) {
        throw "JAR bytes changed: $app"
    }
    Write-Output "$app release context: PASS (JAR included; source, Git, env and logs excluded)"
}
Write-Output "Evidence: $taskRoot"
