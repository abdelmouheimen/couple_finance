<#
.SYNOPSIS
    External orchestrator for CoupleFinance autonomous backlog development.

.DESCRIPTION
    One Issue = one fresh Claude Code process = one feature/fix branch = one Pull Request.

    For each iteration the script:
      1. verifies git, gh and claude, GitHub authentication and the repository;
      2. requires a clean working tree on an up-to-date main (fast-forward only);
      3. reads open Issues, open PRs and merged PRs from GitHub;
      4. computes READY Issues (see CLAUDE.md "READY Issues");
      5. starts a NEW non-interactive Claude Code process running the
         issue-developer agent for the selected Issue;
      6. verifies that the expected PR exists, was pushed and is not merged;
      7. returns to main (fast-forward only) and repeats until -MaxIssues
         or a stop condition is reached.

    The script never merges, approves, enables auto-merge, force-pushes,
    resets, stashes or discards anything. Unexpected repository state stops it.

    Exit codes: 0 = normal stop (dry run done, limit reached, no READY work)
                1 = failure / unsafe state
                2 = human input required (Issue stopped without a PR)

.PARAMETER DryRun
    Analyse the backlog and report the next candidate. Never invokes Claude,
    never creates branches, commits, pushes, PRs or Issue changes.

.PARAMETER MaxIssues
    Maximum number of fresh Claude implementation processes started by this run.

.EXAMPLE
    .\scripts\autonomous-development.ps1 -DryRun

.EXAMPLE
    .\scripts\autonomous-development.ps1 -MaxIssues 1
#>
[CmdletBinding()]
param(
    [switch]$DryRun,

    [ValidateRange(1, 20)]
    [int]$MaxIssues = 1,

    [string]$ExpectedRepo = 'abdelmouheimen/couple_finance',

    [string]$BaseBranch = 'main',

    # Permission mode of the non-interactive Claude process. Permission prompts are
    # answered "no" automatically (--permission-prompts none), so nothing can hang.
    [ValidateSet('auto', 'acceptEdits', 'dontAsk', 'bypassPermissions')]
    [string]$PermissionMode = 'auto',

    # Optional model override; by default the issue-developer agent's model is used.
    [string]$Model = '',

    # Optional spend cap per Issue process (passed to --max-budget-usd). 0 = no cap.
    [decimal]$MaxBudgetUsd = 0
)

Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$ReadyLabel = 'status:ready'
$BlockedLabel = 'status:blocked'
$NonImplementationLabels = @('question', 'invalid', 'wontfix', 'duplicate')
$PriorityRank = @{ 'priority:high' = 0; 'priority:medium' = 1; 'priority:low' = 2 }

# Hard safety boundary, enforced on the Claude process in addition to the agent rules.
$DeniedTools = @(
    'Bash(gh pr merge *)', 'Bash(gh pr review *)', 'Bash(gh api *)',
    'Bash(gh repo edit *)', 'Bash(gh repo delete *)', 'Bash(gh issue close *)',
    'Bash(git push --force *)', 'Bash(git push -f *)', 'Bash(git push --force-with-lease *)',
    'Bash(git push --delete *)', 'Bash(git push origin main*)', 'Bash(git push origin HEAD:main*)',
    'Bash(git reset --hard *)', 'Bash(git clean *)', 'Bash(git stash *)',
    'PowerShell(gh pr merge *)', 'PowerShell(gh pr review *)', 'PowerShell(gh api *)',
    'PowerShell(git push --force *)', 'PowerShell(git push -f *)', 'PowerShell(git push origin main*)',
    'PowerShell(git reset --hard *)', 'PowerShell(git clean *)'
)

$StopPrefix = 'ORCHESTRATOR-STOP: '
$script:StopCode = 1

function Stop-Orchestrator([string]$Message, [int]$Code = 1) {
    $script:StopCode = $Code
    throw ($StopPrefix + $Message)
}

function Write-Section([string]$Text) {
    Write-Host ''
    Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan
}

function Write-Info([string]$Text) { Write-Host $Text }
function Write-Warn([string]$Text) { Write-Host ('WARNING: ' + $Text) -ForegroundColor Yellow }

# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------

