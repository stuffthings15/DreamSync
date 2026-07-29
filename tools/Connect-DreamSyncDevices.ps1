[CmdletBinding()]
param(
    [string]$PhoneSerial,
    [string]$WatchAddress,
    [string]$PairAddress,
    [string]$PairingCode,
    [string]$StatePath = (Join-Path $env:LOCALAPPDATA 'DreamSync\device-lab.json'),
    [switch]$ListOnly
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'DreamSync.DeviceLab.psm1') -Force
$adb = Find-DreamSyncAdb

if ($PairAddress) {
    if (-not $PairingCode) {
        throw 'PairingCode is required with PairAddress. It is used once and is never saved.'
    }
    $pairOutput = & $adb pair $PairAddress $PairingCode 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Watch pairing failed: $($pairOutput -join ' ')" }
}
if ($WatchAddress) {
    $connectOutput = & $adb connect $WatchAddress 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Watch connection failed: $($connectOutput -join ' ')" }
}

$rawDevices = & $adb devices -l 2>&1
if ($LASTEXITCODE -ne 0) { throw ($rawDevices -join [Environment]::NewLine) }
$devices = foreach ($line in $rawDevices) {
    if ($line -notmatch '^(\S+)\s+(\S+)\s*(.*)$' -or $Matches[1] -eq 'List') { continue }
    $serial = $Matches[1]
    $state = $Matches[2]
    $detail = $Matches[3]
    if ($state -ne 'device') {
        [pscustomobject]@{
            Serial = $serial; SerialHash = Protect-DreamSyncIdentifier $serial
            State = $state; Role = 'unknown'; Model = ''; Characteristics = ''; Detail = $detail
        }
        continue
    }
    $characteristics = (Invoke-DreamSyncAdb $adb $serial @(
            'shell', 'getprop', 'ro.build.characteristics') -AllowFailure).Text.Trim()
    $model = (Invoke-DreamSyncAdb $adb $serial @(
            'shell', 'getprop', 'ro.product.model') -AllowFailure).Text.Trim()
    [pscustomobject]@{
        Serial = $serial; SerialHash = Protect-DreamSyncIdentifier $serial
        State = $state; Role = Get-DreamSyncDeviceRole $characteristics
        Model = $model; Characteristics = $characteristics; Detail = $detail
    }
}

$devices | Select-Object SerialHash, State, Role, Model, Characteristics | Format-Table -AutoSize
if ($ListOnly) { return }

$authorized = @($devices | Where-Object State -eq 'device')
if (-not $PhoneSerial) {
    $phones = @($authorized | Where-Object Role -eq 'phone')
    if ($phones.Count -ne 1) { throw "Expected one authorized phone, found $($phones.Count)." }
    $PhoneSerial = $phones[0].Serial
}
$watches = @($authorized | Where-Object Role -eq 'watch')
if (-not $WatchAddress -and (Test-Path -LiteralPath $StatePath)) {
    $saved = Get-Content -Raw $StatePath | ConvertFrom-Json
    if ($saved.watchAddress) { $WatchAddress = $saved.watchAddress }
}
$watch = if ($WatchAddress) {
    $authorized | Where-Object Serial -eq $WatchAddress | Select-Object -First 1
} elseif ($watches.Count -eq 1) {
    $watches[0]
} else {
    $null
}
if (-not $watch) { throw 'Exactly one authorized watch is required. Supply -WatchAddress after enabling watch wireless debugging.' }
if ($PhoneSerial -eq $watch.Serial) { throw 'Phone and watch serials must be different.' }

$phone = $authorized | Where-Object Serial -eq $PhoneSerial | Select-Object -First 1
if (-not $phone -or $phone.Role -ne 'phone') { throw 'PhoneSerial does not identify an authorized phone.' }

function Get-Snapshot {
    param([object]$Device)
    $serial = $Device.Serial
    $deviceUtc = (Invoke-DreamSyncAdb $adb $serial @('shell', 'date', '+%s%3N') -AllowFailure).Text.Trim()
    $hostUtc = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $packages = foreach ($package in @('com.remote.DreamSync', 'com.remote.DreamSync.debug')) {
        $dump = (Invoke-DreamSyncAdb $adb $serial @('shell', 'dumpsys', 'package', $package) -AllowFailure).Text
        if ($dump -match "Package \[$([regex]::Escape($package))\]") {
            [pscustomobject]@{
                package = $package
                versionName = ([regex]::Match($dump, 'versionName=([^\s]+)')).Groups[1].Value
                versionCode = ([regex]::Match($dump, 'versionCode=(\d+)')).Groups[1].Value
                signer = ([regex]::Match($dump, 'signatures=([^\r\n]+)')).Groups[1].Value.Trim()
            }
        }
    }
    [ordered]@{
        role = $Device.Role
        serialHash = $Device.SerialHash
        model = $Device.Model
        characteristics = $Device.Characteristics
        androidVersion = (Invoke-DreamSyncAdb $adb $serial @(
                'shell', 'getprop', 'ro.build.version.release') -AllowFailure).Text.Trim()
        sdk = (Invoke-DreamSyncAdb $adb $serial @(
                'shell', 'getprop', 'ro.build.version.sdk') -AllowFailure).Text.Trim()
        clockOffsetMs = if ($deviceUtc -match '^\d+$') {
            Get-DreamSyncClockOffsetMs $hostUtc ([long]$deviceUtc)
        } else { $null }
        battery = (Invoke-DreamSyncAdb $adb $serial @(
                'shell', 'dumpsys', 'battery') -AllowFailure).Text
        power = (Invoke-DreamSyncAdb $adb $serial @(
                'shell', 'dumpsys', 'power') -AllowFailure).Text
        sensorsOff = (Invoke-DreamSyncAdb $adb $serial @(
                'shell', 'settings', 'get', 'global', 'sensors_off') -AllowFailure).Text.Trim()
        packages = @($packages)
    }
}

$state = [ordered]@{
    updatedUtc = [DateTime]::UtcNow.ToString('o')
    adbVersion = (& $adb version | Select-Object -First 1)
    watchAddress = $watch.Serial
    phone = Get-Snapshot $phone
    watch = Get-Snapshot $watch
}
$parent = Split-Path -Parent $StatePath
New-Item -ItemType Directory -Path $parent -Force | Out-Null
$state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
Write-Host "Device-lab state saved to $StatePath"
