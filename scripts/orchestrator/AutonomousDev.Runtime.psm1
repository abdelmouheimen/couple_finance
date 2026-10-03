<#
    Runtime helpers of the CoupleFinance autonomous orchestrator: processes with timeouts,
    Git, fresh Claude Code agent processes, deterministic verification gates, net-diff
    fingerprints and the local state files used for resumability.

    Shared by scripts/autonomous-development.ps1 (orchestrator) and
    scripts/orchestrator/Invoke-IssueWorker.ps1 (one per Issue / repair item).
#>
Set-StrictMode -Version 3
Import-Module (Join-Path $PSScriptRoot 'AutonomousDev.Planning.psm1')

$script:Utf8 = New-Object System.Text.UTF8Encoding $false

# Hard safety boundary for every implementation agent, in addition to the agent rules.
# Pushing, PR/Issue changes and history rewriting belong to the orchestrator (or the human).
$script:ImplementationDeniedTools = @(
    'Bash(git push*)', 'Bash(gh pr *)', 'Bash(gh api *)', 'Bash(gh repo *)', 'Bash(gh issue edit*)', 'Bash(gh issue close*)',
    'Bash(gh issue comment*)', 'Bash(gh label *)', 'Bash(gh release *)', 'Bash(gh workflow *)',
    'Bash(git reset --hard*)', 'Bash(git clean *)', 'Bash(git stash*)', 'Bash(git rebase*)', 'Bash(git worktree *)',
    'Bash(git branch -D*)', 'Bash(git branch -d*)', 'Bash(git branch --delete*)', 'Bash(git switch *)',
    'Bash(git checkout main*)', 'Bash(git checkout integration/*)', 'Bash(git checkout -b*)', 'Bash(git checkout -B*)',
    'Bash(git update-ref *)', 'Bash(git filter-branch*)', 'Bash(git config *)',
    'PowerShell(git push*)', 'PowerShell(gh pr *)', 'PowerShell(gh api *)', 'PowerShell(gh repo *)', 'PowerShell(gh issue edit*)',
    'PowerShell(gh issue close*)', 'PowerShell(git reset --hard*)', 'PowerShell(git clean *)', 'PowerShell(git stash*)',
    'PowerShell(git rebase*)', 'PowerShell(git worktree *)', 'PowerShell(git branch -D*)', 'PowerShell(git switch *)',
    'PowerShell(git config *)', 'PowerShell(Remove-Item *)'
)
# Reviewers are read-only.
$script:ReviewerDeniedTools = @('Edit', 'Write', 'NotebookEdit',
    'Bash(git add*)', 'Bash(git commit*)', 'Bash(git checkout*)', 'Bash(git switch*)', 'Bash(git merge*)', 'Bash(git reset*)',
    'Bash(git stash*)', 'Bash(git push*)', 'Bash(git rebase*)', 'Bash(git clean*)', 'Bash(git restore*)', 'Bash(git rm*)',
    'Bash(gh pr *)', 'Bash(gh api *)', 'Bash(gh issue edit*)', 'Bash(gh issue close*)', 'Bash(gh issue comment*)',
    'Bash(rm *)', 'Bash(npm *)', 'Bash(npx *)', 'Bash(./gradlew*)', 'Bash(gradlew*)')

function Get-ImplementationDeniedTools { return $script:ImplementationDeniedTools }
function Get-ReviewerDeniedTools { return $script:ReviewerDeniedTools }

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

$script:LogFile = $null
function Set-RunLogFile([string]$Path) { $script:LogFile = $Path }

function Write-RunLog {
    param([string]$Message, [string]$Level = 'INFO', [ConsoleColor]$Color = [ConsoleColor]::Gray)
    $line = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + ' [' + $Level + '] ' + $Message
    Write-Host $line -ForegroundColor $Color
    if ($script:LogFile) { [System.IO.File]::AppendAllText($script:LogFile, $line + [Environment]::NewLine, $script:Utf8) }
}

function Write-TextFile([string]$Path, [string]$Text) {
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Text, $script:Utf8)
}

function Read-TextFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    return [System.IO.File]::ReadAllText($Path, $script:Utf8)
}

