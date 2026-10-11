param(
    [string]$Python = 'python',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$CollectorArgs
)
$ErrorActionPreference = 'Stop'
& $Python (Join-Path $PSScriptRoot 'collect_results.py') @CollectorArgs
if ($LASTEXITCODE -ne 0) { throw "Quality collection failed (exit $LASTEXITCODE). Inspect its manifest.json." }
