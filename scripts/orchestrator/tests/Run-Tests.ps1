<#
    Tests of the orchestrator: planning logic, recovery decisions, real worker processes killed
    mid-flight (fake claude, throw-away Git repositories, no model call, no network), idempotent
    integration, restart reconstruction and the DryRun read-only guarantee.
    Dependency-free (Windows PowerShell 5.1 ships only Pester 3); exit code 0 = all passed.

        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1 -IncludeDryRunSmoke
#>
param([switch]$IncludeDryRunSmoke)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\AutonomousDev.Planning.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\AutonomousDev.Runtime.psm1') -Force

$script:Failures = New-Object System.Collections.ArrayList
$script:Count = 0

function Test-Case([string]$Name, [scriptblock]$Body) {
    $script:Count++
    try { & $Body; Write-Host ('  ok   ' + $Name) -ForegroundColor Green }
    catch { [void]$script:Failures.Add($Name); Write-Host ('  FAIL ' + $Name + ' : ' + $_.Exception.Message) -ForegroundColor Red }
}
function Assert-Equal($Expected, $Actual, [string]$Message = '') {
    $e = ($Expected | Out-String).Trim(); $a = ($Actual | Out-String).Trim()
    if ($e -ne $a) { throw ("expected <" + $e + "> but was <" + $a + "> " + $Message) }
}
function Assert-True($Value, [string]$Message = 'expected true') { if (-not $Value) { throw $Message } }
function Assert-False($Value, [string]$Message = 'expected false') { if ($Value) { throw $Message } }

function New-Issue([int]$Number, [string]$Title, [string[]]$Labels, [string]$Deps, [string]$Extra = '') {
    $body = "## Goal`nx`n`n## Acceptance criteria`n- [ ] works`n`n" + $Extra + "## Dependencies`n" + $Deps + "`n`n## References`nnone`n"
    return @{ number = $Number; title = $Title; labels = $Labels; body = $body }
}

$ready = @('status:ready', 'priority:high')

Write-Host 'Dependency parsing'
Test-Case 'bullet #N and identifiers are resolved, notes ignored' {
    $body = "## Dependencies`n- AUTH-001 (#75)`n- MOBILE-002`nThe ADR deliberately does not block #12.`n`n## References`n"
    $d = Get-IssueDependencies -Number 70 -Body $body -IdentifierIndex @{ 'MOBILE-002' = 69 }
    Assert-True $d.Known
    Assert-Equal '75 69' ($d.Numbers -join ' ')
    Assert-Equal 0 $d.Unresolved.Count
}
Test-Case 'None means no dependency' {
    $d = Get-IssueDependencies -Number 1 -Body "## Dependencies`n`nNone`n" -IdentifierIndex @{}
    Assert-True $d.Known; Assert-Equal 0 $d.Numbers.Count
}
Test-Case 'unknown identifier is unresolved' {
    $d = Get-IssueDependencies -Number 1 -Body "## Dependencies`n- FOO-123`n" -IdentifierIndex @{}
    Assert-Equal 'FOO-123' ($d.Unresolved -join ',')
}
Test-Case 'missing section is unknown' {
    $d = Get-IssueDependencies -Number 1 -Body "## Goal`nx" -IdentifierIndex @{}
    Assert-False $d.Known
}
Test-Case 'CRLF bodies are parsed' {
    $d = Get-IssueDependencies -Number 2 -Body "## Dependencies`r`n- #1`r`n" -IdentifierIndex @{}
    Assert-Equal '1' ($d.Numbers -join ',')
}

Write-Host 'Branch names and markers'
Test-Case 'branch name from identifier title' {
    Assert-Equal 'feature/71-expenses-and-categories-list-quick' (Get-IssueBranchName -Number 71 -Title ('MOBILE-004 ' + [char]0x2014 + ' Expenses and categories: list, quick add, detail') -Labels @())
}
Test-Case 'bug label gives fix/ prefix' {
    Assert-Equal 'fix/9-crash-on-login' (Get-IssueBranchName -Number 9 -Title 'Crash on login' -Labels @('bug'))
}
Test-Case 'integration trailers are detected' {
    $log = "Merge branch 'feature/76-x'`n`nIntegrates-Issue: #76`nSigned-off-by: A <a@b>`n`nIntegrates-Issue: #77`n"
    $set = Get-IntegratedIssueNumbers $log
    Assert-True $set.ContainsKey(76); Assert-True $set.ContainsKey(77); Assert-False $set.ContainsKey(7)
}

Write-Host 'Execution plan (DAG of the CLAUDE.md example)'
function New-ExampleIssues {
    @(
        (New-Issue 76 'AUTH-002 - Refresh' $ready '- AUTH-001 (#75)'),
        (New-Issue 77 'AUTH-003 - Registration' $ready '- AUTH-001 (#75)'),
        (New-Issue 69 'MOBILE-002 - Design system' ($ready + 'area:mobile') '- MOBILE-001 (#68)'),
        (New-Issue 70 'MOBILE-003 - Session' ($ready + 'area:mobile') "- #68`n- #69`n- #75`n- #76`n- #77"),
        (New-Issue 71 'MOBILE-004 - Expenses' ($ready + 'area:mobile') "- #69`n- #70"),
        (New-Issue 72 'MOBILE-005 - Budget' ($ready + 'area:mobile') "- #69`n- #70"),
        (New-Issue 73 'MOBILE-006 - Dashboard' ($ready + 'area:mobile') "- #69`n- #70"),
        (New-Issue 74 'MOBILE-007 - Polish' (@('status:ready', 'priority:low', 'area:mobile')) "- #71`n- #72`n- #73")
    )
}
$done = @{ 75 = 'merged'; 68 = 'merged' }