function Get-TextTail([string]$Text, [int]$Lines = 80) {
    if (-not $Text) { return '' }
    $all = $Text -split "`r?`n"
    if ($all.Count -le $Lines) { return ($all -join "`n") }
    return (($all | Select-Object -Last $Lines) -join "`n")
}

# ---------------------------------------------------------------------------
# Processes
# ---------------------------------------------------------------------------

function Resolve-Tool {
    param([string]$Name, [string[]]$Fallbacks = @())
    $cmd = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue |
        Where-Object { $_.Source -notlike '*.ps1' } | Select-Object -First 1
    if ($null -ne $cmd) { return $cmd.Source }
    foreach ($candidate in $Fallbacks) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            $dir = Split-Path -Parent $candidate
            if (($env:PATH -split ';') -notcontains $dir) { $env:PATH = $dir + ';' + $env:PATH }
            return $candidate
        }
    }
    return $null
}

# Runs a process with redirected output and a hard timeout (the whole process tree is killed).
function Invoke-LoggedProcess {
    param(
        [string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory,
        [string]$StdoutFile, [string]$StderrFile, [string]$StdinFile = '', [int]$TimeoutMinutes = 60
    )
    foreach ($f in @($StdoutFile, $StderrFile)) {
        $d = Split-Path -Parent $f
        if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    }
    $params = @{
        FilePath = $FilePath; WorkingDirectory = $WorkingDirectory; NoNewWindow = $true; PassThru = $true
        RedirectStandardOutput = $StdoutFile; RedirectStandardError = $StderrFile
    }
    $argLine = Join-CommandLine $Arguments
    if ($argLine) { $params.ArgumentList = $argLine }
    if ($StdinFile) { $params.RedirectStandardInput = $StdinFile }
    $p = Start-Process @params
    $null = $p.Handle   # keeps ExitCode available after exit (Windows PowerShell quirk)
    $timedOut = $false
    if (-not $p.WaitForExit([int]([Math]::Min([double]$TimeoutMinutes * 60000, [double][int]::MaxValue)))) {
        $timedOut = $true
        & taskkill.exe /PID $p.Id /T /F 2>&1 | Out-Null
        $p.WaitForExit(30000) | Out-Null
    } else {
        $p.WaitForExit()
    }
    $code = -1
    if (-not $timedOut) { $code = $p.ExitCode }
    return [pscustomobject]@{ ExitCode = $code; TimedOut = $timedOut }
}

# Starts a detached PowerShell script (a worker) with its output redirected; does not wait.
function Start-BackgroundScript {
    param([string]$ScriptPath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogDir)
    if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }
    $all = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments
    $p = Start-Process -FilePath 'powershell.exe' -ArgumentList (Join-CommandLine $all) -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $LogDir 'worker.stdout.log') -RedirectStandardError (Join-Path $LogDir 'worker.stderr.log')
    $null = $p.Handle
    return $p
}

function Test-ProcessAlive([int]$ProcessId) {
    if ($ProcessId -le 0) { return $false }
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    return ($null -ne $p -and -not $p.HasExited -and $p.ProcessName -match '^(powershell|pwsh)$')
}

# Runs a shell command line through cmd.exe (npm/gradle wrappers are .cmd/.bat files).
function Invoke-ShellStep {
    param([string]$Name, [string]$CommandLine, [string]$WorkingDirectory, [string]$LogDir, [int]$TimeoutMinutes = 45)
    $out = Join-Path $LogDir ($Name + '.log')
    $err = Join-Path $LogDir ($Name + '.err.log')
    $started = Get-Date
    $r = Invoke-LoggedProcess -FilePath $env:ComSpec -Arguments @('/d', '/s', '/c', $CommandLine) -WorkingDirectory $WorkingDirectory -StdoutFile $out -StderrFile $err -TimeoutMinutes $TimeoutMinutes
    $text = (Read-TextFile $out) + "`n" + (Read-TextFile $err)
    return [pscustomobject]@{
        Name = $Name; Command = $CommandLine; ExitCode = $r.ExitCode; TimedOut = $r.TimedOut
        Passed = (-not $r.TimedOut -and $r.ExitCode -eq 0); Log = $out; Tail = (Get-TextTail $text 60)
        Seconds = [int]((Get-Date) - $started).TotalSeconds
    }
}

