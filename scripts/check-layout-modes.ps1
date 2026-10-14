param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [switch]$CheckThreeButton
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'quality\Tools.ps1'); $adb = Resolve-RiffAdb $project
$out = (Resolve-Path $EvidenceDirectory).Path
$fontOriginal = (& $adb -s $Serial shell settings get system font_scale).Trim()
$sizeOriginal = (& $adb -s $Serial shell wm size) -join ' '
$navOriginal = (& $adb -s $Serial shell settings get secure navigation_mode).Trim()
$transparentOriginal = ((& $adb -s $Serial shell cmd overlay list) -join "`n") -match '\[x\] com\.android\.internal\.systemui\.navbar\.transparent'
@("font_scale=$fontOriginal", "wm_size=$sizeOriginal", "navigation_mode=$navOriginal",
    "transparent_overlay=$transparentOriginal") | Set-Content (Join-Path $out 'logs\layout-original.txt')

function Restart-App {
    & $adb -s $Serial shell am force-stop com.musicglass.personal | Out-Null
    & $adb -s $Serial shell am start -W -n 'com.musicglass.personal/.MainActivity' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'App restart failed' }
    Start-Sleep -Milliseconds 600
}

function Capture([string]$Name) {
    $remote = "/sdcard/Download/musicglass-m02-$Name"
    & $adb -s $Serial shell uiautomator dump "$remote.xml" | Out-Null
    & $adb -s $Serial pull "$remote.xml" (Join-Path $out "logs\$Name.xml") | Out-Null
    & $adb -s $Serial shell screencap -p "$remote.png" | Out-Null
    & $adb -s $Serial pull "$remote.png" (Join-Path $out "screenshots\$Name.png") | Out-Null
    & $adb -s $Serial shell rm "$remote.xml" "$remote.png" | Out-Null
    return [xml](Get-Content -Raw (Join-Path $out "logs\$Name.xml"))
}

function Assert-Compact($Document, [string]$Mode) {
    $tags = @($Document.SelectNodes('//node') | ForEach-Object { $_.'resource-id' })
    foreach ($tag in @('tab_home', 'tab_podcasts', 'tab_radio', 'tab_library', 'tab_search')) {
        if ($tag -notin $tags) { throw "$Mode missing $tag" }
    }
    if ('nav_search' -notin $tags) { throw "$Mode missing detached search" }
}

try {
    & $adb -s $Serial shell settings put system font_scale 1.3 | Out-Null
    Restart-App
    $font = Capture 'large-font'
    Assert-Compact $font 'large font'

    if ($fontOriginal -eq 'null') {
        & $adb -s $Serial shell settings delete system font_scale | Out-Null
    } else {
        & $adb -s $Serial shell settings put system font_scale $fontOriginal | Out-Null
    }
    & $adb -s $Serial shell wm size 900x2000 | Out-Null
    Restart-App
    $small = Capture 'small-window'
    Assert-Compact $small 'small window'

    $results = @('PASS 130% font: five-item nav', 'PASS 900x2000 window: five-item nav')
    if ($CheckThreeButton) {
        & $adb -s $Serial shell wm size reset | Out-Null
        & $adb -s $Serial shell cmd overlay disable com.android.internal.systemui.navbar.transparent | Out-Null
        & $adb -s $Serial shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton | Out-Null
        Start-Sleep -Seconds 1
        $threeButtonMode = (& $adb -s $Serial shell settings get secure navigation_mode).Trim()
        $threeButtonMode | Set-Content (Join-Path $out 'logs\three-button-mode.txt')
        if ($threeButtonMode -ne '0') { throw "Three-button mode did not activate: $threeButtonMode" }
        Restart-App
        $buttons = Capture 'three-button'
        $tags = @($buttons.SelectNodes('//node') | ForEach-Object { $_.'resource-id' })
        if ('navigation' -notin $tags -or 'nav_search' -notin $tags) {
            throw 'Chrome missing under three-button navigation'
        }
        $results += 'PASS three-button overlay: navigation_mode=0, chrome present'
    }
    $results | Set-Content (Join-Path $out 'logs\layout-checks.txt')
}
finally {
    if ($fontOriginal -eq 'null') {
        & $adb -s $Serial shell settings delete system font_scale | Out-Null
    } else {
        & $adb -s $Serial shell settings put system font_scale $fontOriginal | Out-Null
    }
    $override = [regex]::Match($sizeOriginal, 'Override size: (\d+x\d+)')
    if ($override.Success) {
        & $adb -s $Serial shell wm size $override.Groups[1].Value | Out-Null
    } else {
        & $adb -s $Serial shell wm size reset | Out-Null
    }
    $navOverlay = if ($navOriginal -eq '0') {
        'com.android.internal.systemui.navbar.threebutton'
    } else { 'com.android.internal.systemui.navbar.gestural' }
    & $adb -s $Serial shell cmd overlay enable-exclusive --category $navOverlay | Out-Null
    if ($transparentOriginal) {
        & $adb -s $Serial shell cmd overlay enable com.android.internal.systemui.navbar.transparent | Out-Null
    } else {
        & $adb -s $Serial shell cmd overlay disable com.android.internal.systemui.navbar.transparent | Out-Null
    }
    Restart-App
    @("font_scale=$((& $adb -s $Serial shell settings get system font_scale).Trim())",
        "wm_size=$((& $adb -s $Serial shell wm size) -join ' ')",
        "navigation_mode=$((& $adb -s $Serial shell settings get secure navigation_mode).Trim())") |
        Set-Content (Join-Path $out 'logs\layout-restored.txt')
}