Test-Case 'runnable set and waves follow the DAG, not Issue numbers' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done }
    Assert-Equal '69 76 77' ((Select-RunnableIssues $plan | ForEach-Object Number | Sort-Object) -join ' ')
    Assert-Equal 2 $plan[70].Wave
    Assert-Equal 3 $plan[71].Wave; Assert-Equal 3 $plan[72].Wave; Assert-Equal 3 $plan[73].Wave
    Assert-Equal 4 $plan[74].Wave
}
Test-Case 'runnable ordering prefers Issues that unblock the most work' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done }
    Assert-Equal 69 (Select-RunnableIssues $plan)[0].Number   # 69 unblocks 70..74 (5); 76/77 unblock 5 too -> priority/number tie-break
}
Test-Case 'integrated dependencies unlock dependents' {
    $d = @{ 75 = 'm'; 68 = 'm'; 69 = 'integrated'; 76 = 'integrated'; 77 = 'integrated' }
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $d }
    Assert-Equal 'RUNNABLE' $plan[70].Status
    Assert-Equal 'DONE' $plan[69].Status
}
Test-Case 'a blocked Issue isolates only its dependents' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done; LocalState = @{ 76 = @{ Phase = 'blocked'; Reason = 'max cycles' } } }
    Assert-Equal 'BLOCKED' $plan[76].Status
    Assert-Equal 'RUNNABLE' $plan[77].Status
    Assert-Equal 'RUNNABLE' $plan[69].Status
    Assert-Equal 0 $plan[70].Wave
    Assert-Equal '76' ($plan[70].RootBlockers -join ',')
    Assert-Equal '76' ($plan[74].RootBlockers -join ',')
}
Test-Case 'missing approval label makes an Issue NEEDS_HUMAN and blocks dependents' {
    $issues = New-ExampleIssues
    $issues[1] = New-Issue 77 'AUTH-003 - Registration' @('priority:high') '- AUTH-001 (#75)'
    $plan = New-ExecutionPlan -Issues $issues -Context @{ Done = $done }
    Assert-Equal 'NEEDS_HUMAN' $plan[77].Status
    Assert-True ($plan[77].Reason -like '*status:ready*')
    Assert-Equal '77' ($plan[70].RootBlockers -join ',')
}
Test-Case 'open question blocks' {
    $issues = @(New-Issue 1 'X-001 - a' $ready 'None' "## Open question`nWhich?`n`n")
    $plan = New-ExecutionPlan -Issues $issues -Context @{}
    Assert-Equal 'NEEDS_HUMAN' $plan[1].Status
}
Test-Case 'dependency cycles are reported' {
    $issues = @((New-Issue 1 'a' $ready '- #2'), (New-Issue 2 'b' $ready '- #1'), (New-Issue 3 'c' $ready 'None'))
    $plan = New-ExecutionPlan -Issues $issues -Context @{}
    Assert-True ($plan[1].Reason -like '*cycle*'); Assert-True ($plan[2].Reason -like '*cycle*')
    Assert-Equal 'RUNNABLE' $plan[3].Status
}
Test-Case 'dependency closed without implementation is unsatisfiable' {
    $issues = @(New-Issue 5 'e' $ready '- #4')
    $plan = New-ExecutionPlan -Issues $issues -Context @{ ClosedNotDone = @{ 4 = 'CLOSED without merged implementation' } }
    Assert-Equal 'NEEDS_HUMAN' $plan[5].Status
}
Test-Case 'target restricts to requested Issues plus dependency closure' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done; Target = @(70) }
    Assert-Equal '69 70 76 77' (($plan.Keys | Sort-Object) -join ' ')
}
Test-Case 'human PR Issue nothing depends on does not block completion' {
    $issues = @((New-Issue 78 'DOC-001 - ADR' (@('status:ready', 'documentation')) 'None'), (New-Issue 69 'm' $ready 'None'))
    $plan = New-ExecutionPlan -Issues $issues -Context @{ HumanPr = @{ 78 = 81 }; Done = @{ 69 = 'integrated' } }
    Assert-Equal 'HUMAN_PR' $plan[78].Status
    Assert-True (Test-PlanComplete $plan)
}
Test-Case 'plan not complete while work remains' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done }
    Assert-False (Test-PlanComplete $plan)
}
Test-Case 'approved and running states are not re-launched' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done; Running = @{ 76 = $true }; LocalState = @{ 77 = @{ Phase = 'approved' } } }
    Assert-Equal 'IN_PROGRESS' $plan[76].Status; Assert-Equal 'APPROVED' $plan[77].Status
    Assert-Equal '69' ((Select-RunnableIssues $plan | ForEach-Object Number) -join ',')
}

Test-Case 'AssumeApproved preview ignores approval labels but not structural gaps' {
    $issues = @((New-Issue 1 'a' @('priority:high') 'None'), (New-Issue 2 'b' @('status:blocked') "- #1" "## Open question`nx`n`n"), (New-Issue 3 'c' @() "- FOO-999"))
    $plan = New-ExecutionPlan -Issues $issues -Context @{ AssumeApproved = $true }
    Assert-Equal 'RUNNABLE' $plan[1].Status; Assert-Equal 'WAITING' $plan[2].Status; Assert-Equal 2 $plan[2].Wave
    Assert-Equal 'NEEDS_HUMAN' $plan[3].Status
}

Write-Host 'Review relevance'
Test-Case 'pure UI change: UX review, no security review' {
    $paths = @('mobile/src/features/budget/BudgetScreen.tsx', 'mobile/app/(tabs)/budget.tsx')
    Assert-True (Test-UxReviewRelevant $paths)
    Assert-False (Test-SecurityReviewRelevant -Paths $paths -Labels @('area:mobile') -Title 'MOBILE-005 - Budget: view, set')
}
Test-Case 'session code needs security review' {
    Assert-True (Test-SecurityReviewRelevant -Paths @('mobile/src/features/identity/session/tokenStore.ts') -Labels @() -Title 'x')
}
Test-Case 'backend production code needs security review' {
    Assert-True (Test-SecurityReviewRelevant -Paths @('backend/src/main/java/com/couplefinance/expense/web/ExpenseController.java') -Labels @() -Title 'x')
}
Test-Case 'auth title needs security review' {
    Assert-True (Test-SecurityReviewRelevant -Paths @('docs/x.md') -Labels @() -Title 'AUTH-002 - Refresh-token rotation')
}
Test-Case 'changed areas' {
    $a = Get-ChangedAreas @('api/openapi.yaml', 'backend/src/main/A.java')
    Assert-True $a.Backend; Assert-True $a.Mobile; Assert-False $a.DocsOnly
    Assert-True (Get-ChangedAreas @('docs/a.md', 'README.md')).DocsOnly
}
Test-Case 'clean reviewer is skipped when its scope did not change' {
    $last = [pscustomobject]@{ Fingerprint = [pscustomobject]@{ 'mobile/app/a.tsx' = 'p1'; 'mobile/src/features/identity/session.ts' = 's1' }; Counts = [pscustomobject]@{ BLOCKER = 0; HIGH = 0; MEDIUM = 0; LOW = 1 } }
    $cur = @{ 'mobile/app/a.tsx' = 'p2'; 'mobile/src/features/identity/session.ts' = 's1' }
    Assert-False (Test-ReviewerRerunNeeded -Reviewer 'security-reviewer' -LastReview $last -CurrentFingerprint $cur -Labels @())
    Assert-True (Test-ReviewerRerunNeeded -Reviewer 'mobile-ux-reviewer' -LastReview $last -CurrentFingerprint $cur -Labels @())
    Assert-True (Test-ReviewerRerunNeeded -Reviewer 'code-reviewer' -LastReview $last -CurrentFingerprint $cur -Labels @())
}
Test-Case 'unchanged net diff skips every reviewer; reviewer with findings re-runs on change' {
    $fp = @{ 'a.ts' = '1' }
    $last = [pscustomobject]@{ Fingerprint = [pscustomobject]@{ 'a.ts' = '1' }; Counts = [pscustomobject]@{ BLOCKER = 1; HIGH = 0; MEDIUM = 0; LOW = 0 } }
    Assert-False (Test-ReviewerRerunNeeded -Reviewer 'code-reviewer' -LastReview $last -CurrentFingerprint $fp -Labels @())
    Assert-True (Test-ReviewerRerunNeeded -Reviewer 'security-reviewer' -LastReview $last -CurrentFingerprint @{ 'docs/a.md' = '2'; 'a.ts' = '1' } -Labels @())
}

