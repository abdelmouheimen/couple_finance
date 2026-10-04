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
        [string]$StdoutFile, [string]$StderrFile, [string]$StdinFile = '', [int]$TimeoutMinutes = 60, [string]$PidFile = ''
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
    if ($PidFile) { Write-TextFile $PidFile ([string]$p.Id + '|' + (Get-ProcessStartTicks $p.Id)) }
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

# Process start time (UTC ticks, as a string for JSON) identifies a process across PID reuse
# (after a reboot a recorded PID can belong to an unrelated process).
function Get-ProcessStartTicks([int]$ProcessId) {
    try { return [string](Get-Process -Id $ProcessId -ErrorAction Stop).StartTime.ToUniversalTime().Ticks } catch { return '' }
}

function Test-ProcessAlive([int]$ProcessId, [string]$StartTicks = '', [string]$NamePattern = '^(powershell|pwsh)$') {
    if ($ProcessId -le 0) { return $false }
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $p -or $p.HasExited -or $p.ProcessName -notmatch $NamePattern) { return $false }
    if ($StartTicks) {
        try { if ([string]$p.StartTime.ToUniversalTime().Ticks -ne $StartTicks) { return $false } } catch { return $false }
    }
    return $true
}

# Lock file content: '<pid>|<start ticks>' (older locks hold only the PID).
function Test-LockHeld([string]$LockFile) {
    if (-not (Test-Path -LiteralPath $LockFile)) { return 0 }
    $parts = (Read-TextFile $LockFile).Trim() -split '\|'
    $other = 0
    [void][int]::TryParse($parts[0], [ref]$other)
    $ticks = ''
    if ($parts.Count -gt 1) { $ticks = $parts[1] }
    if ($other -ne $PID -and (Test-ProcessAlive $other $ticks)) { return $other }
    return 0
}

function Write-LockFile([string]$LockFile) { Write-TextFile $LockFile ([string]$PID + '|' + (Get-ProcessStartTicks $PID)) }

# An agent process recorded in a '<pid>|<start ticks>' file (orchestrator-run agents) that
# survived the death of the orchestrator: a restart must not start a second one beside it.
function Test-RecordedProcessAlive([string]$PidFile) {
    if (-not $PidFile -or -not (Test-Path -LiteralPath $PidFile)) { return $false }
    $parts = (Read-TextFile $PidFile).Trim() -split '\|'
    $p = 0
    [void][int]::TryParse($parts[0], [ref]$p)
    if ($parts.Count -lt 2 -or -not $parts[1]) { return $false }
    return (Test-ProcessAlive $p $parts[1] '.')
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

$script:GitReadOnly = $false
# DryRun: every Git command must be read-only (enforced, not just intended); optional
# index refreshes by 'git status' are disabled too.
function Set-GitReadOnly([bool]$Enabled) {
    $script:GitReadOnly = $Enabled
    if ($Enabled) { $env:GIT_OPTIONAL_LOCKS = '0' } else { Remove-Item Env:\GIT_OPTIONAL_LOCKS -ErrorAction SilentlyContinue }
}

function Invoke-GitIn {
    param([string]$Path, [string[]]$Arguments, [switch]$AllowFailure)
    if ($script:GitReadOnly -and -not (Test-ReadOnlyGitCommand $Arguments)) {
        throw ('ORCHESTRATOR-STOP: read-only mode refused a modifying git command: git ' + ($Arguments -join ' '))
    }
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

function Get-StateValue($State, [string]$Name, $Default = $null) {
    if ($null -eq $State) { return $Default }
    if ($State.PSObject.Properties.Name -contains $Name -and $null -ne $State.$Name) { return $State.$Name }
    return $Default
}

function Add-StateHistory($State, [string]$Text) {
    $h = @(Get-StateValue $State 'History' @())
    Set-StateProperty $State 'History' (@($h) + @((Get-Date).ToUniversalTime().ToString('s') + 'Z ' + $Text))
}

# Issue number -> @{ Phase; Reason; InterruptedPhase } from the state files (for the plan).
function Get-LocalPhaseMap([string]$StateDir) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $StateDir)) { return $map }
    foreach ($f in (Get-ChildItem -LiteralPath $StateDir -Filter 'issue-*.json')) {
        $s = Read-ItemState $f.FullName
        if ($null -ne $s) {
            $map[[int]$s.Issue] = @{ Phase = (ConvertTo-ItemPhase ([string]$s.Phase)); Reason = [string](Get-StateValue $s 'Reason' ''); InterruptedPhase = [string](Get-StateValue $s 'InterruptedPhase' '') }
        }
    }
    return $map
}

