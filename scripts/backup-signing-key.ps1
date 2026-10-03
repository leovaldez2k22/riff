<#
.SYNOPSIS
Backs up the Riff release signing key to an encrypted archive and checks that the backup restores.

.DESCRIPTION
Run it yourself in a terminal: 7-Zip asks for the archive passphrase directly and doesn't echo it.
Use a passphrase that differs from the keystore password, and keep it in your password manager.
See docs/release-signing.md.

  Make a backup:       scripts\backup-signing-key.ps1 -Destination $HOME\Documents\riff-key-backup
  Check a backup:      scripts\backup-signing-key.ps1 -VerifyOnly -Archive E:\riff-signing-key-20261003.7z
  Check a key folder:  scripts\backup-signing-key.ps1 -VerifyDir .toolchain\signing
#>
param(
    [string]$Destination,
    [string]$Archive,
    [string]$VerifyDir,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$signingDir = Join-Path $project '.toolchain\signing'
$sevenZip = 'C:\Program Files\7-Zip\7z.exe'
$keytool = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} elseif (Get-Command keytool.exe -ErrorAction SilentlyContinue) {
    (Get-Command keytool.exe).Source
} else {
    'C:\Program Files\Java\jdk-20\bin\keytool.exe'
}
foreach ($tool in @($sevenZip, $keytool)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool missing: $tool" }
}

$doc = Get-Content -Raw (Join-Path $project 'docs\release-signing.md')
$match = [regex]::Match($doc, 'Certificate SHA-256:\s*([0-9a-f]{64})')
if (-not $match.Success) { throw 'No "Certificate SHA-256:" line in docs/release-signing.md.' }
$expected = $match.Groups[1].Value

# Asks for the archive passphrase. Typing is hidden; nothing is echoed or saved.
function Read-Passphrase([switch]$Confirm) {
    while ($true) {
        $first = Read-Host -AsSecureString 'Archive passphrase (typing is hidden)'
        $plain = [System.Net.NetworkCredential]::new('', $first).Password
        if (-not $Confirm) { return $plain }
        if ($plain.Length -lt 12) { Write-Host 'Too short: use at least 12 characters (5 random words is ideal).' -ForegroundColor Yellow; continue }
        $again = [System.Net.NetworkCredential]::new('', (Read-Host -AsSecureString 'Type it again to confirm')).Password
        if ($plain -ceq $again) { return $plain }
        Write-Host "The two entries don't match. Try again." -ForegroundColor Yellow
    }
}

# Checks that a folder holds the keystore and the matching password, and that the certificate is the release one.
function Test-KeyFolder([string]$dir) {
    $p12 = Join-Path $dir 'musicglass-release.p12'
    $pw = Join-Path $dir 'password.txt'
    $dpapi = Join-Path $dir 'password.dpapi'
    if (-not (Test-Path -LiteralPath $p12)) { throw 'Missing: musicglass-release.p12' }
    # -storepass:file / :env keep the password out of the command line and the output.
    if (Test-Path -LiteralPath $pw) {
        $listing = & $keytool -list -v -storetype PKCS12 -keystore $p12 -alias musicglass-release -storepass:file $pw 2>&1
    } elseif (Test-Path -LiteralPath $dpapi) {
        $secure = (Get-Content -LiteralPath $dpapi -Raw).Trim() | ConvertTo-SecureString
        $env:RIFF_KEY_CHECK = [System.Net.NetworkCredential]::new('', $secure).Password
        try { $listing = & $keytool -list -v -storetype PKCS12 -keystore $p12 -alias musicglass-release -storepass:env RIFF_KEY_CHECK 2>&1 }
        finally { Remove-Item Env:RIFF_KEY_CHECK -ErrorAction SilentlyContinue }
    } else { throw 'Missing: password.txt (or password.dpapi)' }
    if ($LASTEXITCODE -ne 0) { throw 'keytool could not open the keystore with the stored password.' }
    $line = $listing | Select-String -Pattern '^\s*SHA256:\s*([0-9A-F:]+)' | Select-Object -First 1
    if (-not $line) { throw 'keytool printed no SHA-256 certificate digest.' }
    $actual = ($line.Matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
    if ($actual -ne $expected) { throw "Certificate mismatch.`n  expected $expected`n  found    $actual" }
    Write-Host "OK: keystore opens with its password and the certificate matches ($expected)." -ForegroundColor Green
}

# Extracts an archive to a temporary folder, checks it, and always deletes the temporary copy.
function Test-Archive([string]$path, [string]$passphrase) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Archive not found: $path" }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("riff-key-drill-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        if (-not $passphrase) { $passphrase = Read-Passphrase }
        Write-Host 'Restore drill: extracting to a temporary folder...'
        & $sevenZip x $path "-o$tmp" "-p$passphrase" -y -bso0 -bsp0
        if ($LASTEXITCODE -ne 0) { throw '7-Zip could not extract the archive (wrong passphrase or damaged file).' }
        Test-KeyFolder $tmp
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $tmp) { Write-Warning "Could not delete the temporary copy at $tmp. Delete it by hand." }
    }
}

