param([string]$OutputDirectory)
. "$PSScriptRoot\Common.ps1"
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $script:ProjectRoot "diagnostics\ldplayer\$(Get-Date -Format 'yyyyMMdd-HHmmss')" }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$adb = Get-AdbPath
$serial = Get-LdPlayerSerial

& $adb -s $serial shell getprop | Set-Content (Join-Path $OutputDirectory 'getprop.txt') -Encoding utf8
& $adb -s $serial shell wm size | Set-Content (Join-Path $OutputDirectory 'display.txt') -Encoding utf8
& $adb -s $serial shell wm density | Add-Content (Join-Path $OutputDirectory 'display.txt') -Encoding utf8
& $adb -s $serial shell dumpsys meminfo $script:PackageName | Set-Content (Join-Path $OutputDirectory 'meminfo.txt') -Encoding utf8
& $adb -s $serial shell dumpsys cpuinfo | Set-Content (Join-Path $OutputDirectory 'cpuinfo.txt') -Encoding utf8
& $adb -s $serial shell dumpsys activity activities | Set-Content (Join-Path $OutputDirectory 'activities.txt') -Encoding utf8
& $adb -s $serial shell dumpsys package $script:PackageName | Set-Content (Join-Path $OutputDirectory 'package.txt') -Encoding utf8
& $adb -s $serial shell dumpsys media.codec | Set-Content (Join-Path $OutputDirectory 'media-codec.txt') -Encoding utf8
& $adb -s $serial shell ps -A | Set-Content (Join-Path $OutputDirectory 'processes.txt') -Encoding utf8
& $adb -s $serial logcat -d -v threadtime | Set-Content (Join-Path $OutputDirectory 'logcat.txt') -Encoding utf8
Write-Output $OutputDirectory
