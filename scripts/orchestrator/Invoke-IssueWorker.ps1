<#
.SYNOPSIS
    Worker for ONE work item (a GitHub Issue or an integration-repair task), started by
    scripts/autonomous-development.ps1 as a separate process inside the item's own Git worktree.

.DESCRIPTION
    implement (fresh issue-developer / integration-validator process)
      -> deterministic verification gate
      -> independent reviews in fresh processes (code / security when relevant / mobile UX when relevant)
      -> fix (fresh process, findings only) -> gate -> re-review of what changed ... (bounded)
      -> VALIDATED (ready to integrate) or BLOCKED (reason recorded, work preserved)

    The worker never pushes, merges, creates PRs or edits Issues: integration is serialized
    in the orchestrator.

    Crash safety: this process and every Claude Code process it starts may die at any moment.
    The phase (RUNNING, IMPLEMENTED, TESTING, REVIEWING, FIXING, VALIDATED, ...) is persisted
    before each step, together with the gate result, every review and the findings of a fix
    pass (PendingFix). A relaunched worker resumes from that checkpoint; any agent it starts
    for interrupted work is a NEW process that receives a recovery context (git status, commits,
    diff, previous timeline and log locations) and continues the existing work. Nothing is
    reset, cleaned, stashed or rebased.

    Exit codes: 0 = VALIDATED / BLOCKED recorded, 1 = crashed (relaunched by the orchestrator),
                3 = INTERRUPTED because Claude was unavailable (InterruptKind AUTH / TRANSIENT).
#>
param([Parameter(Mandatory = $true)][string]$ParamsFile)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
Import-Module (Join-Path $PSScriptRoot 'AutonomousDev.Planning.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'AutonomousDev.Runtime.psm1') -Force

$P = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($ParamsFile, $utf8))
$wt = [string]$P.Worktree
$base = [string]$P.BaseRef
$logDir = [string]$P.LogDir
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Set-RunLogFile (Join-Path $logDir 'worker.log')
$settings = @{ Claude = [string]$P.Claude; PermissionMode = [string]$P.PermissionMode; Model = [string]$P.Model; MaxBudgetUsd = [decimal]$P.MaxBudgetUsd; AgentTimeoutMinutes = [int]$P.AgentTimeoutMinutes }
$labels = @($P.Labels)
$isIssue = ([string]$P.Kind -eq 'issue')
if ($isIssue) { $scope = 'GitHub Issue #' + $P.Issue + ' (' + $P.Title + ')'; $ref = '#' + $P.Issue }
else { $scope = 'integration task "' + $P.Title + '"'; $ref = 'integration' }

# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------

$state = Read-ItemState $P.StateFile
if ($null -eq $state) {
    $state = [pscustomobject]@{
        Key = $P.Key; Kind = $P.Kind; Issue = $P.Issue; Title = $P.Title; Branch = $P.Branch; Worktree = $wt
        Phase = 'PLANNED'; Reason = ''; Head = ''; Cycles = 0; MediumFixDone = $false; SyncCount = 0
        Reviews = [pscustomobject]@{}; Gate = $null; Rejected = ''; CostUsd = 0; History = @()
    }
}
foreach ($name in @('Rejected', 'CostUsd', 'SyncCount', 'MediumFixDone', 'Gate', 'History', 'PendingFix', 'InterruptedPhase', 'InterruptKind', 'PreviousLogDirs', 'Reviews')) {
    if (-not ($state.PSObject.Properties.Name -contains $name)) { Set-StateProperty $state $name $null }
}
if ($null -eq $state.CostUsd) { $state.CostUsd = 0 }
if ($null -eq $state.History) { $state.History = @() }
if ($null -eq $state.PreviousLogDirs) { $state.PreviousLogDirs = @() }
$hadHistory = (@($state.History).Count -gt 0)
$previousLogDir = [string](Get-StateValue $state 'LogDir' '')
if ($previousLogDir -and $previousLogDir -ne $logDir -and @($state.PreviousLogDirs) -notcontains $previousLogDir) {
    $state.PreviousLogDirs = @($state.PreviousLogDirs) + @($previousLogDir)
}
$resumeFrom = Get-ResumePhase ([string]$state.Phase) ([string]$state.InterruptedPhase)
Set-StateProperty $state 'WorkerPid' $PID
Set-StateProperty $state 'WorkerStartTicks' (Get-ProcessStartTicks $PID)
Set-StateProperty $state 'LogDir' $logDir
Set-StateProperty $state 'LastError' $null   # set again only if THIS process crashes (a killed worker leaves none)
$state.InterruptKind = $null

