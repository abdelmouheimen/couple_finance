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
        if ($ctx.LocalState.ContainsKey($n)) { $local = $ctx.LocalState[$n] }
        if ($ctx.Done.ContainsKey($n)) { $status = 'DONE'; $reason = [string]$ctx.Done[$n] }
        elseif ($ctx.HumanPr.ContainsKey($n)) { $status = 'HUMAN_PR'; $reason = 'open PR #' + $ctx.HumanPr[$n] + ' to main awaits human review' }
        elseif ($null -ne $local -and $local.Phase -eq 'blocked') { $status = 'BLOCKED'; $reason = [string]$local.Reason }
        elseif ($ctx.Running.ContainsKey($n)) { $status = 'IN_PROGRESS'; $reason = 'agent running' }
        elseif ($null -ne $local -and $local.Phase -eq 'approved') { $status = 'APPROVED'; $reason = 'reviewed, awaiting integration' }
        elseif ($gaps.Count -gt 0) { $status = 'NEEDS_HUMAN'; $reason = ($gaps -join '; ') }
        else {
            $pending = @($depDetails | Where-Object { $_.Status -ne 'DONE' })
            if ($pending.Count -eq 0) {
                $status = 'RUNNABLE'
                if ($null -ne $local -and $local.Phase) { $reason = 'resume (' + $local.Phase + ')' }
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