Write-Host 'Agent output parsing'
Test-Case 'review summary counts' {
    $r = ConvertFrom-ReviewReport "[HIGH] x`nLocation: a`n`nSummary`nBLOCKER: 0`nHIGH: 2`nMEDIUM: 1`nLOW: 3"
    Assert-True $r.Parsed; Assert-Equal 2 $r.Counts.HIGH; Assert-Equal 3 $r.Counts.LOW; Assert-False $r.HumanDecision
}
Test-Case 'review summary in markdown bold and human decision flag' {
    $r = ConvertFrom-ReviewReport "HUMAN DECISION REQUIRED`n## Summary`n- **BLOCKER: 1**`n- **HIGH**: 0"
    Assert-Equal 1 $r.Counts.BLOCKER; Assert-True $r.HumanDecision
}
Test-Case 'unparseable review is flagged' {
    Assert-False (ConvertFrom-ReviewReport 'no summary here').Parsed
}
Test-Case 'agent result marker' {
    $r = ConvertFrom-AgentResult "done`nORCHESTRATOR-RESULT: {""status"":""blocked"",""category"":""human_decision"",""summary"":""needs ADR""}"
    Assert-True $r.Found; Assert-Equal 'BLOCKED' $r.Status; Assert-True (Test-HumanStopCategory $r.Category)
    Assert-False (Test-HumanStopCategory 'TECHNICAL')
    Assert-False (ConvertFrom-AgentResult 'nothing').Found
}

