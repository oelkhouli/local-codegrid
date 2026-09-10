param([ValidateSet('up','down','status','logs','credentials','test','integration','scale','help')][string]$Action='help',[int]$Workers=2)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if ($Action -eq 'help') { Write-Host './codegrid.ps1 up | down | status | logs | credentials | test | integration | scale -Workers 3';exit 0 }
$CodeGridEngine=$env:CODEGRID_ENGINE
if (-not $CodeGridEngine) {
    if (Get-Command podman -ErrorAction SilentlyContinue) { $CodeGridEngine='podman' }
    elseif (Get-Command docker -ErrorAction SilentlyContinue) { $CodeGridEngine='docker' }
    else { throw 'Install Podman and a Compose provider, or Docker Engine with Compose. Start the Linux engine first.' }
}
if ($CodeGridEngine -notin @('podman','docker')) { throw 'CODEGRID_ENGINE must be podman or docker.' }
function Invoke-Engine { & $CodeGridEngine @args; if ($LASTEXITCODE -ne 0) { throw "Container engine command failed: $LASTEXITCODE" } }
function Invoke-Compose { Invoke-Engine compose --env-file .env -f compose.yaml @args }
Invoke-Engine info | Out-Null
Invoke-Engine compose version | Out-Null
if (-not $env:ENGINE_SOCKET) {
    if ($CodeGridEngine -eq 'podman') { $env:ENGINE_SOCKET=(Invoke-Engine info --format '{{.Host.RemoteSocket.Path}}').Trim() }
    else { $env:ENGINE_SOCKET='/var/run/docker.sock' }
}
if (-not (Test-Path .env)) {
    if ($Action -ne 'up') { throw 'Run ./codegrid.ps1 up first.' }
    if ($CodeGridEngine -eq 'podman') {
        $CodeGridCpu=[int](Invoke-Engine info --format '{{.Host.CPUs}}')
        $CodeGridMemory=[long](Invoke-Engine info --format '{{.Host.MemTotal}}')
    } else {
        $CodeGridCpu=[int](Invoke-Engine info --format '{{.NCPU}}')
        $CodeGridMemory=[long](Invoke-Engine info --format '{{.MemTotal}}')
    }
    if ($CodeGridCpu -lt 2 -or $CodeGridMemory -lt 4GB) { throw 'Allocate at least 2 CPUs and 4 GiB RAM to the existing Linux engine/VM.' }
    $CodeGridSlots=2;if ($CodeGridCpu -le 2) { $CodeGridSlots=1 }
    function New-CodeGridSecret { $bytes=New-Object byte[] 24;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose();return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant() }
    $lines=@("POSTGRES_ADMIN_PASSWORD=$(New-CodeGridSecret)","DATABASE_PASSWORD=$(New-CodeGridSecret)","REDIS_PASSWORD=$(New-CodeGridSecret)","ADMIN_PASSWORD=$(New-CodeGridSecret)","NODE_TOKEN=$(New-CodeGridSecret)",'NODE_ID=local',"NODE_CPU=$($CodeGridSlots*1000)","NODE_MEMORY=$($CodeGridSlots*536870912)","NODE_SLOTS=$CodeGridSlots",'PUBLIC_ORIGIN=http://127.0.0.1:8080','WEB_PORT=8080')
    $CodeGridEnvPath=Join-Path $PSScriptRoot '.env'
    [IO.File]::WriteAllLines($CodeGridEnvPath,$lines,(New-Object Text.UTF8Encoding $false))
    if ($env:OS -eq 'Windows_NT') {
        $CodeGridAcl=Get-Acl $CodeGridEnvPath
        $CodeGridAcl.SetAccessRuleProtection($true,$false)
        $CodeGridOwner=[Security.Principal.WindowsIdentity]::GetCurrent().User
        $CodeGridRule=New-Object Security.AccessControl.FileSystemAccessRule($CodeGridOwner,'FullControl','Allow')
        $CodeGridAcl.SetAccessRule($CodeGridRule)
        Set-Acl -Path $CodeGridEnvPath -AclObject $CodeGridAcl
    } else {
        & chmod 600 $CodeGridEnvPath
        if ($LASTEXITCODE -ne 0) { throw 'Cannot protect generated credentials.' }
    }
}
switch ($Action) {
    'up' {
        Write-Host 'Building CodeGrid and its four language images. First build downloads public dependencies.'
        Invoke-Compose --profile build build runner-java runner-python runner-cpp runner-javascript api worker web
        Invoke-Compose up -d --scale worker=2 postgres redis api reaper worker web prometheus grafana
        $CodeGridReady=$false
        for ($attempt=0;$attempt -lt 120;$attempt++) {
            & $CodeGridEngine compose --env-file .env -f compose.yaml exec -T worker curl --fail --silent http://localhost:9102/health 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                & $CodeGridEngine compose --env-file .env -f compose.yaml exec -T reaper curl --fail --silent http://localhost:9102/health 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) {
                    try {
                        $CodeGridOrigin=(Get-Content .env | Where-Object { $_ -match '^PUBLIC_ORIGIN=' }).Split('=',2)[1]
                        Invoke-WebRequest -UseBasicParsing -Uri "$CodeGridOrigin/api/health" -TimeoutSec 3 | Out-Null
                        Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:3000/api/health' -TimeoutSec 3 | Out-Null
                        $CodeGridReady=$true;break
                    } catch { }
                }
            }
            Start-Sleep -Seconds 1
        }
        if (-not $CodeGridReady) { Invoke-Compose logs --tail=60 api worker reaper;throw 'Startup checks failed. Required isolation limits were not disabled.' }
        Write-Host 'CodeGrid: http://127.0.0.1:8080'
        Write-Host 'Grafana: http://127.0.0.1:3000'
        Write-Host 'Run ./codegrid.ps1 credentials to display the generated administrator password.'
    }
    'credentials' { Write-Host 'Username: admin';Get-Content .env | Where-Object { $_ -match '^ADMIN_PASSWORD=' } | ForEach-Object { Write-Host ($_.Replace('ADMIN_PASSWORD=','Password: ')) } }
    'down' { Invoke-Compose down }
    'status' { Invoke-Compose ps }
    'logs' { Invoke-Compose logs --tail=100 -f }
    'test' { Invoke-Compose --profile test run --build --rm tests }
    'integration' { Invoke-Compose --profile test run --build --rm tests mvn --batch-mode --no-transfer-progress -f backend/pom.xml -Pintegration verify }
    'scale' { if ($Workers -lt 1 -or $Workers -gt 8) { throw 'Worker count must be 1–8.' };Invoke-Compose up -d --no-deps --scale "worker=$Workers" worker }
}
