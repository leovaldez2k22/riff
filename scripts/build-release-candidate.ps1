param([switch]$SkipChecks, [switch]$SkipClean)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = Join-Path $project '.toolchain\sdk'
$gradle = Join-Path $project '.toolchain\gradle-8.13\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $project 'gradlew.bat' }
$keytool = if ($env:JAVA_HOME -and
    (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} elseif (Get-Command keytool.exe -ErrorAction SilentlyContinue) {
    (Get-Command keytool.exe).Source
} else {
    'C:\Program Files\Java\jdk-20\bin\keytool.exe'
}
$apksigner = Join-Path $sdk 'build-tools\36.0.0\apksigner.bat'
$signingDir = Join-Path $project '.toolchain\signing'
$keystore = Join-Path $signingDir 'musicglass-release.p12'
$passwordFile = Join-Path $signingDir 'password.txt'
$apk = Join-Path $project 'app\build\outputs\apk\release\app-release.apk'
$releaseDir = Join-Path $project 'artifacts\release'
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { throw 'Cannot determine versionName from app/build.gradle.kts.' }
$versionName = $versionLine.Matches[0].Groups[1].Value
$releaseApk = Join-Path $releaseDir "riff-$versionName.apk"
$dependencyGraph = Join-Path $releaseDir 'dependencies.txt'

foreach ($tool in @($gradle, $keytool, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required build tool missing: $tool" }
}
if (-not (Test-Path -LiteralPath $sdk)) { throw "Android SDK missing: $sdk" }

New-Item -ItemType Directory -Force -Path $signingDir, $releaseDir | Out-Null
if ((Test-Path -LiteralPath $keystore) -xor (Test-Path -LiteralPath $passwordFile)) {
    throw 'Signing key or password is missing. Restore both from backup; never replace just one.'
}
if (-not (Test-Path -LiteralPath $keystore)) {
    $random = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $password = [Convert]::ToBase64String($random)
    [System.IO.File]::WriteAllText($passwordFile, $password)
    $env:MUSIC_GLASS_STORE_PASSWORD = $password
    $env:MUSIC_GLASS_KEY_PASSWORD = $password
    & $keytool -genkeypair -storetype PKCS12 -keystore $keystore `
        -alias musicglass-release -keyalg RSA -keysize 3072 -validity 3650 `
        -dname 'CN=Riff, OU=Personal Build' `
        -storepass:env MUSIC_GLASS_STORE_PASSWORD -keypass:env MUSIC_GLASS_KEY_PASSWORD `
        -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
}

$env:ANDROID_HOME = $sdk
$env:GRADLE_USER_HOME = Join-Path $project '.toolchain\gradle-user-home'
$env:MUSIC_GLASS_KEYSTORE = $keystore
$env:MUSIC_GLASS_KEY_ALIAS = 'musicglass-release'
$env:MUSIC_GLASS_STORE_PASSWORD = [System.IO.File]::ReadAllText($passwordFile).Trim()
$env:MUSIC_GLASS_KEY_PASSWORD = $env:MUSIC_GLASS_STORE_PASSWORD

Push-Location $project
try {
    if (-not $SkipClean) {
        & $gradle ':app:clean' ':core:glass:clean' '--console=plain' '--quiet'
        if ($LASTEXITCODE -ne 0) { throw 'Clean build preparation failed.' }
    }
    $tasks = if ($SkipChecks) { @(':app:assembleRelease') } else {
        @(':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease')
    }
    & $gradle @tasks '--console=plain' '--quiet'
    if ($LASTEXITCODE -ne 0) { throw 'Release build or checks failed.' }
    if (-not (Test-Path -LiteralPath $apk)) { throw "Release APK missing: $apk" }
    & $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & $gradle ':app:dependencies' '--configuration' 'releaseRuntimeClasspath' `
        '--console=plain' '--quiet' | Set-Content -LiteralPath $dependencyGraph
    if ($LASTEXITCODE -ne 0) { throw 'Release dependency report failed.' }
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $python) { throw 'Python is needed for the release license audit.' }
    & $python.Source (Join-Path $project 'scripts\audit_dependency_licenses.py')
    if ($LASTEXITCODE -ne 0) { throw 'Release license audit failed.' }
    Copy-Item -LiteralPath $apk -Destination $releaseApk -Force
    $hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $releaseDir 'SHA256SUMS.txt'),
        "$hash  $(Split-Path -Leaf $releaseApk)`n")
    Write-Host "Signed APK: $releaseApk"
    Write-Host "SHA-256: $hash"
    Write-Host "Back up $keystore and $passwordFile together before installing this key's builds."
}
finally {
    Pop-Location
    Remove-Item Env:MUSIC_GLASS_STORE_PASSWORD,Env:MUSIC_GLASS_KEY_PASSWORD `
        -ErrorAction SilentlyContinue
}
