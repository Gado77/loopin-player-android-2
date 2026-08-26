$ErrorActionPreference = 'Stop'

$script:ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:PackageName = 'com.loopin.player2'
$script:MainComponent = 'com.loopin.player2/.MainActivity'
$script:DebugApk = Join-Path $script:ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Get-AdbPath {
    $candidates = @(
        $env:LOOPIN_ADB,
        'C:\Users\itach\.cache\loopin-player2-toolchain\android-sdk\platform-tools\adb.exe',
        'C:\LDPlayer\LDPlayer9\adb.exe'
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    if (-not $candidates) { throw 'ADB não encontrado. Defina LOOPIN_ADB.' }
    return $candidates[0]
}

function Get-LdPlayerSerial {
    $adb = Get-AdbPath
    $online = @(& $adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($env:LOOPIN_DEVICE_SERIAL) {
        if ($online -notcontains $env:LOOPIN_DEVICE_SERIAL) { throw "Dispositivo $env:LOOPIN_DEVICE_SERIAL não está online." }
        return $env:LOOPIN_DEVICE_SERIAL
    }
    $emulators = @($online | Where-Object { $_ -like 'emulator-*' })
    if ($emulators.Count -ne 1) { throw "Esperado exatamente um LDPlayer online; encontrados: $($emulators -join ', ')." }
    return $emulators[0]
}

function Invoke-TargetAdb {
    param([Parameter(Mandatory, ValueFromRemainingArguments)][string[]]$Arguments)
    $adb = Get-AdbPath
    $serial = Get-LdPlayerSerial
    & $adb -s $serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB falhou com código $LASTEXITCODE." }
}