# '<pid>|<start ticks>' of the Claude process currently working in this worktree. A worker killed
# without its process tree leaves that agent running: a relaunched worker must never start a
# second agent beside it (see Wait-OrphanedAgent).
$agentPidFile = Join-Path (Split-Path -Parent ([string]$P.StateFile)) ([string]$P.Key + '.agent.pid')

function Save { Save-ItemState $P.StateFile $state }
function Set-Phase([string]$Phase) { $state.Phase = $Phase; $state.InterruptedPhase = $null; Save }
function Add-History([string]$Text) {
    Add-StateHistory $state $Text
    Write-RunLog $Text
    Save
}
function Add-Cost($Run) { if ($null -ne $Run.CostUsd) { $state.CostUsd = [Math]::Round([double]$state.CostUsd + [double]$Run.CostUsd, 4) } }
function Set-Blocked([string]$Reason) {
    $state.Phase = 'BLOCKED'; $state.Reason = $Reason; $state.InterruptedPhase = $null
    Add-History ('BLOCKED: ' + $Reason)
    exit 0
}
# Claude unavailable (session/token expired, quota, network): keep the checkpoint, stop here.
function Exit-Interrupted([string]$Kind, [string]$Reason) {
    $state.InterruptedPhase = $state.Phase; $state.Phase = 'INTERRUPTED'; $state.InterruptKind = $Kind; $state.Reason = $Reason
    Add-History ('INTERRUPTED (' + $Kind + ') during ' + $state.InterruptedPhase + ': ' + $Reason)
    exit 3
}
function Assert-AgentRun($Run, [bool]$HasResult, [string]$What) {
    $kind = Get-AgentRunFailure $Run $HasResult
    if (-not $kind) { return }
    $why = $What + ' process failed (exit ' + $Run.ExitCode + $(if ($Run.TimedOut) { ', timeout' } else { '' }) + '); see ' + $Run.ResultFile
    if ($kind -eq 'AUTH' -or $kind -eq 'TRANSIENT') { Exit-Interrupted $kind $why }
    throw $why   # CRASH: the orchestrator relaunches this worker (bounded) and the work resumes
}

# ---------------------------------------------------------------------------
# Prompts (short: agents load their own context from CLAUDE.md and the Issue)
# ---------------------------------------------------------------------------

$resultContract = @'
Finish with exactly one final line (single-line JSON):
ORCHESTRATOR-RESULT: {"status":"DONE|BLOCKED","category":"NONE|HUMAN_DECISION|REQUIREMENTS_CONFLICT|MISSING_CREDENTIAL|MAIN_OR_PRODUCTION|TECHNICAL","summary":"<one sentence>","rejected":[{"finding":"<title>","justification":"<why invalid>"}]}
Use BLOCKED with a human category ONLY for the human stop conditions of CLAUDE.md "Autonomous decision policy".
Compilation, test, lint, build, merge and ordinary implementation problems are yours to solve.
'@

function Get-ModeHeader {
    return @"
ORCHESTRATION MODE: integration
You run non-interactively in a dedicated Git worktree created by scripts/autonomous-development.ps1.
Worktree: $wt
Branch: $($P.Branch) (already checked out; never switch branches)
Integration base: $base (already contains every dependency of this work, reviewed and integrated)
Follow the "Integration mode" section of your agent definition: it overrides the main-branch, push and Pull Request steps.
"@
}

function Get-Fenced([string]$Text, [int]$MaxLines = 60) {
    $t = Get-TextTail $Text $MaxLines
    if (-not $t) { $t = '(none)' }
    return "``````n" + $t + "`n``````"
}

