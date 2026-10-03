<#
.SYNOPSIS
    Starts the integrated CoupleFinance MVP for HUMAN ACCEPTANCE TESTING on Android.

.DESCRIPTION
    Uses the integration worktree created by scripts/autonomous-development.ps1
    (default: <repo>.worktrees\integration, branch integration/mvp):
      1. starts the local PostgreSQL (infra/docker-compose.yml);
      2. starts the backend (local profile, gradlew bootRun) in a separate window and waits for health;
      3. installs mobile dependencies when needed;
      4. points the app at the backend (EXPO_PUBLIC_API_BASE_URL) and starts Expo for Android:
           -Device emulator : http://10.0.2.2:8080 (the emulator's alias for the host)
           -Device usb      : adb reverse tcp:8080/8081 + http://localhost:8080 over USB
         (the app accepts plain http only for localhost, 127.0.0.1 and 10.0.2.2: mobile/README.md).
    Read-only for Git: it never changes branches or commits.

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device emulator

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device usb
#>
[CmdletBinding()]
param(
    [ValidateSet('emulator', 'usb')]
    [string]$Device = 'emulator',

    # Checkout to run; defaults to the orchestrator's integration worktree.
    [string]$Path = '',

    [switch]$SkipBackend,

    [ValidateRange(1024, 65535)]
    [int]$ApiPort = 8080
)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
if (-not $Path) { $Path = Join-Path (Split-Path -Parent $repoRoot) ((Split-Path -Leaf $repoRoot) + '.worktrees\integration') }
if (-not (Test-Path -LiteralPath (Join-Path $Path 'mobile\package.json'))) {
    throw ("No CoupleFinance checkout at '" + $Path + "'. Run the orchestrator first, or pass -Path (e.g. a checkout of integration/mvp).")
}
$branch = (& git -C $Path branch --show-current)
Write-Host ('Checkout : ' + $Path + ' (' + $branch + ' @ ' + (& git -C $Path rev-parse --short HEAD) + ')') -ForegroundColor Cyan

function Test-Health {
    try { return ((Invoke-RestMethod -Uri ('http://localhost:' + $ApiPort + '/actuator/health') -TimeoutSec 3).status -eq 'UP') } catch { return $false }
}

if (-not $SkipBackend) {
    if (Test-Health) {
        Write-Host ('Backend already UP on port ' + $ApiPort + '.') -ForegroundColor Green
    } else {
        Write-Host 'Starting PostgreSQL (docker compose)...' -ForegroundColor Cyan
        & docker compose -f (Join-Path $Path 'infra\docker-compose.yml') up -d --wait
        if ($LASTEXITCODE -ne 0) { throw 'docker compose failed: is Docker Desktop running?' }
        Write-Host 'Starting the backend (local profile) in a new window...' -ForegroundColor Cyan
        $backend = Join-Path $Path 'backend'
        $cmd = "Set-Location -LiteralPath '" + $backend + "'; `$env:SERVER_PORT='" + $ApiPort + "'; .\gradlew.bat bootRun"
        Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $cmd) | Out-Null
        $deadline = (Get-Date).AddMinutes(6)
        while (-not (Test-Health)) {
            if ((Get-Date) -gt $deadline) { throw 'Backend did not become healthy within 6 minutes; check the backend window.' }
            Start-Sleep -Seconds 5
        }
        Write-Host ('Backend UP: http://localhost:' + $ApiPort) -ForegroundColor Green
    }
}

$mobile = Join-Path $Path 'mobile'
if (-not (Test-Path -LiteralPath (Join-Path $mobile 'node_modules'))) {
    Write-Host 'Installing mobile dependencies (npm ci)...' -ForegroundColor Cyan
    Push-Location $mobile; try { & npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' } } finally { Pop-Location }
}

$adb = (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1)
if ($null -eq $adb -and $env:ANDROID_HOME) { $candidate = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'; if (Test-Path $candidate) { $adb = Get-Item $candidate } }
$adbPath = $null
if ($null -ne $adb) { $adbPath = $adb.Source; if (-not $adbPath) { $adbPath = $adb.FullName } }

$expoArgs = @('expo', 'start', '--android')
if ($Device -eq 'usb') {
    if (-not $adbPath) { throw 'adb not found: install Android SDK platform-tools (or Android Studio) and enable USB debugging on the phone.' }
    & $adbPath reverse ('tcp:' + $ApiPort) ('tcp:' + $ApiPort) | Out-Null
    & $adbPath reverse tcp:8081 tcp:8081 | Out-Null
    $env:EXPO_PUBLIC_API_BASE_URL = 'http://localhost:' + $ApiPort
    $expoArgs += '--localhost'
} else {
    if (-not $adbPath) { Write-Warning 'adb not found: start the emulator from Android Studio first; Expo needs adb to open the app.' }
    $env:EXPO_PUBLIC_API_BASE_URL = 'http://10.0.2.2:' + $ApiPort
}
Write-Host ('EXPO_PUBLIC_API_BASE_URL=' + $env:EXPO_PUBLIC_API_BASE_URL) -ForegroundColor Cyan
Write-Host 'Starting Expo (opens the app in Expo Go on the Android device/emulator; press r to reload, Ctrl+C to stop)...' -ForegroundColor Cyan
Push-Location $mobile
try { & npx @expoArgs } finally { Pop-Location }