Write-Host 'Command-line quoting'
Test-Case 'arguments with spaces and quotes' {
    Assert-Equal '-p "Bash(gh pr merge *)" "a \"b\"" "C:\x y\\"' (Join-CommandLine @('-p', 'Bash(gh pr merge *)', 'a "b"', 'C:\x y\'))
    Assert-Equal '""' (Join-CommandLine @(''))
}

Write-Host 'Recovery decisions (pure)'
Test-Case 'legacy and unknown phases are normalized' {
    Assert-Equal 'RUNNING' (ConvertTo-ItemPhase 'developing'); Assert-Equal 'VALIDATED' (ConvertTo-ItemPhase 'approved')
    Assert-Equal 'BLOCKED' (ConvertTo-ItemPhase 'blocked'); Assert-Equal 'FIXING' (ConvertTo-ItemPhase 'fixing')
    Assert-Equal 'RUNNING' (ConvertTo-ItemPhase 'weird'); Assert-Equal '' (ConvertTo-ItemPhase '')
    Assert-Equal 'REVIEWING' (Get-ResumePhase 'INTERRUPTED' 'REVIEWING'); Assert-Equal 'RUNNING' (Get-ResumePhase 'PLANNED' '')
}
Test-Case 'Git wins: integrated item is skipped whatever the state says' {
    $d = Get-RecoveryAction @{ Phase = 'VALIDATED'; Integrated = $true; WorkerAlive = $true }
    Assert-Equal 'skip' $d.Action; Assert-Equal 'INTEGRATED' $d.Phase; Assert-True ($d.Detail -like '*state said VALIDATED*')
}
Test-Case 'state INTEGRATED without trace on the integration branch is re-validated' {
    $d = Get-RecoveryAction @{ Phase = 'INTEGRATED'; Integrated = $false; CommitsAhead = 2 }
    Assert-Equal 'resume-review' $d.Action; Assert-Equal 'INTERRUPTED' $d.Phase; Assert-Equal 'REVIEWING' $d.InterruptedPhase
}
Test-Case 'live worker (same PID and start time) is adopted' {
    Assert-Equal 'adopt' (Get-RecoveryAction @{ Phase = 'FIXING'; WorkerAlive = $true }).Action
}
Test-Case 'implementation death: with work -> recovery agent, without -> restart' {
    $d = Get-RecoveryAction @{ Phase = 'RUNNING'; Dirty = $true; WorktreeExists = $true }
    Assert-Equal 'recovery-agent' $d.Action; Assert-Equal 'RUNNING' $d.InterruptedPhase; Assert-True ($d.Detail -like '*uncommitted work*')
    Assert-Equal 'recovery-agent' (Get-RecoveryAction @{ Phase = 'developing'; CommitsAhead = 1 }).Action
    Assert-Equal 'restart' (Get-RecoveryAction @{ Phase = 'RUNNING' }).Action
}
Test-Case 'death after commit / during tests or review resumes the review loop' {
    foreach ($p in @('IMPLEMENTED', 'TESTING', 'REVIEWING')) {
        $d = Get-RecoveryAction @{ Phase = $p; CommitsAhead = 1 }
        Assert-Equal 'resume-review' $d.Action $p; Assert-Equal $p $d.InterruptedPhase
    }
    Assert-Equal 'recovery-agent' (Get-RecoveryAction @{ Phase = 'REVIEWING'; CommitsAhead = 1; Dirty = $true }).Action
}
Test-Case 'death with persisted findings resumes the fix' {
    Assert-Equal 'resume-fix' (Get-RecoveryAction @{ Phase = 'FIXING'; CommitsAhead = 1; PendingFix = $true }).Action
    Assert-Equal 'resume-fix' (Get-RecoveryAction @{ Phase = 'INTERRUPTED'; InterruptedPhase = 'FIXING'; CommitsAhead = 1 }).Action
}
Test-Case 'validated work is integrated unless the branch moved' {
    Assert-Equal 'integrate' (Get-RecoveryAction @{ Phase = 'VALIDATED'; HeadMatchesValidated = $true; CommitsAhead = 1 }).Action
    Assert-Equal 'resume-review' (Get-RecoveryAction @{ Phase = 'VALIDATED'; HeadMatchesValidated = $false; CommitsAhead = 2 }).Action
    Assert-Equal 'integrate' (Get-RecoveryAction @{ Phase = 'INTEGRATING'; MergeInProgress = $true }).Action
}
Test-Case 'blocked stays blocked; branch without state is recovered' {
    Assert-Equal 'blocked' (Get-RecoveryAction @{ Phase = 'BLOCKED'; Dirty = $true }).Action
    $d = Get-RecoveryAction @{ HasState = $false; BranchExists = $true; CommitsAhead = 3 }
    Assert-Equal 'recovery-agent' $d.Action
    Assert-Equal 'new' (Get-RecoveryAction @{ HasState = $false }).Action
}
Test-Case 'Claude failures are classified' {
    Assert-Equal 'AUTH' (Get-AgentFailureKind 'OAuth token has expired. Please run /login')
    Assert-Equal 'AUTH' (Get-AgentFailureKind 'Claude AI usage limit reached|1700000000')
    Assert-Equal 'TRANSIENT' (Get-AgentFailureKind 'API Error: 529 {"type":"overloaded_error"}')
    Assert-Equal 'TRANSIENT' (Get-AgentFailureKind 'request failed: getaddrinfo ENOTFOUND api.anthropic.com')
    Assert-Equal 'CRASH' (Get-AgentFailureKind 'something odd'); Assert-Equal 'CRASH' (Get-AgentFailureKind 'token expired' -TimedOut $true)
}
Test-Case 'plan uses canonical phases (VALIDATED awaits integration, INTERRUPTED resumes)' {
    $plan = New-ExecutionPlan -Issues (New-ExampleIssues) -Context @{ Done = $done; LocalState = @{ 76 = @{ Phase = 'VALIDATED' }; 77 = @{ Phase = 'INTERRUPTED'; InterruptedPhase = 'FIXING' } } }
    Assert-Equal 'APPROVED' $plan[76].Status
    Assert-Equal 'RUNNABLE' $plan[77].Status; Assert-Equal 'resume (INTERRUPTED during FIXING)' $plan[77].Reason
}

Write-Host 'Read-only guarantees (DryRun)'
Test-Case 'only read-only git / gh commands are allowed' {
    Assert-True (Test-ReadOnlyGitCommand @('rev-parse', 'HEAD')); Assert-True (Test-ReadOnlyGitCommand @('worktree', 'list', '--porcelain'))
    Assert-True (Test-ReadOnlyGitCommand @('ls-remote', 'origin'))
    foreach ($c in @('fetch', 'push', 'merge', 'commit', 'branch', 'worktree add', 'worktree prune', 'reset', 'checkout')) { Assert-False (Test-ReadOnlyGitCommand ($c -split ' ')) $c }
    Assert-True (Test-ReadOnlyGhCommand @('issue', 'list', '--state', 'open')); Assert-True (Test-ReadOnlyGhCommand @('repo', 'view'))
    foreach ($c in @('pr create', 'pr edit', 'pr merge', 'issue edit', 'issue comment', 'api repos', 'label create')) { Assert-False (Test-ReadOnlyGhCommand ($c -split ' ')) $c }
}
Test-Case 'read-only mode refuses a modifying git command' {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
    Set-GitReadOnly $true
    try {
        Assert-True ((Invoke-GitIn $root @('rev-parse', 'HEAD')).ExitCode -eq 0)
        $refused = $false
        try { [void](Invoke-GitIn $root @('branch', 'should-never-exist')) } catch { $refused = ($_.Exception.Message -like '*read-only mode refused*') }
        Assert-True $refused 'modifying git command was not refused'
    } finally { Set-GitReadOnly $false }
    Assert-False (Test-GitRefExists $root 'refs/heads/should-never-exist')
}

# ---------------------------------------------------------------------------
# Git fixtures (throw-away local repositories, fake claude: no model call, no network)
# ---------------------------------------------------------------------------

function Invoke-TGit([string[]]$A) {
    $ErrorActionPreference = 'Continue'
    $out = & git @A 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('git ' + ($A -join ' ') + ' failed: ' + ($out | Out-String)) }
    return (($out | ForEach-Object { [string]$_ }) -join "`n").Trim()
}
function New-Fixture {
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('cf-orch-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $fx = @{ Tmp = $tmp; Origin = (Join-Path $tmp 'origin.git'); Repo = (Join-Path $tmp 'repo'); State = (Join-Path $tmp 'state'); Fake = (Join-Path $tmp 'fake'); Runs = 0 }
    New-Item -ItemType Directory -Force -Path $fx.State, $fx.Fake | Out-Null
    [void](Invoke-TGit @('init', '-q', '--bare', $fx.Origin))
    [void](Invoke-TGit @('clone', '-q', $fx.Origin, $fx.Repo))
    [void](Invoke-TGit @('-C', $fx.Repo, 'config', 'user.name', 'Orchestrator Test'))
    [void](Invoke-TGit @('-C', $fx.Repo, 'config', 'user.email', 'orchestrator-test@example.invalid'))
    Set-Content -LiteralPath (Join-Path $fx.Repo 'README.md') -Value 'test'
    [void](Invoke-TGit @('-C', $fx.Repo, 'add', 'README.md')); [void](Invoke-TGit @('-C', $fx.Repo, 'commit', '-q', '-m', 'init'))
    [void](Invoke-TGit @('-C', $fx.Repo, 'branch', '-M', 'main'))
    [void](Invoke-TGit @('-C', $fx.Repo, 'push', '-q', 'origin', 'main')); [void](Invoke-TGit @('-C', $fx.Repo, 'push', '-q', 'origin', 'main:refs/heads/integration/mvp'))
    [void](Invoke-TGit @('-C', $fx.Repo, 'fetch', '-q', 'origin'))
    return $fx
}
function Remove-Fixture($Fx) { if ($null -ne $Fx) { Remove-Item -LiteralPath $Fx.Tmp -Recurse -Force -ErrorAction SilentlyContinue } }
function Add-FixtureWorktree($Fx, [string]$Name, [string]$Branch) {
    $wt = Join-Path $Fx.Tmp ('wt\' + $Name)
    [void](Invoke-TGit @('-C', $Fx.Repo, 'worktree', 'add', '-q', '-b', $Branch, $wt, 'origin/integration/mvp'))
    return $wt
}
function Add-FixtureCommit([string]$Wt, [string]$File, [string]$Text) {
    $path = Join-Path $Wt $File
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    Set-Content -LiteralPath $path -Value $Text
    [void](Invoke-TGit @('-C', $Wt, 'add', '-A')); [void](Invoke-TGit @('-C', $Wt, 'commit', '-q', '--signoff', '-m', ('docs: ' + $File)))
    return (Invoke-TGit @('-C', $Wt, 'rev-parse', 'HEAD'))
}
function Write-FixtureState($Fx, [hashtable]$State) {
    $base = @{ Kind = 'issue'; Title = 'X'; Reason = ''; Head = ''; Cycles = 0; History = @(); Worktree = ''; Branch = '' }
    foreach ($k in $State.Keys) { $base[$k] = $State[$k] }
    if (-not $base.ContainsKey('Key')) { $base.Key = 'issue-' + $base.Issue }
    $file = Join-Path $Fx.State ($base.Key + '.json')
    [System.IO.File]::WriteAllText($file, (([pscustomobject]$base) | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding $false))
    return $file
}
function Read-FixtureState($Fx, [string]$Key) { return (Get-Content -LiteralPath (Join-Path $Fx.State ($Key + '.json')) -Raw | ConvertFrom-Json) }
function Invoke-FixtureRecovery($Fx) {
    $refs = @('origin/integration/mvp')
    return (Invoke-StateRecovery -Root $Fx.Repo -StateDir $Fx.State -BaseRef 'origin/integration/mvp' -IntegrationRefs $refs -IntegratedIssues (Get-IntegratedIssueSet $Fx.Repo 'origin/main' $refs))
}
# Runs the real worker as a separate process (a new orchestrator run each time: new log dir).
function Invoke-FixtureWorker($Fx, [string]$Wt, [string]$Branch, [hashtable]$FakeEnv = @{}) {
    $Fx.Runs++
    $stateFile = Join-Path $Fx.State 'issue-7.json'
    $params = @{
        Key = 'issue-7'; Kind = 'issue'; Issue = 7; Title = 'DOC-007 - Write the feature doc'; Labels = @('documentation'); Branch = $Branch
        Worktree = $Wt; BaseRef = 'origin/integration/mvp'; LogDir = (Join-Path $Fx.Tmp ('runs\run' + $Fx.Runs + '\issue-7')); StateFile = $stateFile
        DeveloperAgent = 'issue-developer'; TaskText = ''; MaxReviewCycles = 3
        Claude = (Join-Path $PSScriptRoot 'fixtures\fake-claude.cmd'); PermissionMode = 'auto'; Model = ''; MaxBudgetUsd = 0; AgentTimeoutMinutes = 5
    }
    $pf = Join-Path $Fx.Tmp ('params-' + $Fx.Runs + '.json')
    [System.IO.File]::WriteAllText($pf, ($params | ConvertTo-Json), (New-Object System.Text.UTF8Encoding $false))
    $vars = @{ FAKE_CLAUDE_STATE = $Fx.Fake; FAKE_CLAUDE_WORKER_STATE = $stateFile; FAKE_CLAUDE_DIE = ''; FAKE_CLAUDE_FAIL = ''; FAKE_CLAUDE_REVIEW_CLEAN = '' }
    foreach ($k in $FakeEnv.Keys) { $vars[$k] = $FakeEnv[$k] }
    foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $vars[$k], 'Process') }
    try {
        $ErrorActionPreference = 'Continue'
        $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot '..\Invoke-IssueWorker.ps1') -ParamsFile $pf 2>&1 | Out-String
        $code = $LASTEXITCODE
    } finally { foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $null, 'Process') } }
    return @{ ExitCode = $code; Output = $out; LogDir = $params.LogDir }
}
function Get-Calls($Fx) { return ((Get-Content -LiteralPath (Join-Path $Fx.Fake 'calls.log')) -join ',') }

