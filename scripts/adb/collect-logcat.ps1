param([string]$OutputDirectory)
. "$PSScriptRoot\Common.ps1"
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $script:ProjectRoot 'diagnostics\ldplayer' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$file = Join-Path $OutputDirectory "logcat-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
$adb = Get-AdbPath
$serial = Get-LdPlayerSerial
& $adb -s $serial logcat -d -v threadtime | Set-Content -LiteralPath $file -Encoding utf8
Write-Output $file
