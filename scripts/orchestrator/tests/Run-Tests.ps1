<#
    Unit tests for the orchestrator planning logic and a DryRun smoke test.
    Dependency-free (Windows PowerShell 5.1 ships only Pester 3); exit code 0 = all passed.

        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1
#>
param([switch]$IncludeDryRunSmoke)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\AutonomousDev.Planning.psm1') -Force

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

Write-Host 'Worker smoke test (throw-away local repository, fake claude: no model call, no network)'
Test-Case 'worker: implement -> gate -> HIGH finding -> fix -> re-review -> approved' {
    $ErrorActionPreference = 'Continue'   # native stderr must not become terminating errors (PS 5.1)
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('cf-orch-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $origin = Join-Path $tmp 'origin.git'; $repo = Join-Path $tmp 'repo'; $wt = Join-Path $tmp 'wt\issue-7'; $fake = Join-Path $tmp 'fake'
        New-Item -ItemType Directory -Force -Path $fake | Out-Null
        $q = { param($a) & git @a 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw ('git ' + ($a -join ' ') + ' failed') } }
        & $q @('init', '-q', '--bare', $origin)
        & $q @('clone', '-q', $origin, $repo)
        & $q @('-C', $repo, 'config', 'user.name', 'Orchestrator Test')
        & $q @('-C', $repo, 'config', 'user.email', 'orchestrator-test@example.invalid')
        Set-Content -LiteralPath (Join-Path $repo 'README.md') -Value 'test'
        & $q @('-C', $repo, 'add', 'README.md'); & $q @('-C', $repo, 'commit', '-q', '-m', 'init'); & $q @('-C', $repo, 'branch', '-M', 'main')
        & $q @('-C', $repo, 'push', '-q', 'origin', 'main'); & $q @('-C', $repo, 'push', '-q', 'origin', 'main:refs/heads/integration/mvp')
        & $q @('-C', $repo, 'fetch', '-q', 'origin')
        & $q @('-C', $repo, 'worktree', 'add', '-q', '-b', 'feature/7-doc', $wt, 'origin/integration/mvp')
        $env:FAKE_CLAUDE_STATE = $fake
        $params = @{
            Key = 'issue-7'; Kind = 'issue'; Issue = 7; Title = 'DOC-007 - Write the feature doc'; Labels = @('documentation'); Branch = 'feature/7-doc'
            Worktree = $wt; BaseRef = 'origin/integration/mvp'; LogDir = (Join-Path $tmp 'logs'); StateFile = (Join-Path $tmp 'issue-7.json')
            DeveloperAgent = 'issue-developer'; TaskText = ''; MaxReviewCycles = 3
            Claude = (Join-Path $PSScriptRoot 'fixtures\fake-claude.cmd'); PermissionMode = 'auto'; Model = ''; MaxBudgetUsd = 0; AgentTimeoutMinutes = 5
        }
        $pf = Join-Path $tmp 'params.json'
        [System.IO.File]::WriteAllText($pf, ($params | ConvertTo-Json), (New-Object System.Text.UTF8Encoding $false))
        $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot '..\Invoke-IssueWorker.ps1') -ParamsFile $pf 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { throw ("worker exit " + $LASTEXITCODE + "`n" + $out) }
        $state = Get-Content -LiteralPath $params.StateFile -Raw | ConvertFrom-Json
        Assert-Equal 'approved' $state.Phase ("`n" + $out)
        Assert-Equal 1 $state.Cycles
        Assert-Equal ((& git -C $wt rev-parse HEAD).Trim()) $state.Head
        Assert-Equal 'issue-developer,code-reviewer,issue-developer,code-reviewer' ((Get-Content (Join-Path $fake 'calls.log')) -join ',')
        Assert-True ((Get-Content (Join-Path $params.LogDir 'findings-c1.md') -Raw) -match 'Missing detail') 'findings not forwarded to the fixer'
        Assert-True ((& git -C $wt log -1 --format=%B) -match 'Signed-off-by') 'fix commit not signed off'
    } finally {
        Remove-Item Env:\FAKE_CLAUDE_STATE -ErrorAction SilentlyContinue
        & git -C (Join-Path $tmp 'repo') worktree remove --force (Join-Path $tmp 'wt\issue-7') 2>&1 | Out-Null
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($IncludeDryRunSmoke) {
    Write-Host 'DryRun smoke test (real GitHub read, no modification)'
    Test-Case 'DryRun exits 0 and leaves the repository untouched' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
        $before = (& git -C $root status --porcelain | Out-String) + (& git -C $root worktree list | Out-String) + (& git -C $root for-each-ref --format='%(refname) %(objectname)' refs/heads | Out-String)
        $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\autonomous-development.ps1') -DryRun 2>&1 | Out-String
        $code = $LASTEXITCODE
        $after = (& git -C $root status --porcelain | Out-String) + (& git -C $root worktree list | Out-String) + (& git -C $root for-each-ref --format='%(refname) %(objectname)' refs/heads | Out-String)
        if ($code -ne 0) { throw ('exit ' + $code + "`n" + $out) }
        Assert-Equal $before $after 'repository state changed during DryRun'
        Assert-True ($out -match 'Execution waves') 'missing waves section'
        Assert-True ($out -match 'Nothing was modified') 'missing final DryRun statement'
    }
}

Write-Host ''
if ($script:Failures.Count -gt 0) {
    Write-Host ($script:Failures.Count.ToString() + ' of ' + $script:Count + ' tests FAILED') -ForegroundColor Red
    exit 1
}
Write-Host ('All ' + $script:Count + ' tests passed') -ForegroundColor Green
exit 0
