# Saves, prepares and restores the phone settings that the stress run changes.
#   Save     record the current settings (refuses to overwrite an existing baseline without -Force)
#   Prepare  quiet, stay-awake test setup: media/system/notification volume 0, app exempt from doze
#   Restore  put every recorded setting back except volume, which stays muted (user asleep)
#   RestoreVolume  put the recorded media/system/notification volumes back (only once the user is awake)
#   Show     print the current values
# Ring and alarm volumes are never touched.
param(
    [Parameter(Mandatory)][ValidateSet('Save','Prepare','Restore','RestoreVolume','Show')][string]$Action,
    [string]$Serial,
    [string]$PackageId = 'com.musicglass.personal',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Join-Path $project '.toolchain\sdk\platform-tools\adb.exe'
$stateFile = Join-Path $project 'artifacts\stress\device-baseline.json'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }
function Shell([string]$command) { ((& $adb @adbArgs shell $command) -join "`n").Trim() }

$globalKeys = 'stay_on_while_plugged_in','always_finish_activities','window_animation_scale',
    'transition_animation_scale','animator_duration_scale','airplane_mode_on','mobile_data'
$systemKeys = 'accelerometer_rotation','user_rotation','font_scale','screen_off_timeout'
$mutedStreams = @{ 1 = 'system'; 3 = 'music'; 5 = 'notification' }

function Get-Volume([int]$stream) {
    # cmd media_session volume --set is ignored on this vivo build; cmd audio works.
    $out = Shell "cmd audio get-stream-volume $stream"
    if ($out -match '->\s*(\d+)') { [int]$Matches[1] } else { throw "Cannot read volume for stream $stream`: $out" }
}

function Read-State {
    $state = [ordered]@{ captured = (Get-Date).ToString('s'); global = [ordered]@{}; system = [ordered]@{}; volumes = [ordered]@{} }
    foreach ($k in $globalKeys) { $state.global[$k] = Shell "settings get global $k" }
    foreach ($k in $systemKeys) { $state.system[$k] = Shell "settings get system $k" }
    foreach ($s in $mutedStreams.Keys) { $state.volumes["$s"] = Get-Volume $s }
    $state.nightMode = (Shell 'cmd uimode night') -replace '^Night mode:\s*', ''
    $state.densityOverride = if ((Shell 'wm density') -match 'Override density:\s*(\d+)') { $Matches[1] } else { $null }
    $state.wifiEnabled = (Shell 'cmd wifi status') -match 'Wifi is enabled'
    $state.dozeWhitelisted = (Shell 'dumpsys deviceidle whitelist') -match [regex]::Escape($PackageId)
    $state.standbyBucket = Shell "am get-standby-bucket $PackageId"
    $state
}

function Set-Setting([string]$ns, [string]$key, $value) {
    if ($null -eq $value -or $value -eq 'null' -or $value -eq '') { Shell "settings delete $ns $key" | Out-Null }
    else { Shell "settings put $ns $key $value" | Out-Null }
}

