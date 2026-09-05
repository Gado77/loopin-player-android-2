param([Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$ReleaseId)
$ErrorActionPreference='Stop'
if(-not $env:LOOPIN_RELEASE_ADMIN_TOKEN -or -not $env:LOOPIN_RELEASE_INSPECTOR_TOKEN){throw 'Configure tokens administrativos no ambiente local.'}
$sdk=$env:ANDROID_HOME;if(-not $sdk){$sdk='C:\Users\itach\.cache\loopin-player2-toolchain\android-sdk'}
$aapt=(Get-ChildItem -LiteralPath "$sdk\build-tools" -Filter aapt.exe -Recurse|Sort-Object FullName -Descending|Select-Object -First 1).FullName
$signer=(Get-ChildItem -LiteralPath "$sdk\build-tools" -Filter apksigner.bat -Recurse|Sort-Object FullName -Descending|Select-Object -First 1).FullName
if(-not $aapt -or -not $signer){throw 'Android build-tools indisponível.'}
$badging=& $aapt dump badging $Apk | Select-Object -First 1
if($badging -notmatch "name='([^']+)'.*versionCode='([^']+)'.*versionName='([^']*)'"){throw 'Metadata APK inválida.'}
if($Matches[1] -ne 'com.loopin.player2'){throw 'Package não autorizado.'}
$package=$Matches[1];$versionCode=[long]$Matches[2];$versionName=$Matches[3]
$certOutput=& $signer verify --print-certs $Apk
$certLine=$certOutput|Select-String 'Signer #1 certificate SHA-256 digest:'|Select-Object -First 1
if(-not $certLine){throw 'APK não assinado.'};$cert=($certLine.Line.Split(':')[-1].Trim().ToLower() -replace ':','')
$sha=(Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLower()
$body=@{action='inspect';release_id=$ReleaseId;package_name=$package;version_code=$versionCode;version_name=$versionName;apk_sha256=$sha;certificate_sha256=$cert}|ConvertTo-Json -Compress
$headers=@{Authorization="Bearer $env:LOOPIN_RELEASE_ADMIN_TOKEN";'X-Release-Inspector'=$env:LOOPIN_RELEASE_INSPECTOR_TOKEN}
Invoke-RestMethod -Method Post -Uri 'https://zdhsfirabkmivuzwyids.supabase.co/functions/v1/admin-releases' -Headers $headers -ContentType 'application/json' -Body $body
Write-Output "Inspeção registrada: $package v$versionCode SHA=$($sha.Substring(0,12))… CERT=$cert"
