<#
.SYNOPSIS
    Autonomous MVP orchestrator for CoupleFinance: builds the approved backlog DAG with parallel,
    isolated, reviewed Claude Code agents and stops when the integrated mobile MVP is ready for
    HUMAN ACCEPTANCE TESTING. Never touches main.

.DESCRIPTION
    1. Discovers open approved Issues and their '## Dependencies' and builds the dependency DAG.
    2. Runs RUNNABLE Issues concurrently (-Parallelism): one fresh Claude Code process per role,
       one Git worktree and one feature/<N>-<slug> branch per Issue (scripts/orchestrator/Invoke-IssueWorker.ps1).
    3. Each Issue goes through: implement -> deterministic gate -> code review -> security review
       (when relevant) -> mobile UX review (when relevant) -> fix -> ... (bounded by -MaxReviewCycles).
    4. Approved Issues are integrated one at a time into the integration branch (default
       integration/mvp): sync with the integration tip (conflicts resolved by an agent, then
       re-gated / re-reviewed), merge --no-ff --signoff with an 'Integrates-Issue: #N' trailer, push.
       Integration unlocks dependent Issues. A blocked Issue only stops its own dependents.
    5. When the DAG is complete: full backend + mobile validation, Expo config + Android bundle,
       API acceptance journey (integration-validator), holistic mobile UX review with bounded fixes,
       optional Android APK, then a Pull Request integration/mvp -> main for the HUMAN, and a
       HUMAN-ACCEPTANCE.md report with the exact launch commands.

    Safety: never merges into / pushes to main, never force-pushes, never resets/cleans/stashes,
    never deletes a worktree containing uncommitted work, never edits Issues or labels.
    Resumable: state is derived from Git (integration trailers, branches, worktrees), GitHub
    (merged PRs) and local state files (.autonomous-dev/state); rerunning continues safely.

    Exit codes: 0 = dry run done / READY FOR HUMAN ACCEPTANCE TESTING
                1 = failure / unsafe state
                2 = stopped: human input required (needs approval, blocked Issues, remaining findings)

.EXAMPLE
    .\scripts\autonomous-development.ps1 -DryRun

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\autonomous-development.ps1 -Target mvp -Parallelism 3 -AutoMergeIntegration
#>
[CmdletBinding()]
param(
    [switch]$DryRun,

    # 'mvp' = every open approved implementation Issue of the backlog DAG (narrow with -Issues).
    [ValidateSet('mvp')]
    [string]$Target = 'mvp',

    # Restrict the run to these Issues plus their open dependency closure.
    [int[]]$Issues = @(),
    [int[]]$ExcludeIssues = @(),

    [ValidateRange(1, 8)]
    [int]$Parallelism = 3,

    # Maximum number of NEW Issues started by this run (0 = unlimited).
    [ValidateRange(0, 100)]
    [int]$MaxIssues = 0,

    [ValidateRange(1, 6)]
    [int]$MaxReviewCycles = 3,

    # Holistic mobile UX review/fix rounds in the final acceptance phase.
    [ValidateRange(0, 4)]
    [int]$MaxUxCycles = 2,

    # Repair rounds for final validation / acceptance-journey failures.
    [ValidateRange(0, 4)]
    [int]$MaxRepairCycles = 2,

    [ValidateRange(10, 480)]
    [int]$AgentTimeoutMinutes = 90,

    # Explicit consent to merge reviewed Issue branches automatically into the integration branch.
    # Required for a real run. main is never touched.
    [switch]$AutoMergeIntegration,

    # Retry Issues that a previous run marked blocked (their work is kept and resumed).
    [switch]$RetryBlocked,

    # DryRun only: preview the DAG/waves as if every Issue carried status:ready and had no open
    # question (approval gaps are still listed). Never affects a real run.
    [switch]$AssumeApproved,

    [switch]$SkipAcceptance,

    # Build a debug APK with 'expo prebuild' + Gradle when an Android SDK is installed.
    [switch]$BuildAndroidApk,

    [string]$IntegrationBranch = 'integration/mvp',

    # Default: a sibling directory '<repo>.worktrees' (keeps worktrees out of the repository so
    # agents do not load the parent CLAUDE.md twice and tools never scan other worktrees).
    [string]$WorktreeRoot = '',

    [string]$ExpectedRepo = 'abdelmouheimen/couple_finance',
    [string]$BaseBranch = 'main',

    [ValidateSet('auto', 'acceptEdits', 'dontAsk', 'bypassPermissions')]
    [string]$PermissionMode = 'auto',

    [string]$Model = '',

    # Spend cap per agent process (--max-budget-usd). 0 = no cap.
    [decimal]$MaxBudgetUsd = 0,

    [ValidateRange(5, 600)]
    [int]$PollSeconds = 30
)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
Import-Module (Join-Path $PSScriptRoot 'orchestrator\AutonomousDev.Planning.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'orchestrator\AutonomousDev.Runtime.psm1') -Force

$StopPrefix = 'ORCHESTRATOR-STOP: '
$script:ExitCode = 0
$script:Workers = @{}          # key -> @{ Process (or $null when adopted); Pid; Key; StateFile }
$script:Started = 0
$script:LaunchCount = @{}
$script:Integrated = New-Object System.Collections.ArrayList
$script:Notes = New-Object System.Collections.ArrayList
$IntegrationRef = 'origin/' + $IntegrationBranch

function Stop-Orchestrator([string]$Message, [int]$Code = 1) { $script:ExitCode = $Code; throw ($StopPrefix + $Message) }
function Write-Section([string]$Text) { Write-Host ''; Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan }
function Write-Info([string]$Text) { Write-Host $Text }
function Write-Warn([string]$Text) { Write-Host ('WARNING: ' + $Text) -ForegroundColor Yellow }
function Get-Prop($Obj, [string]$Name, $Default = $null) {
    if ($null -eq $Obj) { return $Default }
    if ($Obj -is [hashtable]) { if ($Obj.ContainsKey($Name)) { return $Obj[$Name] } else { return $Default } }
    if ($Obj.PSObject.Properties.Name -contains $Name) { return $Obj.$Name }
    return $Default
}

# ---------------------------------------------------------------------------
# Prerequisites and repository
# ---------------------------------------------------------------------------

function Invoke-Exe([string]$Exe, [string[]]$Arguments, [switch]$AllowFailure) {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $output = & $Exe @Arguments 2>&1 } finally { $ErrorActionPreference = $previous }
    $code = $LASTEXITCODE
    $text = (($output | ForEach-Object { [string]$_ }) -join "`n").TrimEnd()
    if (-not $AllowFailure -and $code -ne 0) { Stop-Orchestrator ('Command failed (exit ' + $code + '): ' + (Split-Path -Leaf $Exe) + ' ' + ($Arguments -join ' ') + "`n" + $text) }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}
function Invoke-GhJson([string[]]$Arguments) {
    $r = Invoke-Exe $script:Gh $Arguments
    $list = New-Object System.Collections.ArrayList
    if (-not [string]::IsNullOrWhiteSpace($r.Output)) { foreach ($i in (ConvertFrom-Json -InputObject $r.Output)) { [void]$list.Add($i) } }
    return , $list
}
function Invoke-RootGit([string[]]$Arguments, [switch]$AllowFailure) { return Invoke-GitIn -Path $script:Root -Arguments $Arguments -AllowFailure:$AllowFailure }

