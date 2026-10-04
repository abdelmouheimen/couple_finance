<#
.SYNOPSIS
    Runs CoupleFinance locally for testing on a PHYSICAL phone with Expo Go (same Wi-Fi as this PC).

.DESCRIPTION
    Development only. One command:
      1. checks the prerequisites (Docker, Java + Gradle wrapper, Node/npm, Expo CLI);
      2. starts the local PostgreSQL (infra/docker-compose.yml, published on 127.0.0.1 only);
      3. starts the backend (local profile, gradlew bootRun) in the background, logging to
         backend/build/mobile-local/backend.log, and waits for /actuator/health/readiness;
      4. detects this PC's LAN IPv4 address (override with -LanIp) and checks the backend answers on it;
      5. points the app at http://<LAN IP>:<ApiPort> through EXPO_PUBLIC_API_BASE_URL (process environment
         of Expo only; nothing is written to disk) - never localhost, which on the phone is the phone itself.
         Development bundles accept cleartext http on private LAN addresses only (mobile/src/shared/config/env.ts);
      6. starts Expo interactively (LAN, or -Tunnel) so the QR code stays in this terminal.
    Ctrl+C stops Expo, the backend this script started and the PostgreSQL container if this script started it
    (data volume kept). Never changes Git state; production configuration is untouched.

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mobile-local.ps1

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mobile-local.ps1 -Tunnel
#>
[CmdletBinding()]
param(
    # Expo through an ngrok tunnel when the LAN QR code is unreachable. The phone must still reach the backend
    # at http://<LAN IP>:<ApiPort>, i.e. be on the same network as this PC.
    [switch]$Tunnel,

    # Force the LAN IPv4 address (e.g. when several adapters are connected).
    [string]$LanIp = '',

    # Backend port. Default: 8080, or the first free port in 8090-8099 when 8080 is used by another application.
    [ValidateRange(0, 65535)]
    [int]$ApiPort = 0,

    # Checkout to run; defaults to the repository containing this script.
    [string]$Path = '',

    [ValidateRange(1, 30)]
    [int]$BackendTimeoutMinutes = 8
)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'

if (-not $Path) { $Path = Join-Path $PSScriptRoot '..' }
$Path = (Resolve-Path -LiteralPath $Path).Path.TrimEnd('\')
$backendDir = Join-Path $Path 'backend'
$mobileDir = Join-Path $Path 'mobile'
$composeFile = Join-Path $Path 'infra\docker-compose.yml'
foreach ($required in @((Join-Path $backendDir 'gradlew.bat'), (Join-Path $mobileDir 'package.json'), $composeFile)) {
    if (-not (Test-Path -LiteralPath $required)) { throw ("Not a CoupleFinance checkout: missing '" + $required + "'.") }
}

function Write-Step([string]$Message) { Write-Host ('==> ' + $Message) -ForegroundColor Cyan }

function Invoke-Native([scriptblock]$Command) {
    # Windows PowerShell 5.1 turns redirected native stderr (docker progress, npx notices) into terminating errors
    # under ErrorActionPreference=Stop: run with Continue (function scope) and drop stderr; callers check $LASTEXITCODE.
    $ErrorActionPreference = 'Continue'
    & $Command 2>$null
}

function Test-Ready([string]$HostName, [int]$Port) {
    try {
        $uri = 'http://' + $HostName + ':' + $Port + '/actuator/health/readiness'
        return ((Invoke-RestMethod -Uri $uri -TimeoutSec 3).status -eq 'UP')
    } catch { return $false }
}

function Test-CoupleFinanceBackend([int]$Port) {
    # Another local application may listen on the same port: identify ours by its OpenAPI title (local profile).
    if (-not (Test-Ready 'localhost' $Port)) { return $false }
    try { return ((Invoke-RestMethod -Uri ('http://localhost:' + $Port + '/v3/api-docs') -TimeoutSec 5).info.title -eq 'CoupleFinance API') }
    catch { return $false }
}

function Test-PortInUse([int]$Port) {
    return ($null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1))
}

function Get-FreePort([int[]]$Candidates) {
    foreach ($candidate in $Candidates) { if (-not (Test-PortInUse $candidate)) { return $candidate } }
    throw ('No free port among ' + ($Candidates -join ', ') + '.')
}

function Stop-ProcessTree([int]$ProcessId) {
    # cmd (gradlew.bat) -> java (Gradle, --no-daemon) -> java (application): kill the whole tree.
    Invoke-Native { & taskkill.exe /PID $ProcessId /T /F } | Out-Null
}