# Durable context for a NEW Claude process continuing interrupted work: no conversation
# history exists, everything comes from Git, the Issue, CLAUDE.md and the orchestration logs.
function Get-RecoveryContext([string]$Interrupted) {
    $status = (Invoke-GitIn $wt @('status', '--porcelain', '--untracked-files=all') -AllowFailure).Output
    $log = (Invoke-GitIn $wt @('log', '--oneline', '--no-decorate', ($base + '..HEAD')) -AllowFailure).Output
    $stat = (Invoke-GitIn $wt @('diff', '--stat', ($base + '...HEAD')) -AllowFailure).Output
    $uncommitted = (Invoke-GitIn $wt @('diff', '--stat', 'HEAD') -AllowFailure).Output
    $history = (@($state.History) | Select-Object -Last 25) -join "`n"
    $dirs = @(@($state.PreviousLogDirs) + @($logDir) | Where-Object { $_ }) -join "`n"
    $issueStep = ''
    if ($isIssue) { $issueStep = "- the Issue: ``gh issue view $($P.Issue) --comments`` and the BR-xxx rules, docs and ADRs it references;`n" }
    return @"

RECOVERY: a previous Claude Code process working on this item was interrupted while $Interrupted
(session/token expiration, timeout, crash, terminal closure or machine restart). You are a NEW process
with no memory of it. Reconstruct the context from durable sources before changing anything:
- CLAUDE.md;
$issueStep- ``git status``, ``git log --oneline $base..HEAD``, ``git diff $base...HEAD``, ``git diff HEAD`` (uncommitted) and untracked files;
- the existing code and tests those commits and files touch;
- previous orchestration logs (timeline ``worker.log``, agent ``*.result.md`` reports, ``findings-c*.md``) in:
$dirs
Then CONTINUE the existing work instead of restarting the item:
- keep every valid commit and every valid uncommitted/untracked file; complete, correct or commit them (``git commit --signoff``);
- never run ``git reset``, ``git clean``, ``git stash``, ``git rebase``, ``git checkout -- <path>`` or ``git restore`` on this work and never delete files you have not verified are invalid;
- if some existing work is genuinely wrong, fix it with a new commit and say so in your summary.

Current ``git status --porcelain``:
$(Get-Fenced $status)
Commits already on this branch ($base..HEAD):
$(Get-Fenced $log)
Net diff ($base...HEAD):
$(Get-Fenced $stat)
Uncommitted changes (tracked files):
$(Get-Fenced $uncommitted)
Orchestration timeline (most recent last):
$(Get-Fenced $history 25)
"@
}

function Get-ImplementPrompt([string]$Recovery) {
    if ($isIssue) {
        $task = @"
TASK: implement GitHub Issue #$($P.Issue) completely, strictly within its scope, following CLAUDE.md.
Load only the context it needs: CLAUDE.md, ``gh issue view $($P.Issue) --comments``, the BR-xxx rules, docs and ADRs the Issue references, and the relevant code and tests.
"@
    } else {
        $task = "TASK: $($P.Title)`n`n" + [string]$P.TaskText
    }
    return (Get-ModeHeader) + "`n" + $task + $Recovery + @"

- Commit all work on this branch with ``git commit --signoff`` (Conventional Commits, reference $ref); leave the worktree clean.
- Run the relevant verification before finishing (backend: ``gradlew.bat build`` in backend/; mobile: ``npm ci`` when needed, ``npm run check:api``, ``npm run verify`` in mobile/; after an API change: ``gradlew.bat updateOpenApi`` then ``npm run generate:api``).
- Perform the mandatory self-review (CLAUDE.md 4.5). Independent code/security/UX reviews are run by the orchestrator afterwards: do NOT invoke reviewer subagents.
- Do NOT push, create or edit Pull Requests, merge, switch branches or edit Issues.

$resultContract
"@
}