Write-Host 'Worker (fake claude, throw-away repository)'
Test-Case 'worker: implement -> gate -> HIGH finding -> fix -> re-review -> VALIDATED' {
    $fx = New-Fixture
    try {
        $wt = Add-FixtureWorktree $fx 'issue-7' 'feature/7-doc'
        $r = Invoke-FixtureWorker $fx $wt 'feature/7-doc'
        if ($r.ExitCode -ne 0) { throw ('worker exit ' + $r.ExitCode + "`n" + $r.Output) }
        $state = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'VALIDATED' $state.Phase ("`n" + $r.Output)
        Assert-Equal 1 $state.Cycles
        Assert-Equal (Invoke-TGit @('-C', $wt, 'rev-parse', 'HEAD')) $state.Head
        Assert-Equal 'issue-developer,code-reviewer,issue-developer,code-reviewer' (Get-Calls $fx)
        Assert-True ((Get-Content (Join-Path $r.LogDir 'findings-c1.md') -Raw) -match 'Missing detail') 'findings not forwarded to the fixer'
        Assert-True ((Invoke-TGit @('-C', $wt, 'log', '-1', '--format=%B')) -match 'Signed-off-by') 'fix commit not signed off'
        Assert-True ($null -eq $state.PendingFix) 'fix intent not cleared'
    } finally { Remove-Fixture $fx }
}