function Get-LanIPv4 {
    # Connected adapters with a default gateway (the Wi-Fi/Ethernet the phone shares), private ranges only,
    # skipping virtual adapters (Hyper-V/WSL/Docker vEthernet, VPNs, VirtualBox/VMware).
    $virtual = 'vEthernet|Hyper-V|WSL|Docker|VirtualBox|VMware|Loopback|TAP|Tailscale|ZeroTier|WireGuard|VPN'
    $candidates = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue | Where-Object {
            $null -ne $_.IPv4DefaultGateway -and $null -ne $_.NetAdapter -and $_.NetAdapter.Status -eq 'Up' -and
            ($_.InterfaceAlias + ' ' + $_.InterfaceDescription) -notmatch $virtual
        } | ForEach-Object { $_.IPv4Address } | Where-Object {
            $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)'
        } | Sort-Object { if ($_.InterfaceAlias -match 'Wi-?Fi|WLAN|Wireless') { 0 } else { 1 } })
    if ($candidates.Count -eq 0) { return $null }
    return $candidates[0]
}

# --- 1. Prerequisites --------------------------------------------------------------------------------------
Write-Step 'Checking prerequisites'
$missing = @()
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { $missing += 'Docker Desktop (https://www.docker.com/products/docker-desktop)' }
if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) { $missing += 'a JDK (17+ to run Gradle; the JDK 25 toolchain is resolved by Gradle)' }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { $missing += 'Node.js 22+ (https://nodejs.org)' }
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { $missing += 'npm (installed with Node.js)' }
if ($missing.Count -gt 0) { throw ("Missing prerequisites:`n  - " + ($missing -join "`n  - ")) }

$nodeMajor = [int]((& node --version).TrimStart('v').Split('.')[0])
if ($nodeMajor -lt 22) { throw ('Node.js 22+ required (found ' + (& node --version) + ').') }
Invoke-Native { & docker info --format '{{.ServerVersion}}' } | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is installed but not running: start Docker Desktop and retry.' }

if (-not (Test-Path -LiteralPath (Join-Path $mobileDir 'node_modules\expo'))) {
    Write-Step 'Installing mobile dependencies (npm ci)'
    Push-Location -LiteralPath $mobileDir
    try { & npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' } } finally { Pop-Location }
}
Push-Location -LiteralPath $mobileDir
try { $expoVersion = (Invoke-Native { & npx --no-install expo --version } | Select-Object -Last 1) } finally { Pop-Location }
if ($LASTEXITCODE -ne 0 -or -not $expoVersion) { throw 'Expo CLI not available in mobile/node_modules: run "npm ci" in mobile/.' }
Write-Host ('    Docker OK, Node ' + (& node --version) + ', Expo CLI ' + $expoVersion) -ForegroundColor DarkGray

# --- 2. LAN address ----------------------------------------------------------------------------------------
if ($LanIp) {
    if ($LanIp -notmatch '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)[\d.]+$' -or $null -eq ($LanIp -as [ipaddress]) -or $LanIp.Split('.').Count -ne 4) {
        throw ('-LanIp must be a private IPv4 address (10.x, 172.16-31.x, 192.168.x), got ' + $LanIp + '.')
    }
    $lanAlias = 'forced (-LanIp)'
} else {
    $detected = Get-LanIPv4
    if ($null -eq $detected) { throw 'No connected Wi-Fi/Ethernet adapter with a private IPv4 address found. Connect to the Wi-Fi shared with the phone, or pass -LanIp.' }
    $LanIp = $detected.IPAddress
    $lanAlias = $detected.InterfaceAlias
}
$firewallHint = $null
$lanProfile = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceAlias -eq $lanAlias } | Select-Object -First 1
if ($null -ne $lanProfile -and [string]$lanProfile.NetworkCategory -eq 'Public') {
    # Windows Firewall blocks unsolicited inbound traffic on Public networks: the phone could not reach ports 8080/8081.
    $firewallHint = 'Network "' + $lanAlias + '" is classified Public: Windows Firewall will likely block the phone. ' +
        'On your home Wi-Fi, set it to Private (Settings > Network > Wi-Fi > properties), then rerun (-Tunnel only helps Expo, not the backend).'
}

