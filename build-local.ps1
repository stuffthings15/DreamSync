# Builds both debug APKs on Windows.
$ErrorActionPreference = "Stop"

if (-not (Test-Path "$PSScriptRoot\local.properties")) {
    Write-Error "Copy local.properties.example to local.properties and set sdk.dir first."
}

if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\openjdk\jdk-21.0.8"
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}

& "$PSScriptRoot\gradlew.bat" :phone:assembleDebug :wear:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "APKs:"
Write-Host "  phone\build\outputs\apk\debug\phone-debug.apk"
Write-Host "  wear\build\outputs\apk\debug\wear-debug.apk"
