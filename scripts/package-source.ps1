param([switch]$AllowDirty, [string]$OutDir)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outDir = if ($OutDir) { $OutDir } else { Join-Path $project 'artifacts\release' }
$versionLine = Get-Content (Join-Path $project 'app\build.gradle.kts') |
    Select-String 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { throw 'Cannot determine versionName from app/build.gradle.kts.' }
$versionName = $versionLine.Matches[0].Groups[1].Value
$archive = Join-Path $outDir "riff-$versionName-source.zip"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Add-Type -AssemblyName System.IO.Compression

# The corresponding source is exactly the files Git tracks at this revision: never build output, local
# settings, toolchains, caches, keys or evidence (all ignored), and never an untracked file.
$status = git -C $project status --porcelain --untracked-files=no
if ($LASTEXITCODE -ne 0) { throw 'git is needed to know which files are source.' }
if ($status -and -not $AllowDirty) {
    throw 'Tracked files differ from the commit; the archive would not match it. Commit first, or pass -AllowDirty for a draft.'
}
$tracked = git -C $project -c core.quotepath=off ls-files
if ($LASTEXITCODE -ne 0) { throw 'git ls-files failed.' }
$files = @($tracked | Where-Object { $_ } |
    ForEach-Object { Join-Path $project $_ } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    ForEach-Object { Get-Item -LiteralPath $_ })
$required = @('app/build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat',
    'gradle/wrapper/gradle-wrapper.properties', 'gradle/verification-metadata.xml', 'LICENSE', 'NOTICE.md', 'README.md')
foreach ($file in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $project $file))) { throw "Source input missing: $file" }
}

if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
$stream = [System.IO.File]::Open($archive, [System.IO.FileMode]::CreateNew)
try {
    $zip = [System.IO.Compression.ZipArchive]::new($stream,
        [System.IO.Compression.ZipArchiveMode]::Create, $false)
    try {
        foreach ($file in ($files | Sort-Object FullName -Unique)) {
            $relative = $file.FullName.Substring($project.Length).TrimStart('\', '/').Replace('\', '/')
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
