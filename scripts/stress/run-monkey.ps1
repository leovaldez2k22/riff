# Seeded random-input runs against Riff with full log capture. Each seed is reproducible:
#   adb shell monkey -p com.musicglass.personal -s <seed> <same flags> <events>
# The app is pinned (lock task) so input cannot reach the notification shade or other apps.
# No hardware/system keys or "any" keys are sent (no power, volume or call keys).
param(
    [Parameter(Mandatory)][int[]]$Seeds,
    [int]$Events = 10000,
    [int]$Throttle = 250,
    [string]$Label = 'monkey',
    [string]$PackageId = 'com.musicglass.personal',
    [switch]$KeepGoing,  # soak mode: continue past crashes/ANRs instead of stopping at the first
    # Main-thread guardrail (plan W2.5): StrictMode violations from Riff's own process, by kind.
    [string]$SaveBaseline,  # write this run's per-kind counts (summed over seeds) to a JSON file
    [string]$Baseline       # compare with such a file; exit 1 if any kind's count went up
)

$ErrorActionPreference = 'Continue'  # adb writes progress to stderr
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Join-Path $project '.toolchain\sdk\platform-tools\adb.exe'
$out = Join-Path $project "artifacts\stress\$Label"
New-Item -ItemType Directory -Force $out | Out-Null
$summary = Join-Path $out 'summary.jsonl'

function Shell([string]$c) { ((& $adb shell $c 2>&1) -join "`n").Trim() }
function Start-Pinned {
    Shell "am start -W -n $PackageId/.MainActivity" | Out-Null
    Start-Sleep 2
    $task = Shell "am stack list | grep -m1 'com.musicglass.personal/'"
    if ($task -match 'taskId=(\d+)') { Shell "am task lock $($Matches[1])" | Out-Null }
}

# No --monitor-native-crashes: on this phone it reports old (August) tombstones as new.
# Native crashes are counted from "Fatal signal" in logcat instead.
$flags = "--throttle $Throttle --pct-syskeys 0 --pct-anyevent 0 --pct-appswitch 2 --pct-majornav 10 " +
    '--pct-touch 50 --pct-motion 30 --pct-trackball 0 --pct-nav 0 --pct-flip 0 --pct-pinchzoom 3 ' +
    '--pct-rotation 5 --pct-permission 0 ' +
    $(if ($KeepGoing) { '--ignore-crashes --ignore-timeouts --ignore-native-crashes ' } else { '' }) +
    '-v -v'

# Counts StrictMode violations (stress build only) and frame stalls logged by the given pids.
function Get-JankBreakdown([string]$log, [string[]]$pids) {
    $kinds = [ordered]@{}
    foreach ($m in [regex]::Matches($log, '(?m)^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+StrictMode\s*: StrictMode policy violation(?:; ~duration=(\d+) ms)?: android\.os\.strictmode\.(\w+)')) {
        if ($pids -notcontains $m.Groups[1].Value) { continue }
        $kind = $m.Groups[3].Value
        if (-not $kinds.Contains($kind)) { $kinds[$kind] = [ordered]@{ count = 0; totalMs = 0; maxMs = 0 } }
        $k = $kinds[$kind]; $k.count++
        if ($m.Groups[2].Success) { $ms = [int]$m.Groups[2].Value; $k.totalMs += $ms; if ($ms -gt $k.maxMs) { $k.maxMs = $ms } }
    }
    $skipped = @([regex]::Matches($log, '(?m)^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+Choreographer\s*: Skipped (\d+) frames') |
        Where-Object { $pids -contains $_.Groups[1].Value } | ForEach-Object { [int]$_.Groups[2].Value })
    [ordered]@{ strictByKind = $kinds; skippedFrameEvents = $skipped.Count
                maxSkippedFrames = if ($skipped.Count) { ($skipped | Measure-Object -Maximum).Maximum } else { 0 } }
}
$runTotals = @{}

