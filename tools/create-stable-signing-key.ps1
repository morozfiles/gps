#Requires -Version 5.1
<#
Run locally on Windows after installing a JDK (keytool.exe).
Creates ONE permanent Android signing key outside the Git repository.
NEVER run again once this key was used to sign an installed app.
Do not upload the .p12 or .base64.txt to GitHub source control.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $env:USERPROFILE 'OBD-GPS-Signing')
)

$ErrorActionPreference = 'Stop'
$keytool = (Get-Command keytool.exe -ErrorAction SilentlyContinue).Source
if (-not $keytool -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path -LiteralPath $candidate) { $keytool = $candidate }
}
if (-not $keytool) {
    throw 'keytool.exe not found. Install a JDK 17+ or configure JAVA_HOME, then rerun.'
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$keyPath = Join-Path $OutputDirectory 'OBD-GPS-Navigator-Signing.p12'
$b64Path = Join-Path $OutputDirectory 'OBD-GPS-Navigator-Signing.base64.txt'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $b64Path)) {
    throw 'A signing key or Base64 file already exists. Do NOT overwrite it. Keep the original key for future app updates.'
}

Write-Host ''
Write-Host 'Create a NEW permanent signing key. Choose and SAVE a strong password.'
Write-Host 'keytool will prompt you for the password without displaying it.'
& $keytool -genkeypair -alias obdgps -keyalg RSA -keysize 3072 -validity 36500 -storetype PKCS12 -keystore $keyPath -dname 'CN=OBD GPS Navigator, O=Private Android App, C=UA'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keyPath)) {
    throw 'keytool did not create the signing key.'
}

$raw = [System.IO.File]::ReadAllBytes($keyPath)
[System.IO.File]::WriteAllText($b64Path,[System.Convert]::ToBase64String($raw))
Write-Host ''
Write-Host 'Permanent signing files created outside the Git repository:'
Write-Host "Keystore: $keyPath"
Write-Host "Base64 secret: $b64Path"
Write-Host ''
Write-Host 'Configure GitHub repo Settings > Secrets and variables > Actions:'
Write-Host '  OBDGPS_KEYSTORE_B64 = full text contents of the Base64 file'
Write-Host '  OBDGPS_SIGNING_PASSWORD = password entered into keytool'
Write-Host ''
Write-Host 'Keep BOTH the .p12 key and password safely backed up.'
Write-Host 'Once the permanent key is used, changing or losing it will break APK upgrades.'