Write-Host 'Crash recovery (real worker processes killed with their whole tree)'
Test-Case 'RECOVERY uncommitted work survives worker death and is continued by a fresh agent' {
    $fx = New-Fixture
    try {
        $wt = Add-FixtureWorktree $fx 'issue-7' 'feature/7-doc'
        $r1 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_DIE = 'developer-dirty'; FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        Assert-True ($r1.ExitCode -ne 0) ('worker should have died; exit ' + $r1.ExitCode)
        Assert-Equal 'RUNNING' (Read-FixtureState $fx 'issue-7').Phase
        $status = Invoke-TGit @('-C', $wt, 'status', '--porcelain', '--untracked-files=all')
        Assert-True ($status -match 'notes/wip.txt') ('untracked work lost: ' + $status)

        $entries = Invoke-FixtureRecovery $fx
        Assert-Equal 'recovery-agent' $entries[0].Decision.Action
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'INTERRUPTED' $s.Phase; Assert-Equal 'RUNNING' $s.InterruptedPhase
        Assert-Equal 'work in progress' (Get-Content -LiteralPath (Join-Path $wt 'notes\wip.txt') -Raw).Trim() 'recovery touched the worktree'

        $r2 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        if ($r2.ExitCode -ne 0) { throw ('relaunched worker exit ' + $r2.ExitCode + "`n" + $r2.Output) }
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'VALIDATED' $s.Phase
        Assert-Equal 'work in progress' (Invoke-TGit @('-C', $wt, 'show', 'HEAD:notes/wip.txt')) 'interrupted file not preserved in the commit'
        Assert-Equal 'issue-developer:died,issue-developer,code-reviewer' (Get-Calls $fx)
        $prompt = Get-Content -LiteralPath (Join-Path $r2.LogDir 'develop-resume.prompt.md') -Raw
        Assert-True ($prompt -match 'RECOVERY:') 'no recovery context'
        Assert-True ($prompt -match 'notes/wip.txt') 'recovery context lacks git status'
        Assert-True ($prompt.Contains($r1.LogDir)) 'recovery context lacks previous logs'
        Assert-True ($prompt -match 'never run ``?git reset') 'recovery context lacks the no-discard rule'
        Assert-Equal 1 @($s.PreviousLogDirs).Count
    } finally { Remove-Fixture $fx }
}
Test-Case 'RECOVERY committed-but-not-integrated work resumes at review without re-implementing' {
    $fx = New-Fixture
    try {
        $wt = Add-FixtureWorktree $fx 'issue-7' 'feature/7-doc'
        $r1 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_DIE = 'code-reviewer'; FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        Assert-True ($r1.ExitCode -ne 0) 'worker should have died during review'
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'REVIEWING' $s.Phase; Assert-True $s.Gate.Passed 'gate result not persisted'
        $headBefore = Invoke-TGit @('-C', $wt, 'rev-parse', 'HEAD')

        $entries = Invoke-FixtureRecovery $fx
        Assert-Equal 'resume-review' $entries[0].Decision.Action
        $r2 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        if ($r2.ExitCode -ne 0) { throw ('relaunched worker exit ' + $r2.ExitCode + "`n" + $r2.Output) }
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'VALIDATED' $s.Phase
        Assert-Equal $headBefore $s.Head 'existing commit was not reused'
        Assert-Equal 'issue-developer,code-reviewer:died,code-reviewer' (Get-Calls $fx) 'implementation repeated or review skipped'
        Assert-True ((Get-Content -LiteralPath (Join-Path $r2.LogDir 'worker.log') -Raw) -match 'gate already passed') 'gate re-run although HEAD did not change'
        Assert-Equal 0 $s.Cycles
    } finally { Remove-Fixture $fx }
}
Test-Case 'RECOVERY interrupted fix resumes with persisted findings without consuming a cycle' {
    $fx = New-Fixture
    try {
        $wt = Add-FixtureWorktree $fx 'issue-7' 'feature/7-doc'
        $r1 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_DIE = 'fix' }
        Assert-True ($r1.ExitCode -ne 0) 'worker should have died in the fix pass'
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'FIXING' $s.Phase; Assert-Equal 1 $s.Cycles; Assert-Equal 1 $s.PendingFix.Cycle
        Assert-True (Test-Path -LiteralPath $s.PendingFix.FindingsFile) 'findings not persisted before the fix'

        Assert-Equal 'resume-fix' (Invoke-FixtureRecovery $fx)[0].Decision.Action
        $r2 = Invoke-FixtureWorker $fx $wt 'feature/7-doc'
        if ($r2.ExitCode -ne 0) { throw ('relaunched worker exit ' + $r2.ExitCode + "`n" + $r2.Output) }
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'VALIDATED' $s.Phase
        Assert-Equal 1 $s.Cycles 'the interrupted fix consumed an extra review cycle'
        Assert-Equal 'issue-developer,code-reviewer,issue-developer:died,issue-developer,code-reviewer' (Get-Calls $fx) 'review repeated before the fix'
        $prompt = Get-Content -LiteralPath (Join-Path $r2.LogDir 'fix-c1-resume2.prompt.md') -Raw
        Assert-True ($prompt -match 'Missing detail') 'persisted findings not reused'
        Assert-True ($prompt -match 'RECOVERY:') 'no recovery context for the resumed fix'
    } finally { Remove-Fixture $fx }
}
Test-Case 'RECOVERY expired Claude session checkpoints INTERRUPTED (exit 3) and the rerun continues' {
    $fx = New-Fixture
    try {
        $wt = Add-FixtureWorktree $fx 'issue-7' 'feature/7-doc'
        $r1 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_FAIL = 'auth'; FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        Assert-Equal 3 $r1.ExitCode ("`n" + $r1.Output)
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'INTERRUPTED' $s.Phase; Assert-Equal 'RUNNING' $s.InterruptedPhase; Assert-Equal 'AUTH' $s.InterruptKind
        Assert-Equal 'restart' (Invoke-FixtureRecovery $fx)[0].Decision.Action
        $r2 = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        Assert-Equal 0 $r2.ExitCode ("`n" + $r2.Output)
        Assert-Equal 'VALIDATED' (Read-FixtureState $fx 'issue-7').Phase
        Assert-Equal 'issue-developer:auth-failed,issue-developer,code-reviewer' (Get-Calls $fx)
    } finally { Remove-Fixture $fx }
}

Test-Case 'RECOVERY relaunched worker waits for an agent orphaned by the dead worker (never two agents in one worktree)' {
    $fx = New-Fixture
    $orphan = Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile -NonInteractive -Command Start-Sleep -Seconds 6' -WindowStyle Hidden -PassThru
    try {
        $wt = Add-FixtureWorktree $fx 'issue-7' 'feature/7-doc'
        [System.IO.File]::WriteAllText((Join-Path $fx.State 'issue-7.agent.pid'), ([string]$orphan.Id + '|' + (Get-ProcessStartTicks $orphan.Id)))
        $r = Invoke-FixtureWorker $fx $wt 'feature/7-doc' @{ FAKE_CLAUDE_REVIEW_CLEAN = '1' }
        Assert-Equal 0 $r.ExitCode ("`n" + $r.Output)
        $s = Read-FixtureState $fx 'issue-7'
        Assert-Equal 'VALIDATED' $s.Phase
        $wait = @($s.History | Where-Object { $_ -match 'still working in this worktree' })
        Assert-Equal 1 $wait.Count 'worker did not wait for the orphaned agent'
        Assert-True $orphan.HasExited 'worker started its agent while the orphan was alive'
    } finally {
        Stop-Process -Id $orphan.Id -Force -ErrorAction SilentlyContinue
        Remove-Fixture $fx
    }
}

