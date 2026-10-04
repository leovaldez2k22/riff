param()

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outDir = Join-Path $project 'artifacts\release'
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { throw 'Cannot determine versionName from app/build.gradle.kts.' }
$versionName = $versionLine.Matches[0].Groups[1].Value
$archive = Join-Path $outDir "riff-$versionName-source.zip"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Add-Type -AssemblyName System.IO.Compression

$roots = @('app\src', 'docs', 'licenses', 'scripts', 'gradle\wrapper', '.github')
$files = @()
foreach ($root in $roots) {
    $path = Join-Path $project $root
    if (Test-Path -LiteralPath $path) {
        $files += Get-ChildItem -LiteralPath $path -Recurse -File
    }
}
$topLevel = @('app\build.gradle.kts', 'app\proguard-rules.pro',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle\libs.versions.toml',
    'gradlew', 'gradlew.bat', 'CHANGELOG.md', 'LICENSE', 'NOTICE.md', 'README.md',
    'MUSIC_APP_IMPLEMENTATION_PLAN.md', 'YTMUSIC_INTEGRATION_AGENT_GUIDE.md',
    'CONTRIBUTING.md', 'SECURITY.md')
foreach ($file in $topLevel) {
    $path = Join-Path $project $file
    if (-not (Test-Path -LiteralPath $path)) { throw "Source input missing: $file" }
    $files += Get-Item -LiteralPath $path
}

if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
$stream = [System.IO.File]::Open($archive, [System.IO.FileMode]::CreateNew)
try {
    $zip = [System.IO.Compression.ZipArchive]::new($stream,
        [System.IO.Compression.ZipArchiveMode]::Create, $false)
    try {
        foreach ($file in ($files | Sort-Object FullName -Unique)) {
            $relative = [System.IO.Path]::GetRelativePath($project, $file.FullName).Replace('\','/')
            $entry = $zip.CreateEntry($relative,
                [System.IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime = [DateTimeOffset]::new(2020, 1, 1, 0, 0, 0,
                [TimeSpan]::Zero)
            $inputStream = [System.IO.File]::OpenRead($file.FullName)
            try {
                $outputStream = $entry.Open()
                try { $inputStream.CopyTo($outputStream) }
                finally { $outputStream.Dispose() }
            }
            finally { $inputStream.Dispose() }
        }
    }
    finally { $zip.Dispose() }
}
finally { $stream.Dispose() }
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
$sumsPath = Join-Path $outDir 'SHA256SUMS.txt'
$archiveName = Split-Path -Leaf $archive
$existing = if (Test-Path -LiteralPath $sumsPath) {
    @(Get-Content -LiteralPath $sumsPath | Where-Object {
        $_ -notmatch "\s$([regex]::Escape($archiveName))$"
    })
} else { @() }
@($existing) + "$hash  $archiveName" | Set-Content -LiteralPath $sumsPath
Write-Host "Source archive: $archive"
Write-Host "SHA-256: $hash"
