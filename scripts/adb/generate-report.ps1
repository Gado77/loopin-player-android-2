param(
    [string]$OutputFile
)

. "$PSScriptRoot\Common.ps1"

if (-not $OutputFile) {
    $reportDirectory = Join-Path $script:ProjectRoot 'diagnostics\ldplayer'
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $OutputFile = Join-Path $reportDirectory "report-$(Get-Date -Format 'yyyyMMdd-HHmmss').md"
}

$adb = Get-AdbPath
$serial = Get-LdPlayerSerial

function Read-DeviceValue {
    param([string[]]$Arguments)
    return ((& $adb -s $serial @Arguments) -join "`n").Trim()
}

$manufacturer = Read-DeviceValue @('shell', 'getprop', 'ro.product.manufacturer')
$model = Read-DeviceValue @('shell', 'getprop', 'ro.product.model')
$android = Read-DeviceValue @('shell', 'getprop', 'ro.build.version.release')
$sdk = Read-DeviceValue @('shell', 'getprop', 'ro.build.version.sdk')
$abi = Read-DeviceValue @('shell', 'getprop', 'ro.product.cpu.abilist')
$display = Read-DeviceValue @('shell', 'wm', 'size')
$density = Read-DeviceValue @('shell', 'wm', 'density')
$processId = Read-DeviceValue @('shell', 'pidof', $script:PackageName)
$memory = Read-DeviceValue @('shell', 'dumpsys', 'meminfo', $script:PackageName)
$totalPss = ($memory -split "`n" | Where-Object { $_ -cmatch '^\s*TOTAL\s+\d' } | Select-Object -First 1).Trim()

$lines = @(
    '# Relatório de diagnóstico LDPlayer'
    ''
    "Gerado em: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    ''
    "- Serial ADB: ``$serial``"
    "- Fabricante/modelo: $manufacturer $model"
    "- Android/API: $android / $sdk"
    "- ABIs: ``$abi``"
    "- Tela: $display; $density"
    "- Pacote: ``$($script:PackageName)``"
    "- Processo: $(if ($processId) { "ativo (PID $processId)" } else { 'inativo' })"
    "- Memória: $(if ($totalPss) { $totalPss } else { 'processo sem dados de memória' })"
)

$parent = Split-Path -Parent $OutputFile
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$lines | Set-Content -LiteralPath $OutputFile -Encoding utf8
Write-Output (Resolve-Path -LiteralPath $OutputFile).Path