function Get-FixPrompt([string]$Findings, [int]$Cycle, [string]$Recovery) {
    return (Get-ModeHeader) + @"

TASK: FIX MODE for $scope - review/fix cycle $Cycle of $($P.MaxReviewCycles).
The findings below come from the orchestrator's deterministic verification gate and from independent reviewers of the net diff ($base...HEAD).
- Validate each finding against CLAUDE.md, the BR-xxx rules, ADRs, the security model and the scope; do not apply suggestions blindly.
- Fix every valid BLOCKER and HIGH finding and every MEDIUM finding that is within scope; LOW findings are optional.
- Add or adjust tests that prove each fix; re-run the relevant verification.
- List each finding you reject in "rejected" with a precise justification (it is shown to the reviewer next cycle).
- Commit with ``git commit --signoff`` (e.g. ``fix(<module>): address review findings ($ref)``); leave the worktree clean.
- Do NOT push, create Pull Requests, merge, switch branches or edit Issues.
$Recovery
$resultContract

FINDINGS
========
$Findings
"@
}

function Get-ReviewPrompt([string]$Reviewer) {
    if ($isIssue) { $what = "Issue: #$($P.Issue) - read it with ``gh issue view $($P.Issue)``." }
    else { $what = "Scope: integration task (no single Issue). Task description:`n" + [string]$P.TaskText }
    $focus = ''
    if ($Reviewer -eq 'mobile-ux-reviewer') { $focus = "`nReview the mobile screens/components changed by this diff in the context of the existing app (design system, navigation, shared components)." }
    $rejected = ''
    if ($state.Rejected) {
        $rejected = "`nThe developer rejected these earlier findings with a justification. Verify independently; re-raise one only if its justification is wrong:`n" + $state.Rejected + "`n"
    }
    return @"
ORCHESTRATION MODE: integration (read-only review of a dedicated worktree)
$what
Branch: $($P.Branch)
Base: $base - review ONLY the net diff of this work, using three-dot diffs:
  git diff $base...HEAD --stat
  git diff $base...HEAD
Code already on the base was reviewed when it was integrated; inspect it only as context.$focus
$rejected
Report in your Output format and end with the Summary block (BLOCKER/HIGH/MEDIUM/LOW counts).
"@
}

# ---------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------

# Waits for an agent orphaned by a previous worker; past the normal agent timeout it is stopped
# exactly as the timeout would have stopped it (its files stay on disk and are recovered).
function Wait-OrphanedAgent {
    if (-not (Test-RecordedProcessAlive $agentPidFile)) { return }
    $orphan = [int]((Read-TextFile $agentPidFile).Trim() -split '\|')[0]
    $since = (Get-Item -LiteralPath $agentPidFile).LastWriteTime
    Add-History ('an agent of a previous worker (PID ' + $orphan + ') is still working in this worktree; waiting for it')
    while (Test-RecordedProcessAlive $agentPidFile) {
        if (((Get-Date) - $since).TotalMinutes -gt [int]$P.AgentTimeoutMinutes) {
            & taskkill.exe /PID $orphan /T /F 2>&1 | Out-Null
            Add-History ('orphaned agent PID ' + $orphan + ' exceeded the agent timeout and was stopped')
            break
        }
        Start-Sleep -Seconds 5
    }
}

function Invoke-Developer([string]$Prompt, [string]$Name) {
    $agent = [string]$P.DeveloperAgent
    Add-History ('agent ' + $agent + ' started (' + $Name + ')')
    $run = Invoke-ClaudeAgent -Agent $agent -Prompt $Prompt -WorkingDirectory $wt -LogDir $logDir -Name $Name -Settings $settings -DeniedTools (Get-ImplementationDeniedTools) -PidFile $agentPidFile
    Add-Cost $run
    $res = ConvertFrom-AgentResult $run.Text
    Add-History ('agent ' + $agent + ' finished (' + $Name + '): ' + $res.Status + ' ' + $res.Category + ' - ' + $res.Summary + ' [' + $run.Minutes + ' min, exit ' + $run.ExitCode + $(if ($run.TimedOut) { ', TIMEOUT' } else { '' }) + ']')
    Assert-AgentRun $run $res.Found ('agent ' + $agent + ' (' + $Name + ')')
    if ($res.Rejected) { $state.Rejected = (@($state.Rejected, $res.Rejected) | Where-Object { $_ }) -join "`n" }
    if ($res.Status -eq 'BLOCKED' -and (Test-HumanStopCategory $res.Category)) {
        Set-Blocked ($res.Category + ': ' + $res.Summary + ' (see ' + $run.ResultFile + ')')
    }
    return $run
}

