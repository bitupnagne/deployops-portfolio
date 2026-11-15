#Requires -Version 7.3
# 함수 AST와 Docker 응답 mock만 사용한다. 실제 노드·키·Compose 파일을 만들지 않는다.
$ErrorActionPreference = 'Stop'
$simRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../sim')).Replace('\','/')
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $simRoot 'local-lab.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Source AST invalid' }
foreach ($name in @('Test-LabBootMounts','Assert-LabStartupContract','Assert-LabResourcesFree','Get-IPv4Range','Select-LabPreparationNodes')) {
    $definition=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if ($definition.Count -ne 1) { throw "Function absent/nonunique: $name" }
    . ([ScriptBlock]::Create($definition[0].Extent.Text))
}
$labBootHelperRoot=$simRoot
$privateInit='/opt/lab-boot/lab-node-boot.sh'; $composePath='D:/mock/compose.yml'; $labMemoryCeiling=12GB
$ProjectName='mock-new-lab'; $nodeDefinitions=@(@{Name='deploy01';Ports=@()}); $subnets=@{}
$fixtureModel=@{services=@{}}
foreach ($pair in @(@('deploy01',4GB),@('gitlab01',6GB),@('devwas1',2GB),@('devweb1',1GB))) {
    $name=$pair[0]; $identity="fixture-$name-v1"
    $fixtureModel.services[$name]=@{
        cgroup='private';mem_limit=$pair[1];memswap_limit=$pair[1];tmpfs=@('/sys/fs/cgroup')
        entrypoint=@('/bin/bash');command=@($privateInit);restart='no'
        environment=@{LAB_NODE_IDENTITY=$identity};labels=@{'org.officesim.host-identity'=$identity}
        volumes=@('lab-node-boot.sh','lab-node-identity.sh' | ForEach-Object {
            @{type='bind';source="$simRoot/$_";target="/opt/lab-boot/$_";read_only=$true}
        })
    }
}
$fixtureRuntime=@{
    Config=@{Entrypoint=@('/bin/bash');Cmd=@($privateInit);Env=@('LAB_NODE_IDENTITY=fixture-deploy01-v1')
        Labels=@{'com.docker.compose.service'='deploy01';'org.officesim.host-identity'='fixture-deploy01-v1'}}
    HostConfig=@{CgroupnsMode='private';Memory=4GB;MemorySwap=4GB;RestartPolicy=@{Name='no'};Tmpfs=@{'/sys/fs/cgroup'=''};Binds=@()}
    Mounts=@('lab-node-boot.sh','lab-node-identity.sh' | ForEach-Object {@{Destination="/opt/lab-boot/$_";Type='bind';RW=$false}})
    State=@{Running=$false}
}
function Reset-Fixture {
    $script:model=$fixtureModel | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $script:runtime=$fixtureRuntime | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $script:existing=$false; $script:names=@(); $script:commands=@()
}
function Invoke-LabDocker([string[]]$DockerArgs) {
    $script:commands += $DockerArgs -join ' '
    switch ($DockerArgs[0]) {
        compose {
            if ('config' -in $DockerArgs) { $model | ConvertTo-Json -Depth 30; return }
            if ('ps' -in $DockerArgs) { if ($existing) {'fixture-id'}; return }
        }
        container {
            if ($DockerArgs[1] -eq 'inspect') { $runtime | ConvertTo-Json -Depth 30; return }
            if ('--format' -in $DockerArgs) { $names; return }
            return
        }
        network { return }
        volume { return }
    }
    throw 'Unexpected mocked Docker operation'
}
function Reject([string]$Label,[ScriptBlock]$Mutate,[string[]]$Selected=@('deploy01')) {
    Reset-Fixture; & $Mutate
    $caught=$false
    try { Assert-LabStartupContract $Selected } catch { $caught=$true }
    if (-not $caught) { throw "Accepted unsafe fixture: $Label" }
    Write-Output "PASS rejection: $Label"
}
Reset-Fixture
$before=$model | ConvertTo-Json -Depth 30 -Compress
Assert-LabStartupContract @('deploy01'); Assert-LabStartupContract @('deploy01')
if ($before -ne ($model | ConvertTo-Json -Depth 30 -Compress)) { throw 'Stored identity changed on repeated up admission' }
$existing=$true; Assert-LabStartupContract @('deploy01')
Write-Output 'PASS: stable prepared model and compatible existing private node admitted (mock only)'
Reject model-command {$model.services.deploy01.command=@('-c','old mount command')}
Reject model-identity {$model.services.deploy01.environment.LAB_NODE_IDENTITY=''}
Reject model-label {$model.services.deploy01.labels.'org.officesim.host-identity'='other-id'}
Reject model-writable-helper {$model.services.deploy01.volumes[0].read_only=$false}
Reject model-missing-helper {$model.services.deploy01.volumes=@($model.services.deploy01.volumes[0])}
Reject model-helper-source {$model.services.deploy01.volumes[0].source='D:/unexpected/boot.sh'}
Reject model-host-cgroup {$model.services.deploy01.cgroup='host'}
Reject model-restart {$model.services.deploy01.restart='unless-stopped'}
Reject runtime-command {$script:existing=$true;$runtime.Config.Cmd=@('-c','old mount command')}
Reject runtime-identity {$script:existing=$true;$runtime.Config.Env=@('LAB_NODE_IDENTITY=other-id')}
Reject runtime-label {$script:existing=$true;$runtime.Config.Labels.'org.officesim.host-identity'='other-id'}
Reject runtime-writable-helper {$script:existing=$true;$runtime.Mounts[0].RW=$true}
Reject runtime-missing-helper {$script:existing=$true;$runtime.Mounts=@($runtime.Mounts[0])}
Reject runtime-host-cgroup {$script:existing=$true;$runtime.HostConfig.CgroupnsMode='host'}
Reject runtime-restart {$script:existing=$true;$runtime.HostConfig.RestartPolicy.Name='unless-stopped'}
Reset-Fixture; Assert-LabStartupContract @('deploy01','gitlab01','devwas1')
Reject memory-over-ceiling {} @('deploy01','gitlab01','devwas1','devweb1')
Reset-Fixture; $names=@('mock-new-lab-deploy01-1'); $caught=$false
try { Assert-LabResourcesFree } catch { $caught=$true }
if (-not $caught -or $commands.Count -ne 4) { throw 'Global stopped/other-project name collision did not stop at read-only guard' }
Write-Output 'PASS: global name collision stopped before credentials/state/startup (mock only)'
# prepare admission must precede UUID and credential generation; up must retain --no-recreate.
$source=[IO.File]::ReadAllText((Join-Path $simRoot 'local-lab.ps1'))
$prepare=$source.Substring($source.IndexOf("if (`$Action -eq 'prepare')"))
if ($prepare.IndexOf('Assert-LabResourcesFree') -gt $prepare.IndexOf('$node.Identity = [Guid]::NewGuid()') -or
    $prepare.IndexOf('Assert-LabResourcesFree') -gt $prepare.IndexOf('New-LabCredentials') -or
    $source -notmatch "'--no-recreate'") { throw 'Fresh creation ordering/no-recreate contract changed' }
