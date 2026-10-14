# Builds and pairs stress APKs, then records strict instrumentation evidence.
# Installation requires explicit -AllowInstall. Personal devices/accounts are not fixtures.
param(
    [switch]$NoBuild,
    [switch]$AllowInstall,
    [string]$Serial,
    [string]$Python = 'python',
    [ValidateSet('R','L','emulator','unclassified')][string]$DeviceTier = 'unclassified'
)
$ErrorActionPreference = 'Stop'
if (-not $AllowInstall) { throw 'Installation requires -AllowInstall and an authorized test device/profile.' }
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$collector = Join-Path $PSScriptRoot 'quality/collect_results.py'
. (Join-Path $PSScriptRoot 'quality/Tools.ps1'); $adb = Resolve-RiffAdb $project
$devices = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
$ready = @($devices | Select-String '^([^\s]+)\s+device$')
if (-not $Serial) {
    if ($ready.Count -ne 1) { throw 'Specify -Serial when exactly one authorized device is not available.' }
    $Serial = $ready[0].Matches[0].Groups[1].Value
}
if (-not ($ready | Where-Object { $_.Matches[0].Groups[1].Value -eq $Serial })) { throw 'Selected device is not authorized/ready.' }
$adbArgs = @('-s', $Serial)

if (-not $NoBuild) {
    $env:ANDROID_HOME = Resolve-RiffSdk $project
    $env:GRADLE_USER_HOME = Join-Path $project '.toolchain/gradle-user-home'
    $jdk = Get-ChildItem (Join-Path $project '.toolchain') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -Last 1
    if (-not $env:JAVA_HOME -and $jdk) { $env:JAVA_HOME = $jdk.FullName }
    & (Join-Path $project 'gradlew.bat') -p $project ':app:assembleStress' ':app:assembleStressAndroidTest' '-PriffTestBuildType=stress' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$app = Get-ChildItem (Join-Path $project 'app/build/outputs/apk/stress') -Filter '*.apk'
$test = Get-ChildItem (Join-Path $project 'app/build/outputs/apk/androidTest/stress') -Filter '*.apk'
if (@($app).Count -ne 1 -or @($test).Count -ne 1) { throw 'Exactly one stress app/test APK pair is required.' }
$appIdentityJson = & $Python $collector --inspect-apk $app.FullName
if ($LASTEXITCODE -ne 0) { throw 'App APK has no build identity; rebuild it.' }
$testIdentityJson = & $Python $collector --inspect-apk $test.FullName
if ($LASTEXITCODE -ne 0 -or $appIdentityJson -ne $testIdentityJson) { throw 'App/test APK build identities do not match.' }
$identity = $appIdentityJson | ConvertFrom-Json
if ($identity.variant -ne 'stress') { throw 'Expected a stress APK pair.' }

$runId = 'ui-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$raw = Join-Path $project "artifacts/quality/$($identity.revision)-$($identity.sourceState)-$($identity.sourceDigest.Substring(0,12))/$runId-raw"
New-Item -ItemType Directory -Path $raw | Out-Null
& $adb @adbArgs install -r $app.FullName
if ($LASTEXITCODE -ne 0) { throw 'Installing the stress app failed; never uninstall to bypass a certificate mismatch.' }
& $adb @adbArgs install -r $test.FullName
if ($LASTEXITCODE -ne 0) { throw 'Installing the test APK failed.' }
$startUtc = [DateTime]::UtcNow.ToString('o')
$output = & $adb @adbArgs shell am instrument -w -r -e class com.musicglass.personal.UiRegressionInstrumentation com.musicglass.personal.test/androidx.test.runner.AndroidJUnitRunner
$instrumentExit = $LASTEXITCODE
$endUtc = [DateTime]::UtcNow.ToString('o')
$log = Join-Path $raw 'instrumentation.txt'
$output | Set-Content -LiteralPath $log -Encoding utf8
$output
$api = (& $adb @adbArgs shell getprop ro.build.version.sdk).Trim()
$model = (& $adb @adbArgs shell getprop ro.product.model).Trim()
& $Python $collector --collect-only --checks-skipped --variant stress --apk $app.FullName --test-apk $test.FullName --instrumentation $log --start-utc $startUtc --end-utc $endUtc --exit-code $instrumentExit --run-id $runId --device-tier $DeviceTier --device-api $api --device-model $model --mode live --command 'adb instrumentation UiRegressionInstrumentation (serial omitted)'
if ($LASTEXITCODE -eq 1) { throw 'Instrumentation evidence failed validation.' }
if ($LASTEXITCODE -eq 2) { Write-Warning 'Device evidence collected; JVM/lint checks were not run by this command and remain BLOCKED.' }