switch ($Action) {
    'Show' { Read-State | ConvertTo-Json -Depth 4 }
    'Save' {
        if ((Test-Path -LiteralPath $stateFile) -and -not $Force) {
            throw "Baseline already exists at $stateFile. It holds the settings to restore; use -Force only if they are known to be normal."
        }
        New-Item -ItemType Directory -Force (Split-Path $stateFile) | Out-Null
        Read-State | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stateFile -Encoding utf8
        Write-Host "Saved baseline: $stateFile"
    }
    'Prepare' {
        if (-not (Test-Path -LiteralPath $stateFile)) { throw 'Run -Action Save first so the settings can be restored.' }
        foreach ($s in $mutedStreams.Keys) { Shell "cmd audio set-volume $s 0" | Out-Null }
        Shell 'settings put global stay_on_while_plugged_in 15' | Out-Null
        # Maximum timeout as a fallback if the charger disconnects and stay-on stops applying.
        Shell 'settings put system screen_off_timeout 2147483647' | Out-Null
        Shell "dumpsys deviceidle whitelist +$PackageId" | Out-Null
        Shell "am set-standby-bucket $PackageId active" | Out-Null
        Shell 'svc power stayon true' | Out-Null
        Write-Host 'Prepared: media/system/notification muted, screen kept awake, app exempt from doze.'
    }
    'Restore' {
        if (-not (Test-Path -LiteralPath $stateFile)) { throw "No baseline at $stateFile; nothing to restore." }
        $b = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        Shell 'svc power stayon false' | Out-Null  # first: it rewrites stay_on_while_plugged_in, restored below
        foreach ($p in $b.global.PSObject.Properties) {
            if ($p.Name -eq 'airplane_mode_on') {
                Shell ("cmd connectivity airplane-mode " + $(if ($p.Value -eq '1') { 'enable' } else { 'disable' })) | Out-Null
            } elseif ($p.Name -eq 'mobile_data') {
                Shell ("svc data " + $(if ($p.Value -eq '0') { 'disable' } else { 'enable' })) | Out-Null
            } else { Set-Setting 'global' $p.Name $p.Value }
        }
        foreach ($p in $b.system.PSObject.Properties) { Set-Setting 'system' $p.Name $p.Value }
        Shell ("cmd wifi set-wifi-enabled " + $(if ($b.wifiEnabled) { 'enabled' } else { 'disabled' })) | Out-Null
        Shell "cmd uimode night $($b.nightMode)" | Out-Null
        if ($b.densityOverride) { Shell "wm density $($b.densityOverride)" | Out-Null } else { Shell 'wm density reset' | Out-Null }
        if (-not $b.dozeWhitelisted) { Shell "dumpsys deviceidle whitelist -$PackageId" | Out-Null }
        Shell 'dumpsys deviceidle unforce' | Out-Null
        Shell 'dumpsys battery reset' | Out-Null
        if ($b.standbyBucket -match '^\d+$') { Shell "am set-standby-bucket $PackageId $($b.standbyBucket)" | Out-Null }
        # Volume deliberately stays at 0 and playback is stopped: the user is asleep. Use RestoreVolume later.
        Shell 'cmd media_session dispatch pause' | Out-Null
        foreach ($s in $mutedStreams.Keys) { Shell "cmd audio set-volume $s 0" | Out-Null }
        $now = Read-State
        $diff = @()
        foreach ($p in $b.global.PSObject.Properties) { if ("$($now.global[$p.Name])" -ne "$($p.Value)") { $diff += "global.$($p.Name): $($p.Value) -> $($now.global[$p.Name])" } }
        foreach ($p in $b.system.PSObject.Properties) { if ("$($now.system[$p.Name])" -ne "$($p.Value)") { $diff += "system.$($p.Name): $($p.Value) -> $($now.system[$p.Name])" } }
        if ($now.nightMode -ne $b.nightMode) { $diff += "nightMode: $($b.nightMode) -> $($now.nightMode)" }
        if ($now.wifiEnabled -ne $b.wifiEnabled) { $diff += "wifi: $($b.wifiEnabled) -> $($now.wifiEnabled)" }
        if ($diff) { Write-Warning ("Not restored:`n" + ($diff -join "`n")) } else { Write-Host 'All recorded settings restored. Volume left muted; run -Action RestoreVolume once the user is awake.' }
    }
    'RestoreVolume' {
        if (-not (Test-Path -LiteralPath $stateFile)) { throw "No baseline at $stateFile; nothing to restore." }
        $b = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        foreach ($p in $b.volumes.PSObject.Properties) { Shell "cmd audio set-volume $($p.Name) $($p.Value)" | Out-Null }
        Write-Host ('Volumes restored: ' + (($b.volumes.PSObject.Properties | ForEach-Object { "stream $($_.Name)=$($_.Value)" }) -join ', '))
    }
}