# ---------------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------------

function Invoke-GitIn {
    param([string]$Path, [string[]]$Arguments, [switch]$AllowFailure)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & git.exe -C $Path @Arguments 2>&1 } finally { $ErrorActionPreference = $previous }
    $code = $LASTEXITCODE
    $text = (($output | ForEach-Object { [string]$_ }) -join "`n").TrimEnd()
    if (-not $AllowFailure -and $code -ne 0) {
        throw ('ORCHESTRATOR-STOP: git ' + ($Arguments -join ' ') + ' failed in ' + $Path + ' (exit ' + $code + "):`n" + $text)
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Get-GitHead([string]$Path) { return (Invoke-GitIn $Path @('rev-parse', 'HEAD')).Output.Trim() }

function Test-GitAncestor([string]$Path, [string]$Ancestor, [string]$Descendant) {
    return ((Invoke-GitIn $Path @('merge-base', '--is-ancestor', $Ancestor, $Descendant) -AllowFailure).ExitCode -eq 0)
}

function Test-GitRefExists([string]$Path, [string]$Ref) {
    return ((Invoke-GitIn $Path @('rev-parse', '--verify', '--quiet', ($Ref + '^{commit}')) -AllowFailure).ExitCode -eq 0)
}

function Get-GitDirtyStatus([string]$Path) { return (Invoke-GitIn $Path @('status', '--porcelain')).Output }

function Test-MergeInProgress([string]$Path) {
    return ((Invoke-GitIn $Path @('rev-parse', '--verify', '--quiet', 'MERGE_HEAD') -AllowFailure).ExitCode -eq 0)
}

function Get-CommitsAhead([string]$Path, [string]$BaseRef) {
    return [int](Invoke-GitIn $Path @('rev-list', '--count', ($BaseRef + '..HEAD'))).Output.Trim()
}

function Get-NetDiffPaths([string]$Path, [string]$BaseRef) {
    $out = (Invoke-GitIn $Path @('diff', '--name-only', ($BaseRef + '...HEAD'))).Output
    return @($out -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

# Per-file fingerprint of the net diff (BaseRef...HEAD): a hash of the added/removed lines only,
# like `git patch-id`, so it is stable across line shifts and sync merges of other work.
function Get-NetDiffFingerprint([string]$Path, [string]$BaseRef) {
    $fp = @{}
    $sha = [System.Security.Cryptography.SHA256]::Create()
    foreach ($file in (Get-NetDiffPaths $Path $BaseRef)) {
        $diff = (Invoke-GitIn $Path @('diff', '--no-color', '--no-ext-diff', ($BaseRef + '...HEAD'), '--', $file)).Output
        $content = @($diff -split "`n" | Where-Object { ($_.StartsWith('+') -or $_.StartsWith('-') -or $_.StartsWith('Binary')) -and -not $_.StartsWith('+++') -and -not $_.StartsWith('---') } |
            ForEach-Object { $_.TrimEnd() }) -join "`n"
        $bytes = $script:Utf8.GetBytes($content)
        $fp[$file] = ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').Substring(0, 16)
    }
    return $fp
}

# ---------------------------------------------------------------------------
# State files (local, git-ignored: .autonomous-dev/state/<key>.json)
# ---------------------------------------------------------------------------

function Read-ItemState([string]$StateFile) {
    if (-not (Test-Path -LiteralPath $StateFile)) { return $null }
    try { return (ConvertFrom-Json -InputObject (Read-TextFile $StateFile)) } catch { return $null }
}

function Save-ItemState([string]$StateFile, $State) {
    if ($State.PSObject.Properties.Name -contains 'UpdatedAt') { $State.UpdatedAt = (Get-Date).ToUniversalTime().ToString('o') }
    else { $State | Add-Member -NotePropertyName UpdatedAt -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o') }
    $tmp = $StateFile + '.tmp'
    Write-TextFile $tmp ($State | ConvertTo-Json -Depth 10)
    Move-Item -LiteralPath $tmp -Destination $StateFile -Force
}

function Set-StateProperty($State, [string]$Name, $Value) {
    if ($State.PSObject.Properties.Name -contains $Name) { $State.$Name = $Value }
    else { $State | Add-Member -NotePropertyName $Name -NotePropertyValue $Value }
}

# ---------------------------------------------------------------------------
# Fresh Claude Code agent processes
# ---------------------------------------------------------------------------

<#
    $Settings = @{ Claude; PermissionMode; Model; MaxBudgetUsd; AgentTimeoutMinutes }
    Returns @{ ExitCode; TimedOut; IsError; Text; CostUsd; SessionId; ResultFile }
#>
function Invoke-ClaudeAgent {
    param(
        [string]$Agent, [string]$Prompt, [string]$WorkingDirectory, [string]$LogDir, [string]$Name,
        [hashtable]$Settings, [string[]]$DeniedTools
    )
    if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }
    $promptFile = Join-Path $LogDir ($Name + '.prompt.md')
    $stdout = Join-Path $LogDir ($Name + '.json')
    $stderr = Join-Path $LogDir ($Name + '.stderr.log')
    $resultFile = Join-Path $LogDir ($Name + '.result.md')
    Write-TextFile $promptFile $Prompt

    $claudeArgs = @('-p', '--agent', $Agent, '--permission-mode', $Settings.PermissionMode, '--permission-prompts', 'none',
        '--output-format', 'json', '--name', $Name)
    if ($Settings.Model) { $claudeArgs += @('--model', $Settings.Model) }
    if ($Settings.MaxBudgetUsd -gt 0) { $claudeArgs += @('--max-budget-usd', ([decimal]$Settings.MaxBudgetUsd).ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
    if ($DeniedTools -and $DeniedTools.Count -gt 0) { $claudeArgs += '--disallowedTools'; $claudeArgs += $DeniedTools }  # variadic: stays last

    $started = Get-Date
    $r = Invoke-LoggedProcess -FilePath $Settings.Claude -Arguments $claudeArgs -WorkingDirectory $WorkingDirectory -StdoutFile $stdout -StderrFile $stderr -StdinFile $promptFile -TimeoutMinutes $Settings.AgentTimeoutMinutes
    $raw = Read-TextFile $stdout
    $text = ''; $isError = $true; $cost = $null; $session = ''
    try {
        $json = ConvertFrom-Json -InputObject $raw
        if ($json.PSObject.Properties.Name -contains 'result') { $text = [string]$json.result }
        if ($json.PSObject.Properties.Name -contains 'is_error') { $isError = [bool]$json.is_error } else { $isError = $false }
        if ($json.PSObject.Properties.Name -contains 'total_cost_usd') { $cost = $json.total_cost_usd }
        if ($json.PSObject.Properties.Name -contains 'session_id') { $session = [string]$json.session_id }
    } catch { $text = $raw }
    if ($r.TimedOut -or $r.ExitCode -ne 0) { $isError = $true }
    Write-TextFile $resultFile $text
    return [pscustomobject]@{
        ExitCode = $r.ExitCode; TimedOut = $r.TimedOut; IsError = $isError; Text = $text; CostUsd = $cost
        SessionId = $session; ResultFile = $resultFile; Minutes = [Math]::Round(((Get-Date) - $started).TotalMinutes, 1)
    }
}

# ---------------------------------------------------------------------------
# Deterministic verification gate (the repository's actual required commands:
# .github/workflows/ci.yml, backend/build.gradle.kts, mobile/package.json)
# ---------------------------------------------------------------------------

function Get-GateSteps {
    param($Areas, [switch]$Final, [string]$ExportDir = '')
    $steps = New-Object System.Collections.ArrayList
    if ($Areas.Backend -or $Final) {
        # Compilation with -Werror, unit/property tests, Testcontainers + Liquibase integration tests,
        # Spring Modulith/ArchUnit tests and the OpenAPI drift check (OpenApiContractTest).
        [void]$steps.Add(@{ Name = 'backend-build'; Dir = 'backend'; Command = 'gradlew.bat build --no-daemon'; Timeout = 60 })
    }
    if ($Areas.Mobile -or $Final) {
        [void]$steps.Add(@{ Name = 'mobile-install'; Dir = 'mobile'; Command = 'npm ci --no-audit --no-fund'; Timeout = 20; InstallStep = $true })
        [void]$steps.Add(@{ Name = 'mobile-api-client-drift'; Dir = 'mobile'; Command = 'npm run check:api'; Timeout = 10 })
        [void]$steps.Add(@{ Name = 'mobile-verify'; Dir = 'mobile'; Command = 'npm run verify'; Timeout = 20 })   # lint, format:check, typecheck, jest
    }
    if ($Areas.Orchestrator) {
        [void]$steps.Add(@{ Name = 'orchestrator-tests'; Dir = '.'; Command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1'; Timeout = 10 })
    }
    if ($Final) {
        [void]$steps.Add(@{ Name = 'mobile-expo-config'; Dir = 'mobile'; Command = 'npx expo config --type public --json'; Timeout = 10 })
        [void]$steps.Add(@{ Name = 'mobile-android-bundle'; Dir = 'mobile'; Command = ('npx expo export --platform android --output-dir ' + (ConvertTo-CommandLineArgument $ExportDir)); Timeout = 30 })
    }
    return , $steps
}

function Invoke-VerificationGate {
    param([string]$Worktree, $Areas, [string]$LogDir, [switch]$Final, [string]$StateDir)
    $exportDir = Join-Path $LogDir 'expo-export'
    $results = New-Object System.Collections.ArrayList
    $passed = $true
    foreach ($s in (Get-GateSteps -Areas $Areas -Final:$Final -ExportDir $exportDir)) {
        $dir = Join-Path $Worktree $s.Dir
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        if ($s.ContainsKey('InstallStep') -and $s.InstallStep) {
            # npm ci only when node_modules is missing or package-lock.json changed since the last install.
            $lock = Join-Path $dir 'package-lock.json'
            $marker = Join-Path $dir 'node_modules\.orchestrator-lock-hash'
            $hash = ''
            if (Test-Path -LiteralPath $lock) { $hash = (Get-FileHash -LiteralPath $lock -Algorithm SHA256).Hash }
            if ((Test-Path -LiteralPath $marker) -and (Read-TextFile $marker).Trim() -eq $hash) { continue }
            $r = Invoke-ShellStep -Name $s.Name -CommandLine $s.Command -WorkingDirectory $dir -LogDir $LogDir -TimeoutMinutes $s.Timeout
            [void]$results.Add($r)
            if (-not $r.Passed) { $passed = $false; break }
            Write-TextFile $marker $hash
            continue
        }
        $r = Invoke-ShellStep -Name $s.Name -CommandLine $s.Command -WorkingDirectory $dir -LogDir $LogDir -TimeoutMinutes $s.Timeout
        [void]$results.Add($r)
        Write-RunLog ('  gate ' + $s.Name + ': ' + $(if ($r.Passed) { 'PASSED' } else { 'FAILED (exit ' + $r.ExitCode + ')' }) + ' in ' + $r.Seconds + 's')
        if (-not $r.Passed) { $passed = $false; break }
    }
    return [pscustomobject]@{ Passed = $passed; Steps = $results }
}

function Format-GateFailure($Gate, [string]$Worktree) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($s in $Gate.Steps) {
        if ($s.Passed) { continue }
        [void]$sb.AppendLine('[BLOCKER] Verification gate failed: ' + $s.Name)
        [void]$sb.AppendLine('Command: (in ' + $Worktree + ') ' + $s.Command)
        if ($s.TimedOut) { [void]$sb.AppendLine('The command timed out.') }
        [void]$sb.AppendLine('Full log: ' + $s.Log)
        if ($s.Name -eq 'backend-build') { [void]$sb.AppendLine('Test reports: backend/build/reports/tests/test/index.html and backend/build/test-results/') }
        [void]$sb.AppendLine('Last output lines:')
        [void]$sb.AppendLine('```')
        [void]$sb.AppendLine($s.Tail)
        [void]$sb.AppendLine('```')
    }
    return $sb.ToString()
}

Export-ModuleMember -Function *
