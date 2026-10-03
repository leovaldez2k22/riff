param([switch]$SkipChecks, [switch]$SkipClean, [switch]$NewSigningKey, [switch]$AllowDirty)

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
$passwordDpapi = Join-Path $signingDir 'password.dpapi'
$apk = Join-Path $project 'app\build\outputs\apk\release\app-release.apk'
$releaseDir = Join-Path $project 'artifacts\release'
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { throw 'Cannot determine versionName from app/build.gradle.kts.' }
$versionName = $versionLine.Matches[0].Groups[1].Value
$releaseApk = Join-Path $releaseDir "riff-$versionName.apk"
$mapping = Join-Path $project 'appuild\outputs\mapping
elease\mapping.txt'
$releaseMapping = Join-Path $releaseDir "riff-$versionName-mapping.txt"
# Size budget (plan W4): the shrunk arm64 APK is ~26 MiB. A jump past this usually means R8 or
# the ABI filter was switched off, or a large dependency was added.
$maxApkBytes = 40MB
$dependencyGraph = Join-Path $releaseDir 'dependencies.txt'

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
    throw 'New signing key created. Back it up, record its certificate in docs/release-signing.md, then rerun.'
}

$env:ANDROID_HOME = $sdk
$env:GRADLE_USER_HOME = Join-Path $project '.toolchain\gradle-user-home'
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
    if (-not $SkipClean) {
        & $gradle ':app:clean' '--console=plain' '--quiet'
        if ($LASTEXITCODE -ne 0) { throw 'Clean build preparation failed.' }
    }
    $tasks = if ($SkipChecks) { @(':app:assembleRelease') } else {
        @(':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease')
    }
    & $gradle @tasks '--console=plain' '--quiet'
    if ($LASTEXITCODE -ne 0) { throw 'Release build or checks failed.' }
    if (-not (Test-Path -LiteralPath $apk)) { throw "Release APK missing: $apk" }
    $certificates = & $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificates | Write-Host
    $digest = $certificates | Select-String 'Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})' |
        Select-Object -First 1
    if (-not $digest -or $digest.Matches[0].Groups[1].Value -ne $expectedCertificate) {
        throw "APK is not signed with the release certificate $expectedCertificate. Don't install or publish it."
    }
    & $gradle ':app:dependencies' '--configuration' 'releaseRuntimeClasspath' `
        '--console=plain' '--quiet' | Set-Content -LiteralPath $dependencyGraph
    if ($LASTEXITCODE -ne 0) { throw 'Release dependency report failed.' }
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $python) { throw 'Python is needed for the release license audit.' }
    & $python.Source (Join-Path $project 'scripts\audit_dependency_licenses.py')
    if ($LASTEXITCODE -ne 0) { throw 'Release license audit failed.' }
    $apkBytes = (Get-Item -LiteralPath $apk).Length
    if ($apkBytes -gt $maxApkBytes) {
        throw ("Release APK is {0:N1} MiB, over the {1:N0} MiB budget. Check R8, resource shrinking and the ABI filter." -f ($apkBytes / 1MB), ($maxApkBytes / 1MB))
    }
    # Crash stack traces from a shrunk build are unreadable without the matching R8 mapping.
    if (-not (Test-Path -LiteralPath $mapping)) { throw "R8 mapping missing: $mapping" }
    Copy-Item -LiteralPath $apk -Destination $releaseApk -Force
    Copy-Item -LiteralPath $mapping -Destination $releaseMapping -Force
    $hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $releaseDir 'SHA256SUMS.txt'),
        "$hash  $(Split-Path -Leaf $releaseApk)`n")
    Write-Host "Signed APK: $releaseApk ($('{0:N1}' -f ($apkBytes / 1MB)) MiB)"
    Write-Host "R8 mapping: $releaseMapping (keep it with the APK to read crash reports)"
    Write-Host "SHA-256: $hash"
    Write-Host "Certificate matches docs/release-signing.md."
    Write-Host "Built from commit $commit$(if ($dirty) { ' plus uncommitted changes' })."
}
finally {
    Pop-Location
    Remove-Item Env:MUSIC_GLASS_STORE_PASSWORD,Env:MUSIC_GLASS_KEY_PASSWORD `
        -ErrorAction SilentlyContinue
}