Write-Host 'Idempotent integration'
function New-ValidatedItem($Fx, [int]$N) {
    $wt = Add-FixtureWorktree $Fx ('issue-' + $N) ('feature/' + $N + '-x')
    $head = Add-FixtureCommit $wt ('docs/f' + $N + '.md') ('feature ' + $N)
    $file = Write-FixtureState $Fx @{ Issue = $N; Phase = 'VALIDATED'; Head = $head; Branch = ('feature/' + $N + '-x'); Worktree = $wt }
    return @{ Wt = $wt; Head = $head; File = $file; Message = ("chore(integration): integrate #$N x`n`nIntegrates-Issue: #$N`nReviewed-Head: $head") }
}
function Get-IntegrationWorktree($Fx) {
    $iwt = Join-Path $Fx.Tmp 'wt\integration'
    if (-not (Test-Path -LiteralPath $iwt)) { [void](Invoke-TGit @('-C', $Fx.Repo, 'worktree', 'add', '-q', '-b', 'integration/mvp', $iwt, 'origin/integration/mvp')) }
    return $iwt
}
function Get-TrailerCount($Fx, [int]$N) {
    $log = Invoke-TGit @('-C', $Fx.Repo, 'log', '--format=%B', 'origin/main..origin/integration/mvp')
    return ([regex]::Matches($log, ('(?m)^Integrates-Issue: #' + $N + '\s*$'))).Count
}
Test-Case 'RECOVERY integrated-and-pushed but state not updated is never integrated twice' {
    $fx = New-Fixture
    try {
        $it = New-ValidatedItem $fx 7
        $iwt = Get-IntegrationWorktree $fx
        Assert-Equal 'merged' (Invoke-IdempotentIntegrationMerge -IntegrationWt $iwt -Head $it.Head -Message $it.Message)
        [void](Invoke-TGit @('-C', $iwt, 'push', '-q', 'origin', 'integration/mvp')); [void](Invoke-TGit @('-C', $fx.Repo, 'fetch', '-q', 'origin'))
        # The orchestrator dies here: the state file still says VALIDATED.
        Assert-Equal 'VALIDATED' (Read-FixtureState $fx 'issue-7').Phase
        $e = (Invoke-FixtureRecovery $fx)[0]
        Assert-Equal 'skip' $e.Decision.Action
        Assert-Equal 'INTEGRATED' (Read-FixtureState $fx 'issue-7').Phase 'Git reality did not win'
        Assert-Equal 'already-integrated' (Invoke-IdempotentIntegrationMerge -IntegrationWt $iwt -Head $it.Head -Message $it.Message)
        Assert-Equal 1 (Get-TrailerCount $fx 7)
        # A repair item has no Issue trailer: its validated head on the branch is enough.
        $repair = [pscustomobject]@{ Kind = 'repair'; Issue = 0; Head = $it.Head }
        Assert-True (Test-ItemIntegrated -Root $fx.Repo -State $repair -IntegrationRefs @('origin/integration/mvp') -IntegratedIssues @{})
    } finally { Remove-Fixture $fx }
}
Test-Case 'RECOVERY interrupted integration merge is concluded once, then detected' {
    $fx = New-Fixture
    try {
        $it = New-ValidatedItem $fx 7
        $iwt = Get-IntegrationWorktree $fx
        [void](Invoke-TGit @('-C', $iwt, 'merge', '--no-ff', '--no-commit', $it.Head))   # killed before the merge commit
        Assert-True (Test-MergeInProgress $iwt)
        Assert-Equal 'concluded' (Invoke-IdempotentIntegrationMerge -IntegrationWt $iwt -Head $it.Head -Message $it.Message)
        Assert-Equal 'already-integrated' (Invoke-IdempotentIntegrationMerge -IntegrationWt $iwt -Head $it.Head -Message $it.Message)
        [void](Invoke-TGit @('-C', $iwt, 'push', '-q', 'origin', 'integration/mvp')); [void](Invoke-TGit @('-C', $fx.Repo, 'fetch', '-q', 'origin'))
        Assert-Equal 1 (Get-TrailerCount $fx 7)
        Assert-Equal '1' (Invoke-TGit @('-C', $fx.Repo, 'rev-list', '--count', '--merges', 'origin/main..origin/integration/mvp'))
    } finally { Remove-Fixture $fx }
}

