# Device UI regression suite (plan W7.4): builds the stress app and its test APK, installs both
# and runs UiRegressionInstrumentation on the connected phone. The stress build is signed with the
# release key, so it installs over a release install without wiping data.
#
#   .\scripts\run-ui-regression.ps1            # build, install, run
#   .\scripts\run-ui-regression.ps1 -NoBuild   # reuse the last build
#
# The suite never starts playback. It changes font size and rotation lock and restores both;
# keep the phone unlocked and screen-pinning prompts off for an unattended run.
param([switch]$NoBuild, [string]$Serial)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $project '.toolchain\sdk\platform-tools\adb.exe'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }

if (-not $NoBuild) {
    $env:ANDROID_HOME = Join-Path $project '.toolchain\sdk'
    $env:GRADLE_USER_HOME = Join-Path $project '.toolchain\gradle-user-home'
    $jdk = Join-Path $project '.toolchain\jdk-21.0.12.1+1'
    if (Test-Path -LiteralPath $jdk) { $env:JAVA_HOME = $jdk }
    & (Join-Path $project 'gradlew.bat') -p $project ':app:assembleStress' ':app:assembleStressAndroidTest' '-PriffTestBuildType=stress' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}

$app = Get-ChildItem (Join-Path $project 'app\build\outputs\apk\stress') -Filter '*.apk' | Select-Object -First 1
$test = Get-ChildItem (Join-Path $project 'app\build\outputs\apk\androidTest\stress') -Filter '*.apk' | Select-Object -First 1
if (-not $app -or -not $test) { throw 'Stress APKs missing; run without -NoBuild.' }

& $adb @adbArgs install -r $app.FullName
if ($LASTEXITCODE -ne 0) { throw 'Installing the stress app failed.' }
& $adb @adbArgs install -r $test.FullName
if ($LASTEXITCODE -ne 0) { throw 'Installing the test APK failed.' }

$output = & $adb @adbArgs shell am instrument -w -r -e class com.musicglass.personal.UiRegressionInstrumentation `
    com.musicglass.personal.test/androidx.test.runner.AndroidJUnitRunner
$output
$failures = @($output | Select-String 'INSTRUMENTATION_STATUS_CODE: -2')
if ($failures.Count -gt 0 -or -not ($output | Select-String '^OK \(')) { throw "UI regression suite failed ($($failures.Count) failing tests)." }
'UI regression suite passed.'
