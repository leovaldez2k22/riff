# Builds Riff Lite: a separate app (com.musicglass.personal.lite) for low-RAM phones.
# Signed with the same personal key as release; installs beside Riff, never over it.
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = Join-Path $project '.toolchain\sdk'
$gradle = Join-Path $project '.toolchain\gradle-8.13\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $project 'gradlew.bat' }
$apksigner = Join-Path $sdk 'build-tools\36.0.0\apksigner.bat'
$signingDir = Join-Path $project '.toolchain\signing'
$keystore = Join-Path $signingDir 'musicglass-release.p12'
$passwordFile = Join-Path $signingDir 'password.txt'
$passwordDpapi = Join-Path $signingDir 'password.dpapi'
$apk = Join-Path $project 'app\build\outputs\apk\lite\app-lite.apk'
$releaseDir = Join-Path $project 'artifacts\lite'
if (-not ((Test-Path -LiteralPath $keystore) -and ((Test-Path -LiteralPath $passwordDpapi) -or (Test-Path -LiteralPath $passwordFile)))) {
    throw 'Release signing key missing; run build-release-candidate.ps1 once first.'
}
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
$versionName = $versionLine.Matches[0].Groups[1].Value
$liteApk = Join-Path $releaseDir "riff-lite-$versionName.apk"

$env:ANDROID_HOME = $sdk
$env:GRADLE_USER_HOME = Join-Path $project '.toolchain\gradle-user-home'
$env:MUSIC_GLASS_KEYSTORE = $keystore
$env:MUSIC_GLASS_KEY_ALIAS = 'musicglass-release'
# The password is stored encrypted for this Windows user (DPAPI); password.txt is the old plain form.
$env:MUSIC_GLASS_STORE_PASSWORD = if (Test-Path -LiteralPath $passwordDpapi) {
    $secure = (Get-Content -LiteralPath $passwordDpapi -Raw).Trim() | ConvertTo-SecureString
    [System.Net.NetworkCredential]::new('', $secure).Password
} else { [System.IO.File]::ReadAllText($passwordFile).Trim() }
$env:MUSIC_GLASS_KEY_PASSWORD = $env:MUSIC_GLASS_STORE_PASSWORD
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
Push-Location $project
try {
    & $gradle ':app:assembleLite' '--console=plain' '--quiet'
    if ($LASTEXITCODE -ne 0) { throw 'Lite build failed.' }
    & $apksigner verify $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Copy-Item -LiteralPath $apk -Destination $liteApk -Force
    Write-Host "Lite APK: $liteApk"
}
finally {
    Pop-Location
    Remove-Item Env:MUSIC_GLASS_STORE_PASSWORD,Env:MUSIC_GLASS_KEY_PASSWORD -ErrorAction SilentlyContinue
}
