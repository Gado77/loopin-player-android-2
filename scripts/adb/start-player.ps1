. "$PSScriptRoot\Common.ps1"
Invoke-TargetAdb -Arguments @('shell', 'am', 'start', '-W', '-n', $script:MainComponent)
