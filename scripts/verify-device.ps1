param(
    [string]$Milestone = 'M00',
    [string]$Serial,
    [string]$PackageId = 'com.musicglass.personal',
    [string]$LauncherActivity = 'com.musicglass.personal.MainActivity',
    [ValidateSet('Debug','Release')][string]$BuildVariant = 'Debug',
    [ValidateRange(0, 60)][int]$RecordSeconds = 0
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = Join-Path $project '.toolchain\sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$wrapper = Join-Path $project 'gradlew.bat'
$localGradle = Join-Path $project '.toolchain\gradle-8.13\bin\gradle.bat'
$gradle = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { $wrapper }
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { throw 'Cannot determine versionName from app/build.gradle.kts.' }
$versionName = $versionLine.Matches[0].Groups[1].Value
$apk = if ($BuildVariant -eq 'Release') {
    Join-Path $project "artifacts\release\riff-$versionName.apk"
} else {
    Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
}

if (-not (Test-Path -LiteralPath $adb)) { throw "ADB missing at $adb. Install project-local Android platform-tools." }
if (-not (Test-Path -LiteralPath $gradle)) { throw "Gradle missing: checked $localGradle and $wrapper." }

$devices = & $adb devices -l
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
$connected = @($devices | Select-String '^([^\s]+)\s+(device|unauthorized|offline)\b')
if (-not $Serial) {
    $ready = @($connected | Where-Object { $_.Matches[0].Groups[2].Value -eq 'device' })
    if ($ready.Count -eq 1) { $Serial = $ready[0].Matches[0].Groups[1].Value }
    elseif ($ready.Count -eq 0) { throw "DEVICE_CHECK_PENDING: no authorized device. Connect and unlock the phone, then approve USB debugging. $($devices -join ' ')" }
    else { throw 'Multiple authorized devices; specify -Serial explicitly.' }
}
$line = $connected | Where-Object { $_.Matches[0].Groups[1].Value -eq $Serial } | Select-Object -First 1
if (-not $line) { throw "DEVICE_CHECK_PENDING: serial $Serial not found." }
$state = $line.Matches[0].Groups[2].Value
if ($state -ne 'device') { throw "DEVICE_CHECK_PENDING: serial $Serial is $state. Unlock the phone and approve USB debugging." }

$env:ANDROID_HOME = $sdk
$env:GRADLE_USER_HOME = Join-Path $project '.toolchain\gradle-user-home'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $project "artifacts\device-checks\$Milestone\$stamp"
New-Item -ItemType Directory -Force (Join-Path $out 'screenshots'),(Join-Path $out 'recordings'),(Join-Path $out 'logs') | Out-Null

Push-Location $project
try {
    if ($BuildVariant -eq 'Release') {
        & (Join-Path $project 'scripts\build-release-candidate.ps1') -SkipClean 2>&1 |
            Tee-Object -FilePath (Join-Path $out 'logs\build.txt')
    } else {
        & $gradle ':app:testDebugUnitTest' ':app:lintDebug' ':app:assembleDebug' '--console=plain' 2>&1 |
            Tee-Object -FilePath (Join-Path $out 'logs\build.txt')
    }
    if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed. See logs/build.txt.' }
    if (-not (Test-Path -LiteralPath $apk)) { throw "Expected APK missing: $apk" }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    $revision = if (Get-Command git -ErrorAction SilentlyContinue) { git rev-parse HEAD 2>$null } else { 'NO_GIT' }
    if ($LASTEXITCODE -ne 0) { $revision = 'NO_GIT' }
    $deviceInfo = [ordered]@{
        serial = $Serial
        model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
        androidRelease = (& $adb -s $Serial shell getprop ro.build.version.release).Trim()
        api = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
        build = (& $adb -s $Serial shell getprop ro.build.fingerprint).Trim()
        gpu = (& $adb -s $Serial shell getprop ro.hardware.egl).Trim()
        size = ((& $adb -s $Serial shell wm size) -join ' ').Trim()
        density = ((& $adb -s $Serial shell wm density) -join ' ').Trim()
    }
    $deviceInfo | ConvertTo-Json | Set-Content (Join-Path $out 'device.json')
    [ordered]@{ milestone=$Milestone; apkSha256=$hash; revision=$revision; package=$PackageId; variant=$BuildVariant } |
        ConvertTo-Json | Set-Content (Join-Path $out 'build.json')

    & $adb -s $Serial install -r $apk | Tee-Object -FilePath (Join-Path $out 'logs\install.txt')
    if ($LASTEXITCODE -ne 0) { throw 'APK install failed.' }
    & $adb -s $Serial shell am start -W -n "$PackageId/$LauncherActivity" | Tee-Object -FilePath (Join-Path $out 'logs\launch.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Activity launch failed.' }
    Start-Sleep -Seconds 2
    $remotePng = "/sdcard/Download/musicglass-$Milestone-$stamp.png"
    & $adb -s $Serial shell screencap -p $remotePng
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot capture failed.' }
    & $adb -s $Serial pull $remotePng (Join-Path $out 'screenshots\launch.png')
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot pull failed.' }
    & $adb -s $Serial shell rm $remotePng
    if ($RecordSeconds -gt 0) {
        $remoteMp4 = "/sdcard/Download/musicglass-$Milestone-$stamp.mp4"
        & $adb -s $Serial shell screenrecord --time-limit $RecordSeconds $remoteMp4
        if ($LASTEXITCODE -ne 0) { throw 'Screen recording failed.' }
        & $adb -s $Serial pull $remoteMp4 (Join-Path $out 'recordings\scene.mp4')
        if ($LASTEXITCODE -ne 0) { throw 'Recording pull failed.' }
        & $adb -s $Serial shell rm $remoteMp4
    }
    & $adb -s $Serial logcat -d -t 500 | Set-Content (Join-Path $out 'logs\logcat.txt')
    & $adb -s $Serial shell dumpsys gfxinfo $PackageId framestats |
        Set-Content (Join-Path $out 'logs\gfxinfo.txt')
    & $adb -s $Serial shell dumpsys meminfo $PackageId |
        Set-Content (Join-Path $out 'logs\meminfo.txt')
    @("# $Milestone device check", '', "Status: DEVICE_REVIEW_PENDING", "APK SHA-256: $hash", "Device serial: $Serial", '', 'Inspect launch.png and logcat, then record visual findings.') |
        Set-Content (Join-Path $out 'report.md')
    Write-Host "Evidence: $out"
    Write-Host 'DEVICE_REVIEW_PENDING: inspect screenshot and logs before declaring PASS.'
}
finally { Pop-Location }
