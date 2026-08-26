param([string]$OutputFile)

. "$PSScriptRoot\Common.ps1"

$adb = Get-AdbPath
$serial = Get-LdPlayerSerial
$ldConsole = 'C:\LDPlayer\LDPlayer9\ldconsole.exe'
if (-not (Test-Path -LiteralPath $ldConsole)) { throw 'LDPlayer console not found.' }
if (-not $OutputFile) {
    $directory = Join-Path $script:ProjectRoot 'diagnostics\ldplayer\phase4'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $OutputFile = Join-Path $directory "offline-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
}

function Read-PlaybackState {
    param([string]$TargetSerial)
    & $adb -s $TargetSerial shell uiautomator dump /sdcard/phase4-window.xml | Out-Null
    $xml = (& $adb -s $TargetSerial shell cat /sdcard/phase4-window.xml) -join ''
    return [regex]::Match($xml, 'PLAYING[^<]+').Value
}

& $adb -s $serial shell am start -W -n "$($script:MainComponent)" | Out-Null
Start-Sleep -Seconds 5
$pidBefore = (& $adb -s $serial shell pidof $script:PackageName).Trim()
$playbackBefore = Read-PlaybackState $serial
$pointerBefore = ((& $adb -s $serial shell run-as $script:PackageName cat files/transactional-media/pointers/active-playlist.json) -join "`n")

try {
    & $ldConsole action --index 0 --key call.network --value offline | Out-Null
    Start-Sleep -Seconds 20
} finally {
    & $ldConsole action --index 0 --key call.network --value connect | Out-Null
}

Start-Sleep -Seconds 8
& $adb connect 127.0.0.1:5555 | Out-Null
$pidAfter = (& $adb -s $serial shell pidof $script:PackageName).Trim()
$playbackAfter = Read-PlaybackState $serial
$pointerAfter = ((& $adb -s $serial shell run-as $script:PackageName cat files/transactional-media/pointers/active-playlist.json) -join "`n")
$fatal = @(& $adb -s $serial logcat -d -v brief | Select-String 'FATAL EXCEPTION|ANR in com.loopin.player2|OutOfMemoryError')

$result = @(
    "pidBefore=$pidBefore"
    "pidAfter=$pidAfter"
    "playbackBefore=$playbackBefore"
    "playbackAfter=$playbackAfter"
    "activePointerUnchanged=$($pointerBefore -eq $pointerAfter)"
    "fatalCount=$($fatal.Count)"
)
$outputDirectory = Split-Path -Parent $OutputFile
if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$result | Set-Content -LiteralPath $OutputFile -Encoding utf8
$result

if (-not $pidBefore -or $pidBefore -ne $pidAfter) { throw 'Player process did not survive the offline window.' }
if (-not $playbackBefore -or -not $playbackAfter) { throw 'Playback state was not observable.' }
if ($pointerBefore -ne $pointerAfter) { throw 'ACTIVE pointer changed while offline.' }
if ($fatal.Count -gt 0) { throw 'Fatal event found in logcat.' }
