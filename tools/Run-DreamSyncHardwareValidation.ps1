[CmdletBinding()]
param(
    [string]$PhoneSerial,
    [string]$WatchSerial,
    [string]$OutputDirectory,
    [switch]$InstallDebugApks,
    [switch]$RunInstrumentation,
    [switch]$RunProcessDeathTest,
    [switch]$RunRebootTest,
    [ValidateSet('Galaxy','Pixel','ThirdPartyWearOS','Other')]
    [string]$DeviceGroup = 'Other',
    [ValidateRange(0,20)]
    [int]$NightIndex = 0,
    [switch]$ListOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Find-Adb {
    $candidates = @()
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Android platform-tools adb.exe was not found.'
}

$adb = Find-Adb

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = & $adb -s $Serial @Arguments 2>&1
    $code = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb failed for $Serial ($code): $text"
    }
    return $text
}

function Get-ConnectedDevices {
    $lines = & $adb devices -l 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($lines | Out-String).Trim()) }
    $devices = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device\s*(.*)$') {
            $serial = $Matches[1]
            $characteristics = Invoke-Adb -Serial $serial -Arguments @('shell', 'getprop', 'ro.build.characteristics') -AllowFailure
            $model = Invoke-Adb -Serial $serial -Arguments @('shell', 'getprop', 'ro.product.model') -AllowFailure
            $devices += [pscustomobject]@{
                Serial = $serial
                Model = $model.Trim()
                Characteristics = $characteristics.Trim()
                Detail = $Matches[2]
            }
        }
    }
    return $devices
}

$devices = @(Get-ConnectedDevices)
Write-Host "Connected Android devices: $($devices.Count)"
$devices | Format-Table Serial, Model, Characteristics -AutoSize
if ($ListOnly) { return }

if (-not $PhoneSerial) {
    $PhoneSerial = ($devices | Where-Object { $_.Characteristics -notmatch 'watch' } | Select-Object -First 1).Serial
}
if (-not $WatchSerial) {
    $WatchSerial = ($devices | Where-Object { $_.Characteristics -match 'watch' } | Select-Object -First 1).Serial
}
if (-not $PhoneSerial -or -not $WatchSerial) {
    throw 'Both phone and watch must be connected. Supply -PhoneSerial and -WatchSerial if automatic detection is ambiguous.'
}
if ($PhoneSerial -eq $WatchSerial) { throw 'Phone and watch serials must be different.' }
if ($devices.Serial -notcontains $PhoneSerial) { throw "Phone $PhoneSerial is not connected and authorized." }
if ($devices.Serial -notcontains $WatchSerial) { throw "Watch $WatchSerial is not connected and authorized." }

if (-not $OutputDirectory) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $projectRoot "docs\hardware-validation\$stamp"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function Save-Text {
    param([string]$Name, [string]$Value)
    $path = Join-Path $OutputDirectory $Name
    [IO.File]::WriteAllText($path, $Value, [Text.UTF8Encoding]::new($false))
}

function Get-DeviceRecord {
    param([string]$Role, [string]$Serial)
    $props = [ordered]@{
        role = $Role
        serial = $Serial
        manufacturer = (Invoke-Adb $Serial @('shell', 'getprop', 'ro.product.manufacturer') -AllowFailure).Trim()
        model = (Invoke-Adb $Serial @('shell', 'getprop', 'ro.product.model') -AllowFailure).Trim()
        device = (Invoke-Adb $Serial @('shell', 'getprop', 'ro.product.device') -AllowFailure).Trim()
        androidVersion = (Invoke-Adb $Serial @('shell', 'getprop', 'ro.build.version.release') -AllowFailure).Trim()
        sdk = (Invoke-Adb $Serial @('shell', 'getprop', 'ro.build.version.sdk') -AllowFailure).Trim()
        buildFingerprint = (Invoke-Adb $Serial @('shell', 'getprop', 'ro.build.fingerprint') -AllowFailure).Trim()
        appPackage = (Invoke-Adb $Serial @('shell', 'dumpsys', 'package', 'com.remote.DreamSync') -AllowFailure)
        battery = (Invoke-Adb $Serial @('shell', 'dumpsys', 'battery') -AllowFailure)
    }
    return [pscustomobject]$props
}

$results = [ordered]@{
    createdUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = (& git -C $projectRoot rev-parse HEAD 2>$null)
    phoneSerial = $PhoneSerial
    watchSerial = $WatchSerial
    installDebugApks = [bool]$InstallDebugApks
    runInstrumentation = [bool]$RunInstrumentation
    runProcessDeathTest = [bool]$RunProcessDeathTest
    runRebootTest = [bool]$RunRebootTest
    deviceGroup = $DeviceGroup
    nightIndex = $NightIndex
    steps = [ordered]@{}
}

if ($InstallDebugApks) {
    $phoneApk = Join-Path $projectRoot 'phone\build\outputs\apk\debug\phone-debug.apk'
    $watchApk = Join-Path $projectRoot 'wear\build\outputs\apk\debug\wear-debug.apk'
    if (-not (Test-Path -LiteralPath $phoneApk)) { throw "Missing $phoneApk" }
    if (-not (Test-Path -LiteralPath $watchApk)) { throw "Missing $watchApk" }
    $results.steps.phoneInstall = Invoke-Adb $PhoneSerial @('install', '-r', $phoneApk)
    $results.steps.watchInstall = Invoke-Adb $WatchSerial @('install', '-r', $watchApk)
}

$results.steps.phoneLaunch = Invoke-Adb $PhoneSerial @('shell', 'am', 'start', '-W', '-n', 'com.remote.DreamSync/.MainActivity')
$results.steps.watchLaunch = Invoke-Adb $WatchSerial @('shell', 'am', 'start', '-W', '-n', 'com.remote.DreamSync/.MainActivity')