function Assert-Prerequisites {
    Write-Section 'Prerequisites'
    $script:GitExe = Resolve-Tool 'git'
    $script:Gh = Resolve-Tool 'gh' @((Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe'), (Join-Path $env:LOCALAPPDATA 'Programs\GitHub CLI\gh.exe'))
    $script:Claude = Resolve-Tool 'claude' @((Join-Path $env:APPDATA 'npm\claude.cmd'), (Join-Path $env:USERPROFILE '.local\bin\claude.exe'))
    if (-not $script:GitExe) { Stop-Orchestrator 'git is not available.' }
    if (-not $script:Gh) { Stop-Orchestrator 'GitHub CLI (gh) is not available.' }
    Write-Info ('git    : ' + (Invoke-Exe $script:GitExe @('--version')).Output)
    Write-Info ('gh     : ' + ((Invoke-Exe $script:Gh @('--version')).Output -split "`n")[0])
    if ($script:Claude) { Write-Info ('claude : ' + (Invoke-Exe $script:Claude @('--version')).Output) }
    elseif ($DryRun) { Write-Warn 'claude CLI not found (required for a real run).' }
    else { Stop-Orchestrator 'claude CLI is not available.' }

    foreach ($tool in @(@{ N = 'node'; W = 'mobile gates' }, @{ N = 'npm'; W = 'mobile gates' }, @{ N = 'java'; W = 'Gradle (JDK 17+ to run the wrapper)' })) {
        if (-not (Resolve-Tool $tool.N)) { if ($DryRun) { Write-Warn ($tool.N + ' not found (' + $tool.W + ').') } else { Stop-Orchestrator ($tool.N + ' is required for ' + $tool.W + '.') } }
    }
    $docker = Resolve-Tool 'docker'
    $dockerOk = $false
    if ($docker) { $dockerOk = ((Invoke-Exe $docker @('info', '--format', '{{.ServerVersion}}') -AllowFailure).ExitCode -eq 0) }
    Write-Info ('docker : ' + $(if ($dockerOk) { 'daemon running' } else { 'NOT running' }))
    if (-not $dockerOk -and -not $DryRun) { Stop-Orchestrator 'Docker must be running (Testcontainers integration tests are part of every backend gate).' }

    if ((Invoke-Exe $script:Gh @('auth', 'status') -AllowFailure).ExitCode -ne 0) { Stop-Orchestrator 'GitHub CLI is not authenticated (gh auth status failed).' }
    Write-Info 'GitHub : authenticated'

    $script:Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
    $top = Invoke-GitIn -Path $script:Root -Arguments @('rev-parse', '--show-toplevel') -AllowFailure
    if ($top.ExitCode -ne 0 -or $top.Output.Trim().Replace('/', '\').TrimEnd('\') -ne $script:Root) { Stop-Orchestrator ('Run the script from the primary CoupleFinance checkout (' + $script:Root + ').') }
    Set-Location -LiteralPath $script:Root

    $repo = (Invoke-GhJson @('repo', 'view', '--json', 'nameWithOwner,defaultBranchRef'))[0]
    if ($repo.nameWithOwner -ne $ExpectedRepo) { Stop-Orchestrator ("GitHub repository is '" + $repo.nameWithOwner + "', expected '" + $ExpectedRepo + "'.") }
    if ($repo.defaultBranchRef.name -ne $BaseBranch) { Stop-Orchestrator ("Default branch is '" + $repo.defaultBranchRef.name + "', expected '" + $BaseBranch + "'.") }
    if ($IntegrationBranch -eq $BaseBranch -or $IntegrationBranch -notmatch '^integration/[A-Za-z0-9._-]+$') { Stop-Orchestrator ("Integration branch must be 'integration/<name>' and never " + $BaseBranch + '.') }
    Write-Info ('Repo   : ' + $repo.nameWithOwner + ' (' + $script:Root + ')')

    $script:AutoDir = Join-Path $script:Root '.autonomous-dev'
    $script:StateDir = Join-Path $script:AutoDir 'state'
    if (-not $WorktreeRoot) { $script:WtRoot = Join-Path (Split-Path -Parent $script:Root) ((Split-Path -Leaf $script:Root) + '.worktrees') }
    else { $script:WtRoot = [System.IO.Path]::GetFullPath($WorktreeRoot) }
    $script:IntegrationWt = Join-Path $script:WtRoot 'integration'
    $script:Settings = @{ Claude = $script:Claude; PermissionMode = $PermissionMode; Model = $Model; MaxBudgetUsd = $MaxBudgetUsd; AgentTimeoutMinutes = $AgentTimeoutMinutes }

    $status = (Invoke-RootGit @('status', '--porcelain')).Output
    if ($status) { Write-Warn ("The primary checkout has local changes (not touched: all work happens in worktrees):`n" + $status) }
}

function Enter-Lock {
    New-Item -ItemType Directory -Force -Path $script:StateDir | Out-Null
    $script:LockFile = Join-Path $script:AutoDir 'orchestrator.lock'
    if (Test-Path -LiteralPath $script:LockFile) {
        $other = 0
        [void][int]::TryParse((Read-TextFile $script:LockFile).Trim(), [ref]$other)
        if (Test-ProcessAlive $other) { Stop-Orchestrator ('Another orchestrator is running (PID ' + $other + ').') }
    }
    Write-TextFile $script:LockFile ([string]$PID)
}

# ---------------------------------------------------------------------------
# Integration branch
# ---------------------------------------------------------------------------

function Get-WorktreeMap {
    $map = @{}
    $current = $null
    foreach ($line in ((Invoke-RootGit @('worktree', 'list', '--porcelain')).Output -split "`n")) {
        if ($line -like 'worktree *') { $current = [System.IO.Path]::GetFullPath($line.Substring(9).Replace('/', '\')); $map[$current] = '' }
        elseif ($line -like 'branch *' -and $current) { $map[$current] = $line.Substring(7) -replace '^refs/heads/', '' }
    }
    return $map
}

function Ensure-Worktree([string]$Path, [string]$Branch, [string]$StartRef) {
    [void](Invoke-RootGit @('worktree', 'prune'))
    $map = Get-WorktreeMap
    $full = [System.IO.Path]::GetFullPath($Path)
    if ($map.ContainsKey($full)) {
        if ($map[$full] -ne $Branch) { throw ($StopPrefix + 'worktree ' + $full + ' has branch ' + $map[$full] + ', expected ' + $Branch) }
        return $full
    }
    if (Test-Path -LiteralPath $full) { throw ($StopPrefix + 'directory ' + $full + ' exists but is not a registered worktree; it is left untouched - move it away') }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $full) | Out-Null
    if (Test-GitRefExists $script:Root ('refs/heads/' + $Branch)) { [void](Invoke-RootGit @('worktree', 'add', $full, $Branch)) }
    elseif (Test-GitRefExists $script:Root ('refs/remotes/origin/' + $Branch)) { [void](Invoke-RootGit @('worktree', 'add', '-b', $Branch, $full, ('origin/' + $Branch))) }
    else { [void](Invoke-RootGit @('worktree', 'add', '-b', $Branch, $full, $StartRef)) }
    return $full
}

function Sync-IntegrationWorktree {
    # Local integration branch == origin, fast-forward only; a local-only validated merge (push
    # interrupted) is pushed; divergence stops the run.
    [void](Invoke-RootGit @('fetch', '--prune', 'origin'))
    if (Test-MergeInProgress $script:IntegrationWt) { Stop-Orchestrator ('A merge is in progress in ' + $script:IntegrationWt + '; inspect it manually.') }
    if (Get-GitDirtyStatus $script:IntegrationWt) { Stop-Orchestrator ('The integration worktree ' + $script:IntegrationWt + ' has local changes; nothing is discarded automatically.') }
    $local = Get-GitHead $script:IntegrationWt
    $remote = (Invoke-RootGit @('rev-parse', $IntegrationRef)).Output.Trim()
    if ($local -eq $remote) { return }
    if (Test-GitAncestor $script:Root $local $remote) { [void](Invoke-GitIn $script:IntegrationWt @('merge', '--ff-only', $IntegrationRef)); return }
    if (Test-GitAncestor $script:Root $remote $local) {
        Write-Warn ('Local ' + $IntegrationBranch + ' has validated commits not pushed yet; pushing.')
        [void](Invoke-GitIn $script:IntegrationWt @('push', 'origin', $IntegrationBranch))
        [void](Invoke-RootGit @('fetch', 'origin'))
        return
    }
    Stop-Orchestrator ('Local and remote ' + $IntegrationBranch + ' diverged; resolve manually (never force-pushed automatically).')
}

function Initialize-Integration {
    Write-Section ('Integration branch ' + $IntegrationBranch)
    if (-not (Test-GitRefExists $script:Root $IntegrationRef)) {
        if (-not (Test-GitRefExists $script:Root ('refs/heads/' + $IntegrationBranch))) {
            [void](Invoke-RootGit @('branch', '--no-track', $IntegrationBranch, ('origin/' + $BaseBranch)))
        }
        [void](Invoke-RootGit @('push', 'origin', ($IntegrationBranch + ':refs/heads/' + $IntegrationBranch)))
        [void](Invoke-RootGit @('fetch', 'origin'))
        Write-Info ('Created ' + $IntegrationBranch + ' from origin/' + $BaseBranch + ' and pushed it.')
    }
    if (-not (Test-GitRefExists $script:Root ('refs/heads/' + $IntegrationBranch))) { [void](Invoke-RootGit @('branch', '--no-track', $IntegrationBranch, $IntegrationRef)) }
    $script:IntegrationWt = Ensure-Worktree $script:IntegrationWt $IntegrationBranch $IntegrationRef
    Sync-IntegrationWorktree
    Write-Info ('Worktree: ' + $script:IntegrationWt + ' at ' + (Get-GitHead $script:IntegrationWt).Substring(0, 10))

    # Keep the integration branch current with main (human-merged work). Clean merges are gated;
    # conflicts and gate failures become integration-repair items for an agent.
    if (-not (Test-GitAncestor $script:Root ('origin/' + $BaseBranch) $IntegrationRef)) {
        $m = Invoke-GitIn $script:IntegrationWt @('merge', '--no-ff', '--no-commit', ('origin/' + $BaseBranch)) -AllowFailure
        if ($m.ExitCode -ne 0) {
            if (Test-MergeInProgress $script:IntegrationWt) { [void](Invoke-GitIn $script:IntegrationWt @('merge', '--abort')) }
            Write-Warn ('Merging ' + $BaseBranch + ' into ' + $IntegrationBranch + ' conflicts; queued as an integration-repair item.')
            Add-RepairItem 'main-sync' ('Merge origin/' + $BaseBranch + ' into the integration branch') ("Run ``git merge --no-ff --signoff origin/$BaseBranch`` on this branch and resolve every conflict, keeping both the human-merged work of $BaseBranch and the integrated MVP work. Regenerate generated files (api/openapi.yaml via ``gradlew.bat updateOpenApi``, the mobile client via ``npm run generate:api``) instead of hand-merging them. Then build and test, and commit the merge with --signoff.")
            return
        }
        # The clean merge is staged but not committed: gate it, then commit+push or abort (no history rewrite).
        $areas = Get-ChangedAreas @((Invoke-GitIn $script:IntegrationWt @('diff', '--cached', '--name-only', 'HEAD')).Output -split "`n")
        $gate = Invoke-VerificationGate -Worktree $script:IntegrationWt -Areas $areas -LogDir (Join-Path $script:RunDir 'main-sync-gate')
        if ($gate.Passed) {
            [void](Invoke-GitIn $script:IntegrationWt @('commit', '--no-edit', '--signoff', '-m', ('chore(integration): sync ' + $BaseBranch + ' into ' + $IntegrationBranch)))
            [void](Invoke-GitIn $script:IntegrationWt @('push', 'origin', $IntegrationBranch))
            [void](Invoke-RootGit @('fetch', 'origin'))
            Write-Info ('Synced ' + $BaseBranch + ' into ' + $IntegrationBranch + ' (gate passed).')
        } else {
            [void](Invoke-GitIn $script:IntegrationWt @('merge', '--abort'))
            Write-Warn ('Main sync breaks the gate; queued as an integration-repair item.')
            Add-RepairItem 'main-sync' ('Merge origin/' + $BaseBranch + ' into the integration branch') ("Run ``git merge --no-ff --signoff origin/$BaseBranch`` on this branch, then make the combined code pass the gate. Failures seen after a plain merge:`n" + (Format-GateFailure $gate $script:IntegrationWt))
        }
    }
}

# ---------------------------------------------------------------------------
# GitHub / Git snapshot -> plan
# ---------------------------------------------------------------------------

$script:AncestorCache = @{}
function Test-InMain([string]$Oid) {
    if (-not $Oid) { return $false }
    if (-not $script:AncestorCache.ContainsKey($Oid)) { $script:AncestorCache[$Oid] = Test-GitAncestor $script:Root $Oid ('origin/' + $BaseBranch) }
    return $script:AncestorCache[$Oid]
}

function Get-Snapshot {
    $open = Invoke-GhJson @('issue', 'list', '--state', 'open', '--limit', '500', '--json', 'number,title,labels,body,url')
    $all = Invoke-GhJson @('issue', 'list', '--state', 'all', '--limit', '1000', '--json', 'number,title,state')
    $openPrs = Invoke-GhJson @('pr', 'list', '--state', 'open', '--limit', '200', '--json', 'number,url,headRefName,baseRefName,body,closingIssuesReferences')
    $mergedPrs = Invoke-GhJson @('pr', 'list', '--state', 'merged', '--base', $BaseBranch, '--limit', '1000', '--json', 'number,headRefName,body,closingIssuesReferences,mergeCommit')

    $idx = @{}; $states = @{}
    foreach ($i in $all) { $states[[int]$i.number] = $i; $id = Get-IssueIdentifier $i.title; if ($id -and -not $idx.ContainsKey($id)) { $idx[$id] = [int]$i.number } }

    $integrated = @{}
    if (Test-GitRefExists $script:Root $IntegrationRef) {
        $log = (Invoke-RootGit @('log', '--format=%B', ('origin/' + $BaseBranch + '..' + $IntegrationRef))).Output
        $integrated = Get-IntegratedIssueNumbers $log
    }
    $done = @{}; $closedNotDone = @{}; $humanPr = @{}
    foreach ($n in $integrated.Keys) { $done[$n] = 'integrated into ' + $IntegrationBranch }
    foreach ($n in $states.Keys) {
        if ($done.ContainsKey($n)) { continue }
        foreach ($pr in $mergedPrs) {
            if ((Test-PrClosesIssue $pr $n) -and $pr.mergeCommit -and (Test-InMain $pr.mergeCommit.oid)) { $done[$n] = 'merged into ' + $BaseBranch + ' by PR #' + $pr.number; break }
        }
        if (-not $done.ContainsKey($n) -and $states[$n].state -ne 'OPEN') { $closedNotDone[$n] = 'CLOSED without a merged or integrated implementation' }
    }
    foreach ($i in $open) {
        $n = [int]$i.number
        $pr = $openPrs | Where-Object { $_.headRefName -ne $IntegrationBranch -and (Test-PrClosesIssue $_ $n) } | Select-Object -First 1
        if ($null -ne $pr) { $humanPr[$n] = [int]$pr.number }
    }
    return @{ Open = $open; IdentifierIndex = $idx; Done = $done; ClosedNotDone = $closedNotDone; HumanPr = $humanPr; Integrated = $integrated; OpenPrs = $openPrs }
}

function Get-StateFile([string]$Key) { return (Join-Path $script:StateDir ($Key + '.json')) }

function Get-LocalStates {
    $map = @{}
    if (-not (Test-Path -LiteralPath $script:StateDir)) { return $map }
    foreach ($f in (Get-ChildItem -LiteralPath $script:StateDir -Filter 'issue-*.json')) {
        $s = Read-ItemState $f.FullName
        if ($null -ne $s) { $map[[int]$s.Issue] = @{ Phase = [string]$s.Phase; Reason = [string](Get-Prop $s 'Reason' '') } }
    }
    return $map
}

function Get-Plan($Snapshot) {
    $running = @{}
    foreach ($w in $script:Workers.Values) { if ($w.Kind -eq 'issue') { $running[[int]$w.Issue] = $true } }
    $openIssues = @($Snapshot.Open)
    $ctx = @{
        IdentifierIndex = $Snapshot.IdentifierIndex; Done = $Snapshot.Done; ClosedNotDone = $Snapshot.ClosedNotDone
        HumanPr = $Snapshot.HumanPr; LocalState = (Get-LocalStates); Running = $running; Exclude = $ExcludeIssues
    }
    if ($Issues.Count -gt 0) { $ctx.Target = $Issues }
    if ($DryRun -and $AssumeApproved) { $ctx.AssumeApproved = $true }
    return New-ExecutionPlan -Issues $openIssues -Context $ctx
}

# ---------------------------------------------------------------------------
# Workers
# ---------------------------------------------------------------------------

function Find-ExistingIssueBranch([int]$Number) {
    $refs = (Invoke-RootGit @('for-each-ref', '--format=%(refname)', 'refs/heads', 'refs/remotes/origin')).Output -split "`n"
    foreach ($r in $refs) {
        $name = $r -replace '^refs/heads/', '' -replace '^refs/remotes/origin/', ''
        if ($name -match ('^(feature|fix)/' + $Number + '-')) { return $name }
    }
    return ''
}

function Start-Worker([hashtable]$Item) {
    $key = $Item.Key
    if (-not $script:LaunchCount.ContainsKey($key)) { $script:LaunchCount[$key] = 0 }
    $script:LaunchCount[$key]++
    if ($script:LaunchCount[$key] -gt 8) {
        # Guard against a worker that dies before it can record anything (would relaunch forever).
        $st = Read-ItemState (Get-StateFile $key)
        if ($null -eq $st) { $st = [pscustomobject]@{ Key = $key; Kind = $Item.Kind; Issue = $Item.Issue; Title = $Item.Title; Branch = $Item.Branch; Worktree = $Item.Worktree; Phase = 'blocked'; Reason = '' } }
        $st.Phase = 'blocked'; Set-StateProperty $st 'Reason' ('worker launched ' + ($script:LaunchCount[$key] - 1) + ' times in this run without finishing; see ' + (Join-Path $script:RunDir $key))
        Save-ItemState (Get-StateFile $key) $st
        Write-RunLog ($key + ': BLOCKED - ' + $st.Reason) 'WARN' Yellow
        return
    }
    $logDir = Join-Path $script:RunDir $key
    $params = [ordered]@{
        Key = $key; Kind = $Item.Kind; Issue = $Item.Issue; Title = $Item.Title; Labels = @($Item.Labels); Branch = $Item.Branch
        Worktree = $Item.Worktree; BaseRef = $IntegrationRef; LogDir = $logDir; StateFile = (Get-StateFile $key)
        DeveloperAgent = $Item.DeveloperAgent; TaskText = $Item.TaskText; MaxReviewCycles = $MaxReviewCycles
        Claude = $script:Claude; PermissionMode = $PermissionMode; Model = $Model; MaxBudgetUsd = $MaxBudgetUsd; AgentTimeoutMinutes = $AgentTimeoutMinutes
    }
    $paramsFile = Join-Path $logDir 'worker.params.json'
    Write-TextFile $paramsFile (([pscustomobject]$params) | ConvertTo-Json -Depth 5)
    $p = Start-BackgroundScript -ScriptPath (Join-Path $PSScriptRoot 'orchestrator\Invoke-IssueWorker.ps1') -Arguments @('-ParamsFile', $paramsFile) -WorkingDirectory $Item.Worktree -LogDir $logDir
    $script:Workers[$key] = @{ Process = $p; Pid = $p.Id; Key = $key; Kind = $Item.Kind; Issue = $Item.Issue; StateFile = (Get-StateFile $key); Item = $Item }
    Write-RunLog ('started worker ' + $key + ' (PID ' + $p.Id + ') in ' + $Item.Worktree + ' - logs: ' + $logDir) 'INFO' Green
}

function New-IssueItem($PlanEntry) {
    $n = $PlanEntry.Number
    $existing = Find-ExistingIssueBranch $n
    $branch = $PlanEntry.Branch
    if ($existing) { $branch = $existing }
    $wt = Ensure-Worktree (Join-Path $script:WtRoot ('issue-' + $n)) $branch $IntegrationRef
    return @{ Key = ('issue-' + $n); Kind = 'issue'; Issue = $n; Title = $PlanEntry.Title; Labels = @($PlanEntry.Labels); Branch = $branch; Worktree = $wt; DeveloperAgent = 'issue-developer'; TaskText = '' }
}

$script:PendingRepairs = New-Object System.Collections.ArrayList
function Add-RepairItem([string]$Slug, [string]$Title, [string]$TaskText, [string]$Agent = 'integration-validator') {
    $key = 'repair-' + $Slug
    $branch = 'integration-fix/' + $script:RunId + '-' + $Slug
    $existing = Read-ItemState (Get-StateFile $key)
    if ($null -ne $existing -and @('integrated') -notcontains $existing.Phase) { $branch = [string]$existing.Branch }
    elseif ($null -ne $existing) { Remove-Item -LiteralPath (Get-StateFile $key) -Force }   # previous repair of the same name is integrated
    [void]$script:PendingRepairs.Add(@{ Key = $key; Kind = 'repair'; Issue = 0; Title = $Title; Labels = @(); Branch = $branch; Worktree = (Join-Path $script:WtRoot $key); DeveloperAgent = $Agent; TaskText = $TaskText })
}

function Resume-RepairItems {
    if (-not (Test-Path -LiteralPath $script:StateDir)) { return }
    foreach ($f in (Get-ChildItem -LiteralPath $script:StateDir -Filter 'repair-*.json')) {
        $s = Read-ItemState $f.FullName
        if ($null -eq $s -or @('integrated', 'blocked') -contains $s.Phase) { continue }
        $params = Join-Path (Get-Prop $s 'LogDir' '') 'worker.params.json'
        $task = ''; $agent = 'integration-validator'
        if ($params -and (Test-Path -LiteralPath $params)) { $pp = ConvertFrom-Json (Read-TextFile $params); $task = $pp.TaskText; $agent = $pp.DeveloperAgent }
        [void]$script:PendingRepairs.Add(@{ Key = $s.Key; Kind = 'repair'; Issue = 0; Title = $s.Title; Labels = @(); Branch = $s.Branch; Worktree = $s.Worktree; DeveloperAgent = $agent; TaskText = $task })
    }
}

# Adopt workers still running from an interrupted orchestrator (state file records the PID).
function Register-AdoptedWorkers {
    if (-not (Test-Path -LiteralPath $script:StateDir)) { return }
    foreach ($f in (Get-ChildItem -LiteralPath $script:StateDir -Filter '*.json')) {
        $s = Read-ItemState $f.FullName
        if ($null -eq $s) { continue }
        $workerPid = [int](Get-Prop $s 'WorkerPid' 0)
        if (@('developing', 'reviewing') -contains $s.Phase -and (Test-ProcessAlive $workerPid)) {
            $script:Workers[$s.Key] = @{ Process = $null; Pid = $workerPid; Key = $s.Key; Kind = $s.Kind; Issue = [int]$s.Issue; StateFile = $f.FullName; Item = $null }
            Write-RunLog ('adopted running worker ' + $s.Key + ' (PID ' + $workerPid + ')')
        }
    }
}

function Test-WorkerExited($w) {
    if ($null -ne $w.Process) { return $w.Process.HasExited }
    return -not (Test-ProcessAlive $w.Pid)
}

# Handles finished workers; returns $true when something changed.
function Receive-FinishedWorkers {
    $changed = $false
    foreach ($key in @($script:Workers.Keys)) {
        $w = $script:Workers[$key]
        if (-not (Test-WorkerExited $w)) { continue }
        $script:Workers.Remove($key)
        $changed = $true
        $s = Read-ItemState $w.StateFile
        $phase = [string](Get-Prop $s 'Phase' 'unknown')
        if ($phase -eq 'approved') { Write-RunLog ($key + ': APPROVED (ready to integrate)') 'INFO' Green; continue }
        if ($phase -eq 'blocked') { Write-RunLog ($key + ': BLOCKED - ' + (Get-Prop $s 'Reason' '')) 'WARN' Yellow; continue }
        # Crashed / killed worker: relaunch a bounded number of times, then block.
        $errors = [int](Get-Prop $s 'ErrorCount' 0)
        $item = $w.Item
        if ($null -eq $item -and $null -ne $s) { $item = Get-ItemFromState $s }
        if ($null -ne $s -and $errors -lt 3 -and $null -ne $item) {
            Write-RunLog ($key + ': worker ended in phase ' + $phase + ' (' + (Get-Prop $s 'LastError' 'no error recorded') + '); relaunching') 'WARN' Yellow
            if (-not (Get-Prop $s 'LastError' $null)) { Set-StateProperty $s 'ErrorCount' ($errors + 1); Save-ItemState $w.StateFile $s }
            Start-Worker $item
        } elseif ($null -ne $s) {
            $s.Phase = 'blocked'; Set-StateProperty $s 'Reason' ('worker failed repeatedly: ' + (Get-Prop $s 'LastError' 'unknown')); Save-ItemState $w.StateFile $s
            Write-RunLog ($key + ': BLOCKED after repeated worker failures') 'WARN' Yellow
        }
    }
    return $changed
}

# ---------------------------------------------------------------------------
# Integration of approved work (serialized)
# ---------------------------------------------------------------------------

function Get-ConflictPrompt([hashtable]$Item) {
    $scope = if ($Item.Kind -eq 'issue') { 'GitHub Issue #' + $Item.Issue + ' (' + $Item.Title + ')' } else { 'integration task "' + $Item.Title + '"' }
    return @"
ORCHESTRATION MODE: integration
Worktree: $($Item.Worktree)
Branch: $($Item.Branch) (already checked out; never switch branches)
TASK: MERGE CONFLICT RESOLUTION for $scope.
The branch was reviewed and approved, but $IntegrationRef moved and the merge conflicts.
1. Run: git merge --no-ff --signoff $IntegrationRef
2. Resolve every conflict so that BOTH this branch's scope and the already integrated work keep their approved behaviour.
   Decide from the approved docs, the Issues' scopes, existing code and architecture. Regenerate generated files
   (api/openapi.yaml: ``gradlew.bat updateOpenApi`` in backend/; mobile client: ``npm run generate:api`` in mobile/) instead of hand-merging them.
   Never modify a Liquibase changeset that is already on ${IntegrationRef}: renumber/rename this branch's own changeset instead.
3. Build and test the affected areas, then conclude the merge: git commit --no-edit --signoff
4. If a correct resolution requires changing an approved product, security or architecture decision: run ``git merge --abort`` and report BLOCKED with category HUMAN_DECISION.
Do NOT push, create Pull Requests or edit Issues.
Finish with exactly one final line:
ORCHESTRATOR-RESULT: {"status":"DONE|BLOCKED","category":"NONE|HUMAN_DECISION|TECHNICAL","summary":"<one sentence>"}
"@
}

function Set-ItemPhase([string]$Key, [string]$Phase, [string]$Reason = '') {
    $f = Get-StateFile $Key
    $s = Read-ItemState $f
    $s.Phase = $Phase; Set-StateProperty $s 'Reason' $Reason
    Save-ItemState $f $s
    return $s
}

function Get-ItemFromState($s) {
    $params = Join-Path ([string](Get-Prop $s 'LogDir' '')) 'worker.params.json'
    $task = ''; $agent = 'issue-developer'; $labels = @()
    if ((Get-Prop $s 'LogDir' '') -and (Test-Path -LiteralPath $params)) { $pp = ConvertFrom-Json (Read-TextFile $params); $task = $pp.TaskText; $agent = $pp.DeveloperAgent; $labels = @($pp.Labels) }
    return @{ Key = $s.Key; Kind = $s.Kind; Issue = [int]$s.Issue; Title = $s.Title; Labels = $labels; Branch = $s.Branch; Worktree = $s.Worktree; DeveloperAgent = $agent; TaskText = $task }
}

function Invoke-Integration([string]$Key) {
    $f = Get-StateFile $Key
    $s = Read-ItemState $f
    $item = Get-ItemFromState $s
    $wt = [string]$s.Worktree
    Write-Section ('Integrating ' + $Key + ' (' + $s.Branch + ')')

    if ((Get-GitHead $wt) -ne $s.Head -or (Get-GitDirtyStatus $wt) -or (Test-MergeInProgress $wt)) {
        Write-RunLog ($Key + ': worktree changed since approval; re-validating') 'WARN' Yellow
        [void](Set-ItemPhase $Key 'reviewing'); Start-Worker $item; return
    }
    Sync-IntegrationWorktree
    if (-not (Test-GitAncestor $wt $IntegrationRef 'HEAD')) {
        $syncs = [int](Get-Prop $s 'SyncCount' 0)
        if ($syncs -ge 5) { [void](Set-ItemPhase $Key 'blocked' 'integration branch moved during 5 consecutive re-validations'); return }
        Set-StateProperty $s 'SyncCount' ($syncs + 1); Save-ItemState $f $s
        $label = if ($s.Kind -eq 'issue') { '#' + $s.Issue } else { 'integration' }
        $m = Invoke-GitIn $wt @('merge', '--no-ff', '--signoff', '-m', ('chore(integration): sync ' + $IntegrationBranch + ' into ' + $s.Branch + ' (' + $label + ')'), $IntegrationRef) -AllowFailure
        if ($m.ExitCode -ne 0) {
            if (Test-MergeInProgress $wt) { [void](Invoke-GitIn $wt @('merge', '--abort')) }
            Write-RunLog ($Key + ': conflicts with ' + $IntegrationBranch + '; starting a conflict-resolution agent') 'WARN' Yellow
            $run = Invoke-ClaudeAgent -Agent $item.DeveloperAgent -Prompt (Get-ConflictPrompt $item) -WorkingDirectory $wt -LogDir (Join-Path $script:RunDir $Key) -Name ('conflict-' + ($syncs + 1)) -Settings $script:Settings -DeniedTools (Get-ImplementationDeniedTools)
            $res = ConvertFrom-AgentResult $run.Text
            if ((Test-MergeInProgress $wt) -or (Get-GitDirtyStatus $wt) -or -not (Test-GitAncestor $wt $IntegrationRef 'HEAD')) {
                if (Test-MergeInProgress $wt) { [void](Invoke-GitIn $wt @('merge', '--abort') -AllowFailure) }
                $why = 'merge conflict with ' + $IntegrationBranch + ' not resolved'
                if ($res.Status -eq 'BLOCKED') { $why += ': ' + $res.Category + ' - ' + $res.Summary }
                [void](Set-ItemPhase $Key 'blocked' ($why + ' (see ' + $run.ResultFile + ')')); return
            }
            Write-RunLog ($Key + ': conflicts resolved by agent; re-running gate and reviews')
        } else {
            Write-RunLog ($Key + ': synced with ' + $IntegrationBranch + '; re-running the gate on the combined code')
        }
        [void](Set-ItemPhase $Key 'reviewing'); Start-Worker $item; return
    }

    # HEAD contains the integration tip: the --no-ff merge result is exactly the validated tree.
    $trailer = if ($s.Kind -eq 'issue') { 'Integrates-Issue: #' + $s.Issue } else { 'Integrates-Repair: ' + $s.Key }
    $subject = if ($s.Kind -eq 'issue') { 'chore(integration): integrate #' + $s.Issue + ' ' + $s.Title } else { 'chore(integration): ' + $s.Title }
    $msg = $subject + "`n`nReviewed and gated by the autonomous orchestrator (run " + $script:RunId + ").`n`n" + $trailer + "`nIntegrated-Branch: " + $s.Branch + "`nReviewed-Head: " + $s.Head
    [void](Invoke-GitIn $script:IntegrationWt @('merge', '--no-ff', '--signoff', '-m', $msg, $s.Branch))
    if ((Invoke-GitIn $script:IntegrationWt @('diff', '--quiet', 'HEAD', $s.Branch) -AllowFailure).ExitCode -ne 0) {
        Stop-Orchestrator ('Integration merge of ' + $s.Branch + ' does not equal the validated tree; local ' + $IntegrationBranch + ' is NOT pushed. Inspect ' + $script:IntegrationWt + '.')
    }
    $push = Invoke-GitIn $script:IntegrationWt @('push', 'origin', $IntegrationBranch) -AllowFailure
    if ($push.ExitCode -ne 0) { Stop-Orchestrator ('Push of ' + $IntegrationBranch + ' was rejected (never forced). The validated merge stays local and is pushed by the next run.' + "`n" + $push.Output) }
    [void](Invoke-GitIn $wt @('push', 'origin', ($s.Branch + ':refs/heads/' + $s.Branch)) -AllowFailure)
    [void](Invoke-RootGit @('fetch', 'origin'))
    [void](Set-ItemPhase $Key 'integrated')
    [void]$script:Integrated.Add($Key + ' ' + $s.Title)
    Write-RunLog ($Key + ': INTEGRATED into ' + $IntegrationBranch) 'INFO' Green
    Remove-ItemWorktree $wt
}

function Remove-ItemWorktree([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    if (Get-GitDirtyStatus $Path) { Write-Warn ('Worktree ' + $Path + ' kept: it contains uncommitted changes.'); return }
    $r = Invoke-RootGit @('worktree', 'remove', $Path) -AllowFailure
    if ($r.ExitCode -ne 0) { Write-Warn ('Worktree ' + $Path + ' kept (' + $r.Output + ')') }
}

function Invoke-ApprovedIntegrations {
    $any = $false
    if (-not (Test-Path -LiteralPath $script:StateDir)) { return $false }
    foreach ($f in (Get-ChildItem -LiteralPath $script:StateDir -Filter '*.json' | Sort-Object Name)) {
        $s = Read-ItemState $f.FullName
        if ($null -eq $s -or $s.Phase -ne 'approved' -or $script:Workers.ContainsKey($s.Key)) { continue }
        Invoke-Integration $s.Key
        $any = $true
    }
    return $any
}

# Cleans worktrees of items already integrated (e.g. after an interrupted run).
function Clear-IntegratedWorktrees($Snapshot) {
    if (-not (Test-Path -LiteralPath $script:StateDir)) { return }
    foreach ($f in (Get-ChildItem -LiteralPath $script:StateDir -Filter 'issue-*.json')) {
        $s = Read-ItemState $f.FullName
        if ($null -ne $s -and $Snapshot.Integrated.ContainsKey([int]$s.Issue) -and $s.Phase -ne 'integrated') {
            $s.Phase = 'integrated'; Save-ItemState $f.FullName $s
            Remove-ItemWorktree ([string]$s.Worktree)
        }
    }
}

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

function Write-Plan([hashtable]$Plan) {
    Write-Section 'Issue DAG'
    foreach ($p in ($Plan.Values | Sort-Object Number)) {
        $deps = if ($p.Dependencies.Count -eq 0) { 'none' } else { ($p.Dependencies | ForEach-Object { '#' + $_.Number + '(' + $_.Status + ')' }) -join ' ' }
        $color = switch ($p.Status) { 'DONE' { 'DarkGray' } 'RUNNABLE' { 'Green' } 'IN_PROGRESS' { 'Cyan' } 'APPROVED' { 'Cyan' } 'WAITING' { 'Gray' } default { 'Yellow' } }
        Write-Host ('#' + $p.Number + ' ' + $p.Identifier.PadRight(11) + ' ' + $p.Status.PadRight(11) + ' deps: ' + $deps) -ForegroundColor $color
        if ($p.Reason -and $p.Status -ne 'DONE') { Write-Host ('      ' + $p.Reason) -ForegroundColor DarkGray }
    }
}

function Write-Waves([hashtable]$Plan) {
    Write-Section ('Execution waves (parallelism ' + $Parallelism + ')')
    $max = 0; foreach ($p in $Plan.Values) { if ($p.Wave -gt $max) { $max = $p.Wave } }
    if ($max -eq 0) { Write-Info 'No Issue can be scheduled.' }
    for ($w = 1; $w -le $max; $w++) {
        $items = @($Plan.Values | Where-Object { $_.Wave -eq $w } | Sort-Object @{ Expression = 'Unblocks'; Descending = $true }, PriorityRank, Number)
        Write-Info ('Wave ' + $w + ': ' + (($items | ForEach-Object { '#' + $_.Number + ' ' + $_.Identifier }) -join ', '))
    }
    $stuck = @($Plan.Values | Where-Object { $_.Status -eq 'WAITING' -and $_.Wave -eq 0 } | Sort-Object Number)
    if ($stuck.Count -gt 0) {
        Write-Info 'Cannot be scheduled until a human unblocks their root blockers:'
        foreach ($p in $stuck) { Write-Info ('  #' + $p.Number + ' ' + $p.Identifier + ' <- root blocker(s): ' + (($p.RootBlockers | ForEach-Object { '#' + $_ }) -join ', ')) }
    }
}

function Write-DryRunDetails([hashtable]$Plan) {
    Write-Section 'Runnable now (launch order)'
    $runnable = @(Select-RunnableIssues $Plan)
    if ($runnable.Count -eq 0) { Write-Info 'None.' }
    foreach ($p in $runnable) { Write-Info ('#' + $p.Number + ' ' + $p.Identifier + ' (unblocks ' + $p.Unblocks + ', priority rank ' + $p.PriorityRank + ')') }

    Write-Section 'Needs a human / blocked'
    $human = @($Plan.Values | Where-Object { @('NEEDS_HUMAN', 'BLOCKED', 'HUMAN_PR') -contains $_.Status } | Sort-Object Number)
    if ($human.Count -eq 0) { Write-Info 'None.' }
    foreach ($p in $human) { Write-Info ('#' + $p.Number + ' ' + $p.Identifier + ' ' + $p.Status + ': ' + $p.Reason) }

    Write-Section 'Intended worktrees, branches and agent roles'
    foreach ($p in ($Plan.Values | Where-Object { @('RUNNABLE', 'WAITING', 'IN_PROGRESS', 'APPROVED') -contains $_.Status } | Sort-Object Wave, Number)) {
        $branch = Find-ExistingIssueBranch $p.Number
        if (-not $branch) { $branch = $p.Branch + ' (new, from ' + $IntegrationRef + ')' } else { $branch += ' (existing, resumed)' }
        Write-Info ('#' + $p.Number + ' wave ' + $p.Wave + ': ' + (Join-Path $script:WtRoot ('issue-' + $p.Number)))
        Write-Info ('      branch: ' + $branch)
        Write-Info ('      roles : ' + ($p.PredictedRoles -join ', ') + ' (final reviewer set decided from the changed paths)')
    }

    Write-Section 'Validation steps'
    Write-Info 'Per Issue gate (by changed area, in the Issue worktree):'
    Write-Info '  backend : backend\gradlew.bat build --no-daemon   (-Werror, unit/property, Testcontainers + Liquibase, Modulith/ArchUnit, OpenAPI drift)'
    Write-Info '  mobile  : npm ci (when lock changed), npm run check:api, npm run verify (lint, format:check, typecheck, jest)'
    Write-Info ('Review/fix loop: max ' + $MaxReviewCycles + ' cycles; BLOCKER/HIGH must be fixed; one extra cycle for in-scope MEDIUM.')
    Write-Info ('Integration: sync with ' + $IntegrationRef + ' (agent resolves conflicts), re-gate, merge --no-ff --signoff, push ' + $IntegrationBranch + '.')
    Write-Info 'Final acceptance (integration worktree): full backend build, mobile install/drift/verify, expo config, expo export --platform android,'
    Write-Info ('  API acceptance journey (integration-validator), holistic mobile UX review (max ' + $MaxUxCycles + ' fix rounds),')
    Write-Info ('  repair rounds max ' + $MaxRepairCycles + ', Android APK ' + $(if ($BuildAndroidApk) { 'when an Android SDK is installed' } else { 'skipped (-BuildAndroidApk not set)' }) + ', PR ' + $IntegrationBranch + ' -> ' + $BaseBranch + ' for the human.')
}

# ---------------------------------------------------------------------------
# Final acceptance
# ---------------------------------------------------------------------------

function Wait-Item([string]$Key) {
    while ($true) {
        if ($script:Workers.ContainsKey($Key)) { [void](Receive-FinishedWorkers); Start-Sleep -Seconds $PollSeconds; continue }
        $s = Read-ItemState (Get-StateFile $Key)
        $phase = [string](Get-Prop $s 'Phase' '')
        if ($phase -eq 'approved') { Invoke-Integration $Key; continue }
        return $phase
    }
}

function Invoke-RepairNow([string]$Slug, [string]$Title, [string]$TaskText, [string]$Agent = 'integration-validator') {
    Add-RepairItem $Slug $Title $TaskText $Agent
    $item = $script:PendingRepairs[$script:PendingRepairs.Count - 1]
    $script:PendingRepairs.Remove($item)
    $item.Worktree = Ensure-Worktree $item.Worktree $item.Branch $IntegrationRef
    Start-Worker $item
    return (Wait-Item $item.Key)
}

function Get-JourneyPrompt([string]$Wt, [string]$Branch) {
    return @"
ORCHESTRATION MODE: integration
Worktree: $Wt
Branch: $Branch (already checked out, created from the integration tip; never switch branches)
TASK: ACCEPTANCE JOURNEY of the integrated CoupleFinance MVP (follow the "Acceptance journey" section of your agent definition).
1. Start the backend from this worktree on a throw-away database: in backend/ run ``gradlew.bat bootTestRun --args=--server.port=18080`` in the background,
   wait for http://localhost:18080/actuator/health to be UP, and stop it (and its container) at the end.
2. Using api/openapi.yaml as the contract, exercise over HTTP: register -> email verification (approved fake/test email adapter only) -> login ->
   authenticated /me -> create/load household -> create expense -> list expenses -> edit expense -> budget -> dashboard/analytics ->
   refresh token -> logout -> refresh after logout is rejected. A step whose endpoint is absent from the contract is NOT_IMPLEMENTED (not a failure).
3. Check the mobile app's configuration matches that backend (EXPO_PUBLIC_API_BASE_URL, generated client in sync).
4. Fix genuine integration defects on this branch with tests and signed-off commits (``git commit --signoff``), within approved behaviour.
   Never weaken security, validation or business rules to make a step pass. Do NOT push or create Pull Requests.
5. Never print raw tokens, passwords or verification links.
Report: a markdown table (step | result PASS/FAIL/NOT_IMPLEMENTED | evidence), then a section "## Human acceptance notes" explaining how a human
completes the same journey locally (for example where the local fake email adapter exposes the verification link/token).
Finish with exactly one final line:
ORCHESTRATOR-RESULT: {"status":"DONE|BLOCKED","category":"NONE|HUMAN_DECISION|MISSING_CREDENTIAL|TECHNICAL","summary":"<one sentence>"}
"@
}

function Get-UxFinalPrompt {
    return @"
ORCHESTRATION MODE: integration (read-only, holistic review)
Review the WHOLE integrated mobile app in this worktree (mobile/app, mobile/src) - not a diff - as one product built by several independent agents.
Follow the "Holistic review" section of your agent definition: consistency between Dashboard, Expenses, Budget, Analytics and Settings,
design-system usage, financial-number readability, states (loading/empty/error/success), forms and keyboard, accessibility, quick expense entry.
Do not invent business rules; reference the Issue/BR when a rule is involved.
Report in your Output format and end with the Summary block (BLOCKER/HIGH/MEDIUM/LOW counts).
"@
}

function Invoke-FinalAcceptance($Snapshot) {
    $final = Join-Path $script:RunDir 'final'
    New-Item -ItemType Directory -Force -Path $final | Out-Null
    $report = [ordered]@{ Gate = 'not run'; Journey = 'not run'; JourneyReport = ''; Ux = 'not run'; UxReport = ''; Apk = 'not built'; Pr = '' }
    $repairs = 0; $uxRounds = 0; $journeyDone = $false; $journeyRuns = 0; $uxClean = $false; $uxMediumDone = $false
    for ($round = 1; $round -le (4 + $MaxRepairCycles + $MaxUxCycles); $round++) {
        Write-Section ('Final acceptance round ' + $round)
        Sync-IntegrationWorktree
        $all = [pscustomobject]@{ Backend = $true; Mobile = $true; Api = $true; Orchestrator = $true; DocsOnly = $false }
        $gate = Invoke-VerificationGate -Worktree $script:IntegrationWt -Areas $all -Final -LogDir (Join-Path $final ('gate-r' + $round))
        if (-not $gate.Passed) {
            $report.Gate = 'FAILED'
            if ($repairs -ge $MaxRepairCycles) { return @{ Ready = $false; Reason = 'final validation still failing after ' + $repairs + ' repair round(s)'; Report = $report } }
            $repairs++
            $phase = Invoke-RepairNow ('final-gate-' + $repairs) 'Fix final integration validation failures' ("The full validation of the integrated branch fails. Make it pass without weakening any check:`n" + (Format-GateFailure $gate $script:IntegrationWt))
            if ($phase -ne 'integrated') { return @{ Ready = $false; Reason = 'final validation repair ended ' + $phase; Report = $report } }
            continue
        }
        $report.Gate = 'PASSED (' + (($gate.Steps | ForEach-Object { $_.Name }) -join ', ') + ')'

        if (-not $journeyDone) {
            $journeyRuns++
            $slug = 'acceptance-journey-' + $journeyRuns
            $key = 'repair-' + $slug
            $branch = 'integration-fix/' + $script:RunId + '-' + $slug
            $wt = Ensure-Worktree (Join-Path $script:WtRoot $key) $branch $IntegrationRef
            $run = Invoke-ClaudeAgent -Agent 'integration-validator' -Prompt (Get-JourneyPrompt $wt $branch) -WorkingDirectory $wt -LogDir (Join-Path $script:RunDir $key) -Name 'journey' -Settings $script:Settings -DeniedTools (Get-ImplementationDeniedTools)
            $res = ConvertFrom-AgentResult $run.Text
            $report.JourneyReport = $run.Text
            if ((Get-CommitsAhead $wt $IntegrationRef) -gt 0 -and $journeyRuns -le $MaxRepairCycles) {
                # Fixes made during the journey go through the normal gate + review loop, then integration.
                $state = [pscustomobject]@{ Key = $key; Kind = 'repair'; Issue = 0; Title = 'Acceptance journey fixes'; Branch = $branch; Worktree = $wt; Phase = 'reviewing'; Reason = ''; Head = ''; Cycles = 0; MediumFixDone = $false; SyncCount = 0; Reviews = [pscustomobject]@{}; Gate = $null; Rejected = ''; CostUsd = 0; History = @() }
                Save-ItemState (Get-StateFile $key) $state
                Start-Worker @{ Key = $key; Kind = 'repair'; Issue = 0; Title = 'Acceptance journey fixes'; Labels = @(); Branch = $branch; Worktree = $wt; DeveloperAgent = 'integration-validator'; TaskText = ("Fixes for integration defects found by the acceptance journey:`n" + (Get-TextTail $run.Text 120)) }
                $phase = Wait-Item $key
                if ($phase -ne 'integrated') { return @{ Ready = $false; Reason = 'acceptance-journey fixes ended ' + $phase; Report = $report } }
                continue   # re-validate and replay the journey on the new tip
            }
            Remove-ItemWorktree $wt
            if ($res.Status -ne 'DONE') {
                $report.Journey = 'FAILED: ' + $res.Category + ' ' + $res.Summary
                return @{ Ready = $false; Reason = 'acceptance journey: ' + $res.Summary + ' (see ' + $run.ResultFile + ')'; Report = $report }
            }
            $report.Journey = 'PASSED: ' + $res.Summary
            $journeyDone = $true
        }

        if (-not $uxClean) {
            $ux = Invoke-ClaudeAgent -Agent 'mobile-ux-reviewer' -Prompt (Get-UxFinalPrompt) -WorkingDirectory $script:IntegrationWt -LogDir $final -Name ('ux-final-r' + $round) -Settings $script:Settings -DeniedTools (Get-ReviewerDeniedTools)
            $parsed = ConvertFrom-ReviewReport $ux.Text
            $c = $parsed.Counts
            $report.Ux = 'BLOCKER ' + $c.BLOCKER + ', HIGH ' + $c.HIGH + ', MEDIUM ' + $c.MEDIUM + ', LOW ' + $c.LOW
            $report.UxReport = $ux.ResultFile
            $needsFix = ($c.BLOCKER + $c.HIGH) -gt 0 -or ($c.MEDIUM -gt 0 -and -not $uxMediumDone) -or -not $parsed.Parsed
            if ($needsFix -and $uxRounds -lt $MaxUxCycles) {
                $uxRounds++
                if (($c.BLOCKER + $c.HIGH) -eq 0) { $uxMediumDone = $true }
                $phase = Invoke-RepairNow ('ux-' + $uxRounds) 'Mobile UX consistency fixes' ("Fix the findings of the holistic mobile UX review below (BLOCKER/HIGH mandatory, MEDIUM when within the approved scope). Do not invent business rules or change approved behaviour.`n`n" + $ux.Text) 'issue-developer'
                if ($phase -ne 'integrated') { return @{ Ready = $false; Reason = 'UX fix round ended ' + $phase; Report = $report } }
                continue
            }
            if (($c.BLOCKER + $c.HIGH) -gt 0) { return @{ Ready = $false; Reason = 'holistic UX review still reports BLOCKER/HIGH after ' + $uxRounds + ' fix round(s): ' + $ux.ResultFile; Report = $report } }
            $uxClean = $true
            if ($uxRounds -gt 0) { continue }   # re-validate the tip after the last UX fixes
        }
        break
    }

    if ($BuildAndroidApk) { $report.Apk = Invoke-AndroidApkBuild $final }
    $report.Pr = Publish-FinalPullRequest $Snapshot $report
    return @{ Ready = $true; Reason = ''; Report = $report }
}

function Invoke-AndroidApkBuild([string]$Dir) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) { return 'skipped: no Android SDK (ANDROID_HOME) on this machine' }
    # Built in a throw-away detached worktree so generated native folders never touch a branch.
    $wt = Join-Path $script:WtRoot 'apk-build'
    if (-not (Test-Path -LiteralPath $wt)) { [void](Invoke-RootGit @('worktree', 'add', '--detach', $wt, $IntegrationRef)) }
    else { [void](Invoke-GitIn $wt @('checkout', '--detach', $IntegrationRef)) }
    $mobile = Join-Path $wt 'mobile'
    $steps = @(
        @{ N = 'apk-npm-ci'; C = 'npm ci --no-audit --no-fund'; D = $mobile },
        @{ N = 'apk-prebuild'; C = 'npx expo prebuild --platform android --no-install'; D = $mobile },
        @{ N = 'apk-assemble'; C = 'gradlew.bat assembleDebug'; D = (Join-Path $mobile 'android') })
    foreach ($s in $steps) {
        $r = Invoke-ShellStep -Name $s.N -CommandLine $s.C -WorkingDirectory $s.D -LogDir $Dir -TimeoutMinutes 45
        if (-not $r.Passed) { return 'FAILED at ' + $s.N + ' (see ' + $r.Log + '); use the Expo Go path instead' }
    }
    $apk = Get-ChildItem -LiteralPath (Join-Path $mobile 'android\app\build\outputs\apk\debug') -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $apk) { return 'FAILED: no APK produced' }
    $dest = Join-Path $script:RunDir 'couplefinance-debug.apk'
    Copy-Item -LiteralPath $apk.FullName -Destination $dest -Force
    return 'built: ' + $dest + ' (debug build: needs Metro, see launch commands)'
}

function Publish-FinalPullRequest($Snapshot, $Report) {
    $snap = Get-Snapshot
    $closes = @($snap.Integrated.Keys | Where-Object { @($snap.Open | ForEach-Object { [int]$_.number }) -contains $_ } | Sort-Object)
    $body = New-Object System.Text.StringBuilder
    [void]$body.AppendLine('## Summary'); [void]$body.AppendLine('')
    [void]$body.AppendLine('Integrated CoupleFinance MVP built by the autonomous orchestrator (run ' + $script:RunId + '). Ready for **human acceptance testing**; merging into `' + $BaseBranch + '` is the human decision.'); [void]$body.AppendLine('')
    [void]$body.AppendLine('## Issues'); [void]$body.AppendLine('')
    foreach ($n in $closes) { [void]$body.AppendLine('Closes #' + $n) }
    [void]$body.AppendLine(''); [void]$body.AppendLine('## Validation'); [void]$body.AppendLine('')
    [void]$body.AppendLine('- Final gate: ' + $Report.Gate)
    [void]$body.AppendLine('- Acceptance journey: ' + $Report.Journey)
    [void]$body.AppendLine('- Holistic mobile UX review: ' + $Report.Ux)
    [void]$body.AppendLine('- Android APK: ' + $Report.Apk)
    [void]$body.AppendLine(''); [void]$body.AppendLine('Every Issue passed its gate and independent code/security/UX reviews (BLOCKER: 0, HIGH: 0) before integration; per-Issue logs are in `.autonomous-dev/runs/` on the orchestrating machine.')
    [void]$body.AppendLine(''); [void]$body.AppendLine('## Human acceptance'); [void]$body.AppendLine('')
    [void]$body.AppendLine('See `scripts/orchestrator/README.md` ("Human acceptance testing") and run `scripts/start-mvp-acceptance.ps1`.')
    [void]$body.AppendLine(''); [void]$body.AppendLine([char]::ConvertFromUtf32(0x1F916) + ' Generated with [Claude Code](https://claude.com/claude-code)')
    $bodyFile = Join-Path $script:RunDir 'final-pr-body.md'
    Write-TextFile $bodyFile $body.ToString()
    $existing = $snap.OpenPrs | Where-Object { $_.headRefName -eq $IntegrationBranch -and $_.baseRefName -eq $BaseBranch } | Select-Object -First 1
    if ($null -ne $existing) {
        [void](Invoke-Exe $script:Gh @('pr', 'edit', [string]$existing.number, '--body-file', $bodyFile))
        return [string]$existing.url
    }
    $r = Invoke-Exe $script:Gh @('pr', 'create', '--base', $BaseBranch, '--head', $IntegrationBranch, '--title', ('MVP integration: ready for human acceptance (' + $IntegrationBranch + ')'), '--body-file', $bodyFile) -AllowFailure
    if ($r.ExitCode -ne 0) { return 'not created: ' + $r.Output }
    return ($r.Output -split "`n" | Select-Object -Last 1)
}

function Write-AcceptanceReport($Result) {
    $r = $Result.Report
    $file = Join-Path $script:RunDir 'HUMAN-ACCEPTANCE.md'
    $text = @"
# CoupleFinance MVP - ready for human acceptance testing

Run: $($script:RunId)
Integration branch: $IntegrationBranch at $((Get-GitHead $script:IntegrationWt).Substring(0, 10))
Integration worktree: $($script:IntegrationWt)
Pull Request (NOT merged, your decision): $($r.Pr)

## Automated validation
- Final gate: $($r.Gate)
- Acceptance journey: $($r.Journey)
- Holistic mobile UX review: $($r.Ux) ($($r.UxReport))
- Android APK: $($r.Apk)

## Launch on Android
Emulator (start it first in Android Studio):
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device emulator
Physical device (USB debugging on, Expo Go installed):
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device usb

## Acceptance journey report
$($r.JourneyReport)
"@
    Write-TextFile $file $text
    Write-TextFile (Join-Path $script:AutoDir 'HUMAN-ACCEPTANCE.md') $text
    return $file
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

$lockTaken = $false
try {
    if ($DryRun) { Write-Host 'MODE: DRY RUN (reads Git/GitHub only; no worktree, branch, commit, push, PR, Issue or file change)' -ForegroundColor Magenta }
    else {
        if (-not $AutoMergeIntegration) { Stop-Orchestrator ('A real run merges reviewed work into ' + $IntegrationBranch + ' automatically: pass -AutoMergeIntegration to consent (main is never touched).') }
        Write-Host ('MODE: REAL RUN - target ' + $Target + ', parallelism ' + $Parallelism + ', review cycles ' + $MaxReviewCycles + ', integration ' + $IntegrationBranch) -ForegroundColor Magenta
    }
    Assert-Prerequisites
    [void](Invoke-RootGit @('fetch', '--prune', 'origin'))
    $script:RunId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')

    if ($DryRun) {
        Write-Section ('Integration branch ' + $IntegrationBranch)
        if (Test-GitRefExists $script:Root $IntegrationRef) {
            $ahead = (Invoke-RootGit @('rev-list', '--count', ('origin/' + $BaseBranch + '..' + $IntegrationRef))).Output
            $behind = (Invoke-RootGit @('rev-list', '--count', ($IntegrationRef + '..origin/' + $BaseBranch))).Output
            Write-Info ('exists: ' + $ahead + ' commit(s) ahead of ' + $BaseBranch + ', ' + $behind + ' behind (would be synced).')
        } else { Write-Info ('does not exist yet: would be created from origin/' + $BaseBranch + ' and pushed.') }
        Write-Info ('Worktree root: ' + $script:WtRoot)
        if ($AssumeApproved) { Write-Warn 'PREVIEW (-AssumeApproved): approval labels and open questions are ignored for planning; a real run requires them.' }
        $snapshot = Get-Snapshot
        $plan = Get-Plan $snapshot
        Write-Plan $plan
        Write-Waves $plan
        Write-DryRunDetails $plan
        Write-Section 'Dry run complete'
        Write-Info 'Nothing was modified.'
        exit 0
    }

    Enter-Lock; $lockTaken = $true
    $script:RunDir = Join-Path $script:AutoDir ('runs\' + $script:RunId)
    New-Item -ItemType Directory -Force -Path $script:RunDir | Out-Null
    Set-RunLogFile (Join-Path $script:RunDir 'orchestrator.log')
    Write-RunLog ('run ' + $script:RunId + ' started; logs in ' + $script:RunDir)
    New-Item -ItemType Directory -Force -Path $script:WtRoot | Out-Null

    if ($RetryBlocked) {
        foreach ($f in (Get-ChildItem -LiteralPath $script:StateDir -Filter '*.json' -ErrorAction SilentlyContinue)) {
            $s = Read-ItemState $f.FullName
            if ($null -ne $s -and $s.Phase -eq 'blocked') {
                $s.Phase = 'reviewing'; $s.Cycles = 0; $s.Reason = ''; Set-StateProperty $s 'ErrorCount' 0; Set-StateProperty $s 'SyncCount' 0
                Save-ItemState $f.FullName $s; Write-RunLog ($s.Key + ': blocked state cleared (-RetryBlocked)')
            }
        }
    }

    Initialize-Integration
    Register-AdoptedWorkers
    Resume-RepairItems

    $refresh = $true
    $snapshot = $null; $plan = $null
    while ($true) {
        if (Receive-FinishedWorkers) { $refresh = $true }
        if (Invoke-ApprovedIntegrations) { $refresh = $true }
        if ($refresh -or $null -eq $plan) {
            $snapshot = Get-Snapshot
            Clear-IntegratedWorktrees $snapshot
            $plan = Get-Plan $snapshot
            $refresh = $false
            $counts = $plan.Values | Group-Object Status | ForEach-Object { $_.Name + '=' + $_.Count }
            Write-RunLog ('plan: ' + ($counts -join ', ') + '; running workers: ' + $script:Workers.Count)
        }

        # Launch: repair items first, then runnable Issues in DAG order.
        while ($script:Workers.Count -lt $Parallelism -and $script:PendingRepairs.Count -gt 0) {
            $item = $script:PendingRepairs[0]; $script:PendingRepairs.RemoveAt(0)
            $item.Worktree = Ensure-Worktree $item.Worktree $item.Branch $IntegrationRef
            Start-Worker $item; $refresh = $true
        }
        foreach ($p in @(Select-RunnableIssues $plan)) {
            if ($script:Workers.Count -ge $Parallelism) { break }
            if ($MaxIssues -gt 0 -and $script:Started -ge $MaxIssues) { break }
            if ($script:Workers.ContainsKey('issue-' + $p.Number)) { continue }
            try { $item = New-IssueItem $p }
            catch {
                $msg = $_.Exception.Message -replace [regex]::Escape($StopPrefix), ''
                $s = [pscustomobject]@{ Key = 'issue-' + $p.Number; Kind = 'issue'; Issue = $p.Number; Title = $p.Title; Branch = $p.Branch; Worktree = ''; Phase = 'blocked'; Reason = ('worktree setup failed: ' + $msg) }
                Save-ItemState (Get-StateFile $s.Key) $s; Write-RunLog ($s.Key + ': BLOCKED - ' + $s.Reason) 'WARN' Yellow
                $refresh = $true; continue
            }
            Start-Worker $item
            $script:Started++; $refresh = $true
        }

        $approvedLeft = @(Get-ChildItem -LiteralPath $script:StateDir -Filter '*.json' | ForEach-Object { Read-ItemState $_.FullName } | Where-Object { $null -ne $_ -and $_.Phase -eq 'approved' }).Count
        $runnableLeft = @(Select-RunnableIssues $plan).Count
        if ($MaxIssues -gt 0 -and $script:Started -ge $MaxIssues) { $runnableLeft = 0 }
        if ($script:Workers.Count -eq 0 -and $approvedLeft -eq 0 -and $script:PendingRepairs.Count -eq 0) {
            if ($refresh) { continue }   # re-plan once more before deciding to stop
            if ($runnableLeft -eq 0) { break }
        }
        if (-not $refresh) { Start-Sleep -Seconds $PollSeconds }
    }

    Write-Plan $plan
    Write-Waves $plan
    $blockedRepairs = @(Get-ChildItem -LiteralPath $script:StateDir -Filter 'repair-*.json' | ForEach-Object { Read-ItemState $_.FullName } | Where-Object { $null -ne $_ -and $_.Phase -eq 'blocked' })
    if (-not (Test-PlanComplete $plan) -or $blockedRepairs.Count -gt 0) {
        Write-Section 'STOP: human input required before the MVP DAG can complete'
        foreach ($p in ($plan.Values | Where-Object { @('NEEDS_HUMAN', 'BLOCKED', 'HUMAN_PR') -contains $_.Status } | Sort-Object Number)) { Write-Info ('#' + $p.Number + ' ' + $p.Identifier + ' ' + $p.Status + ': ' + $p.Reason) }
        foreach ($s in $blockedRepairs) { Write-Info ($s.Key + ' BLOCKED: ' + $s.Reason) }
        if ($MaxIssues -gt 0 -and $script:Started -ge $MaxIssues) { Write-Info ('MaxIssues (' + $MaxIssues + ') reached.') }
        $script:ExitCode = 2
    } elseif ($SkipAcceptance) {
        Write-Section 'MVP DAG complete (final acceptance skipped by -SkipAcceptance)'
    } else {
        $result = Invoke-FinalAcceptance $snapshot
        if ($result.Ready) {
            $file = Write-AcceptanceReport $result
            Write-Host ''
            Write-Host '==============================================================' -ForegroundColor Green
            Write-Host '   READY FOR HUMAN ACCEPTANCE TESTING' -ForegroundColor Green
            Write-Host '==============================================================' -ForegroundColor Green
            Write-Info ('Report : ' + $file)
            Write-Info ('PR     : ' + $result.Report.Pr + '  (not merged - your decision)')
            Write-Info 'Launch : powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device emulator   (or -Device usb)'
        } else {
            Write-Section 'STOP: final acceptance not reached'
            Write-Info $result.Reason
            $script:ExitCode = 2
        }
    }
}
catch {
    $message = $_.Exception.Message
    if ($message.StartsWith($StopPrefix)) {
        if ($script:ExitCode -eq 0) { $script:ExitCode = 1 }
        Write-Section 'STOP'
        Write-Host $message.Substring($StopPrefix.Length) -ForegroundColor Red
    } else {
        $script:ExitCode = 1
        Write-Section 'STOP: unexpected error'
        Write-Host ($_ | Out-String) -ForegroundColor Red
    }
}
finally {
    if (-not $DryRun) {
        if ($script:Workers.Count -gt 0) {
            Write-Warn ($script:Workers.Count.ToString() + ' worker(s) are still running in the background (' + (($script:Workers.Keys) -join ', ') + '). Rerun the orchestrator to adopt them; nothing is lost.')
        }
        if ($script:Integrated.Count -gt 0) { Write-Section 'Integrated in this run'; foreach ($i in $script:Integrated) { Write-Info $i } }
        if ($lockTaken -and (Test-Path -LiteralPath $script:LockFile)) { Remove-Item -LiteralPath $script:LockFile -Force }
        Write-Info ('Nothing was merged into ' + $BaseBranch + '.')
    }
}
exit $script:ExitCode
