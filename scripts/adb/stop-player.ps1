. "$PSScriptRoot\Common.ps1"
Invoke-TargetAdb -Arguments @('shell', 'am', 'force-stop', $script:PackageName)
