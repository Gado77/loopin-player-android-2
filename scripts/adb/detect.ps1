. "$PSScriptRoot\Common.ps1"
$adb = Get-AdbPath
& $adb version
& $adb devices -l
$serial = Get-LdPlayerSerial
Write-Output "LDPlayer online: $serial"
Invoke-TargetAdb -Arguments @('shell', 'getprop', 'ro.product.model')
Invoke-TargetAdb -Arguments @('shell', 'getprop', 'ro.build.version.release')
