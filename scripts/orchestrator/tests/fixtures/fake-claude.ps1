# Fake `claude -p` used by the worker smoke test: never calls a model.
# implementation agents commit a docs change; code-reviewer reports one HIGH finding the first time.
$agent = ''
for ($i = 0; $i -lt $args.Count; $i++) { if ($args[$i] -eq '--agent') { $agent = $args[$i + 1] } }
$prompt = [Console]::In.ReadToEnd()
$stateDir = $env:FAKE_CLAUDE_STATE
$text = ''
switch -Regex ($agent) {
    'issue-developer|integration-validator' {
        if ($prompt -match 'FIX MODE') {
            Add-Content -LiteralPath 'docs/feature.md' -Value 'fixed after review'
            & git add docs/feature.md | Out-Null
            & git commit -q --signoff -m 'fix(docs): address review findings (#7)' | Out-Null
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
        if (Test-Path -LiteralPath $marker) { $text = "Summary`nBLOCKER: 0`nHIGH: 0`nMEDIUM: 0`nLOW: 1" }
        else { Set-Content -LiteralPath $marker -Value 1; $text = "[HIGH] Missing detail`nLocation: docs/feature.md:1`n`nSummary`nBLOCKER: 0`nHIGH: 1`nMEDIUM: 0`nLOW: 0" }
    }
    default { $text = "Summary`nBLOCKER: 0`nHIGH: 0`nMEDIUM: 0`nLOW: 0" }
}
Add-Content -LiteralPath (Join-Path $stateDir 'calls.log') -Value $agent
@{ type = 'result'; is_error = $false; result = $text; total_cost_usd = 0.01; session_id = 'fake' } | ConvertTo-Json -Compress