# ---------------------------------------------------------------------------
# Crash recovery: reconcile persisted checkpoints with Git reality
# ---------------------------------------------------------------------------

function Get-GitWorktreeMap([string]$Root) {
    $map = @{}
    $current = $null
    foreach ($line in ((Invoke-GitIn $Root @('worktree', 'list', '--porcelain')).Output -split "`n")) {
        if ($line -like 'worktree *') { $current = [System.IO.Path]::GetFullPath($line.Substring(9).Replace('/', '\')); $map[$current] = '' }
        elseif ($line -like 'branch *' -and $current) { $map[$current] = $line.Substring(7) -replace '^refs/heads/', '' }
    }
    return $map
}

# Issue numbers integrated on any of the given refs ('Integrates-Issue: #N' trailers).
function Get-IntegratedIssueSet([string]$Root, [string]$BaseRef, [string[]]$IntegrationRefs) {
    $set = @{}
    foreach ($ref in $IntegrationRefs) {
        if (-not $ref -or -not (Test-GitRefExists $Root $ref)) { continue }
        $range = $ref
        if ($BaseRef -and (Test-GitRefExists $Root $BaseRef)) { $range = $BaseRef + '..' + $ref }
        $found = Get-IntegratedIssueNumbers ((Invoke-GitIn $Root @('log', '--format=%B', $range)).Output)
        foreach ($k in $found.Keys) { $set[$k] = $true }
    }
    return $set
}

# Git reality: an Issue is integrated when an integration ref carries its trailer, any item
# when its validated head is reachable from an integration ref (also covers a merge whose
# state update was lost, and repair items which have no Issue number).
function Test-ItemIntegrated {
    param([string]$Root, $State, [string[]]$IntegrationRefs, [hashtable]$IntegratedIssues)
    if ([string](Get-StateValue $State 'Kind' '') -eq 'issue' -and $null -ne $IntegratedIssues -and $IntegratedIssues.ContainsKey([int]$State.Issue)) { return $true }
    $head = [string](Get-StateValue $State 'Head' '')
    if (-not $head -or -not (Test-GitRefExists $Root $head)) { return $false }
    foreach ($ref in $IntegrationRefs) {
        if ($ref -and (Test-GitRefExists $Root $ref) -and (Test-GitAncestor $Root $head $ref)) { return $true }
    }
    return $false
}

function Get-ItemRecoveryFacts {
    param([string]$Root, $State, [string]$BaseRef, [hashtable]$WorktreeMap, [bool]$Integrated)
    $facts = @{
        HasState = $true; Phase = [string](Get-StateValue $State 'Phase' ''); InterruptedPhase = [string](Get-StateValue $State 'InterruptedPhase' '')
        PendingFix = ($null -ne (Get-StateValue $State 'PendingFix' $null)); Integrated = $Integrated
        WorkerAlive = (Test-ProcessAlive ([int](Get-StateValue $State 'WorkerPid' 0)) ([string](Get-StateValue $State 'WorkerStartTicks' '')))
        WorktreeExists = $false; BranchExists = $false; Dirty = $false; CommitsAhead = 0; MergeInProgress = $false; HeadMatchesValidated = $false
    }
    $branch = [string](Get-StateValue $State 'Branch' '')
    $wt = [string](Get-StateValue $State 'Worktree' '')
    $headNow = ''
    if ($branch -and (Test-GitRefExists $Root ('refs/heads/' + $branch))) {
        $facts.BranchExists = $true
        $headNow = (Invoke-GitIn $Root @('rev-parse', ('refs/heads/' + $branch))).Output.Trim()
        if ($BaseRef -and (Test-GitRefExists $Root $BaseRef)) {
            $facts.CommitsAhead = [int](Invoke-GitIn $Root @('rev-list', '--count', ($BaseRef + '..refs/heads/' + $branch))).Output.Trim()
        }
    }
    if ($wt -and (Test-Path -LiteralPath $wt) -and $WorktreeMap.ContainsKey([System.IO.Path]::GetFullPath($wt))) {
        $facts.WorktreeExists = $true
        $facts.Dirty = [bool](Get-GitDirtyStatus $wt)
        $facts.MergeInProgress = Test-MergeInProgress $wt
        $headNow = Get-GitHead $wt
        if ($BaseRef -and (Test-GitRefExists $Root $BaseRef)) { $facts.CommitsAhead = Get-CommitsAhead $wt $BaseRef }
    }
    $validated = [string](Get-StateValue $State 'Head' '')
    $facts.HeadMatchesValidated = [bool]($validated -and $headNow -eq $validated)
    return $facts
}

<#
    Reconciles every state file with Git and returns one entry per item:
        @{ Key; Kind; Issue; Label; File; Before; Decision (Get-RecoveryAction) }
    Unless -ReadOnly, corrected phases are persisted (with a History line); nothing in Git
    or in a worktree is ever modified here.
#>
function Invoke-StateRecovery {
    param([string]$Root, [string]$StateDir, [string]$BaseRef, [string[]]$IntegrationRefs, [hashtable]$IntegratedIssues, [switch]$ReadOnly)
    $entries = New-Object System.Collections.ArrayList
    if (-not (Test-Path -LiteralPath $StateDir)) { return , $entries }
    $wtMap = Get-GitWorktreeMap $Root
    foreach ($f in (Get-ChildItem -LiteralPath $StateDir -Filter '*.json' | Sort-Object Name)) {
        $s = Read-ItemState $f.FullName
        if ($null -eq $s -or -not ($s.PSObject.Properties.Name -contains 'Key')) { continue }
        $integrated = Test-ItemIntegrated -Root $Root -State $s -IntegrationRefs $IntegrationRefs -IntegratedIssues $IntegratedIssues
        $facts = Get-ItemRecoveryFacts -Root $Root -State $s -BaseRef $BaseRef -WorktreeMap $wtMap -Integrated $integrated
        $decision = Get-RecoveryAction $facts
        $before = ConvertTo-ItemPhase ([string]$s.Phase)
        $beforeIp = [string](Get-StateValue $s 'InterruptedPhase' '')
        if (-not $ReadOnly -and $decision.Action -ne 'adopt' -and ($before -ne $decision.Phase -or $beforeIp -ne $decision.InterruptedPhase -or [string]$s.Phase -cne $before)) {
            $s.Phase = $decision.Phase
            Set-StateProperty $s 'InterruptedPhase' $decision.InterruptedPhase
            if ($decision.Phase -eq 'INTEGRATED') { Set-StateProperty $s 'PendingFix' $null; Set-StateProperty $s 'Reason' '' }
            if ($decision.Phase -eq 'BLOCKED' -and $before -ne 'BLOCKED') { Set-StateProperty $s 'Reason' $decision.Detail }
            if ($decision.Action -eq 'resume-review' -and $decision.InterruptedPhase -eq 'REVIEWING') { Set-StateProperty $s 'PendingFix' $null }
            Add-StateHistory $s ('recovery: ' + $before + ' -> ' + $decision.Phase + $(if ($decision.InterruptedPhase) { ' (' + $decision.InterruptedPhase + ')' } else { '' }) + ', ' + $decision.Action + ': ' + $decision.Detail)
            Save-ItemState $f.FullName $s
        }
        $label = if ([string]$s.Kind -eq 'issue') { '#' + $s.Issue } else { [string]$s.Key }
        [void]$entries.Add([pscustomobject]@{ Key = [string]$s.Key; Kind = [string]$s.Kind; Issue = [int](Get-StateValue $s 'Issue' 0); Label = $label; File = $f.FullName; Before = $before; Decision = $decision })
    }
    return , $entries
}

<#
    Merges the validated commit $Head into the integration worktree, idempotently:
      - $Head already reachable from HEAD           -> 'already-integrated' (no new commit)
      - an interrupted merge of exactly $Head        -> concluded with $Message ('concluded')
      - otherwise                                   -> merge --no-ff --signoff ('merged')
    Any other merge in progress, or a merged tree different from the validated tree, stops.
#>
function Invoke-IdempotentIntegrationMerge {
    param([string]$IntegrationWt, [string]$Head, [string]$Message)
    $result = ''
    if (Test-MergeInProgress $IntegrationWt) {
        $mergeHead = (Invoke-GitIn $IntegrationWt @('rev-parse', 'MERGE_HEAD')).Output.Trim()
        if ($mergeHead -ne $Head) { throw ('ORCHESTRATOR-STOP: an unrelated merge (' + $mergeHead + ') is in progress in ' + $IntegrationWt + '; inspect it manually') }
        $unmerged = (Invoke-GitIn $IntegrationWt @('diff', '--name-only', '--diff-filter=U')).Output
        if ($unmerged) { throw ('ORCHESTRATOR-STOP: the interrupted integration merge in ' + $IntegrationWt + ' has conflicts; inspect it manually') }
        [void](Invoke-GitIn $IntegrationWt @('commit', '--signoff', '-m', $Message))
        $result = 'concluded'
    } elseif (Test-GitAncestor $IntegrationWt $Head 'HEAD') {
        return 'already-integrated'
    } else {
        [void](Invoke-GitIn $IntegrationWt @('merge', '--no-ff', '--signoff', '-m', $Message, $Head))
        $result = 'merged'
    }
    if ((Invoke-GitIn $IntegrationWt @('diff', '--quiet', 'HEAD', $Head) -AllowFailure).ExitCode -ne 0) {
        throw ('ORCHESTRATOR-STOP: the integration merge of ' + $Head + ' does not equal the validated tree; nothing is pushed. Inspect ' + $IntegrationWt + '.')
    }
    return $result
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
        [hashtable]$Settings, [string[]]$DeniedTools, [string]$PidFile = ''
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
    $r = Invoke-LoggedProcess -FilePath $Settings.Claude -Arguments $claudeArgs -WorkingDirectory $WorkingDirectory -StdoutFile $stdout -StderrFile $stderr -StdinFile $promptFile -TimeoutMinutes $Settings.AgentTimeoutMinutes -PidFile $PidFile
    if ($PidFile -and (Test-Path -LiteralPath $PidFile)) { Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue }
    $raw = Read-TextFile $stdout
    $text = ''; $isError = $true; $cost = $null; $session = ''; $apiStatus = 0
    try {
        $json = ConvertFrom-Json -InputObject $raw
        if ($json.PSObject.Properties.Name -contains 'result') { $text = [string]$json.result }
        if ($json.PSObject.Properties.Name -contains 'is_error') { $isError = [bool]$json.is_error } else { $isError = $false }
        if ($json.PSObject.Properties.Name -contains 'total_cost_usd') { $cost = $json.total_cost_usd }
        if ($json.PSObject.Properties.Name -contains 'session_id') { $session = [string]$json.session_id }
        if ($json.PSObject.Properties.Name -contains 'api_error_status' -and $null -ne $json.api_error_status) { $apiStatus = [int]$json.api_error_status }
    } catch { $text = $raw }
    if ($r.TimedOut -or $r.ExitCode -ne 0) { $isError = $true }
    Write-TextFile $resultFile $text
    return [pscustomobject]@{
        ExitCode = $r.ExitCode; TimedOut = $r.TimedOut; IsError = $isError; Text = $text; CostUsd = $cost
        SessionId = $session; ResultFile = $resultFile; Minutes = [Math]::Round(((Get-Date) - $started).TotalMinutes, 1)
        StderrTail = (Get-TextTail (Read-TextFile $stderr) 40); ApiErrorStatus = $apiStatus
    }
}

# A failed agent process (non-zero exit, is_error or timeout) that produced no
# ORCHESTRATOR-RESULT / review summary: '' when the run is usable, else AUTH | TRANSIENT | CRASH.
function Get-AgentRunFailure($Run, [bool]$HasResult) {
    if ($HasResult -and -not $Run.TimedOut) { return '' }
    if (-not $Run.IsError -and -not $Run.TimedOut) { return '' }
    $apiStatus = 0
    if ($Run.PSObject.Properties.Name -contains 'ApiErrorStatus') { $apiStatus = [int]$Run.ApiErrorStatus }
    return (Get-AgentFailureKind -Text ([string]$Run.Text + "`n" + [string]$Run.StderrTail) -TimedOut ([bool]$Run.TimedOut) -ApiErrorStatus $apiStatus)
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
