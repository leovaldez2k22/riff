# Unattended-run guard, running on the phone so it survives USB drops. Every few seconds it:
#  - holds media, system and notification volume at 0 (the in-app volume slider calls
#    setStreamVolume(STREAM_MUSIC), so random input could otherwise unmute playback);
#  - wakes the screen if it has gone to sleep, so a secure lock screen never engages.
# Ring and alarm volumes are never touched. Wake events are logged to $logFile.
# Stop it with -Stop.
param([string]$Serial, [switch]$Stop)

$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Join-Path $project '.toolchain\sdk\platform-tools\adb.exe'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$pidFile = '/data/local/tmp/riff-night-guard.pid'
$logFile = '/data/local/tmp/riff-night-guard.log'

if ($Stop) {
    & $adb @adbArgs shell "[ -f $pidFile ] && kill `$(cat $pidFile) 2>/dev/null; rm -f $pidFile"
    Write-Host 'Night guard stopped.'
    return
}
$loop = 'while true; do ' +
    'for s in 1 3 5; do cmd audio set-volume $s 0 >/dev/null 2>&1; done; ' +
    "dumpsys power | grep -q mWakefulness=Awake || { input keyevent KEYCODE_WAKEUP; echo `"`$(date) woke screen`" >> $logFile; }; " +
    'sleep 2; done'
& $adb @adbArgs shell "[ -f $pidFile ] && kill `$(cat $pidFile) 2>/dev/null; setsid nohup sh -c '$loop' </dev/null >/dev/null 2>&1 & echo `$! > $pidFile"
Write-Host "Night guard running on the phone (pid file $pidFile, wake log $logFile)."
