# Q16: finds the Android SDK and adb for the PowerShell scripts, in the order verify.py uses: ANDROID_HOME,
# ANDROID_SDK_ROOT, sdk.dir in local.properties, then the project's own .toolchain\sdk. Dot-source it:
#   . (Join-Path $PSScriptRoot 'quality\Tools.ps1'); $adb = Resolve-RiffAdb $project

function Get-RiffLocalSdk([string]$Project) {
    $file = Join-Path $Project 'local.properties'
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    $line = Get-Content -LiteralPath $file | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\')
}

function Resolve-RiffSdk([string]$Project) {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Get-RiffLocalSdk $Project), (Join-Path $Project '.toolchain\sdk'))) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw 'No Android SDK: set ANDROID_HOME, or sdk.dir in local.properties.'
}

function Resolve-RiffAdb([string]$Project) {
    $sdk = Resolve-RiffSdk $Project
    foreach ($name in 'adb.exe', 'adb') {
        $path = Join-Path $sdk (Join-Path 'platform-tools' $name)
        if (Test-Path -LiteralPath $path) { return $path }
    }
    throw "adb is missing from $sdk\platform-tools."
}