function Resolve-Tool([string]$Name, [string[]]$Fallbacks) {
    $cmd = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $cmd) { return $cmd.Source }
    foreach ($candidate in $Fallbacks) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            # Make it visible to the child Claude process as well.
            $dir = Split-Path -Parent $candidate
            if (($env:PATH -split ';') -notcontains $dir) { $env:PATH = $dir + ';' + $env:PATH }
            return $candidate
        }
    }
    Stop-Orchestrator ("Required command '" + $Name + "' is not available (not on PATH, no known install location).")
}

function Invoke-Tool([string]$Exe, [string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $Exe @Arguments
    $code = $LASTEXITCODE
    $text = ($output | Out-String).TrimEnd()
    if (-not $AllowFailure -and $code -ne 0) {
        Stop-Orchestrator ("Command failed (exit " + $code + "): " + (Split-Path -Leaf $Exe) + ' ' + ($Arguments -join ' '))
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Invoke-Git([string[]]$Arguments, [switch]$AllowFailure) {
    return Invoke-Tool -Exe $script:Git -Arguments $Arguments -AllowFailure:$AllowFailure
}

function ConvertTo-List($Parsed) {
    # Windows PowerShell 5.1 does not unroll JSON arrays; normalise to a real list.
    $list = New-Object System.Collections.ArrayList
    foreach ($item in $Parsed) { [void]$list.Add($item) }
    return , $list
}

function Invoke-GhJson([string[]]$Arguments) {
    $result = Invoke-Tool -Exe $script:Gh -Arguments $Arguments
    if ([string]::IsNullOrWhiteSpace($result.Output)) { return , (New-Object System.Collections.ArrayList) }
    return , (ConvertTo-List (ConvertFrom-Json -InputObject $result.Output))
}

# ---------------------------------------------------------------------------
# Repository state
# ---------------------------------------------------------------------------

function Get-PorcelainStatus { return (Invoke-Git @('status', '--porcelain')).Output }

function Get-CurrentBranch { return (Invoke-Git @('branch', '--show-current')).Output.Trim() }

function Assert-Prerequisites {
    Write-Section 'Prerequisites'
    $script:Git = Resolve-Tool 'git' @()
    $script:Gh = Resolve-Tool 'gh' @(
        (Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\GitHub CLI\gh.exe'))
    $script:Claude = Resolve-Tool 'claude' @(
        (Join-Path $env:APPDATA 'npm\claude.cmd'),
        (Join-Path $env:USERPROFILE '.local\bin\claude.exe'))
    Write-Info ('git    : ' + (Invoke-Git @('--version')).Output)
    Write-Info ('gh     : ' + ((Invoke-Tool $script:Gh @('--version')).Output -split "`n")[0])
    Write-Info ('claude : ' + (Invoke-Tool $script:Claude @('--version')).Output)

    $auth = Invoke-Tool $script:Gh @('auth', 'status') -AllowFailure
    if ($auth.ExitCode -ne 0) { Stop-Orchestrator 'GitHub CLI is not authenticated (gh auth status failed).' }
    Write-Info 'GitHub : authenticated'

    $expectedRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
    $top = (Invoke-Git @('rev-parse', '--show-toplevel') -AllowFailure)
    if ($top.ExitCode -ne 0) { Stop-Orchestrator 'Not inside a git repository.' }
    $actualRoot = $top.Output.Trim().Replace('/', '\').TrimEnd('\')
    if ($actualRoot -ne $expectedRoot) {
        Stop-Orchestrator ("Unexpected repository root '" + $actualRoot + "' (expected '" + $expectedRoot + "'). Run the script from the CoupleFinance repository.")
    }
    Set-Location -LiteralPath $expectedRoot

    $repo = Invoke-GhJson @('repo', 'view', '--json', 'nameWithOwner,defaultBranchRef')
    $repoInfo = $repo[0]
    if ($repoInfo.nameWithOwner -ne $ExpectedRepo) {
        Stop-Orchestrator ("GitHub repository is '" + $repoInfo.nameWithOwner + "', expected '" + $ExpectedRepo + "'.")
    }
    if ($repoInfo.defaultBranchRef.name -ne $BaseBranch) {
        Stop-Orchestrator ("Default branch is '" + $repoInfo.defaultBranchRef.name + "', expected '" + $BaseBranch + "'.")
    }
    Write-Info ('Repo   : ' + $repoInfo.nameWithOwner + ' (' + $expectedRoot + ')')
}

function Sync-BaseBranch([switch]$ReadOnly) {
    Write-Section 'Repository state'
    $status = Get-PorcelainStatus
    $branch = Get-CurrentBranch
    Write-Info ('Current branch: ' + $branch)

    if ($status) {
        $message = "Working tree is not clean:`n" + $status
        if ($ReadOnly) {
            Write-Warn ($message + "`nA real run would STOP here. Commit or remove these files first (they are not touched by this script).")
        } else {
            Stop-Orchestrator ($message + "`nRefusing to continue: nothing is discarded, stashed or committed automatically.")
        }
    } else {
        Write-Info 'Working tree: clean'
    }

    Invoke-Git @('fetch', '--prune', 'origin') | Out-Null
    Write-Info 'Fetched origin.'

    if ($ReadOnly) { return }

    if ($branch -ne $BaseBranch) {
        # Safe: the tree is clean, so checkout cannot lose work.
        Invoke-Git @('checkout', $BaseBranch) | Out-Null
    }
    $ahead = [int]((Invoke-Git @('rev-list', '--count', ('origin/' + $BaseBranch + '..' + $BaseBranch))).Output)
    if ($ahead -ne 0) {
        Stop-Orchestrator ("Local " + $BaseBranch + " has " + $ahead + " commit(s) not on origin/" + $BaseBranch + ". Unexpected state; resolve manually.")
    }
    Invoke-Git @('merge', '--ff-only', ('origin/' + $BaseBranch)) | Out-Null
    Write-Info ($BaseBranch + ' fast-forwarded to origin/' + $BaseBranch + ' at ' + (Invoke-Git @('rev-parse', '--short', 'HEAD')).Output)
}

# ---------------------------------------------------------------------------
# Issue parsing
# ---------------------------------------------------------------------------

function Get-Section([string]$Body, [string]$HeadingPattern) {
    if ($null -eq $Body) { return $null }
    $normalized = $Body -replace "`r`n", "`n"
    $regex = '(?ms)^##[ \t]+(?:' + $HeadingPattern + ')[ \t]*:?[ \t]*\n(.*?)(?=^##[ \t]|\z)'
    $match = [regex]::Match($normalized, $regex, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.Trim()
}

function Get-Identifier([string]$Title) {
    $m = [regex]::Match($Title, '^\s*([A-Z][A-Z0-9]*-\d+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

# Returns @{ Known = bool; Items = list of @{ Number; Text } ; Unresolved = list of text }
function Get-Dependencies($Issue, $IdentifierIndex) {
    $section = Get-Section $Issue.body 'Dependencies'
    $result = @{ Known = $false; Items = (New-Object System.Collections.ArrayList); Unresolved = (New-Object System.Collections.ArrayList) }
    if ($null -eq $section) { return $result }
    $result.Known = $true
    foreach ($rawLine in ($section -split "`n")) {
        $line = $rawLine.Trim()
        if (-not $line) { continue }
        if ($line -match '^(?i)(none|n/a|-)\.?$') { continue }
        if ($line -notmatch '^[-*+]\s+') { continue }   # non-bullet lines are informational notes
        $numbers = [regex]::Matches($line, '(?<![\w/])#(\d+)\b')
        if ($numbers.Count -gt 0) {
            foreach ($n in $numbers) {
                $num = [int]$n.Groups[1].Value
                if ($num -ne $Issue.number) { [void]$result.Items.Add(@{ Number = $num; Text = $line.TrimStart('-', '*', '+', ' ') }) }
            }
            continue
        }
        $ids = [regex]::Matches($line, '\b([A-Z][A-Z0-9]*-\d{3})\b')
        if ($ids.Count -eq 0) { [void]$result.Unresolved.Add($line); continue }
        foreach ($id in $ids) {
            $key = $id.Groups[1].Value
            if ($IdentifierIndex.ContainsKey($key)) {
                [void]$result.Items.Add(@{ Number = $IdentifierIndex[$key]; Text = $key })
            } else {
                [void]$result.Unresolved.Add($key)
            }
        }
    }
    return $result
}

function Test-ClosesIssue($Pr, [int]$Number) {
    if ($Pr.closingIssuesReferences) {
        foreach ($ref in $Pr.closingIssuesReferences) { if ([int]$ref.number -eq $Number) { return $true } }
    }
    if ($Pr.body -and ($Pr.body -match ('(?i)\b(close[sd]?|fix(e[sd])?|resolve[sd]?)\s*:?\s+#' + $Number + '\b'))) { return $true }
    if ($Pr.headRefName -match ('^(feature|fix)/' + $Number + '-')) { return $true }
    return $false
}

# ---------------------------------------------------------------------------
# Backlog evaluation
# ---------------------------------------------------------------------------

function Get-BacklogState {
    Write-Section 'GitHub backlog'
    $open = Invoke-GhJson @('issue', 'list', '--state', 'open', '--limit', '500', '--json', 'number,title,labels,body,url')
    $all = Invoke-GhJson @('issue', 'list', '--state', 'all', '--limit', '1000', '--json', 'number,title,state')
    $openPrs = Invoke-GhJson @('pr', 'list', '--state', 'open', '--limit', '200', '--json', 'number,title,url,headRefName,baseRefName,body,closingIssuesReferences,isDraft')
    $mergedPrs = Invoke-GhJson @('pr', 'list', '--state', 'merged', '--base', $BaseBranch, '--limit', '1000', '--json', 'number,headRefName,body,closingIssuesReferences,mergeCommit')

    $identifierIndex = @{}
    $issueState = @{}
    foreach ($i in $all) {
        $issueState[[int]$i.number] = $i
        $id = Get-Identifier $i.title
        if ($id -and -not $identifierIndex.ContainsKey($id)) { $identifierIndex[$id] = [int]$i.number }
    }

    $readyCount = 0; $blockedCount = 0
    foreach ($i in $open) {
        $names = @($i.labels | ForEach-Object { $_.name })
        if ($names -contains $ReadyLabel) { $readyCount++ }
        if ($names -contains $BlockedLabel) { $blockedCount++ }
    }
    Write-Info ('Open Issues            : ' + $open.Count)
    Write-Info ('Labelled ' + $ReadyLabel + '  : ' + $readyCount)
    Write-Info ('Labelled ' + $BlockedLabel + ': ' + $blockedCount)
    Write-Info ('Open PRs               : ' + $openPrs.Count)
    Write-Info ('Merged PRs into ' + $BaseBranch + '   : ' + $mergedPrs.Count)

    return @{
        Open = $open; IssueState = $issueState; IdentifierIndex = $identifierIndex
        OpenPrs = $openPrs; MergedPrs = $mergedPrs; ReadyCount = $readyCount; BlockedCount = $blockedCount
    }
}

$script:AncestorCache = @{}
function Test-InBaseBranch([string]$Oid) {
    if (-not $Oid) { return $false }
    if ($script:AncestorCache.ContainsKey($Oid)) { return $script:AncestorCache[$Oid] }
    $r = Invoke-Git @('merge-base', '--is-ancestor', $Oid, ('origin/' + $BaseBranch)) -AllowFailure
    $ok = ($r.ExitCode -eq 0)
    $script:AncestorCache[$Oid] = $ok
    return $ok
}

function Get-DependencyStatus([int]$Number, $State) {
    $title = '(unknown Issue)'
    $issueStateText = 'MISSING'
    if ($State.IssueState.ContainsKey($Number)) {
        $title = $State.IssueState[$Number].title
        $issueStateText = $State.IssueState[$Number].state
    }
    foreach ($pr in $State.MergedPrs) {
        if (Test-ClosesIssue $pr $Number) {
            $oid = ''
            if ($pr.mergeCommit) { $oid = $pr.mergeCommit.oid }
            if (Test-InBaseBranch $oid) {
                return @{ Satisfied = $true; Detail = ('#' + $Number + ' ' + $title + ' -> PR #' + $pr.number + ' MERGED into ' + $BaseBranch + ' (Issue ' + $issueStateText + ')') }
            }
        }
    }
    $openPr = $State.OpenPrs | Where-Object { Test-ClosesIssue $_ $Number } | Select-Object -First 1
    if ($null -ne $openPr) {
        return @{ Satisfied = $false; Detail = ('#' + $Number + ' ' + $title + ' -> PR #' + $openPr.number + ' OPEN, not merged') }
    }
    return @{ Satisfied = $false; Detail = ('#' + $Number + ' ' + $title + ' -> no merged PR (Issue ' + $issueStateText + ')') }
}

function Measure-Backlog($State) {
    $openNumbers = @{}
    foreach ($i in $State.Open) { $openNumbers[[int]$i.number] = $true }

    # Parse dependencies and build the reverse graph between open Issues.
    $deps = @{}
    $dependents = @{}
    foreach ($i in $State.Open) {
        $d = Get-Dependencies $i $State.IdentifierIndex
        $deps[[int]$i.number] = $d
        foreach ($item in $d.Items) {
            if (-not $dependents.ContainsKey($item.Number)) { $dependents[$item.Number] = New-Object System.Collections.ArrayList }
            [void]$dependents[$item.Number].Add([int]$i.number)
        }
    }

    $evaluations = New-Object System.Collections.ArrayList
    foreach ($i in $State.Open) {
        $n = [int]$i.number
        $labels = @($i.labels | ForEach-Object { $_.name })
        $reasons = New-Object System.Collections.ArrayList

        if ($labels -notcontains $ReadyLabel) { [void]$reasons.Add('not labelled ' + $ReadyLabel) }
        if ($labels -contains $BlockedLabel) { [void]$reasons.Add('labelled ' + $BlockedLabel) }
        foreach ($l in $labels) { if ($NonImplementationLabels -contains $l) { [void]$reasons.Add('labelled ' + $l) } }

        $ac = Get-Section $i.body 'Acceptance criteria'
        if ($null -eq $ac -or $ac -notmatch '(?m)^\s*[-*]\s+\[[ xX]\]') { [void]$reasons.Add('no acceptance-criteria checklist') }

        $openQuestion = Get-Section $i.body 'Open questions?'
        if ($openQuestion) { [void]$reasons.Add("unresolved '## Open question' section (human decision required)") }

        $activePr = $State.OpenPrs | Where-Object { Test-ClosesIssue $_ $n } | Select-Object -First 1
        if ($null -ne $activePr) { [void]$reasons.Add('active PR #' + $activePr.number + ' already implements it') }

        $d = $deps[$n]
        $depStatuses = New-Object System.Collections.ArrayList
        if (-not $d.Known) {
            [void]$reasons.Add("no '## Dependencies' section (dependencies cannot be verified)")
        } else {
            foreach ($u in $d.Unresolved) { [void]$reasons.Add('unresolvable dependency reference: ' + $u) }
            foreach ($item in $d.Items) {
                $s = Get-DependencyStatus $item.Number $State
                [void]$depStatuses.Add($s)
                if (-not $s.Satisfied) { [void]$reasons.Add('dependency not merged: #' + $item.Number) }
            }
        }

        # Transitive count of open Issues waiting on this one (dependency order).
        $visited = @{}
        $queue = New-Object System.Collections.Queue
        $queue.Enqueue($n)
        while ($queue.Count -gt 0) {
            $cur = $queue.Dequeue()
            if ($dependents.ContainsKey($cur)) {
                foreach ($x in $dependents[$cur]) {
                    if (-not $visited.ContainsKey($x) -and $x -ne $n -and $openNumbers.ContainsKey($x)) { $visited[$x] = $true; $queue.Enqueue($x) }
                }
            }
        }

        $priority = 'none'
        $rank = 3
        foreach ($l in $labels) { if ($PriorityRank.ContainsKey($l) -and $PriorityRank[$l] -lt $rank) { $rank = $PriorityRank[$l]; $priority = $l } }

        [void]$evaluations.Add([pscustomobject]@{
            Number = $n; Identifier = (Get-Identifier $i.title); Title = $i.title; Url = $i.url
            Priority = $priority; PriorityRank = $rank; Dependents = $visited.Count
            DependencyStatuses = $depStatuses; DependenciesKnown = $d.Known
            Reasons = $reasons; Eligible = ($reasons.Count -eq 0)
        })
    }

    $eligible = @($evaluations | Where-Object { $_.Eligible } |
        Sort-Object @{ Expression = 'Dependents'; Descending = $true }, @{ Expression = 'PriorityRank'; Descending = $false }, @{ Expression = 'Number'; Descending = $false })
    return @{ All = $evaluations; Eligible = $eligible }
}

function Write-Evaluation($Evaluation) {
    Write-Section 'Issue eligibility'
    foreach ($e in ($Evaluation.All | Sort-Object Number)) {
        if ($e.Eligible) {
            Write-Host ('#' + $e.Number + ' ' + $e.Identifier + ' : ELIGIBLE') -ForegroundColor Green
        } else {
            Write-Info ('#' + $e.Number + ' ' + $e.Identifier + ' : not eligible - ' + ($e.Reasons -join '; '))
        }
    }
}

function Write-Candidate($Candidate) {
    Write-Section 'Candidate Issue'
    Write-Info ('#' + $Candidate.Number)
    Write-Info ('Identifier        : ' + $Candidate.Identifier)
    Write-Info ('Title             : ' + $Candidate.Title)
    Write-Info ('Priority          : ' + $Candidate.Priority)
    if ($Candidate.DependencyStatuses.Count -eq 0) {
        Write-Info 'Dependencies      : None'
        Write-Info 'Dependency status : n/a'
    } else {
        Write-Info 'Dependencies / status:'
        foreach ($s in $Candidate.DependencyStatuses) { Write-Info ('  - ' + $s.Detail) }
    }
    Write-Info ('Open Issues waiting on it (transitively): ' + $Candidate.Dependents)
    Write-Info ('Why eligible      : open, ' + $ReadyLabel + ', not ' + $BlockedLabel + ', acceptance-criteria checklist present, no open question, no active PR, all dependencies merged into ' + $BaseBranch + '.')
}

function Write-NoCandidate($Evaluation, $State) {
    Write-Section 'No eligible Issue'
    if ($State.Open.Count -eq 0) { Write-Info 'There are no open Issues.'; return }
    $counts = @{}
    foreach ($e in $Evaluation.All) {
        foreach ($r in $e.Reasons) {
            $key = $r -replace '#\d+', '#N' -replace 'active PR #N.*', 'active PR already implements it'
            if (-not $counts.ContainsKey($key)) { $counts[$key] = 0 }
            $counts[$key]++
        }
    }
    Write-Info 'Blocking reasons across open Issues (Issue count):'
    foreach ($k in ($counts.Keys | Sort-Object { -$counts[$_] })) { Write-Info ('  ' + $counts[$k] + ' x ' + $k) }
    if ($State.ReadyCount -eq 0) {
        Write-Info ''
        Write-Info ("No open Issue carries the '" + $ReadyLabel + "' label. The Tech Lead marks approved, decision-free Issues with it, e.g.:")
        Write-Info ('  gh issue edit <N> --add-label ' + $ReadyLabel)
    }
}

# ---------------------------------------------------------------------------
# Fresh Claude process per Issue
# ---------------------------------------------------------------------------

function Get-IssuePrompt([int]$Number) {
    return @"
You are running as the issue-developer agent (if you are not, use the issue-developer subagent).

Implement GitHub Issue #$Number autonomously according to CLAUDE.md.

This is a FRESH CONTEXT.

Do not assume any knowledge from previous Claude sessions.

Reconstruct required context from:
- CLAUDE.md
- GitHub Issue #$Number
- referenced BR-xxx rules
- relevant approved documentation
- relevant ADRs
- current implementation
- current tests

Do not read all documentation by default: follow the references of the Issue.

Own the complete lifecycle:
- analyze Issue
- verify dependencies (merged into main)
- update main
- create branch feature/$Number-<short-description> or fix/$Number-<short-description>
- implement
- migrations when required
- OpenAPI when required
- tests
- verification
- self-review
- independent reviews (code-reviewer and security-reviewer subagents)
- corrections
- commit
- push the feature/fix branch
- create PR targeting main with 'Closes #$Number'

Do not merge the Pull Request.

Do not start another Issue.

After PR creation, terminate the session.

Stop instead if:
- requirements conflict
- dependency is missing
- human architecture/product/security approval is required
- implementation cannot safely be completed
"@
}

function Invoke-IssueDeveloper($Candidate) {
    $n = $Candidate.Number
    $logDir = Join-Path (Get-Location).Path '.autonomous-dev\logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $logFile = Join-Path $logDir ('issue-' + $n + '-' + $stamp + '.json')

    $claudeArgs = @('-p', '--agent', 'issue-developer',
        '--permission-mode', $PermissionMode, '--permission-prompts', 'none',
        '--output-format', 'json', '--name', ('issue-' + $n))
    if ($Model) { $claudeArgs += @('--model', $Model) }
    if ($MaxBudgetUsd -gt 0) { $claudeArgs += @('--max-budget-usd', $MaxBudgetUsd.ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
    $claudeArgs += '--disallowedTools'
    $claudeArgs += $DeniedTools   # variadic option: must stay last; the prompt is sent on stdin

    Write-Section ('Starting fresh Claude Code process for Issue #' + $n)
    Write-Info ('Command : claude ' + (($claudeArgs | Select-Object -First 10) -join ' ') + ' --disallowedTools <' + $DeniedTools.Count + ' rules>  (prompt on stdin)')
    Write-Info ('Log     : ' + $logFile)
    Write-Info ('Started : ' + (Get-Date).ToString('u') + ' - this can take a long time; output is shown when the process exits.')

    $output = (Get-IssuePrompt $n) | & $script:Claude @claudeArgs
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    [System.IO.File]::WriteAllText($logFile, $text, $utf8)

    $result = $null
    try { $result = ConvertFrom-Json -InputObject $text } catch { $result = $null }
    Write-Section ('Claude process finished (exit ' + $exitCode + ')')
    if ($null -ne $result -and ($result.PSObject.Properties.Name -contains 'result')) {
        Write-Info ([string]$result.result)
        if ($result.PSObject.Properties.Name -contains 'session_id') { Write-Info ('Session id: ' + $result.session_id + '  (claude --resume <id> to inspect)') }
        if ($result.PSObject.Properties.Name -contains 'total_cost_usd') { Write-Info ('Reported cost (USD): ' + $result.total_cost_usd) }
    } else {
        Write-Info $text
    }
    $isError = ($null -ne $result -and ($result.PSObject.Properties.Name -contains 'is_error') -and $result.is_error)
    return @{ ExitCode = $exitCode; IsError = $isError; LogFile = $logFile }
}

function Confirm-IssueResult($Candidate, $Run) {
    $n = $Candidate.Number
    Write-Section ('Verifying result for Issue #' + $n)

    $prs = Invoke-GhJson @('pr', 'list', '--state', 'all', '--limit', '200', '--json', 'number,url,state,headRefName,headRefOid,baseRefName,body,closingIssuesReferences,autoMergeRequest,statusCheckRollup')
    $pr = $prs | Where-Object { $_.state -ne 'CLOSED' -and (Test-ClosesIssue $_ $n) } | Sort-Object number -Descending | Select-Object -First 1

    if ($null -eq $pr) {
        $code = 2
        if ($Run.ExitCode -ne 0 -or $Run.IsError) { $code = 1 }
        Stop-Orchestrator ("No Pull Request was created for Issue #" + $n + ". The Issue stopped (human decision, blocker or failure - see Claude's report above and " + $Run.LogFile + "). Work, if any, was left in place on its branch.") $code
    }
    Write-Info ('PR #' + $pr.number + ' ' + $pr.url + ' [' + $pr.state + '] ' + $pr.headRefName + ' -> ' + $pr.baseRefName)

    if ($pr.state -eq 'MERGED') { Stop-Orchestrator ('PR #' + $pr.number + ' is MERGED. Merging is forbidden for automation; investigate immediately.') }
    if ($pr.baseRefName -ne $BaseBranch) { Stop-Orchestrator ('PR #' + $pr.number + ' targets ' + $pr.baseRefName + ', expected ' + $BaseBranch + '.') }
    if ($null -ne $pr.autoMergeRequest) { Stop-Orchestrator ('Auto-merge is enabled on PR #' + $pr.number + '. This is forbidden; disable it and investigate.') }
    if ($pr.headRefName -notmatch ('^(feature|fix)/' + $n + '-')) { Write-Warn ("PR branch '" + $pr.headRefName + "' does not follow feature|fix/" + $n + "-<description>.") }
    if (-not (Test-ClosesIssue @{ closingIssuesReferences = $pr.closingIssuesReferences; body = $pr.body; headRefName = '' } $n)) {
        Write-Warn ("PR #" + $pr.number + " body does not contain 'Closes #" + $n + "'.")
    }

    $failed = @()
    foreach ($c in $pr.statusCheckRollup) {
        $conclusion = ''
        if ($c.PSObject.Properties.Name -contains 'conclusion' -and $c.conclusion) { $conclusion = [string]$c.conclusion }
        elseif ($c.PSObject.Properties.Name -contains 'state' -and $c.state) { $conclusion = [string]$c.state }
        if (@('FAILURE', 'ERROR', 'CANCELLED', 'TIMED_OUT', 'ACTION_REQUIRED') -contains $conclusion) { $failed += $conclusion }
    }
    if ($failed.Count -gt 0) { Stop-Orchestrator ('PR #' + $pr.number + ' has failing checks (' + ($failed -join ', ') + '). It cannot be presented as ready.') }
    if (@($pr.statusCheckRollup).Count -eq 0) { Write-Warn 'No CI checks reported on the PR (no GitHub Actions workflow configured yet).' }

    # Local repository must be clean, on the PR branch, and fully pushed.
    $status = Get-PorcelainStatus
    if ($status) { Stop-Orchestrator ("Working tree is not clean after Issue #" + $n + ":`n" + $status) }
    $branch = Get-CurrentBranch
    Invoke-Git @('fetch', '--prune', 'origin') | Out-Null
    if ($branch -eq $pr.headRefName) {
        $local = (Invoke-Git @('rev-parse', 'HEAD')).Output.Trim()
        if ($local -ne $pr.headRefOid) { Stop-Orchestrator ("Local branch " + $branch + " (" + $local + ") differs from the pushed PR head (" + $pr.headRefOid + "). Unpushed or diverged work; resolve manually.") }
    } elseif ($branch -ne $BaseBranch) {
        Stop-Orchestrator ("Unexpected current branch '" + $branch + "' after Issue #" + $n + " (PR branch is '" + $pr.headRefName + "').")
    }
    Write-Info 'PR verified: open, targets main, not merged, no auto-merge, branch pushed, working tree clean.'
    return $pr
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

$exitCode = 0
$created = New-Object System.Collections.ArrayList
try {
    if ($DryRun) { Write-Host 'MODE: DRY RUN (no Claude process, no branch/commit/push/PR/Issue change)' -ForegroundColor Magenta }
    else { Write-Host ('MODE: REAL RUN, MaxIssues = ' + $MaxIssues) -ForegroundColor Magenta }

    Assert-Prerequisites

    if ($DryRun) {
        Sync-BaseBranch -ReadOnly
        $state = Get-BacklogState
        $evaluation = Measure-Backlog $state
        Write-Evaluation $evaluation
        if ($evaluation.Eligible.Count -eq 0) {
            Write-NoCandidate $evaluation $state
        } else {
            Write-Candidate $evaluation.Eligible[0]
            if ($evaluation.Eligible.Count -gt 1) {
                Write-Section 'Eligible queue (subsequent independent Issues, re-evaluated after each PR)'
                foreach ($e in $evaluation.Eligible) { Write-Info ('#' + $e.Number + ' ' + $e.Identifier + ' (priority ' + $e.Priority + ', unblocks ' + $e.Dependents + ')') }
            }
        }
        Write-Section 'Dry run complete'
        Write-Info 'Nothing was modified.'
    } else {
        $started = 0
        while ($started -lt $MaxIssues) {
            Sync-BaseBranch
            $state = Get-BacklogState
            $evaluation = Measure-Backlog $state
            Write-Evaluation $evaluation
            if ($evaluation.Eligible.Count -eq 0) {
                Write-NoCandidate $evaluation $state
                Write-Section 'STOP: no READY Issue remains'
                break
            }
            $candidate = $evaluation.Eligible[0]
            Write-Candidate $candidate

            $run = Invoke-IssueDeveloper $candidate
            $started++
            $pr = Confirm-IssueResult $candidate $run
            [void]$created.Add(('#' + $candidate.Number + ' ' + $candidate.Identifier + ' -> PR #' + $pr.number + ' ' + $pr.url))

            # Return to a predictable state: main, fast-forwarded (tree verified clean above).
            Invoke-Git @('checkout', $BaseBranch) | Out-Null
            Invoke-Git @('merge', '--ff-only', ('origin/' + $BaseBranch)) | Out-Null
        }
        if ($started -ge $MaxIssues) { Write-Section ('STOP: MaxIssues (' + $MaxIssues + ') reached') }
    }
}
catch {
    $message = $_.Exception.Message
    if ($message.StartsWith($StopPrefix)) {
        $exitCode = $script:StopCode
        Write-Section 'STOP'
        Write-Host $message.Substring($StopPrefix.Length) -ForegroundColor Red
    } else {
        $exitCode = 1
        Write-Section 'STOP: unexpected error'
        Write-Host ($_ | Out-String) -ForegroundColor Red
    }
}
finally {
    if (-not $DryRun) {
        Write-Section 'Summary'
        if ($created.Count -eq 0) { Write-Info 'No Pull Request created in this run.' }
        foreach ($c in $created) { Write-Info $c }
        Write-Info 'Pull Requests await human review. Nothing was merged.'
    }
}
exit $exitCode
