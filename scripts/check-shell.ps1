param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $project '.toolchain\sdk\platform-tools\adb.exe'
$out = (Resolve-Path $EvidenceDirectory).Path
$logs = Join-Path $out 'logs'
$screenshots = Join-Path $out 'screenshots'
$checks = [System.Collections.Generic.List[string]]::new()

function Invoke-Adb([string[]]$Arguments) {
    $result = & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $result
}

function Snapshot([string]$Name) {
    $remote = "/sdcard/Download/musicglass-m02-$Name.xml"
    Invoke-Adb @('shell', 'uiautomator', 'dump', $remote) | Out-Null
    Invoke-Adb @('pull', $remote, (Join-Path $logs "$Name.xml")) | Out-Null
    Invoke-Adb @('shell', 'rm', $remote) | Out-Null
    return [xml](Get-Content -Raw (Join-Path $logs "$Name.xml"))
}

function Capture([string]$Name) {
    $remote = "/sdcard/Download/musicglass-m02-$Name.png"
    Invoke-Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-Adb @('pull', $remote, (Join-Path $screenshots "$Name.png")) | Out-Null
    Invoke-Adb @('shell', 'rm', $remote) | Out-Null
}

function NodeByTag($Document, [string]$Tag) {
    return @($Document.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq $Tag }) | Select-Object -First 1
}

function Assert-Tag($Document, [string]$Tag) {
    if (-not (NodeByTag $Document $Tag)) { throw "Missing tag: $Tag" }
    $checks.Add("PASS tag $Tag")
}

function Assert-Text($Document, [string]$Value) {
    if (-not @($Document.SelectNodes('//node') | Where-Object { $_.text -eq $Value }).Count) {
        throw "Missing text: $Value"
    }
    $checks.Add("PASS text $Value")
}

function Tap-Tag($Document, [string]$Tag) {
    $node = NodeByTag $Document $Tag
    if (-not $node) { throw "Cannot tap absent tag: $Tag" }
    $match = [regex]::Match($node.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) { throw "Bad bounds for $Tag : $($node.bounds)" }
    $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
    $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
    Invoke-Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Milliseconds 350
}

function Tap-Text($Document, [string]$Value) {
    $node = @($Document.SelectNodes('//node') | Where-Object { $_.text -eq $Value }) | Select-Object -First 1
    if (-not $node) { throw "Cannot tap absent text: $Value" }
    $match = [regex]::Match($node.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
    $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
    Invoke-Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Milliseconds 350
}

Invoke-Adb @('shell', 'am', 'force-stop', 'com.musicglass.personal') | Out-Null
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'com.musicglass.personal/.MainActivity') | Out-Null
Start-Sleep -Milliseconds 700
$doc = Snapshot 'home'
Assert-Text $doc 'Listen closer.'
Assert-Tag $doc 'home_hero'
Assert-Tag $doc 'mini_player'
Assert-Tag $doc 'tab_home'
Assert-Tag $doc 'tab_podcasts'
Assert-Tag $doc 'tab_radio'
Assert-Tag $doc 'tab_library'
Assert-Tag $doc 'nav_search'
Capture 'home'

Tap-Tag $doc 'tab_podcasts'
$doc = Snapshot 'podcasts'
Assert-Text $doc 'Podcasts'
Assert-Text $doc 'FEATURED AUDIO PREVIEW'
Capture 'podcasts'

Tap-Tag $doc 'tab_radio'
$doc = Snapshot 'radio'
Assert-Text $doc 'Radio'
Assert-Text $doc 'ORIGINAL DEMO STATION'
Capture 'radio'

Tap-Tag $doc 'tab_library'
$doc = Snapshot 'library'
Assert-Text $doc 'Library'
Assert-Tag $doc 'filter_albums'
Tap-Tag $doc 'filter_albums'
$doc = Snapshot 'library-filtered'
Assert-Tag $doc 'track_afterglow_circuit'
Assert-Tag $doc 'track_low_orbit'