function Get-ReviewMap {
    $map = @{}
    if ($null -ne $state.Reviews) { foreach ($p in $state.Reviews.PSObject.Properties) { $map[$p.Name] = $p.Value } }
    return $map
}

function Invoke-Reviewer([string]$Reviewer, [hashtable]$Fingerprint, [int]$Cycle) {
    $name = $Reviewer + '-c' + $Cycle
    $parsed = $null; $run = $null
    foreach ($attempt in 1..2) {
        $run = Invoke-ClaudeAgent -Agent $Reviewer -Prompt (Get-ReviewPrompt $Reviewer) -WorkingDirectory $wt -LogDir $logDir -Name ($name + '-a' + $attempt) -Settings $settings -DeniedTools (Get-ReviewerDeniedTools) -PidFile $agentPidFile
        Add-Cost $run
        $parsed = ConvertFrom-ReviewReport $run.Text
        if ($parsed.Parsed -and -not $run.TimedOut) { break }
        $kind = Get-AgentRunFailure $run $parsed.Parsed
        if ($kind -eq 'AUTH' -or $kind -eq 'TRANSIENT') { Exit-Interrupted $kind ($Reviewer + ' process failed (exit ' + $run.ExitCode + '); see ' + $run.ResultFile) }
        Add-History ($Reviewer + ' produced no parseable Summary (attempt ' + $attempt + ')')
    }
    if (-not $parsed.Parsed) { Set-Blocked ($Reviewer + ' did not produce a usable report twice (see ' + $run.ResultFile + ')') }
    $c = $parsed.Counts
    Add-History ($Reviewer + ': BLOCKER ' + $c.BLOCKER + ', HIGH ' + $c.HIGH + ', MEDIUM ' + $c.MEDIUM + ', LOW ' + $c.LOW + $(if ($parsed.HumanDecision) { ' (HUMAN DECISION REQUIRED flagged)' } else { '' }))
    return [pscustomobject]@{
        Head = (Get-GitHead $wt); Fingerprint = [pscustomobject]$Fingerprint
        Counts = [pscustomobject]@{ BLOCKER = $c.BLOCKER; HIGH = $c.HIGH; MEDIUM = $c.MEDIUM; LOW = $c.LOW }
        HumanDecision = $parsed.HumanDecision; Report = $run.ResultFile
    }
}

# Runs the persisted fix pass (FIXING checkpoint). On resume the same cycle and findings are
# reused: an interrupted fix never consumes another review cycle.
function Invoke-PendingFix([bool]$Recovering) {
    $pf = $state.PendingFix
    $cycle = [int]$pf.Cycle
    $attempts = [int](Get-StateValue $pf 'Attempts' 0) + 1
    if ($attempts -gt 3) { Set-Blocked ('fix pass of cycle ' + $cycle + ' was interrupted ' + ($attempts - 1) + ' times; see ' + $logDir) }
    Set-StateProperty $pf 'Attempts' $attempts
    Set-Phase 'FIXING'
    $findings = Read-TextFile ([string]$pf.FindingsFile)
    $recovery = ''
    if ($Recovering -or $attempts -gt 1) {
        $recovery = Get-RecoveryContext ('fixing the findings of cycle ' + $cycle + ' (findings unchanged below; HEAD when the fix pass started: ' + $pf.HeadBefore + ')')
    }
    Invoke-Developer (Get-FixPrompt $findings $cycle $recovery) ('fix-c' + $cycle + $(if ($attempts -gt 1) { '-resume' + $attempts } else { '' })) | Out-Null
    $state.PendingFix = $null
    Set-Phase 'REVIEWING'
}

# ---------------------------------------------------------------------------
# Main flow
# ---------------------------------------------------------------------------