foreach ($seed in $Seeds) {
    $tag = "seed-$seed"
    $logFile = Join-Path $out "$tag-logcat.txt"
    $monkeyFile = Join-Path $out "$tag-monkey.txt"
    Start-Pinned
    $pidStart = Shell "pidof $PackageId"
    & $adb logcat -c 2>$null
    $logcat = Start-Process -FilePath $adb -ArgumentList 'logcat','-v','threadtime','-b','main,system,crash' `
        -RedirectStandardOutput $logFile -NoNewWindow -PassThru
    $started = Get-Date
    & $adb shell "monkey -p $PackageId -s $seed $flags $Events" *> $monkeyFile
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    Start-Sleep 2
    Stop-Process -Id $logcat.Id -Force -ErrorAction SilentlyContinue
    Shell 'am task lock stop' | Out-Null
    Shell 'settings put system user_rotation 0' | Out-Null  # rotation events can leave it landscape

    $monkey = Get-Content $monkeyFile -Raw
    $log = if (Test-Path $logFile) { Get-Content $logFile -Raw } else { '' }
    $pidNow = Shell "pidof $PackageId"
    $mem = Shell "dumpsys meminfo $PackageId | grep -m1 'TOTAL PSS'"
    $result = [ordered]@{
        seed = $seed; events = $Events; seconds = $elapsed
        finished = $monkey -match 'Monkey finished'
        eventsInjected = if ($monkey -match 'Events injected: (\d+)') { [int]$Matches[1] } else { $null }
        crash = $monkey -match '// CRASH'
        anr = $monkey -match '// NOT RESPONDING'
        fatalInLog = ([regex]::Matches($log, 'FATAL EXCEPTION')).Count
        nativeCrash = ([regex]::Matches($log, 'Fatal signal')).Count
        anrInLog = ([regex]::Matches($log, "ANR in $([regex]::Escape($PackageId))")).Count
        strictMode = ([regex]::Matches($log, 'StrictMode policy violation|StrictMode: Tried to access')).Count
        appAlive = [bool]$pidNow
        pssKb = if ($mem -match 'TOTAL PSS:\s*(\d+)') { [int]$Matches[1] } else { $null }
        jank = Get-JankBreakdown $log @($pidStart, $pidNow | Where-Object { $_ })
    }
    foreach ($kind in $result.jank.strictByKind.Keys) { $runTotals[$kind] = [int]$runTotals[$kind] + $result.jank.strictByKind[$kind].count }
    if ($result.anr -or $result.anrInLog) {
        Shell 'dumpsys activity processes' | Out-Null
        & $adb bugreport (Join-Path $out "$tag-bugreport.zip") *> $null
    }
    ($result | ConvertTo-Json -Compress) | Add-Content $summary
    Write-Host ("seed {0}: {1}s injected={2} crash={3} anr={4} fatal={5} native={6} strict={7} pss={8}MB" -f `
        $seed, $elapsed, $result.eventsInjected, $result.crash, $result.anr, $result.fatalInLog,
        $result.nativeCrash, $result.strictMode, [int]($result.pssKb / 1024))
}

$totalsText = ($runTotals.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' '
Write-Host "StrictMode (Riff process, all seeds): $(if ($totalsText) { $totalsText } else { 'none' })"
if ($SaveBaseline) {
    ([ordered]@{ seeds = $Seeds; events = $Events; strictByKind = $runTotals } | ConvertTo-Json) | Set-Content $SaveBaseline -Encoding utf8
    Write-Host "Baseline saved: $SaveBaseline"
}
if ($Baseline) {
    $base = Get-Content $Baseline -Raw | ConvertFrom-Json
    if ((@($base.seeds) -join ',') -ne ($Seeds -join ',') -or $base.events -ne $Events) {
        Write-Warning "Baseline was recorded with seeds $(@($base.seeds) -join ',') x $($base.events) events; counts are only comparable for the same seeds and event count."
    }
    $worse = @()
    foreach ($kind in $runTotals.Keys) {
        $before = if ($base.strictByKind.PSObject.Properties[$kind]) { [int]$base.strictByKind.$kind } else { 0 }
        if ($runTotals[$kind] -gt $before) { $worse += "$kind $before -> $($runTotals[$kind])" }
    }
    if ($worse) { Write-Host "Main-thread guardrail FAILED: $($worse -join '; ')" -ForegroundColor Red; exit 1 }
    Write-Host 'Main-thread guardrail passed: no StrictMode kind increased.' -ForegroundColor Green
}
