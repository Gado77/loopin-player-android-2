. "$PSScriptRoot\Common.ps1"
if (-not (Test-Path -LiteralPath $script:DebugApk)) { throw "APK não encontrado: $script:DebugApk" }
$adb = Get-AdbPath
$serial = Get-LdPlayerSerial
& $adb -s $serial install -r $script:DebugApk
if ($LASTEXITCODE -ne 0) { throw "Atualização falhou com código $LASTEXITCODE." }