$startedBackend = $null
$startedPostgres = $false
try {
    # --- 3. PostgreSQL + backend ---------------------------------------------------------------------------
    if ($ApiPort -eq 0) {
        $ApiPort = 8080
        if ((Test-PortInUse 8080) -and -not (Test-CoupleFinanceBackend 8080)) {
            $ApiPort = Get-FreePort (8090..8099)
            Write-Host ('    Port 8080 is used by another application: the backend will use ' + $ApiPort) -ForegroundColor Yellow
        }
    }
    $apiBaseUrl = 'http://' + $LanIp + ':' + $ApiPort
    if (Test-CoupleFinanceBackend $ApiPort) {
        Write-Step ('CoupleFinance backend already running on port ' + $ApiPort + ': reusing it')
        $postgresState = 'not managed (backend already running)'
    } else {
        if (Test-PortInUse $ApiPort) { throw ('Port ' + $ApiPort + ' is used by another application: pass a free -ApiPort.') }
        $running = @(Invoke-Native { & docker compose -f $composeFile ps --status running --services })
        if ($running -contains 'postgres') {
            $dbPort = [int](((Invoke-Native { & docker compose -f $composeFile port postgres 5432 }) | Select-Object -First 1) -split ':')[-1]
        } elseif (Test-PortInUse 5432) {
            $dbPort = Get-FreePort (5440..5449)
            Write-Host ('    Port 5432 is used by another database: CoupleFinance PostgreSQL will use ' + $dbPort) -ForegroundColor Yellow
        } else {
            $dbPort = 5432
        }
        # Read by infra/docker-compose.yml and by the backend local profile (application-local.yaml).
        $env:POSTGRES_PORT = [string]$dbPort
        Write-Step 'Starting PostgreSQL (docker compose, waits for its healthcheck)'
        & docker compose -f $composeFile up -d --wait postgres
        if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed; see the output above.' }
        $startedPostgres = -not ($running -contains 'postgres')
        $postgresState = 'UP (127.0.0.1:' + $dbPort + ', docker compose)'

        $logDir = Join-Path $backendDir 'build\mobile-local'
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $log = Join-Path $logDir 'backend.log'
        Write-Step ('Starting the backend (local profile) on port ' + $ApiPort + '; log: ' + $log)
        # Child-process environment only: local profile, explicit bind on all interfaces so the phone can reach it.
        $env:SPRING_PROFILES_ACTIVE = 'local'
        $env:SERVER_PORT = [string]$ApiPort
        $env:SERVER_ADDRESS = '0.0.0.0'
        $startedBackend = Start-Process -FilePath (Join-Path $backendDir 'gradlew.bat') -ArgumentList @('bootRun', '--no-daemon', '--console=plain') `
            -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $log -RedirectStandardError (Join-Path $logDir 'backend.err.log')
        $deadline = (Get-Date).AddMinutes($BackendTimeoutMinutes)
        while (-not (Test-CoupleFinanceBackend $ApiPort)) {
            if ($startedBackend.HasExited) { throw ('Backend exited (code ' + $startedBackend.ExitCode + '); see ' + $log) }
            if ((Get-Date) -gt $deadline) { throw ('Backend not ready after ' + $BackendTimeoutMinutes + ' minutes; see ' + $log) }
            Write-Host '    waiting for /actuator/health/readiness ...' -ForegroundColor DarkGray
            Start-Sleep -Seconds 5
        }
    }

    # --- 4. Reachability on the LAN address ---------------------------------------------------------------
    if (-not (Test-Ready $LanIp $ApiPort)) {
        throw ('Backend is UP on localhost but not on ' + $apiBaseUrl + ': it is bound to loopback only. Stop it and rerun this script (it starts the backend with SERVER_ADDRESS=0.0.0.0).')
    }
    $backendState = 'UP (readiness), reachable on ' + $apiBaseUrl

    # --- 5. Expo -------------------------------------------------------------------------------------------
    $env:EXPO_PUBLIC_API_BASE_URL = $apiBaseUrl
    # Make Metro advertise the Wi-Fi address instead of a virtual adapter in the QR code.
    $env:REACT_NATIVE_PACKAGER_HOSTNAME = $LanIp
    $expoArgs = @('expo', 'start', '--go', '--clear')
    if ($Tunnel) { $expoArgs += '--tunnel' } else { $expoArgs += '--lan' }

    Write-Host ''
    Write-Host '================ CoupleFinance - physical phone (Expo Go) ================' -ForegroundColor Green
    Write-Host ('  Backend URL (app)  : ' + $apiBaseUrl)
    Write-Host ('  LAN IP detected    : ' + $LanIp + '  [' + $lanAlias + ']')
    Write-Host ('  PostgreSQL         : ' + $postgresState)
    Write-Host ('  Backend            : ' + $backendState)
    Write-Host ('  Mobile folder      : ' + $mobileDir)
    Write-Host ('  Expo mode          : ' + $(if ($Tunnel) { 'tunnel (ngrok)' } else { 'LAN' }))
    Write-Host ''
    Write-Host '  Ouvre Expo Go et scanne le QR code' -ForegroundColor Yellow
    Write-Host '    Android : Expo Go > "Scan QR code"   |   iPhone : app Appareil photo, puis ouvrir dans Expo Go'
    Write-Host '    Phone and PC on the same Wi-Fi. If Windows Firewall asks, allow Java and Node on "Private" networks.'
    Write-Host ('    Check from the phone browser: ' + $apiBaseUrl + '/actuator/health  -> {"status":"UP"}')
    Write-Host '    In Expo: r = reload, Ctrl+C = stop everything.'
    if ($firewallHint) { Write-Host ('  WARNING: ' + $firewallHint) -ForegroundColor Yellow }
    Write-Host '==========================================================================' -ForegroundColor Green
    Write-Host ''

    Push-Location -LiteralPath $mobileDir
    try { & npx --no-install @expoArgs } finally { Pop-Location }
} finally {
    if ($null -ne $startedBackend) {
        Write-Host 'Stopping the backend started by this script...' -ForegroundColor Cyan
        Stop-ProcessTree $startedBackend.Id
    }
    if ($startedPostgres) {
        Write-Host 'Stopping PostgreSQL (data volume kept)...' -ForegroundColor Cyan
        Invoke-Native { & docker compose -f $composeFile stop postgres } | Out-Null
    }
}