Tap-Tag $doc 'nav_search'
$doc = Snapshot 'search'
Assert-Tag $doc 'search_field'
Tap-Tag $doc 'search_field'
Invoke-Adb @('shell', 'input', 'text', 'Orbit') | Out-Null
Start-Sleep -Milliseconds 500
$doc = Snapshot 'search-keyboard'
Assert-Text $doc 'Low Orbit'
if (NodeByTag $doc 'nav_search') { throw 'Chrome remained visible above keyboard' }
Capture 'search-keyboard'
Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
Start-Sleep -Milliseconds 400
$doc = Snapshot 'search-closed'
Assert-Tag $doc 'nav_search'
Tap-Tag $doc 'tab_home'
$doc = Snapshot 'home-return'
Assert-Tag $doc 'home_hero'
Tap-Tag $doc 'nav_search'
$doc = Snapshot 'search-restored'
Assert-Text $doc 'Low Orbit'
$checks.Add('PASS Search query preserved across tabs')

Tap-Tag $doc 'tab_library'
$doc = Snapshot 'library-restored'
Assert-Tag $doc 'filter_albums'
if (NodeByTag $doc 'track_slow_motion_city') { throw 'Library filter was lost across tabs' }
$checks.Add('PASS Library filter preserved across tabs')
Invoke-Adb @('shell', 'input', 'swipe', '500', '1500', '500', '700', '350') | Out-Null
$doc = Snapshot 'library-tools'
Assert-Tag $doc 'mini_toggle'
Tap-Tag $doc 'mini_toggle'
$doc = Snapshot 'no-mini'
if (NodeByTag $doc 'mini_player') { throw 'Mini-player remained after toggle off' }
Capture 'no-mini'
Tap-Tag $doc 'mini_toggle'
$doc = Snapshot 'mini-restored'
Assert-Tag $doc 'mini_player'
Tap-Text $doc 'Component gallery'
$doc = Snapshot 'gallery'
Assert-Text $doc 'Building blocks'
Assert-Text $doc 'FILTER CHIPS'
Capture 'gallery'
Tap-Text $doc 'Open sheet'
$doc = Snapshot 'sheet'
Assert-Text $doc 'Fixture preview'
Capture 'sheet'
Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
$doc = Snapshot 'gallery-after-sheet'
Assert-Text $doc 'Building blocks'
Tap-Tag $doc 'back'
$doc = Snapshot 'library-after-gallery'
Assert-Text $doc 'Library'
Tap-Text $doc 'Glass Lab'
$doc = Snapshot 'glass-lab'
Assert-Text $doc 'Glass Lab · live View backdrop proof'
Capture 'glass-lab'
Tap-Text $doc 'Back'
$doc = Snapshot 'library-after-lab'
Assert-Text $doc 'Library'

Tap-Tag $doc 'tab_home'
$doc = Snapshot 'home-before-detail'
Tap-Tag $doc 'home_hero'
$doc = Snapshot 'album'
Assert-Text $doc 'Night Transit'
Assert-Tag $doc 'album_play'
Capture 'album'
Tap-Tag $doc 'back'
$doc = Snapshot 'home-after-detail'
Assert-Tag $doc 'home_hero'
Invoke-Adb @('shell', 'input', 'swipe', '520', '1700', '520', '550', '500') | Out-Null
$doc = Snapshot 'home-bottom'
Assert-Text $doc 'Original demo collection · No music account connected'
Capture 'home-bottom'
$footerBefore = @($doc.SelectNodes('//node') | Where-Object {
    $_.text -eq 'Original demo collection · No music account connected'
}) | Select-Object -First 1
Tap-Tag $doc 'tab_podcasts'
$doc = Snapshot 'podcasts-after-home-scroll'
Tap-Tag $doc 'tab_home'
$doc = Snapshot 'home-scroll-restored'
Assert-Text $doc 'Original demo collection · No music account connected'
$footerAfter = @($doc.SelectNodes('//node') | Where-Object {
    $_.text -eq 'Original demo collection · No music account connected'
}) | Select-Object -First 1
if ($footerBefore.bounds -ne $footerAfter.bounds) { throw 'Home scroll position changed across tabs' }
$checks.Add('PASS Home scroll position preserved across tabs')

$checks | Set-Content (Join-Path $logs 'shell-checks.txt')
Write-Host ($checks -join [Environment]::NewLine)