Write-Host 'Orchestrator restart'
function Start-Sleeper { return (Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile -NonInteractive -Command Start-Sleep -Seconds 120' -WindowStyle Hidden -PassThru) }
Test-Case 'RECOVERY restart with several Issue states reconstructs the DAG and the report' {
    $fx = New-Fixture
    $sleeper = Start-Sleeper
    try {
        # 1: integrated and pushed, state lost its update; 2: died in review; 3: died with uncommitted work;
        # 6: blocked; 8: worker still alive (orchestrator killed); 4 waits for 3; 7 waits for 6; 5 is independent.
        $i1 = New-ValidatedItem $fx 1
        $iwt = Get-IntegrationWorktree $fx
        [void](Invoke-IdempotentIntegrationMerge -IntegrationWt $iwt -Head $i1.Head -Message $i1.Message)
        [void](Invoke-TGit @('-C', $iwt, 'push', '-q', 'origin', 'integration/mvp')); [void](Invoke-TGit @('-C', $fx.Repo, 'fetch', '-q', 'origin'))
        $wt2 = Add-FixtureWorktree $fx 'issue-2' 'feature/2-b'; [void](Add-FixtureCommit $wt2 'docs/b.md' 'b')
        [void](Write-FixtureState $fx @{ Issue = 2; Phase = 'REVIEWING'; Branch = 'feature/2-b'; Worktree = $wt2; WorkerPid = 999999; History = @('x') })
        $wt3 = Add-FixtureWorktree $fx 'issue-3' 'feature/3-c'; Set-Content -LiteralPath (Join-Path $wt3 'draft.md') -Value 'draft'
        [void](Write-FixtureState $fx @{ Issue = 3; Phase = 'RUNNING'; Branch = 'feature/3-c'; Worktree = $wt3 })
        [void](Write-FixtureState $fx @{ Issue = 6; Phase = 'BLOCKED'; Reason = 'max cycles' })
        $wt8 = Add-FixtureWorktree $fx 'issue-8' 'feature/8-h'
        [void](Write-FixtureState $fx @{ Issue = 8; Phase = 'REVIEWING'; Branch = 'feature/8-h'; Worktree = $wt8; WorkerPid = $sleeper.Id; WorkerStartTicks = (Get-ProcessStartTicks $sleeper.Id) })

        $entries = Invoke-FixtureRecovery $fx
        $running = @{}; foreach ($e in $entries) { if ($e.Decision.Action -eq 'adopt') { $running[$e.Issue] = $true } }
        $integrated = Get-IntegratedIssueSet $fx.Repo 'origin/main' @('origin/integration/mvp')
        $doneMap = @{}; foreach ($k in $integrated.Keys) { $doneMap[$k] = 'integrated' }
        $issues = @(
            (New-Issue 1 'A-001 - a' $ready 'None'), (New-Issue 2 'A-002 - b' $ready 'None'), (New-Issue 3 'A-003 - c' $ready 'None'),
            (New-Issue 4 'A-004 - d' $ready '- #3'), (New-Issue 5 'A-005 - e' $ready 'None'), (New-Issue 6 'A-006 - f' $ready 'None'),
            (New-Issue 7 'A-007 - g' $ready '- #6'), (New-Issue 8 'A-008 - h' $ready 'None'))
        $plan = New-ExecutionPlan -Issues $issues -Context @{ Done = $doneMap; LocalState = (Get-LocalPhaseMap $fx.State); Running = $running }

        Assert-Equal 'DONE' $plan[1].Status
        Assert-Equal 'RUNNABLE' $plan[2].Status; Assert-Equal 'resume (INTERRUPTED during REVIEWING)' $plan[2].Reason
        Assert-Equal 'RUNNABLE' $plan[3].Status; Assert-Equal 'resume (INTERRUPTED during RUNNING)' $plan[3].Reason
        Assert-Equal 'WAITING' $plan[4].Status; Assert-Equal 2 $plan[4].Wave
        Assert-Equal 'RUNNABLE' $plan[5].Status 'unrelated branch must continue'
        Assert-Equal 'BLOCKED' $plan[6].Status
        Assert-Equal 'WAITING' $plan[7].Status; Assert-Equal '6' ($plan[7].RootBlockers -join ',')
        Assert-Equal 'IN_PROGRESS' $plan[8].Status
        Assert-Equal '2 3 5' ((Select-RunnableIssues $plan | ForEach-Object Number | Sort-Object) -join ' ')

        $report = (Format-RecoveryReport $entries $plan) -join "`n"
        foreach ($expected in @('#1 INTEGRATED -> skip', '#2 INTERRUPTED during REVIEWING -> resume review', '#3 INTERRUPTED during RUNNING -> recovery agent',
                '#4 WAITING -> dependency #3', '#5 RUNNABLE -> continue', '#6 BLOCKED -> stays blocked', '#7 WAITING -> dependency #6', '#8 REVIEWING -> adopt running worker')) {
            Assert-True ($report.Contains($expected)) ("report lacks '" + $expected + "':`n" + $report)
        }
        Assert-True (Test-Path -LiteralPath (Join-Path $wt3 'draft.md')) 'uncommitted work of #3 was touched'
        Assert-Equal 'INTEGRATED' (Read-FixtureState $fx 'issue-1').Phase
        Assert-Equal 'REVIEWING' (Read-FixtureState $fx 'issue-8').Phase 'a live worker state must not be rewritten'
    } finally {
        Stop-Process -Id $sleeper.Id -Force -ErrorAction SilentlyContinue
        Remove-Fixture $fx
    }
}
Test-Case 'RECOVERY reused PIDs after a reboot are not mistaken for live workers or orchestrators' {
    $sleeper = Start-Sleeper
    $lock = Join-Path ([System.IO.Path]::GetTempPath()) ('cf-orch-lock-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    try {
        $ticks = Get-ProcessStartTicks $sleeper.Id
        Assert-True (Test-ProcessAlive $sleeper.Id $ticks)
        Assert-False (Test-ProcessAlive $sleeper.Id '123') 'PID with another start time treated as alive'
        Assert-Equal 'recovery-agent' (Get-RecoveryAction @{ Phase = 'RUNNING'; Dirty = $true; WorkerAlive = (Test-ProcessAlive $sleeper.Id '123') }).Action
        [System.IO.File]::WriteAllText($lock, ([string]$sleeper.Id + '|' + $ticks))
        Assert-Equal $sleeper.Id (Test-LockHeld $lock)
        [System.IO.File]::WriteAllText($lock, ([string]$sleeper.Id + '|123'))
        Assert-Equal 0 (Test-LockHeld $lock) 'stale lock (reused PID) blocks the restart'
        [System.IO.File]::WriteAllText($lock, '999999')
        Assert-Equal 0 (Test-LockHeld $lock)
    } finally {
        Stop-Process -Id $sleeper.Id -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
    }
}

if ($IncludeDryRunSmoke) {
    Write-Host 'DryRun safety test (real GitHub read, no modification)'
    Test-Case 'DryRun exits 0 and modifies nothing (refs incl. remote-tracking, worktrees, index, status, .autonomous-dev)' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
        $snap = {
            $ErrorActionPreference = 'Continue'
            $gitDir = (& git -C $root rev-parse --absolute-git-dir).Trim()
            $files = @('index', 'FETCH_HEAD', 'packed-refs', 'HEAD') | ForEach-Object {
                $p = Join-Path $gitDir $_
                if (Test-Path -LiteralPath $p) { $_ + ' ' + (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash } else { $_ + ' missing' }
            }
            $auto = Join-Path $root '.autonomous-dev'
            $autoList = if (Test-Path -LiteralPath $auto) { Get-ChildItem -LiteralPath $auto -Recurse -Force | ForEach-Object { $_.FullName + ' ' + $_.LastWriteTimeUtc.Ticks } } else { 'none' }
            return ((& git -C $root --no-optional-locks status --porcelain | Out-String) + (& git -C $root worktree list --porcelain | Out-String) +
                (& git -C $root for-each-ref --format='%(refname) %(objectname)' | Out-String) + ($files -join "`n") + "`n" + ($autoList -join "`n"))
        }
        $before = & $snap
        $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\autonomous-development.ps1') -DryRun 2>&1 | Out-String
        $code = $LASTEXITCODE
        $after = & $snap
        if ($code -ne 0) { throw ('exit ' + $code + "`n" + $out) }
        Assert-Equal $before $after 'repository state changed during DryRun'
        Assert-True ($out -match 'Execution waves') 'missing waves section'
        Assert-True ($out -match 'Nothing was modified') 'missing final DryRun statement'
        Assert-False ($out -match 'refused a modifying') 'DryRun attempted a modifying command'
    }
}

Write-Host ''
if ($script:Failures.Count -gt 0) {
    Write-Host ($script:Failures.Count.ToString() + ' of ' + $script:Count + ' tests FAILED') -ForegroundColor Red
    exit 1
}
Write-Host ('All ' + $script:Count + ' tests passed') -ForegroundColor Green
exit 0
