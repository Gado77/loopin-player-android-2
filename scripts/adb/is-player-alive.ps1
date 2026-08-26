. "$PSScriptRoot\Common.ps1"
$output = Invoke-TargetAdb -Arguments @('shell', 'pidof', $script:PackageName)
if ($output) { Write-Output "ALIVE pid=$output"; exit 0 }
Write-Output 'NOT_RUNNING'
exit 1
