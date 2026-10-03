<#
    Pure planning logic of the CoupleFinance autonomous orchestrator.

    No Git, GitHub, file-system or process access here: every function works on plain
    data so that dependency parsing, DAG planning, wave computation, review relevance
    and agent-output parsing are unit-tested by scripts/orchestrator/tests/Run-Tests.ps1.

    Windows PowerShell 5.1 compatible (no ternary / null-coalescing operators).
#>
Set-StrictMode -Version 3

$script:ReadyLabel = 'status:ready'
$script:BlockedLabel = 'status:blocked'
$script:NonImplementationLabels = @('question', 'invalid', 'wontfix', 'duplicate')
$script:PriorityRank = @{ 'priority:high' = 0; 'priority:medium' = 1; 'priority:low' = 2 }

# ---------------------------------------------------------------------------
# Issue body parsing (repository conventions, see CLAUDE.md "READY Issues")
# ---------------------------------------------------------------------------

function Get-IssueSection {
    param([string]$Body, [string]$HeadingPattern)
    if ([string]::IsNullOrEmpty($Body)) { return $null }
    $normalized = $Body -replace "`r`n", "`n"
    $regex = '(?ms)^##[ \t]+(?:' + $HeadingPattern + ')[ \t]*:?[ \t]*\n(.*?)(?=^##[ \t]|\z)'
    $match = [regex]::Match($normalized, $regex, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.Trim()
}

function Get-IssueIdentifier {
    param([string]$Title)
    $m = [regex]::Match([string]$Title, '^\s*([A-Z][A-Z0-9]*-\d+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

# Dependencies are bullet lines of the '## Dependencies' section referencing '#N' or an
# identifier such as 'AUTH-001'. Non-bullet lines are informational notes (not blocking).
function Get-IssueDependencies {
    param([int]$Number, [string]$Body, [hashtable]$IdentifierIndex)
    $result = @{ Known = $false; Numbers = (New-Object System.Collections.ArrayList); Unresolved = (New-Object System.Collections.ArrayList) }
    $section = Get-IssueSection $Body 'Dependencies'
    if ($null -eq $section) { return $result }
    $result.Known = $true
    foreach ($rawLine in ($section -split "`n")) {
        $line = $rawLine.Trim()
        if (-not $line) { continue }
        if ($line -match '^[-*+]\s+(?i)(none|n/a)\.?$') { continue }
        if ($line -notmatch '^[-*+]\s+') { continue }
        $numbers = [regex]::Matches($line, '(?<![\w/])#(\d+)\b')
        if ($numbers.Count -gt 0) {
            foreach ($n in $numbers) {
                $num = [int]$n.Groups[1].Value
                if ($num -ne $Number -and -not $result.Numbers.Contains($num)) { [void]$result.Numbers.Add($num) }
            }
            continue
        }
        $ids = [regex]::Matches($line, '\b([A-Z][A-Z0-9]*-\d{3})\b')
        if ($ids.Count -eq 0) { [void]$result.Unresolved.Add($line); continue }
        foreach ($id in $ids) {
            $key = $id.Groups[1].Value
            if ($null -ne $IdentifierIndex -and $IdentifierIndex.ContainsKey($key)) {
                $num = [int]$IdentifierIndex[$key]
                if ($num -ne $Number -and -not $result.Numbers.Contains($num)) { [void]$result.Numbers.Add($num) }
            } else {
                [void]$result.Unresolved.Add($key)
            }
        }
    }
    return $result
}

# Reasons why an Issue is not approved for autonomous implementation (empty = approved).
# These are human gates: the orchestrator never edits labels or Issue bodies itself.
function Get-ApprovalGaps {
    param([string[]]$Labels, [string]$Body, $Dependencies)
    $gaps = New-Object System.Collections.ArrayList
    if ($Labels -notcontains $script:ReadyLabel) { [void]$gaps.Add('not labelled ' + $script:ReadyLabel) }
    if ($Labels -contains $script:BlockedLabel) { [void]$gaps.Add('labelled ' + $script:BlockedLabel) }
    foreach ($l in $Labels) { if ($script:NonImplementationLabels -contains $l) { [void]$gaps.Add('labelled ' + $l) } }
    $ac = Get-IssueSection $Body 'Acceptance criteria'
    if ($null -eq $ac -or $ac -notmatch '(?m)^\s*[-*]\s+\[[ xX]\]') { [void]$gaps.Add('no acceptance-criteria checklist') }
    $openQuestion = Get-IssueSection $Body 'Open questions?'
    if ($openQuestion) { [void]$gaps.Add("unresolved '## Open question' section") }
    if (-not $Dependencies.Known) { [void]$gaps.Add("no '## Dependencies' section") }
    foreach ($u in $Dependencies.Unresolved) { [void]$gaps.Add('unresolvable dependency reference: ' + $u) }
    return , $gaps
}

function Test-PrClosesIssue {
    param($Pr, [int]$Number)
    $refs = $null
    if ($Pr -is [hashtable]) { if ($Pr.ContainsKey('closingIssuesReferences')) { $refs = $Pr['closingIssuesReferences'] } }
    elseif ($Pr.PSObject.Properties.Name -contains 'closingIssuesReferences') { $refs = $Pr.closingIssuesReferences }
    if ($refs) { foreach ($ref in $refs) { if ([int]$ref.number -eq $Number) { return $true } } }
    if ($Pr.body -and ($Pr.body -match ('(?i)\b(close[sd]?|fix(e[sd])?|resolve[sd]?)\s*:?\s+#' + $Number + '\b'))) { return $true }
    if ($Pr.headRefName -and ($Pr.headRefName -match ('^(feature|fix)/' + $Number + '-'))) { return $true }
    return $false
}

function Get-PriorityRank {
    param([string[]]$Labels)
    $rank = 3
    foreach ($l in $Labels) { if ($script:PriorityRank.ContainsKey($l) -and $script:PriorityRank[$l] -lt $rank) { $rank = $script:PriorityRank[$l] } }
    return $rank
}

function Get-IssueBranchName {
    param([int]$Number, [string]$Title, [string[]]$Labels)
    $prefix = 'feature'
    if ($Labels -contains 'bug') { $prefix = 'fix' }
    $text = [string]$Title
    $text = $text -replace '^\s*[A-Z][A-Z0-9]*-\d+\s*', ''
    $text = $text.ToLowerInvariant().Normalize([Text.NormalizationForm]::FormD)
    $text = [regex]::Replace($text, '\p{Mn}', '')
    $text = [regex]::Replace($text, '[^a-z0-9]+', '-').Trim('-')
    $slug = ''
    foreach ($w in @($text -split '-' | Where-Object { $_ } | Select-Object -First 5)) {
        $candidate = if ($slug) { $slug + '-' + $w } else { $w }
        if ($candidate.Length -gt 40) { break }
        $slug = $candidate
    }
    if (-not $slug) { $slug = 'issue' }
    return ($prefix + '/' + $Number + '-' + $slug)
}

# Integration merge commits carry an 'Integrates-Issue: #N' trailer (see the orchestrator).
function Get-IntegratedIssueNumbers {
    param([string]$LogText)
    $set = @{}
    if ([string]::IsNullOrEmpty($LogText)) { return $set }
    foreach ($m in [regex]::Matches($LogText, '(?m)^\s*Integrates-Issue:\s*#(\d+)\s*$')) { $set[[int]$m.Groups[1].Value] = $true }
    return $set
}

# ---------------------------------------------------------------------------
# Dependency graph and plan
# ---------------------------------------------------------------------------

# Returns the set of Issue numbers that are part of a dependency cycle (among $Edges keys).
function Find-DependencyCycles {
    param([hashtable]$Edges)
    $index = 0
    $stack = New-Object System.Collections.Stack
    $onStack = @{}; $indices = @{}; $low = @{}
    $inCycle = @{}
    $visit = $null
    $visit = {
        param($v)
        $indices[$v] = $script:tarjanIndex; $low[$v] = $script:tarjanIndex; $script:tarjanIndex++
        $stack.Push($v); $onStack[$v] = $true
        foreach ($w in $Edges[$v]) {
            if (-not $Edges.ContainsKey($w)) { continue }
            if (-not $indices.ContainsKey($w)) {
                & $visit $w
                if ($low[$w] -lt $low[$v]) { $low[$v] = $low[$w] }
            } elseif ($onStack.ContainsKey($w) -and $onStack[$w]) {
                if ($indices[$w] -lt $low[$v]) { $low[$v] = $indices[$w] }
            }
        }
        if ($low[$v] -eq $indices[$v]) {
            $component = New-Object System.Collections.ArrayList
            do { $w = $stack.Pop(); $onStack[$w] = $false; [void]$component.Add($w) } while ($w -ne $v)
            $selfLoop = ($component.Count -eq 1) -and (@($Edges[$v]) -contains $v)
            if ($component.Count -gt 1 -or $selfLoop) { foreach ($c in $component) { $inCycle[$c] = $true } }
        }
    }
    $script:tarjanIndex = $index
    foreach ($v in @($Edges.Keys | Sort-Object)) { if (-not $indices.ContainsKey($v)) { & $visit $v } }
    return $inCycle
}

<#
    Builds the execution plan.

    $Issues      : open Issues @{ number; title; labels (string[]); body }
    $Context     : @{
        IdentifierIndex = @{ 'AUTH-001' = 75 }      # all Issues, open or closed
        Done            = @{ 75 = 'merged into main by PR #79' }   # merged into main or integrated
        ClosedNotDone   = @{ 12 = 'CLOSED without merged/integrated implementation' }
        HumanPr         = @{ 78 = 81 }               # open PR to main (human review flow)
        LocalState      = @{ 76 = @{ Phase = 'blocked'; Reason = '...' } }   # orchestrator state
        Running         = @{ 77 = $true }
        Target          = $null or @(69, 70)          # restrict to these + open dependency closure
        Exclude         = @()
      }
    Status values: DONE, HUMAN_PR, BLOCKED, IN_PROGRESS, APPROVED, NEEDS_HUMAN, RUNNABLE, WAITING.
#>
function New-ExecutionPlan {
    param($Issues, [hashtable]$Context)
    $ctx = @{ IdentifierIndex = @{}; Done = @{}; ClosedNotDone = @{}; HumanPr = @{}; LocalState = @{}; Running = @{}; Target = $null; Exclude = @(); AssumeApproved = $false }
    foreach ($k in $Context.Keys) { $ctx[$k] = $Context[$k] }

    $byNumber = @{}
    foreach ($i in $Issues) { $byNumber[[int]$i.number] = $i }

    $deps = @{}
    foreach ($i in $Issues) {
        $deps[[int]$i.number] = Get-IssueDependencies -Number ([int]$i.number) -Body ([string]$i.body) -IdentifierIndex $ctx.IdentifierIndex
    }

    # Target selection: explicit Issues plus their open dependency closure; default = every open Issue.
    $inTarget = @{}
    if ($null -ne $ctx.Target -and @($ctx.Target).Count -gt 0) {
        $queue = New-Object System.Collections.Queue
        foreach ($t in $ctx.Target) { $queue.Enqueue([int]$t) }
        while ($queue.Count -gt 0) {
            $n = $queue.Dequeue()
            if ($inTarget.ContainsKey($n) -or -not $byNumber.ContainsKey($n)) { continue }
            $inTarget[$n] = $true
            foreach ($d in $deps[$n].Numbers) { $queue.Enqueue([int]$d) }
        }
    } else {
        foreach ($n in $byNumber.Keys) { $inTarget[$n] = $true }
    }
    foreach ($x in @($ctx.Exclude)) { if ($null -ne $x) { [void]$inTarget.Remove([int]$x) } }

    # Graph between open, not-done target Issues.
    $edges = @{}
    foreach ($n in $inTarget.Keys) {
        $edges[$n] = New-Object System.Collections.ArrayList
        foreach ($d in $deps[$n].Numbers) { if ($inTarget.ContainsKey([int]$d) -and -not $ctx.Done.ContainsKey([int]$d)) { [void]$edges[$n].Add([int]$d) } }
    }
    $cycles = Find-DependencyCycles -Edges $edges

    $dependents = @{}
    foreach ($n in $edges.Keys) { foreach ($d in $edges[$n]) { if (-not $dependents.ContainsKey($d)) { $dependents[$d] = New-Object System.Collections.ArrayList }; [void]$dependents[$d].Add($n) } }

    $plan = @{}
    foreach ($n in $inTarget.Keys) {
        $i = $byNumber[$n]
        $labels = @($i.labels | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.name } })
        $gaps = Get-ApprovalGaps -Labels $labels -Body ([string]$i.body) -Dependencies $deps[$n]
        if ($ctx.AssumeApproved) {
            # Preview only (DryRun -AssumeApproved): ignore the human approval labels/open questions.
            $kept = New-Object System.Collections.ArrayList
            foreach ($g in $gaps) { if ($g -notmatch "^(not labelled|labelled status:blocked|unresolved '## Open question')") { [void]$kept.Add($g) } }
            $gaps = $kept
        }
        if ($cycles.ContainsKey($n)) { [void]$gaps.Add('dependency cycle') }
        $depDetails = New-Object System.Collections.ArrayList
        foreach ($d in $deps[$n].Numbers) {
            $d = [int]$d
            $s = 'OPEN'
            if ($ctx.Done.ContainsKey($d)) { $s = 'DONE' }
            elseif ($ctx.ClosedNotDone.ContainsKey($d)) { $s = 'UNSATISFIABLE'; [void]$gaps.Add('dependency #' + $d + ': ' + $ctx.ClosedNotDone[$d]) }
            elseif (-not $byNumber.ContainsKey($d)) { $s = 'UNSATISFIABLE'; [void]$gaps.Add('dependency #' + $d + ' is not an open Issue and has no merged/integrated implementation') }
            elseif (-not $inTarget.ContainsKey($d)) { $s = 'EXCLUDED' ; [void]$gaps.Add('dependency #' + $d + ' is excluded from this run') }
            [void]$depDetails.Add([pscustomobject]@{ Number = $d; Status = $s })
        }

        $status = ''
        $reason = ''
        $local = $null
        $localPhase = ''
        if ($ctx.LocalState.ContainsKey($n)) { $local = $ctx.LocalState[$n]; $localPhase = ConvertTo-ItemPhase ([string]$local.Phase) }
        if ($ctx.Done.ContainsKey($n)) { $status = 'DONE'; $reason = [string]$ctx.Done[$n] }
        elseif ($ctx.HumanPr.ContainsKey($n)) { $status = 'HUMAN_PR'; $reason = 'open PR #' + $ctx.HumanPr[$n] + ' to main awaits human review' }
        elseif ($localPhase -eq 'BLOCKED') { $status = 'BLOCKED'; $reason = [string]$local.Reason }
        elseif ($ctx.Running.ContainsKey($n)) { $status = 'IN_PROGRESS'; $reason = 'agent running' }
        elseif (@('VALIDATED', 'INTEGRATING') -contains $localPhase) { $status = 'APPROVED'; $reason = 'validated, awaiting integration' }
        elseif ($gaps.Count -gt 0) { $status = 'NEEDS_HUMAN'; $reason = ($gaps -join '; ') }
        else {
            $pending = @($depDetails | Where-Object { $_.Status -ne 'DONE' })
            if ($pending.Count -eq 0) {
                $status = 'RUNNABLE'
                if ($localPhase) {
                    $where = $localPhase
                    if ($localPhase -eq 'INTERRUPTED' -and $local.ContainsKey('InterruptedPhase') -and $local.InterruptedPhase) { $where += ' during ' + $local.InterruptedPhase }
                    $reason = 'resume (' + $where + ')'
                }
            } else { $status = 'WAITING'; $reason = 'waiting for ' + (($pending | ForEach-Object { '#' + $_.Number }) -join ', ') }
        }

        # Transitive count of target Issues waiting on this one.
        $seen = @{}
        $q = New-Object System.Collections.Queue
        $q.Enqueue($n)
        while ($q.Count -gt 0) {
            $c = $q.Dequeue()
            if ($dependents.ContainsKey($c)) { foreach ($x in $dependents[$c]) { if (-not $seen.ContainsKey($x) -and $x -ne $n) { $seen[$x] = $true; $q.Enqueue($x) } } }
        }

        $plan[$n] = [pscustomobject]@{
            Number = $n; Identifier = (Get-IssueIdentifier $i.title); Title = [string]$i.title; Labels = $labels
            Dependencies = $depDetails; Status = $status; Reason = $reason
            PriorityRank = (Get-PriorityRank $labels); Unblocks = $seen.Count
            Branch = (Get-IssueBranchName -Number $n -Title $i.title -Labels $labels)
            PredictedRoles = (Get-PredictedRoles -Labels $labels -Title $i.title -Body ([string]$i.body))
            Wave = 0; RootBlockers = @()
        }
    }

    # Projected waves: wave 1 = runnable/in-flight now; wave k+1 = waiting Issues whose open
    # dependencies are all in earlier waves. Issues never reached are stuck behind root blockers.
    $scheduled = @{}
    foreach ($p in $plan.Values) { if (@('RUNNABLE', 'IN_PROGRESS', 'APPROVED') -contains $p.Status) { $p.Wave = 1; $scheduled[$p.Number] = 1 } }
    $wave = 1
    $progress = $true
    while ($progress) {
        $progress = $false
        $wave++
        $next = @()
        foreach ($p in $plan.Values) {
            if ($p.Status -ne 'WAITING' -or $scheduled.ContainsKey($p.Number)) { continue }
            $ok = $true
            foreach ($d in $p.Dependencies) { if ($d.Status -ne 'DONE' -and -not $scheduled.ContainsKey($d.Number)) { $ok = $false } }
            if ($ok) { $next += $p }
        }
        foreach ($p in $next) { $p.Wave = $wave; $scheduled[$p.Number] = $wave; $progress = $true }
    }
    foreach ($p in $plan.Values) {
        if ($p.Status -eq 'WAITING' -and $p.Wave -eq 0) { $p.RootBlockers = @(Get-RootBlockers -Plan $plan -Number $p.Number) }
    }
    return $plan
}

function Get-RootBlockers {
    param([hashtable]$Plan, [int]$Number)
    $roots = New-Object System.Collections.ArrayList
    $seen = @{}
    $q = New-Object System.Collections.Queue
    $q.Enqueue($Number)
    while ($q.Count -gt 0) {
        $c = $q.Dequeue()
        if ($seen.ContainsKey($c)) { continue }
        $seen[$c] = $true
        if (-not $Plan.ContainsKey($c)) { continue }
        foreach ($d in $Plan[$c].Dependencies) {
            if ($d.Status -eq 'DONE') { continue }
            if ($Plan.ContainsKey($d.Number)) {
                $s = $Plan[$d.Number].Status
                if ($s -eq 'WAITING') { $q.Enqueue($d.Number); continue }
                if (@('RUNNABLE', 'IN_PROGRESS', 'APPROVED', 'DONE') -contains $s) { continue }
            }
            if (-not $roots.Contains($d.Number)) { [void]$roots.Add($d.Number) }
        }
    }
    return @($roots | Sort-Object)
}

# Ordering inside a wave / among runnable Issues: unblocks most, then priority, then number.
function Select-RunnableIssues {
    param([hashtable]$Plan)
    return @($Plan.Values | Where-Object { $_.Status -eq 'RUNNABLE' } |
        Sort-Object @{ Expression = 'Unblocks'; Descending = $true }, @{ Expression = 'PriorityRank'; Descending = $false }, @{ Expression = 'Number'; Descending = $false })
}

# MVP is complete when every target Issue is DONE (integrated or merged); HUMAN_PR Issues that
# nothing depends on do not block (they are already in the human review flow).
function Test-PlanComplete {
    param([hashtable]$Plan)
    foreach ($p in $Plan.Values) {
        if ($p.Status -eq 'DONE') { continue }
        if ($p.Status -eq 'HUMAN_PR' -and $p.Unblocks -eq 0) { continue }
        return $false
    }
    return $true
}

# ---------------------------------------------------------------------------
# Review relevance
# ---------------------------------------------------------------------------

function Get-ChangedAreas {
    param([string[]]$Paths)
    $areas = @{ Backend = $false; Mobile = $false; Api = $false; Orchestrator = $false; DocsOnly = $true }
    foreach ($p in $Paths) {
        if (-not $p) { continue }
        $path = $p -replace '\\', '/'
        if ($path -like 'backend/*') { $areas.Backend = $true }
        if ($path -like 'mobile/*') { $areas.Mobile = $true }
        if ($path -eq 'api/openapi.yaml') { $areas.Api = $true; $areas.Mobile = $true }
        if ($path -like 'scripts/orchestrator/*' -or $path -eq 'scripts/autonomous-development.ps1') { $areas.Orchestrator = $true }
        if (-not ($path -like 'docs/*' -or $path -like '*.md')) { $areas.DocsOnly = $false }
    }
    return $areas
}

$script:SecurityKeywords = '(?i)\b(auth\w*|login|logout|session|token|jwt|password|credential|secret|household|member|invitation|identity|receipt|upload|presign\w*|personal|privacy|consent|permission|encrypt\w*|security)\b'
$script:SecurityMobilePath = '(?i)(auth|session|token|secure|storage|identity|household|api/|http|client|config)'

function Test-SecurityReviewRelevant {
    param([string[]]$Paths, [string[]]$Labels, [string]$Title)
    if ($Labels -contains 'security') { return $true }
    if ([string]$Title -match $script:SecurityKeywords) { return $true }
    foreach ($p in $Paths) {
        if (-not $p) { continue }
        $path = $p -replace '\\', '/'
        if ($path -like 'backend/src/main/*') { return $true }
        if ($path -like 'backend/build.gradle*' -or $path -like 'backend/settings.gradle*') { return $true }
        if ($path -eq 'api/openapi.yaml') { return $true }
        if ($path -like 'infra/*' -or $path -like '.github/*') { return $true }
        if ($path -eq 'mobile/package.json' -or $path -eq 'mobile/package-lock.json' -or $path -eq 'mobile/app.config.ts') { return $true }
        if ($path -like 'mobile/src/*' -and $path -match $script:SecurityMobilePath -and $path -notmatch '(?i)/generated/') { return $true }
    }
    return $false
}

function Test-UxReviewRelevant {
    param([string[]]$Paths)
    foreach ($p in $Paths) {
        if (-not $p) { continue }
        $path = $p -replace '\\', '/'
        if ($path -like 'mobile/app/*') { return $true }
        if ($path -like 'mobile/src/*' -and $path -like '*.tsx') { return $true }
        if ($path -like 'mobile/src/shared/ui/*' -or $path -like 'mobile/src/shared/theme/*') { return $true }
    }
    return $false
}

function Get-PredictedRoles {
    param([string[]]$Labels, [string]$Title, [string]$Body)
    $roles = New-Object System.Collections.ArrayList
    [void]$roles.Add('issue-developer'); [void]$roles.Add('code-reviewer')
    $isMobile = ($Labels -contains 'area:mobile') -or ((Get-IssueIdentifier $Title) -like 'MOBILE-*')
    $isDocs = ($Labels -contains 'documentation')
    if (-not $isDocs -and (($Labels -contains 'area:backend') -or ([string]$Title -match $script:SecurityKeywords) -or -not $isMobile)) { [void]$roles.Add('security-reviewer') }
    if ($isMobile) { [void]$roles.Add('mobile-ux-reviewer') }
    return @($roles)
}

# A reviewer is re-run only when the part of the Issue's net diff it cares about changed.
# $Previous / $Current: hashtable path -> per-file patch-id of the net diff (base...HEAD).
function Get-ChangedNetFiles {
    param([hashtable]$Previous, [hashtable]$Current)
    $changed = New-Object System.Collections.ArrayList
    if ($null -eq $Previous) { $Previous = @{} }
    foreach ($k in $Current.Keys) { if (-not $Previous.ContainsKey($k) -or $Previous[$k] -ne $Current[$k]) { [void]$changed.Add($k) } }
    foreach ($k in $Previous.Keys) { if (-not $Current.ContainsKey($k)) { [void]$changed.Add($k) } }
    return @($changed | Sort-Object)
}

function Test-ReviewerRerunNeeded {
    param([string]$Reviewer, $LastReview, [hashtable]$CurrentFingerprint, [string[]]$Labels, [string]$Title)
    if ($null -eq $LastReview) { return $true }
    $prev = @{}
    if ($LastReview.Fingerprint) { foreach ($p in $LastReview.Fingerprint.PSObject.Properties) { $prev[$p.Name] = [string]$p.Value } }
    $changed = @(Get-ChangedNetFiles -Previous $prev -Current $CurrentFingerprint)
    if ($changed.Count -eq 0) { return $false }
    $counts = $LastReview.Counts
    if ($null -ne $counts -and (([int]$counts.BLOCKER + [int]$counts.HIGH + [int]$counts.MEDIUM) -gt 0)) { return $true }
    switch ($Reviewer) {
        'security-reviewer' { return (Test-SecurityReviewRelevant -Paths $changed -Labels $Labels -Title '') }
        'mobile-ux-reviewer' { return (Test-UxReviewRelevant -Paths $changed) }
        default { return $true }
    }
}

# ---------------------------------------------------------------------------
# Agent output parsing
# ---------------------------------------------------------------------------

# Reviewer agents end their report with a 'Summary' block: 'BLOCKER: n' ... 'LOW: n'.
function ConvertFrom-ReviewReport {
    param([string]$Text)
    $counts = @{ BLOCKER = 0; HIGH = 0; MEDIUM = 0; LOW = 0 }
    $parsed = $false
    if ($Text) {
        $normalized = $Text -replace "`r`n", "`n"
        $idx = $normalized.LastIndexOf('Summary')
        $tail = $normalized
        if ($idx -ge 0) { $tail = $normalized.Substring($idx) }
        foreach ($sev in @('BLOCKER', 'HIGH', 'MEDIUM', 'LOW')) {
            $m = [regex]::Match($tail, '(?m)^[\s*>`-]*' + $sev + '\**\s*[:=]\s*\**(\d+)')
            if ($m.Success) { $counts[$sev] = [int]$m.Groups[1].Value; $parsed = $true }
        }
    }
    $human = ($Text -match 'HUMAN DECISION REQUIRED')
    return [pscustomobject]@{ Parsed = $parsed; Counts = $counts; HumanDecision = [bool]$human }
}

# Implementation agents end with one line: ORCHESTRATOR-RESULT: {"status":"DONE"|"BLOCKED", ...}
function ConvertFrom-AgentResult {
    param([string]$Text)
    $result = [pscustomobject]@{ Found = $false; Status = 'UNKNOWN'; Category = ''; Summary = ''; Rejected = '' }
    if (-not $Text) { return $result }
    $matches_ = [regex]::Matches($Text, '(?m)ORCHESTRATOR-RESULT:\s*(\{.*\})\s*$')
    if ($matches_.Count -eq 0) { return $result }
    $json = $matches_[$matches_.Count - 1].Groups[1].Value
    try { $obj = ConvertFrom-Json -InputObject $json } catch { return $result }
    $result.Found = $true
    if ($obj.PSObject.Properties.Name -contains 'status') { $result.Status = ([string]$obj.status).ToUpperInvariant() }
    if ($obj.PSObject.Properties.Name -contains 'category') { $result.Category = ([string]$obj.category).ToUpperInvariant() }
    if ($obj.PSObject.Properties.Name -contains 'summary') { $result.Summary = [string]$obj.summary }
    if ($obj.PSObject.Properties.Name -contains 'rejected' -and $null -ne $obj.rejected) {
        $lines = @()
        foreach ($r in @($obj.rejected)) {
            if ($r -is [string]) { $lines += ('- ' + $r) }
            else { $lines += ('- ' + [string]$r.finding + ' -- justification: ' + [string]$r.justification) }
        }
        $result.Rejected = ($lines -join "`n")
    }
    return $result
}

# Categories that genuinely need the human (CLAUDE.md autonomous decision policy).
function Test-HumanStopCategory {
    param([string]$Category)
    return (@('HUMAN_DECISION', 'REQUIREMENTS_CONFLICT', 'MISSING_CREDENTIAL', 'MAIN_OR_PRODUCTION') -contains $Category)
}

# ---------------------------------------------------------------------------
# Item phases and crash recovery
#
# Claude Code processes, workers and the orchestrator itself are disposable: any of them can
# die at any moment. The phase persisted per item is a checkpoint, never the truth: on every
# start the orchestrator reconciles it with Git (integration trailers, branch heads, worktree
# status) and decides, with Get-RecoveryAction, how the item continues.
# ---------------------------------------------------------------------------

# PLANNED      worktree/branch prepared, no worker ran yet
# RUNNING      implementation agent running
# IMPLEMENTED  implementation agent finished
# TESTING      deterministic gate running
# REVIEWING    independent reviewers running
# FIXING       fix agent running for persisted findings (PendingFix)
# VALIDATED    gate green, no BLOCKER/HIGH: ready to integrate (Head = validated commit)
# INTEGRATING  orchestrator is syncing/merging/pushing (IntegrationStep)
# INTEGRATED   on the integration branch
# BLOCKED      needs a human; work preserved
# INTERRUPTED  a process died or Claude was unavailable; InterruptedPhase = where it stopped
$script:ItemPhases = @('PLANNED', 'RUNNING', 'IMPLEMENTED', 'TESTING', 'REVIEWING', 'FIXING', 'VALIDATED', 'INTEGRATING', 'INTEGRATED', 'BLOCKED', 'INTERRUPTED')
$script:LegacyPhases = @{ developing = 'RUNNING'; reviewing = 'REVIEWING'; approved = 'VALIDATED'; blocked = 'BLOCKED'; integrated = 'INTEGRATED' }
$script:WorkerPhases = @('PLANNED', 'RUNNING', 'IMPLEMENTED', 'TESTING', 'REVIEWING', 'FIXING')

function Get-ItemPhases { return $script:ItemPhases }

# Canonical upper-case phase; state files written by the first orchestrator version used
# developing/reviewing/approved/blocked/integrated. Unknown values are treated as RUNNING.
function ConvertTo-ItemPhase {
    param([string]$Phase)
    if ([string]::IsNullOrWhiteSpace($Phase)) { return '' }
    $p = $Phase.Trim()
    if ($script:LegacyPhases.ContainsKey($p.ToLowerInvariant())) { return $script:LegacyPhases[$p.ToLowerInvariant()] }
    $u = $p.ToUpperInvariant()
    if ($script:ItemPhases -contains $u) { return $u }
    return 'RUNNING'
}

function Test-WorkerPhase {
    param([string]$Phase)
    return ($script:WorkerPhases -contains (ConvertTo-ItemPhase $Phase))
}

# The phase a worker resumes from (INTERRUPTED -> the phase it was interrupted in).
function Get-ResumePhase {
    param([string]$Phase, [string]$InterruptedPhase)
    $p = ConvertTo-ItemPhase $Phase
    if ($p -eq 'INTERRUPTED') {
        $p = ConvertTo-ItemPhase $InterruptedPhase
        if (-not $p -or $p -eq 'INTERRUPTED') { $p = 'RUNNING' }
    }
    if (-not $p -or $p -eq 'PLANNED') { $p = 'RUNNING' }
    return $p
}

<#
    Decides how an item continues after a restart, from its persisted checkpoint and the
    Git reality. Git wins: an item found on the integration branch is INTEGRATED whatever
    the state file says; a validated item whose branch moved is re-validated; existing
    commits or uncommitted files are never discarded but continued by a recovery agent.

    $Facts = @{
        HasState; Phase; InterruptedPhase; PendingFix (bool)
        Integrated        # Integrates-Issue trailer or validated head reachable from the integration branch
        WorkerAlive       # recorded worker PID alive with the same start time
        WorktreeExists; BranchExists; Dirty; CommitsAhead; MergeInProgress
        HeadMatchesValidated  # current branch head == the validated Head
    }
    Returns @{ Phase; InterruptedPhase; Action; Detail } with Action one of
        skip | blocked | adopt | integrate | resume-review | resume-fix | recovery-agent | restart | new
#>
function Get-RecoveryAction {
    param([hashtable]$Facts)
    $f = @{ HasState = $true; Phase = ''; InterruptedPhase = ''; PendingFix = $false; Integrated = $false; WorkerAlive = $false
        WorktreeExists = $false; BranchExists = $false; Dirty = $false; CommitsAhead = 0; MergeInProgress = $false; HeadMatchesValidated = $false }
    foreach ($k in $Facts.Keys) { $f[$k] = $Facts[$k] }
    $phase = ConvertTo-ItemPhase ([string]$f.Phase)
    $ahead = [int]$f.CommitsAhead
    $work = @()
    if ($ahead -gt 0) { $work += ($ahead.ToString() + ' commit(s)') }
    if ($f.Dirty) { $work += 'uncommitted work' }
    $workText = $work -join ' + '
    $decide = { param($p, $ip, $a, $d) return @{ Phase = $p; InterruptedPhase = $ip; Action = $a; Detail = $d } }

    if ($f.Integrated) {
        $d = 'already on the integration branch'
        if ($f.HasState -and $phase -ne 'INTEGRATED') { $d += ' (state said ' + $phase + '; Git wins, state corrected)' }
        return (& $decide 'INTEGRATED' '' 'skip' $d)
    }
    if (-not $f.HasState) {
        if ($f.BranchExists -and ($ahead -gt 0 -or $f.Dirty)) { return (& $decide 'INTERRUPTED' 'RUNNING' 'recovery-agent' ('existing branch with ' + $workText + ' but no state file')) }
        return (& $decide '' '' 'new' 'not started')
    }
    if ($phase -eq 'BLOCKED') { return (& $decide 'BLOCKED' '' 'blocked' 'kept blocked, work preserved (-RetryBlocked retries it)') }
    if ($phase -eq 'INTEGRATED') {
        return (& $decide 'INTERRUPTED' 'REVIEWING' 'resume-review' 'state said INTEGRATED but the integration branch does not contain it; re-validating')
    }
    if ($f.WorkerAlive) { return (& $decide $phase ([string]$f.InterruptedPhase) 'adopt' 'worker process still running') }

    $resume = Get-ResumePhase $phase ([string]$f.InterruptedPhase)
    if ($resume -eq 'INTEGRATING') { return (& $decide 'INTEGRATING' '' 'integrate' 'integration was interrupted; resumed idempotently') }
    if ($f.MergeInProgress) { return (& $decide 'BLOCKED' '' 'blocked' 'a merge is in progress in the Issue worktree outside integration; inspect it manually (work preserved)') }
    if ($resume -eq 'VALIDATED') {
        if ($f.HeadMatchesValidated -and -not $f.Dirty) { return (& $decide 'VALIDATED' '' 'integrate' 'validated, not integrated yet') }
        if ($f.Dirty) { return (& $decide 'INTERRUPTED' 'RUNNING' 'recovery-agent' 'worktree has uncommitted work after validation') }
        return (& $decide 'INTERRUPTED' 'REVIEWING' 'resume-review' 'branch moved after validation; re-validating')
    }
    if ($resume -eq 'FIXING' -or $f.PendingFix) {
        $d = 'fix pass interrupted; resumed with the persisted findings'
        if ($workText) { $d += ' (' + $workText + ' kept)' }
        return (& $decide 'INTERRUPTED' 'FIXING' 'resume-fix' $d)
    }
    if (@('IMPLEMENTED', 'TESTING', 'REVIEWING') -contains $resume) {
        if ($f.Dirty) { return (& $decide 'INTERRUPTED' 'RUNNING' 'recovery-agent' ('interrupted during ' + $resume + ' with ' + $workText)) }
        if ($ahead -gt 0) { return (& $decide 'INTERRUPTED' $resume 'resume-review' ('interrupted during ' + $resume + '; ' + $workText + ' kept, passed gate and unchanged reviews reused')) }
        return (& $decide 'INTERRUPTED' 'RUNNING' 'restart' ('interrupted during ' + $resume + ' with no commit; implementation restarts'))
    }
    # PLANNED / RUNNING
    if ($ahead -gt 0 -or $f.Dirty) { return (& $decide 'INTERRUPTED' 'RUNNING' 'recovery-agent' ('implementation interrupted with ' + $workText)) }
    if ($phase -eq 'PLANNED') { return (& $decide 'PLANNED' '' 'restart' 'planned, worker never ran') }
    return (& $decide 'INTERRUPTED' 'RUNNING' 'restart' 'implementation interrupted before any commit or file change; fresh implementation')
}

function Format-RecoveryLine {
    param([string]$Label, [hashtable]$Decision)
    $actionText = @{
        'skip' = 'skip'; 'blocked' = 'stays blocked'; 'adopt' = 'adopt running worker'; 'integrate' = 'integrate'
        'resume-review' = 'resume review'; 'resume-fix' = 'resume fix'; 'recovery-agent' = 'recovery agent'; 'restart' = 'restart implementation'; 'new' = 'start'
    }[$Decision.Action]
    $state = [string]$Decision.Phase
    if ($state -eq 'INTERRUPTED' -and $Decision.InterruptedPhase) { $state += ' during ' + $Decision.InterruptedPhase }
    if (-not $state) { $state = 'NEW' }
    return ($Label + ' ' + $state + ' -> ' + $actionText + ' (' + $Decision.Detail + ')')
}

<#
    Concise restart report: one line per item with a state file (recovery decision) and one
    line per remaining planned Issue (DAG status). $Entries: objects with Label, Issue, Decision.
#>
function Format-RecoveryReport {
    param($Entries, [hashtable]$Plan)
    $lines = New-Object System.Collections.ArrayList
    $seen = @{}
    foreach ($e in @($Entries | Sort-Object @{ Expression = { [int]$_.Issue } }, Label)) {
        [void]$lines.Add((Format-RecoveryLine $e.Label $e.Decision))
        if ([int]$e.Issue -gt 0) { $seen[[int]$e.Issue] = $true }
    }
    if ($null -ne $Plan) {
        foreach ($p in ($Plan.Values | Sort-Object Number)) {
            if ($seen.ContainsKey([int]$p.Number)) { continue }
            $text = switch ($p.Status) {
                'DONE' { 'INTEGRATED -> skip' }
                'RUNNABLE' { 'RUNNABLE -> continue' }
                'WAITING' { 'WAITING -> dependency ' + ((@($p.Dependencies | Where-Object { $_.Status -ne 'DONE' }) | ForEach-Object { '#' + $_.Number }) -join ', ') }
                'IN_PROGRESS' { 'RUNNING -> adopt' }
                'APPROVED' { 'VALIDATED -> integrate' }
                default { $p.Status + ' -> ' + $p.Reason }
            }
            [void]$lines.Add('#' + $p.Number + ' ' + $text)
        }
    }
    return , $lines
}

# Classifies a failed Claude Code process that produced no ORCHESTRATOR-RESULT:
#   AUTH      session/token expired, not logged in, credit/usage limit -> stop the run, rerun later
#   TRANSIENT overloaded, rate limited, network -> relaunch the item later (fresh process)
#   CRASH     anything else (timeout, crash) -> bounded relaunch with recovery context
$script:AgentAuthPattern = '(?i)(/login\b|not logged in|log in again|oauth token|token (has )?expired|session (has )?expired|invalid api key|invalid x-api-key|authentication[_ ]error|authentication failed|unauthori[sz]ed|\b401\b|credit balance|usage limit|limit reached|billing)'
$script:AgentTransientPattern = '(?i)(overloaded|\b529\b|rate[ _-]?limit|\b429\b|too many requests|econnreset|econnrefused|enotfound|etimedout|eai_again|getaddrinfo|socket hang up|network error|fetch failed|connection error|service unavailable|\b503\b|\b502\b)'

function Get-AgentFailureKind {
    param([string]$Text, [bool]$TimedOut = $false)
    if ($TimedOut) { return 'CRASH' }
    if ([string]$Text -match $script:AgentAuthPattern) { return 'AUTH' }
    if ([string]$Text -match $script:AgentTransientPattern) { return 'TRANSIENT' }
    return 'CRASH'
}

# DryRun guarantees: only these read-only Git / GitHub CLI commands may run.
function Test-ReadOnlyGitCommand {
    param([string[]]$Arguments)
    $args_ = @($Arguments | Where-Object { $_ -ne $null })
    if ($args_.Count -eq 0) { return $false }
    $sub = $args_[0]
    if (@('rev-parse', 'rev-list', 'log', 'for-each-ref', 'merge-base', 'status', 'diff', 'show', 'ls-remote', 'cat-file', '--version') -contains $sub) { return $true }
    if ($sub -eq 'worktree' -and $args_.Count -ge 2 -and $args_[1] -eq 'list') { return $true }
    return $false
}

function Test-ReadOnlyGhCommand {
    param([string[]]$Arguments)
    $args_ = @($Arguments | Where-Object { $_ -ne $null })
    if ($args_.Count -eq 0) { return $false }
    if ($args_[0] -eq '--version') { return $true }
    if ($args_.Count -lt 2) { return $false }
    $cmd = $args_[0] + ' ' + $args_[1]
    return (@('issue list', 'issue view', 'pr list', 'pr view', 'repo view', 'auth status') -contains $cmd)
}

# ---------------------------------------------------------------------------
# Process argument quoting (Windows CommandLineToArgvW rules)
# ---------------------------------------------------------------------------

function ConvertTo-CommandLineArgument {
    param([string]$Argument)
    if ($Argument -ne '' -and $Argument -notmatch '[\s"]') { return $Argument }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $backslashes = 0
    foreach ($ch in $Argument.ToCharArray()) {
        if ($ch -eq '\') { $backslashes++; continue }
        if ($ch -eq '"') { [void]$sb.Append('\' * ($backslashes * 2 + 1)); [void]$sb.Append('"'); $backslashes = 0; continue }
        if ($backslashes -gt 0) { [void]$sb.Append('\' * $backslashes); $backslashes = 0 }
        [void]$sb.Append($ch)
    }
    if ($backslashes -gt 0) { [void]$sb.Append('\' * ($backslashes * 2)) }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Join-CommandLine {
    param([string[]]$Arguments)
    return (($Arguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' ')
}

Export-ModuleMember -Function *
