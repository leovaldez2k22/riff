# Windows entry point for scripts/quality/verify.py (Q16). Every argument is passed through, for example:
#   .\scripts\quality\verify.ps1 --lane pr --offline
#   .\scripts\quality\verify.ps1 --lane emulator --allow-install --serial emulator-5580
param(
    [string]$Python = 'python',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$VerifyArgs
)
$ErrorActionPreference = 'Stop'
& $Python (Join-Path $PSScriptRoot 'verify.py') @VerifyArgs
exit $LASTEXITCODE