if ($RunInstrumentation) {
    $runner = 'com.remote.DreamSync.test/androidx.test.runner.AndroidJUnitRunner'
    $results.steps.phoneInstrumentation = Invoke-Adb $PhoneSerial @('shell', 'am', 'instrument', '-w', '-r', $runner)
    $results.steps.watchInstrumentation = Invoke-Adb $WatchSerial @('shell', 'am', 'instrument', '-w', '-r', $runner)
}

if ($RunProcessDeathTest) {
    $results.steps.phoneForceStop = Invoke-Adb $PhoneSerial @('shell', 'am', 'force-stop', 'com.remote.DreamSync') -AllowFailure
    $results.steps.phoneRelaunch = Invoke-Adb $PhoneSerial @('shell', 'am', 'start', '-W', '-n', 'com.remote.DreamSync/.MainActivity')
    $results.steps.watchForceStop = Invoke-Adb $WatchSerial @('shell', 'am', 'force-stop', 'com.remote.DreamSync') -AllowFailure
    $results.steps.watchRelaunch = Invoke-Adb $WatchSerial @('shell', 'am', 'start', '-W', '-n', 'com.remote.DreamSync/.MainActivity')
}

if ($RunRebootTest) {
    $results.steps.phoneRebootRequested = Invoke-Adb $PhoneSerial @('reboot')
    $results.steps.watchRebootRequested = Invoke-Adb $WatchSerial @('reboot')
    & $adb -s $PhoneSerial wait-for-device | Out-Null
    & $adb -s $WatchSerial wait-for-device | Out-Null
    Start-Sleep -Seconds 10
    $results.steps.phoneAfterReboot = Invoke-Adb $PhoneSerial @(
        'shell', 'dumpsys', 'package', 'com.remote.DreamSync') -AllowFailure
    $results.steps.watchAfterReboot = Invoke-Adb $WatchSerial @(
        'shell', 'dumpsys', 'package', 'com.remote.DreamSync') -AllowFailure
}

$phone = Get-DeviceRecord 'phone' $PhoneSerial
$watch = Get-DeviceRecord 'watch' $WatchSerial
$results.phone = $phone
$results.watch = $watch

Save-Text 'phone-package.txt' $phone.appPackage
Save-Text 'watch-package.txt' $watch.appPackage
Save-Text 'phone-battery.txt' $phone.battery
Save-Text 'watch-battery.txt' $watch.battery
Save-Text 'phone-audio.txt' (Invoke-Adb $PhoneSerial @('shell', 'dumpsys', 'audio') -AllowFailure)
Save-Text 'watch-audio.txt' (Invoke-Adb $WatchSerial @('shell', 'dumpsys', 'audio') -AllowFailure)
Save-Text 'watch-sensors.txt' (Invoke-Adb $WatchSerial @('shell', 'dumpsys', 'sensorservice') -AllowFailure)
Save-Text 'phone-batterystats.txt' (Invoke-Adb $PhoneSerial @('shell', 'dumpsys', 'batterystats', 'com.remote.DreamSync') -AllowFailure)
Save-Text 'watch-batterystats.txt' (Invoke-Adb $WatchSerial @('shell', 'dumpsys', 'batterystats', 'com.remote.DreamSync') -AllowFailure)
Save-Text 'phone-jobs.txt' (Invoke-Adb $PhoneSerial @('shell', 'dumpsys', 'jobscheduler', 'com.remote.DreamSync') -AllowFailure)
Save-Text 'watch-jobs.txt' (Invoke-Adb $WatchSerial @('shell', 'dumpsys', 'jobscheduler', 'com.remote.DreamSync') -AllowFailure)
Save-Text 'phone-logcat.txt' (Invoke-Adb $PhoneSerial @('logcat', '-d', '-v', 'threadtime') -AllowFailure)
Save-Text 'watch-logcat.txt' (Invoke-Adb $WatchSerial @('logcat', '-d', '-v', 'threadtime') -AllowFailure)

$manual = @"
# DreamSync hardware validation follow-up

Generated: $([DateTime]::UtcNow.ToString('u'))
Phone: $($phone.manufacturer) $($phone.model) ($PhoneSerial)
Watch: $($watch.manufacturer) $($watch.model) ($WatchSerial)
Group: $DeviceGroup
Night: $NightIndex

- [ ] Permissions deny/grant/revoke behavior recorded
- [ ] 30-minute motion/heart-rate coverage export attached
- [ ] Audio timing and 10% gain ceiling measured
- [ ] Mute, headphones, Bluetooth, and unknown-route blocks recorded
- [ ] Cue-linked arousal starts 20-minute quiet period and disables later cues
- [ ] Disconnect and stale-phone watchdog result recorded
- [ ] Off-wrist and low-battery result recorded
- [ ] Process-death fresh-handshake behavior recorded
- [ ] Reboot-night restores On monitoring but never resumes cueing without a fresh handshake
- [ ] Battery start/end, elapsed hours, and app-attributed drain recorded
- [ ] Three sensor-only nights exported
- [ ] Seven consecutive baseline nights exported and learned threshold confirmed
- [ ] Baseline, cue, and recovery nights exported
- [ ] Morning disturbance and adverse-effect records attached

Do not mark a model validated or make an efficacy claim until the applicable evidence is attached and reviewed.
"@
Save-Text 'MANUAL_CHECKLIST.md' $manual
Save-Text 'validation-manifest.json' ($results | ConvertTo-Json -Depth 8)

Write-Host "Validation evidence saved to $OutputDirectory"
