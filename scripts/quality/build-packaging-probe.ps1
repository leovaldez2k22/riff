param([Parameter(Mandatory=$true)][string]$AppApk, [string]$OutDir, [string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'Tools.ps1')
$sdk = Resolve-RiffSdk $project
$toolchain = Join-Path $project '.toolchain'
$jdk = Get-ChildItem -LiteralPath $toolchain -Directory -Filter 'jdk-*' | Select-Object -First 1
if (-not $jdk) { throw 'Project JDK is missing.' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = $jdk.FullName }
$androidJar = Join-Path $sdk 'platforms/android-36/android.jar'
$tools = Join-Path $sdk 'build-tools/36.0.0'
$source = Join-Path $PSScriptRoot 'packaging/EditionSwitchProbe.java'
$fixture = Join-Path $project 'app/src/androidTest/assets/quality/seek/seek-source.webm'
$keystore = Join-Path $toolchain 'signing/musicglass-release.p12'
foreach ($path in @($androidJar, $source, $fixture, $keystore, $AppApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing probe input: $path" }
}
$identityJson = & $Python (Join-Path $PSScriptRoot 'collect_results.py') --inspect-apk $AppApk
if ($LASTEXITCODE -ne 0) { throw 'Candidate build identity is unreadable.' }
$identity = $identityJson | ConvertFrom-Json
if ($identity.variant -notin @('riffRelease', 'plusRelease')) { throw 'The probe must be paired with a release candidate.' }
$destination = if ($OutDir) { [System.IO.Path]::GetFullPath($OutDir) } else { Join-Path $project 'artifacts/quality/packaging-probe' }
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$work = Join-Path $destination ('build-' + [guid]::NewGuid().ToString('N'))
$assets = Join-Path $work 'assets'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
foreach ($folder in @($assets, $classes, $dex)) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
$utf8 = [System.Text.UTF8Encoding]::new($false)
$metadata = @{identity=$identity; probeSourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()}
[System.IO.File]::WriteAllText((Join-Path $assets 'packaging-probe.json'), ($metadata | ConvertTo-Json -Depth 8), $utf8)
Copy-Item -LiteralPath $fixture -Destination (Join-Path $assets 'seek-source.webm')
$manifest = Join-Path $work 'AndroidManifest.xml'
$manifestText = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.musicglass.packagingprobe" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="31" android:targetSdkVersion="36"/><application android:label="Riff packaging probe" android:allowBackup="false" android:debuggable="false"/><instrumentation android:name="com.musicglass.quality.EditionSwitchProbe" android:targetPackage="com.musicglass.personal" android:functionalTest="true"/></manifest>'
[System.IO.File]::WriteAllText($manifest, $manifestText, $utf8)
& (Join-Path $jdk.FullName 'bin/javac.exe') -encoding UTF-8 --release 8 -Xlint:-options -classpath $androidJar -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed.' }
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
if (-not $classFiles) { throw 'Probe compilation produced no classes.' }
& (Join-Path $tools 'd8.bat') --min-api 31 --lib $androidJar --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { throw 'Probe DEX conversion failed.' }
$unsigned = Join-Path $work 'probe-unsigned.apk'
& (Join-Path $tools 'aapt2.exe') link -o $unsigned -I $androidJar --manifest $manifest -A $assets
if ($LASTEXITCODE -ne 0) { throw 'Probe APK linking failed.' }
Add-Type -AssemblyName System.IO.Compression
$stream = [System.IO.File]::Open($unsigned, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite)
try {
    $zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $entry = $zip.CreateEntry('classes.dex')
        $input = [System.IO.File]::OpenRead((Join-Path $dex 'classes.dex'))
        try { $output = $entry.Open(); try { $input.CopyTo($output) } finally { $output.Dispose() } }
        finally { $input.Dispose() }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }
$aligned = Join-Path $work 'probe-aligned.apk'
& (Join-Path $tools 'zipalign.exe') -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'Probe alignment failed.' }
$dpapi = Join-Path $toolchain 'signing/password.dpapi'
$plaintext = Join-Path $toolchain 'signing/password.txt'
try {
    $env:RIFF_PROBE_KEY_PASSWORD = if (Test-Path -LiteralPath $dpapi) {
        $secure = (Get-Content -LiteralPath $dpapi -Raw).Trim() | ConvertTo-SecureString
        [System.Net.NetworkCredential]::new('', $secure).Password
    } elseif (Test-Path -LiteralPath $plaintext) { [System.IO.File]::ReadAllText($plaintext).Trim() }
    else { throw 'Restore the signing password before building the probe.' }
    $apk = Join-Path $destination 'edition-switch-probe.apk'
    & (Join-Path $tools 'apksigner.bat') sign --ks $keystore --ks-key-alias musicglass-release --ks-pass env:RIFF_PROBE_KEY_PASSWORD --key-pass env:RIFF_PROBE_KEY_PASSWORD --out $apk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Probe signing failed.' }
    $verified = & (Join-Path $tools 'apksigner.bat') verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'Probe signature verification failed.' }
    $doc = [System.IO.File]::ReadAllText((Join-Path $project 'docs/release-signing.md'))
    $pinned = [regex]::Match($doc, 'Certificate SHA-256:\s*([0-9a-f]{64})')
    $actual = [regex]::Match(($verified -join "`n"), 'Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})')
    if (-not $pinned.Success -or -not $actual.Success -or $pinned.Groups[1].Value -ne $actual.Groups[1].Value) { throw 'Probe certificate differs from the pinned release certificate.' }
    Write-Host "Signed test-only packaging probe: $apk"
    Write-Host "SHA-256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant())"
} finally { Remove-Item Env:RIFF_PROBE_KEY_PASSWORD -ErrorAction SilentlyContinue }