if ($VerifyDir) { Test-KeyFolder (Resolve-Path $VerifyDir).Path; return }
if ($VerifyOnly) {
    if (-not $Archive) { throw 'Pass -Archive <path to .7z> with -VerifyOnly.' }
    Test-Archive $Archive; return
}

if (-not $Destination) { throw 'Pass -Destination <folder on removable media>, for example -Destination E:\' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$dest = (Resolve-Path $Destination).Path
if ($dest.StartsWith($project, [StringComparison]::OrdinalIgnoreCase)) { throw 'Destination is inside the project. Choose a folder outside it.' }
if ($env:OneDrive -and $dest.StartsWith($env:OneDrive, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Host 'Destination is in OneDrive: the encrypted archive will sync off this PC.'
} elseif ([IO.Path]::GetPathRoot($dest) -ieq [IO.Path]::GetPathRoot($env:SystemRoot)) {
    Write-Warning "Destination is on this PC ($([IO.Path]::GetPathRoot($dest))). It is not a backup until you upload it somewhere else (see docs/release-signing.md)."
}

$archivePath = Join-Path $dest ("riff-signing-key-" + (Get-Date -Format 'yyyyMMdd') + '.7z')
# An empty file is what an interrupted run leaves behind; anything else is a real backup.
if ((Test-Path -LiteralPath $archivePath) -and (Get-Item -LiteralPath $archivePath).Length -eq 0) { Remove-Item -LiteralPath $archivePath }
if (Test-Path -LiteralPath $archivePath) { throw "Already exists, not overwriting: $archivePath" }

# The archive must hold the plain password: password.dpapi can only be read by this Windows user
# on this PC. When only password.dpapi exists, stage a plain copy in a temporary folder, deleted below.
$source = $signingDir
$stage = $null
$dpapi = Join-Path $signingDir 'password.dpapi'
if (-not (Test-Path -LiteralPath (Join-Path $signingDir 'password.txt')) -and (Test-Path -LiteralPath $dpapi)) {
    $stage = Join-Path ([IO.Path]::GetTempPath()) ("riff-key-stage-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    Copy-Item -LiteralPath (Join-Path $signingDir 'musicglass-release.p12') -Destination $stage
    $secure = (Get-Content -LiteralPath $dpapi -Raw).Trim() | ConvertTo-SecureString
    [IO.File]::WriteAllText((Join-Path $stage 'password.txt'), [System.Net.NetworkCredential]::new('', $secure).Password)
    $source = $stage
}
try {
    Test-KeyFolder $source

    Write-Host ''
    Write-Host 'Choose a NEW passphrase for the archive (not the keystore password).'
    $passphrase = Read-Passphrase -Confirm
    Write-Host "Creating $archivePath"
    # -mhe=on also encrypts file names. The passphrase is passed only to this local 7-Zip process.
    & $sevenZip a -t7z -mhe=on -mx=9 "-p$passphrase" $archivePath (Join-Path $source 'musicglass-release.p12') (Join-Path $source 'password.txt') -bso0 -bsp0
    if ($LASTEXITCODE -ne 0) {
        Remove-Item -LiteralPath $archivePath -ErrorAction SilentlyContinue
        throw '7-Zip failed to create the archive.'
    }
} finally {
    if ($stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $stage) { Write-Warning "Could not delete the staged copy at $stage. Delete it by hand." }
    }
}

Test-Archive $archivePath $passphrase
$passphrase = $null
$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host ''
Write-Host "Backup made and verified: $archivePath" -ForegroundColor Green
Write-Host "Archive SHA-256: $hash"
Write-Host 'Next: save the passphrase in your password manager, upload the archive to Google Drive and Gmail, delete this local copy, and fill in the tables in docs/release-signing.md.'