Write-Output 'PASS: focused identity/cgroup/memory startup contract regression (mock only)'

# PROD-only preparation must not reserve unrelated deploy/GitLab/test ports or networks.
$definitions = @(
    @{Name='deploy01';Nets=@('devnet:198.18.0.10');Ports=@('18081:8081','15000:5000')},
    @{Name='gitlab01';Nets=@('devnet:198.18.0.20');Ports=@('18929:8929')},
    @{Name='devweb1';Nets=@('devdmz:198.18.1.20');Ports=@('18088:80')},
    @{Name='prdwas1';Nets=@('prodnet:198.18.2.11');Docker=$true},
    @{Name='prdwas2';Nets=@('prodnet:198.18.2.12');Docker=$true},
    @{Name='prdweb1';Nets=@('proddmz:198.18.3.20');Ports=@('28080:80')},
    @{Name='prdweb2';Nets=@('proddmz:198.18.3.21');Ports=@('28081:80')}
)
$networks = [ordered]@{devnet='198.18.0.0/24';devdmz='198.18.1.0/24';prodnet='198.18.2.0/24';proddmz='198.18.3.0/24'}
$selection = Select-LabPreparationNodes $definitions $networks @('prdwas1','prdwas2','prdweb1','prdweb2')
if (($selection.Nodes.Name -join ',') -ne 'prdwas1,prdwas2,prdweb1,prdweb2' -or
    ($selection.Networks.Keys -join ',') -ne 'prodnet,proddmz' -or
    (@($selection.Nodes | ForEach-Object { $_.Ports } | Where-Object { $_ }) -join ',') -ne '28080:80,28081:80') {
    throw 'Subset preparation retained unrelated nodes, ports or networks'
}
if ((Select-LabPreparationNodes $definitions $networks @()).Nodes.Count -ne $definitions.Count) {
    throw 'Default preparation no longer preserves full LAB selection'
}
$caught=$false
try { Select-LabPreparationNodes $definitions $networks @('unknown') | Out-Null } catch { $caught=$true }
if (-not $caught) { throw 'Unknown subset node accepted' }
$prepare=$source.Substring($source.IndexOf("if (`$Action -eq 'prepare')"))
if ($prepare.IndexOf('Select-LabPreparationNodes') -gt $prepare.IndexOf('Assert-LabResourcesFree') -or
    $source -notmatch 'foreach \(\$net in \$subnets.Keys\)') { throw 'Subset selection is not applied before resource admission and compose networks' }
Write-Output 'PASS: PROD-only node/network/port subset selection before preparation (mock only)'
