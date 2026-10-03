# Fake `claude -p` used by the worker tests: never calls a model.
# implementation agents commit a docs change; code-reviewer reports one HIGH finding the first time.
#
# Crash simulation (each fires once per FAKE_CLAUDE_STATE directory):
#   FAKE_CLAUDE_DIE = developer-dirty  first implementation call leaves uncommitted/untracked work, then the worker dies
#                   | code-reviewer    first code-reviewer call kills the worker (after commit + gate)
#                   | fix              first FIX MODE call kills the worker (findings already persisted)
#   The worker is killed with its whole process tree (taskkill /T /F), like a closed terminal.
#   FAKE_CLAUDE_FAIL = auth            first call fails like an expired Claude session (exit 1, is_error)
#   FAKE_CLAUDE_REVIEW_CLEAN = 1       reviewers never report findings
$agent = ''
for ($i = 0; $i -lt $args.Count; $i++) { if ($args[$i] -eq '--agent') { $agent = $args[$i + 1] } }
$prompt = [Console]::In.ReadToEnd()
$stateDir = $env:FAKE_CLAUDE_STATE
$text = ''
$isFix = ($prompt -match 'FIX MODE')

function Test-Once([string]$Name) {
    $marker = Join-Path $stateDir ('once-' + $Name)
    if (Test-Path -LiteralPath $marker) { return $false }
    Set-Content -LiteralPath $marker -Value 1
    return $true
}
function Stop-Worker {
    $state = Get-Content -LiteralPath $env:FAKE_CLAUDE_WORKER_STATE -Raw | ConvertFrom-Json
    Add-Content -LiteralPath (Join-Path $stateDir 'calls.log') -Value ($agent + ':died')
    & taskkill.exe /PID ([int]$state.WorkerPid) /T /F 2>&1 | Out-Null
    Start-Sleep -Seconds 30   # never reached: this process belongs to the killed tree
    exit 99
}

if ($env:FAKE_CLAUDE_FAIL -eq 'auth' -and (Test-Once 'fail-auth')) {
    Add-Content -LiteralPath (Join-Path $stateDir 'calls.log') -Value ($agent + ':auth-failed')
    @{ type = 'result'; is_error = $true; result = 'OAuth token has expired. Please run /login'; session_id = 'fake' } | ConvertTo-Json -Compress
    exit 1
}

$isDeveloper = ($agent -match 'issue-developer|integration-validator')
if ($isDeveloper -and -not $isFix -and $env:FAKE_CLAUDE_DIE -eq 'developer-dirty' -and (Test-Once 'die')) {
    New-Item -ItemType Directory -Force -Path docs, notes | Out-Null
    Set-Content -LiteralPath 'docs/feature.md' -Value 'feature'
    Set-Content -LiteralPath 'notes/wip.txt' -Value 'work in progress'
    Stop-Worker
}
if ($isDeveloper -and $isFix -and $env:FAKE_CLAUDE_DIE -eq 'fix' -and (Test-Once 'die')) { Stop-Worker }
if ($agent -eq 'code-reviewer' -and $env:FAKE_CLAUDE_DIE -eq 'code-reviewer' -and (Test-Once 'die')) { Stop-Worker }

switch -Regex ($agent) {
    'issue-developer|integration-validator' {
        if ($isFix) {
            Add-Content -LiteralPath 'docs/feature.md' -Value 'fixed after review'
            & git add docs/feature.md | Out-Null
            & git commit -q --signoff -m 'fix(docs): address review findings (#7)' | Out-Null
        } elseif ($prompt -match 'RECOVERY:' -and (& git status --porcelain)) {
            # Continue the interrupted work: keep and commit everything that exists.
            & git add -A | Out-Null
            & git commit -q --signoff -m 'docs: complete interrupted implementation (#7)' | Out-Null
        } else {
            New-Item -ItemType Directory -Force -Path docs | Out-Null
            Set-Content -LiteralPath 'docs/feature.md' -Value 'feature'
            & git add docs/feature.md | Out-Null
            & git commit -q --signoff -m 'docs: implement feature (#7)' | Out-Null
        }
        $text = "Implemented.`nORCHESTRATOR-RESULT: {`"status`":`"DONE`",`"category`":`"NONE`",`"summary`":`"ok`"}"
    }
    'code-reviewer' {
        $marker = Join-Path $stateDir 'code-reviewed'
        if ($env:FAKE_CLAUDE_REVIEW_CLEAN -eq '1' -or (Test-Path -LiteralPath $marker)) { $text = "Summary`nBLOCKER: 0`nHIGH: 0`nMEDIUM: 0`nLOW: 1" }
        else { Set-Content -LiteralPath $marker -Value 1; $text = "[HIGH] Missing detail`nLocation: docs/feature.md:1`n`nSummary`nBLOCKER: 0`nHIGH: 1`nMEDIUM: 0`nLOW: 0" }
    }
    default { $text = "Summary`nBLOCKER: 0`nHIGH: 0`nMEDIUM: 0`nLOW: 0" }
}
Add-Content -LiteralPath (Join-Path $stateDir 'calls.log') -Value $agent
@{ type = 'result'; is_error = $false; result = $text; total_cost_usd = 0.01; session_id = 'fake' } | ConvertTo-Json -Compress
