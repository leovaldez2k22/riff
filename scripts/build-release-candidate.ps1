# -Toolchain: the folder that holds signing, gradle-user-home and jdk-* (default: this checkout's .toolchain), so a
# clean worktree can be built with the main checkout's key and cache. -OutDir: where the candidate goes (default
# artifacts\release). -Offline: no network; every dependency must already be in the Gradle cache. -Preview: also
# build the preview (the same release under com.musicglass.personal.preview, to try beside an installed release).
param([switch]$SkipChecks, [switch]$SkipClean, [switch]$NewSigningKey, [switch]$AllowDirty, [string]$Python = 'python',
    [string]$Toolchain, [string]$OutDir, [switch]$Offline, [switch]$Preview)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchain = if ($Toolchain) { (Resolve-Path -LiteralPath $Toolchain).Path } else { Join-Path $project '.toolchain' }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem -LiteralPath $toolchain -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $toolchain 'sdk'))) { $env:ANDROID_HOME = Join-Path $toolchain 'sdk' }
$gradleFlags = @('--console=plain', '--quiet') + @(if ($Offline) { '--offline' })
. (Join-Path $PSScriptRoot 'quality\Tools.ps1'); $sdk = Resolve-RiffSdk $project
$gradle = Join-Path $project 'gradlew.bat'
$keytool = if ($env:JAVA_HOME -and
    (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} elseif (Get-Command keytool.exe -ErrorAction SilentlyContinue) {
    (Get-Command keytool.exe).Source
} else {
    'C:\Program Files\Java\jdk-20\bin\keytool.exe'
}
$apksigner = Join-Path $sdk 'build-tools\36.0.0\apksigner.bat'
$signingDir = Join-Path $toolchain 'signing'
$keystore = Join-Path $signingDir 'musicglass-release.p12'
$passwordFile = Join-Path $signingDir 'password.txt'
$passwordDpapi = Join-Path $signingDir 'password.dpapi'
$releaseDir = if ($OutDir) { $OutDir } else { Join-Path $project 'artifacts\release' }
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { throw 'Cannot determine versionName from app/build.gradle.kts.' }
$versionName = $versionLine.Matches[0].Groups[1].Value
# Riff's 16 MiB ceiling is checked below; Riff+ has no size limit (owner, 8 November 2026). Only Plus carries ONNX and its models.

foreach ($tool in @($gradle, $keytool, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required build tool missing: $tool" }
}
if (-not (Test-Path -LiteralPath $sdk)) { throw "Android SDK missing: $sdk" }

# The certificate every Riff release must carry (docs/release-signing.md). Updates install over
# existing copies only with this certificate.
$signingDoc = Get-Content -Raw (Join-Path $project 'docs\release-signing.md')
$pinned = [regex]::Match($signingDoc, 'Certificate SHA-256:\s*([0-9a-f]{64})')
if (-not $pinned.Success) { throw 'No "Certificate SHA-256:" line in docs/release-signing.md.' }
$expectedCertificate = $pinned.Groups[1].Value

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$hasPassword = (Test-Path -LiteralPath $passwordDpapi) -or (Test-Path -LiteralPath $passwordFile)
if ((Test-Path -LiteralPath $keystore) -xor $hasPassword) {
    throw 'Signing key or password is missing. Restore both from backup; never replace just one.'
}
if (-not (Test-Path -LiteralPath $keystore)) {
    # Never create a key silently: a new key can't update any installed copy of Riff.
    $earlier = Get-ChildItem -LiteralPath $releaseDir -Filter '*.apk' -ErrorAction SilentlyContinue
    if (-not $NewSigningKey -or $earlier) {
        throw 'Signing key missing. Restore from backup (see docs/release-signing.md).'
    }
    New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
    $random = New-Object byte[] 48
    # Not RandomNumberGenerator::Fill: Windows PowerShell 5.1 (.NET Framework) has no such method, so the bytes stayed zero.
    (New-Object System.Security.Cryptography.RNGCryptoServiceProvider).GetBytes($random)
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
    throw 'New signing key created. Back it up, record its certificate in docs/release-signing.md, then rerun.'
}

$env:ANDROID_HOME = $sdk
$env:GRADLE_USER_HOME = Join-Path $toolchain 'gradle-user-home'
$env:MUSIC_GLASS_KEYSTORE = $keystore
$env:MUSIC_GLASS_KEY_ALIAS = 'musicglass-release'
# password.dpapi is readable only by this Windows user; password.txt is the plaintext fallback.
$env:MUSIC_GLASS_STORE_PASSWORD = if (Test-Path -LiteralPath $passwordDpapi) {
    $secure = (Get-Content -LiteralPath $passwordDpapi -Raw).Trim() | ConvertTo-SecureString
    [System.Net.NetworkCredential]::new('', $secure).Password
} else {
    [System.IO.File]::ReadAllText($passwordFile).Trim()
}
$env:MUSIC_GLASS_KEY_PASSWORD = $env:MUSIC_GLASS_STORE_PASSWORD

# A release must be reproducible from a commit (plan W0, remediation P0.4). -AllowDirty is for
# test builds while uncommitted work is in progress; the APK is then not traceable to a commit.
$dirty = (& git -C $project status --porcelain 2>$null) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'git status failed; is this a git checkout?' }
if ($dirty -and -not $AllowDirty) {
    throw "The working tree has uncommitted changes. Commit them first, or pass -AllowDirty for a test build.`n$dirty"
}
if ($dirty) { Write-Warning 'Building from a working tree with uncommitted changes (-AllowDirty).' }
$commit = (& git -C $project rev-parse --short HEAD).Trim()

Push-Location $project
try {
    $qualityStartUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.ffffffZ')
    if (-not $SkipClean) {
        $ErrorActionPreference = 'Continue'
        & $gradle ':app:clean' @gradleFlags
        $gradleExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($gradleExit -ne 0) { throw 'Clean build preparation failed.' }
    }
    $tasks = @(':app:assembleRiffRelease', ':app:assemblePlusRelease')
    if (-not $SkipChecks) {
        $tasks = @(':app:testRiffDebugUnitTest', ':app:testPlusDebugUnitTest',
            ':app:lintRiffRelease', ':app:lintPlusRelease') + $tasks
    }
    if ($Preview) { $tasks += @(':app:assembleRiffPreview', ':app:assemblePlusPreview') }
    $ErrorActionPreference = 'Continue'
    & $gradle @tasks @gradleFlags --rerun-tasks
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($gradleExit -ne 0) { throw 'Edition builds or checks failed.' }

    function Assert-ReleaseCertificate([string]$path) {
        $certificates = & $apksigner verify --verbose --print-certs $path
        if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $path" }
        $digest = $certificates | Select-String 'Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})' | Select-Object -First 1
        if (-not $digest -or $digest.Matches[0].Groups[1].Value -ne $expectedCertificate) {
            throw "$path is not signed with the pinned release certificate. Do not distribute it."
        }
    }
    $qualityEndUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.ffffffZ')
    $checksumLines = @()
    $identities = @()
    foreach ($edition in @('riff', 'plus')) {
        $prefix = if ($edition -eq 'plus') { 'riff-plus' } else { 'riff' }
        $variant = "$($edition)Release"
        $apk = Join-Path $project "app\build\outputs\apk\$edition\release\app-$edition-release.apk"
        $mapping = Join-Path $project "app\build\outputs\mapping\$variant\mapping.txt"
        $releaseApk = Join-Path $releaseDir "$prefix-$versionName.apk"
        $releaseMapping = Join-Path $releaseDir "$prefix-$versionName-mapping.txt"
        if (-not (Test-Path -LiteralPath $apk)) { throw "Release APK missing: $apk" }
        if (-not (Test-Path -LiteralPath $mapping)) { throw "R8 mapping missing: $mapping" }
        Assert-ReleaseCertificate $apk
        & $Python (Join-Path $project 'scripts\quality\check_release_content.py') --apk $apk --edition $edition --aapt2 (Join-Path $sdk 'build-tools\36.0.0\aapt2.exe') --apksigner $apksigner
        if ($LASTEXITCODE -ne 0) { throw "$edition release content check failed." }
        $identity = & $Python (Join-Path $project 'scripts\quality\collect_results.py') --inspect-apk $apk
        if ($LASTEXITCODE -ne 0) { throw "$edition build identity unreadable." }
        $identities += ($identity | ConvertFrom-Json)
        $dependencyGraph = Join-Path $releaseDir "$prefix-dependencies.txt"
        & $gradle ':app:dependencies' '--configuration' "$($edition)ReleaseRuntimeClasspath" @gradleFlags | Set-Content -LiteralPath $dependencyGraph
        if ($LASTEXITCODE -ne 0) { throw "$edition dependency report failed." }
        & $Python (Join-Path $project 'scripts\audit_dependency_licenses.py') $dependencyGraph
        if ($LASTEXITCODE -ne 0) { throw "$edition license audit failed." }
        $maxApkBytes = if ($edition -eq 'riff') { 16MB } else { [long]::MaxValue }
        $apkBytes = (Get-Item -LiteralPath $apk).Length
        if ($apkBytes -gt $maxApkBytes) { throw "$edition APK exceeds the current $($maxApkBytes / 1MB) MiB ceiling." }
        Copy-Item -LiteralPath $apk -Destination $releaseApk -Force
        Copy-Item -LiteralPath $mapping -Destination $releaseMapping -Force
        $hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
        $checksumLines += "$hash  $(Split-Path -Leaf $releaseApk)"
        $qualityArgs = @('--collect-only', '--variant', 'release', '--edition', $edition, '--apk', $releaseApk,
            '--start-utc', $qualityStartUtc, '--end-utc', $qualityEndUtc, '--exit-code', '0',
            '--command', ('Gradle ' + ($tasks -join ' ')))
        if ($SkipChecks) { $qualityArgs += '--checks-skipped' }
        & $Python (Join-Path $PSScriptRoot 'quality/collect_results.py') @qualityArgs
        if ($LASTEXITCODE -eq 1) { throw "$edition evidence failed validation; do not distribute this candidate." }
        if ($LASTEXITCODE -eq 2) { Write-Warning "$edition evidence BLOCKED: checks were explicitly skipped." }
        if ($Preview) {
            $previewApk = Join-Path $project "app\build\outputs\apk\$edition\preview\app-$edition-preview.apk"
            if (-not (Test-Path -LiteralPath $previewApk)) { throw "Preview APK missing: $previewApk" }
            Assert-ReleaseCertificate $previewApk
            & $Python (Join-Path $project 'scripts\quality\check_release_content.py') --apk $previewApk --edition $edition --build-type preview --package com.musicglass.personal.preview --aapt2 (Join-Path $sdk 'build-tools\36.0.0\aapt2.exe') --apksigner $apksigner
            if ($LASTEXITCODE -ne 0) { throw "$edition preview content check failed." }
            $previewJson = & $Python (Join-Path $project 'scripts\quality\collect_results.py') --inspect-apk $previewApk
            if ($LASTEXITCODE -ne 0) { throw "$edition preview build identity unreadable." }
            $previewIdentity = $previewJson | ConvertFrom-Json
            if ($previewIdentity.variant -ne "$($edition)Preview" -or $previewIdentity.versionName -ne "$versionName-preview") {
                throw "$edition preview has the wrong variant or version name."
            }
            foreach ($field in @('versionCode', 'sourceDigest', 'revision', 'sourceState')) {
                if ($previewIdentity.$field -ne $identities[-1].$field) { throw "$edition preview/release identity mismatch: $field" }
            }
            $previewOut = Join-Path $releaseDir "$prefix-$versionName-preview.apk"
            Copy-Item -LiteralPath $previewApk -Destination $previewOut -Force
            $previewHash = (Get-FileHash -LiteralPath $previewOut -Algorithm SHA256).Hash.ToLowerInvariant()
            $checksumLines += "$previewHash  $(Split-Path -Leaf $previewOut)"
            Write-Host "Preview APK: $previewOut"
        }
        Write-Host "Signed APK: $releaseApk ($('{0:N1}' -f ($apkBytes / 1MB)) MiB)"
        Write-Host "R8 mapping: $releaseMapping"
    }
    foreach ($field in @('versionName', 'versionCode', 'sourceDigest', 'revision', 'sourceState')) {
        if ($identities[0].$field -ne $identities[1].$field) { throw "Edition identity mismatch: $field" }
    }
    [System.IO.File]::WriteAllText((Join-Path $releaseDir 'SHA256SUMS.txt'), ($checksumLines -join "`n") + "`n")
    Write-Host "Both editions match the pinned certificate and release identity."
    Write-Host "Built from commit $commit$(if ($dirty) { ' plus uncommitted changes' })."
}
finally {
    Pop-Location
    Remove-Item Env:MUSIC_GLASS_STORE_PASSWORD,Env:MUSIC_GLASS_KEY_PASSWORD `
        -ErrorAction SilentlyContinue
}