try {
    Add-History ('worker started for ' + $scope + ' in ' + $wt + ' (checkpoint ' + $state.Phase + $(if ($state.InterruptedPhase) { ' during ' + $state.InterruptedPhase } else { '' }) + ', resuming ' + $resumeFrom + ')')
    if (@('VALIDATED', 'INTEGRATING', 'INTEGRATED', 'BLOCKED') -contains $resumeFrom) { Write-RunLog ('nothing to do for the worker in ' + $resumeFrom); exit 0 }
    Wait-OrphanedAgent
    if (Test-MergeInProgress $wt) { Set-Blocked 'a merge is in progress in the worktree (unexpected); inspect it manually' }

    $ahead = Get-CommitsAhead $wt $base
    $dirty = [bool](Get-GitDirtyStatus $wt)
    $recovering = $hadHistory -or $ahead -gt 0 -or $dirty

    if ($resumeFrom -eq 'RUNNING') {
        Set-Phase 'RUNNING'
        $recovery = ''
        if ($recovering) { $recovery = Get-RecoveryContext 'implementing the task' }
        Invoke-Developer (Get-ImplementPrompt $recovery) ('develop' + $(if ($recovery) { '-resume' } else { '' })) | Out-Null
        Set-Phase 'IMPLEMENTED'
    } elseif ($resumeFrom -eq 'FIXING') {
        if ($null -ne $state.PendingFix -and (Test-Path -LiteralPath ([string]$state.PendingFix.FindingsFile))) {
            Invoke-PendingFix $true
        } else {
            # Findings no longer available: recompute them in the review loop for the same cycle.
            if ([int]$state.Cycles -gt 0) { $state.Cycles = [int]$state.Cycles - 1 }
            $state.PendingFix = $null
            Set-Phase 'REVIEWING'
        }
    }

    while ($true) {
        $cycle = [int]$state.Cycles
        $findings = New-Object System.Text.StringBuilder
        $blockerHigh = 0; $medium = 0; $gateOk = $true
        $dirty = Get-GitDirtyStatus $wt
        $ahead = Get-CommitsAhead $wt $base
        $head = Get-GitHead $wt

        if ($dirty) {
            $gateOk = $false
            [void]$findings.AppendLine("[BLOCKER] Uncommitted changes left in the worktree`nEvery change must be committed (signed off) or removed if it is not part of the work:`n" + $dirty + "`n")
        } elseif ($ahead -eq 0) {
            $gateOk = $false
            [void]$findings.AppendLine("[BLOCKER] No commit on the branch`nThe work is not implemented/committed yet. Implement $scope and commit it.`n")
        } else {
            $paths = Get-NetDiffPaths $wt $base
            $areas = Get-ChangedAreas $paths
            if ($null -ne $state.Gate -and $state.Gate.Head -eq $head -and $state.Gate.Passed) {
                Write-RunLog ('gate already passed for ' + $head)
            } else {
                Set-Phase 'TESTING'
                Add-History ('gate started (backend=' + $areas.Backend + ', mobile=' + $areas.Mobile + ', orchestrator=' + $areas.Orchestrator + ')')
                $gate = Invoke-VerificationGate -Worktree $wt -Areas $areas -LogDir (Join-Path $logDir ('gate-c' + $cycle))
                $state.Gate = [pscustomobject]@{ Head = $head; Passed = $gate.Passed; Steps = @($gate.Steps | ForEach-Object { $_.Name + '=' + $(if ($_.Passed) { 'ok' } else { 'FAILED' }) }) }
                Add-History ('gate ' + $(if ($gate.Passed) { 'PASSED' } else { 'FAILED' }) + ': ' + ($state.Gate.Steps -join ', '))
                if (-not $gate.Passed) { $gateOk = $false; [void]$findings.AppendLine((Format-GateFailure $gate $wt)) }
            }

            # Reviews run on a green gate only (a red build is fixed first; it saves review tokens).
            if ($gateOk) {
                Set-Phase 'REVIEWING'
                $fingerprint = Get-NetDiffFingerprint $wt $base
                $reviewers = @('code-reviewer')
                if (Test-SecurityReviewRelevant -Paths $paths -Labels $labels -Title ([string]$P.Title)) { $reviewers += 'security-reviewer' }
                if (Test-UxReviewRelevant -Paths $paths) { $reviewers += 'mobile-ux-reviewer' }
                $map = Get-ReviewMap
                foreach ($r in $reviewers) {
                    $last = $null
                    if ($map.ContainsKey($r)) { $last = $map[$r] }
                    if (Test-ReviewerRerunNeeded -Reviewer $r -LastReview $last -CurrentFingerprint $fingerprint -Labels $labels -Title ([string]$P.Title)) {
                        $map[$r] = Invoke-Reviewer $r $fingerprint $cycle
                        $obj = New-Object psobject
                        foreach ($k in $map.Keys) { $obj | Add-Member -NotePropertyName $k -NotePropertyValue $map[$k] }
                        $state.Reviews = $obj; Save
                    } else {
                        Write-RunLog ($r + ': skipped, nothing relevant changed since its last review')
                    }
                    $rev = $map[$r]
                    $blockerHigh += [int]$rev.Counts.BLOCKER + [int]$rev.Counts.HIGH
                    $medium += [int]$rev.Counts.MEDIUM
                    if (([int]$rev.Counts.BLOCKER + [int]$rev.Counts.HIGH + [int]$rev.Counts.MEDIUM) -gt 0 -or $rev.HumanDecision) {
                        [void]$findings.AppendLine('--- ' + $r + ' report (' + $rev.Report + ') ---')
                        [void]$findings.AppendLine((Read-TextFile $rev.Report))
                        [void]$findings.AppendLine('')
                    }
                }
            }
        }

        $mediumPending = ($medium -gt 0 -and -not $state.MediumFixDone)
        if ($gateOk -and $blockerHigh -eq 0 -and -not $mediumPending) {
            $state.Phase = 'VALIDATED'; $state.InterruptedPhase = $null; $state.Head = $head; $state.Reason = ''
            Add-History ('VALIDATED at ' + $head + ' (cycles used: ' + $cycle + ', remaining MEDIUM: ' + $medium + ')')
            exit 0
        }
        if ($cycle -ge [int]$P.MaxReviewCycles) {
            $why = @()
            if (-not $gateOk) { $why += 'verification gate failing' }
            if ($blockerHigh -gt 0) { $why += ($blockerHigh.ToString() + ' BLOCKER/HIGH finding(s) unresolved') }
            if ($why.Count -eq 0) { $why += 'MEDIUM findings unresolved' }
            Set-Blocked (($why -join ', ') + ' after ' + $cycle + ' review/fix cycle(s); see ' + $logDir)
        }
        # Checkpoint the fix intent BEFORE starting the fix agent: findings file, cycle and HEAD.
        $findingsFile = Join-Path $logDir ('findings-c' + ($cycle + 1) + '.md')
        Write-TextFile $findingsFile $findings.ToString()
        if ($gateOk -and $blockerHigh -eq 0 -and $mediumPending) { $state.MediumFixDone = $true }
        $state.Cycles = $cycle + 1
        $state.PendingFix = [pscustomobject]@{ Cycle = ($cycle + 1); FindingsFile = $findingsFile; HeadBefore = $head; Attempts = 0 }
        Set-Phase 'FIXING'
        Invoke-PendingFix $false
    }
}
catch {
    # Not BLOCKED: the orchestrator relaunches a crashed worker a bounded number of times and
    # the relaunched worker resumes from the persisted checkpoint.
    $msg = $_.Exception.Message
    try {
        $errors = 0
        if ($state.PSObject.Properties.Name -contains 'ErrorCount' -and $null -ne $state.ErrorCount) { $errors = [int]$state.ErrorCount }
        Set-StateProperty $state 'ErrorCount' ($errors + 1)
        Set-StateProperty $state 'LastError' $msg
        Save
    } catch { }
    Write-RunLog ('worker error: ' + ($_ | Out-String)) 'ERROR'
    exit 1
}
