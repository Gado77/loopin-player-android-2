param([switch]$ConfirmClear)
. "$PSScriptRoot\Common.ps1"
if (-not $ConfirmClear) { throw 'Operação destrutiva bloqueada. Use -ConfirmClear explicitamente.' }
Invoke-TargetAdb -Arguments @('shell', 'pm', 'clear', $script:PackageName)
