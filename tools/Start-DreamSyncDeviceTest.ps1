[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Debug', 'Play')][string]$Lane,
    [Parameter(Mandatory)][ValidateSet(
        'watch-system-check', 'sensor-contention', 'samsung-policy', 'audio-haptic',
        'clock-drift', 'activity-lifecycle', 'disconnect', 'process-death', 'reboot',
        'off-wrist', 'power-saving', 'sensors-off', 'overnight-sensor-only',
        'controlled-cue', 'sensor-isolation')][string]$Scenario,
    [ValidateSet(
        'android-accelerometer', 'android-linear-acceleration', 'android-gravity',
        'samsung-accelerometer', 'health-services-heart', 'android-heart',
        'samsung-heart')][string]$ProbeProvider,
    [string]$PhoneSerial,
    [string]$WatchSerial,
    [switch]$Install,
    [ValidateRange(1, 720)][int]$DurationMinutes = 5,
    [string]$OutputDirectory,
    [switch]$CaptureBugreport,
    [switch]$PullPlayExport,
    [switch]$RequireDestructiveApproval,
    [switch]$RequireControlledCueApproval,
    [switch]$PreflightOnly
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'DreamSync.DeviceLab.psm1') -Force
$adb = Find-DreamSyncAdb
$projectRoot = Split-Path -Parent $PSScriptRoot
$package = Get-DreamSyncPackageName $Lane
$mainActivity = Get-DreamSyncMainActivityComponent $Lane
$destructiveScenarios = @('process-death', 'reboot', 'power-saving', 'sensors-off')

if ($Scenario -in $destructiveScenarios -and -not $RequireDestructiveApproval) {
    throw "$Scenario requires -RequireDestructiveApproval."
}
if ($Scenario -eq 'controlled-cue' -and -not $RequireControlledCueApproval) {
    throw 'controlled-cue requires -RequireControlledCueApproval.'
}
if ($Lane -eq 'Play' -and $Install) {
    throw 'Debug installs cannot target the Play package. Install Play builds from Internal testing.'
}
if ($Scenario -eq 'sensor-isolation') {
    if ($Lane -ne 'Debug') { throw 'sensor-isolation is available only in the Debug lane.' }
    if (-not $ProbeProvider) { throw 'sensor-isolation requires -ProbeProvider.' }
}

$deviceLines = & $adb devices -l
$devices = foreach ($line in $deviceLines) {
    if ($line -notmatch '^(\S+)\s+device\s*(.*)$') { continue }
    $serial = $Matches[1]
    $characteristics = (Invoke-DreamSyncAdb $adb $serial @(
            'shell', 'getprop', 'ro.build.characteristics') -AllowFailure).Text.Trim()
    [pscustomobject]@{
        Serial = $serial
        Role = Get-DreamSyncDeviceRole $characteristics
        Model = (Invoke-DreamSyncAdb $adb $serial @(
                'shell', 'getprop', 'ro.product.model') -AllowFailure).Text.Trim()
    }
}
if (-not $PhoneSerial) {
    $phones = @($devices | Where-Object Role -eq 'phone')
    if ($phones.Count -eq 1) { $PhoneSerial = $phones[0].Serial }
}
if (-not $WatchSerial) {
    $watches = @($devices | Where-Object Role -eq 'watch')
    if ($watches.Count -eq 1) { $WatchSerial = $watches[0].Serial }
}
if (-not $PhoneSerial -or -not $WatchSerial) {
    throw 'One authorized phone and one authorized watch are required.'
}
if ($PhoneSerial -eq $WatchSerial) { throw 'Phone and watch serials must be different.' }

function Get-PackageDump {
    param([string]$Serial)
    (Invoke-DreamSyncAdb $adb $Serial @('shell', 'dumpsys', 'package', $package) -AllowFailure).Text
}

if ($Install) {
    $phoneApk = Join-Path $projectRoot 'phone\build\outputs\apk\debug\phone-debug.apk'
    $watchApk = Join-Path $projectRoot 'wear\build\outputs\apk\debug\wear-debug.apk'
    if (-not (Test-Path -LiteralPath $phoneApk) -or -not (Test-Path -LiteralPath $watchApk)) {
        throw 'Debug APKs are missing. Build :phone:assembleDebug and :wear:assembleDebug first.'
    }
    Invoke-DreamSyncAdb $adb $PhoneSerial @('install', '-r', $phoneApk) | Out-Null
    Invoke-DreamSyncAdb $adb $WatchSerial @('install', '-r', $watchApk) | Out-Null
}

$phoneDump = Get-PackageDump $PhoneSerial
$watchDump = Get-PackageDump $WatchSerial
$phoneInstalled = $phoneDump -match "Package \[$([regex]::Escape($package))\]"
$watchInstalled = $watchDump -match "Package \[$([regex]::Escape($package))\]"
if (-not $phoneInstalled -or -not $watchInstalled) {
    throw "Both devices must have $package installed. Mixed Debug/Play pairs are rejected."
}
if ($Lane -eq 'Play') {
    $phoneCode = ([regex]::Match($phoneDump, 'versionCode=(\d+)')).Groups[1].Value
    $watchCode = ([regex]::Match($watchDump, 'versionCode=(\d+)')).Groups[1].Value
    if ($phoneCode -ne '69' -or $watchCode -ne '70') {
        throw "Play lane requires phone code 69 and watch code 70; found $phoneCode/$watchCode."
    }
}

$preflight = [ordered]@{
    lane = $Lane
    scenario = $Scenario
    package = $package
    phoneHash = Protect-DreamSyncIdentifier $PhoneSerial
    watchHash = Protect-DreamSyncIdentifier $WatchSerial
    phoneModel = ($devices | Where-Object Serial -eq $PhoneSerial).Model
    watchModel = ($devices | Where-Object Serial -eq $WatchSerial).Model
    phoneVersionCode = ([regex]::Match($phoneDump, 'versionCode=(\d+)')).Groups[1].Value
    watchVersionCode = ([regex]::Match($watchDump, 'versionCode=(\d+)')).Groups[1].Value
}
if ($PreflightOnly) {
    $preflight | ConvertTo-Json -Depth 4
    return
}

if (-not $OutputDirectory) {
    $root = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'AI Brain\Dream Sync\hardware-validation'
    $date = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
    $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + "-$($Scenario)-$($Lane.ToLowerInvariant())"
    $OutputDirectory = Join-Path (Join-Path $root $date) $runId
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$rawPhone = Join-Path $OutputDirectory 'raw\phone'
$rawWatch = Join-Path $OutputDirectory 'raw\watch'
$exports = Join-Path $OutputDirectory 'exports'
$screenshots = Join-Path $OutputDirectory 'screenshots'
@($rawPhone, $rawWatch, $exports, $screenshots) | ForEach-Object {
    New-Item -ItemType Directory -Path $_ -Force | Out-Null
}

$startedUtc = [DateTime]::UtcNow
$marker = "DREAMSYNC_TEST_START lane=$Lane scenario=$Scenario utc=$($startedUtc.ToString('o'))"
$logProcesses = @()
$stepResults = [ordered]@{}
try {
    foreach ($serial in @($PhoneSerial, $WatchSerial)) {
        Invoke-DreamSyncAdb $adb $serial @('logcat', '-b', 'main', '-b', 'crash', '-c') -AllowFailure | Out-Null
        Invoke-DreamSyncAdb $adb $serial @('shell', 'log', '-t', 'DreamSyncLab', $marker) -AllowFailure | Out-Null
    }
    $phoneLog = Join-Path $rawPhone 'logcat.txt'
    $watchLog = Join-Path $rawWatch 'logcat.txt'
    $logProcesses += Start-Process -FilePath $adb -ArgumentList @(
        '-s', $PhoneSerial, 'logcat', '-b', 'main', '-b', 'crash', '-v', 'epoch') `
        -RedirectStandardOutput $phoneLog -RedirectStandardError (Join-Path $rawPhone 'logcat-error.txt') `
        -WindowStyle Hidden -PassThru
    $logProcesses += Start-Process -FilePath $adb -ArgumentList @(
        '-s', $WatchSerial, 'logcat', '-b', 'main', '-b', 'crash', '-v', 'epoch') `
        -RedirectStandardOutput $watchLog -RedirectStandardError (Join-Path $rawWatch 'logcat-error.txt') `
        -WindowStyle Hidden -PassThru

    $stepResults.phoneLaunch = (Invoke-DreamSyncAdb $adb $PhoneSerial @(
            'shell', 'am', 'start', '-W', '-n', $mainActivity) -AllowFailure)
    $watchLaunchArguments = if ($Scenario -eq 'sensor-isolation') {
        @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
            '-d', "dreamsync://watch/system-check?lab=true&provider=$ProbeProvider",
            '-n', $mainActivity)
    } else {
        @('shell', 'am', 'start', '-W', '-n', $mainActivity)
    }
    $stepResults.watchLaunch = (Invoke-DreamSyncAdb $adb $WatchSerial `
            $watchLaunchArguments -AllowFailure)
    Write-Host "Capture running for $DurationMinutes minute(s). Perform scenario: $Scenario"
    Start-Sleep -Seconds ($DurationMinutes * 60)
} finally {
    foreach ($process in $logProcesses) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
}

function Save-AdbText {
    param([string]$Serial, [string[]]$Arguments, [string]$Path)
    $result = Invoke-DreamSyncAdb $adb $Serial $Arguments -AllowFailure
    [IO.File]::WriteAllText($Path, $result.Text, [Text.UTF8Encoding]::new($false))
    return $result.ExitCode
}

foreach ($record in @(
    @{ S = $PhoneSerial; A = @('shell','dumpsys','package',$package); P = Join-Path $rawPhone 'package.txt' },
    @{ S = $WatchSerial; A = @('shell','dumpsys','package',$package); P = Join-Path $rawWatch 'package.txt' },
    @{ S = $PhoneSerial; A = @('shell','dumpsys','battery'); P = Join-Path $rawPhone 'battery.txt' },
    @{ S = $WatchSerial; A = @('shell','dumpsys','battery'); P = Join-Path $rawWatch 'battery.txt' },
    @{ S = $PhoneSerial; A = @('shell','dumpsys','audio'); P = Join-Path $rawPhone 'audio.txt' },
    @{ S = $WatchSerial; A = @('shell','dumpsys','audio'); P = Join-Path $rawWatch 'audio.txt' },
    @{ S = $WatchSerial; A = @('shell','dumpsys','sensorservice'); P = Join-Path $rawWatch 'sensors.txt' },
    @{ S = $PhoneSerial; A = @('shell','dumpsys','jobscheduler',$package); P = Join-Path $rawPhone 'jobs.txt' },
    @{ S = $WatchSerial; A = @('shell','dumpsys','jobscheduler',$package); P = Join-Path $rawWatch 'jobs.txt' },
    @{ S = $PhoneSerial; A = @('shell','dumpsys','activity','processes',$package); P = Join-Path $rawPhone 'processes.txt' },
    @{ S = $WatchSerial; A = @('shell','dumpsys','activity','processes',$package); P = Join-Path $rawWatch 'processes.txt' }
)) {
    $stepResults[$record.P.Replace($OutputDirectory, '').TrimStart('\')] =
        Save-AdbText $record.S $record.A $record.P
}

function Save-Screenshot {
    param([string]$Serial, [string]$Path)
    $errorPath = "$Path.error.txt"
    $process = Start-Process -FilePath $adb -ArgumentList @(
        '-s', $Serial, 'exec-out', 'screencap', '-p') `
        -RedirectStandardOutput $Path -RedirectStandardError $errorPath `
        -WindowStyle Hidden -Wait -PassThru
    return $process.ExitCode
}
$stepResults['screenshots\phone.png'] =
    Save-Screenshot $PhoneSerial (Join-Path $screenshots 'phone.png')
$stepResults['screenshots\watch.png'] =
    Save-Screenshot $WatchSerial (Join-Path $screenshots 'watch.png')

if ($Lane -eq 'Debug') {
    foreach ($target in @(
        @{ S = $PhoneSerial; P = Join-Path $exports 'phone-app-debug.log' },
        @{ S = $WatchSerial; P = Join-Path $exports 'watch-app-debug.log' }
    )) {
        $privateName = if ($target.S -eq $PhoneSerial) {
            'dreamsync-phone-debug.log'
        } else {
            'dreamsync-watch-debug.log'
        }
        $runAs = & $adb -s $target.S exec-out run-as $package cat "files/$privateName" 2>$null
        if ($LASTEXITCODE -eq 0) {
            [IO.File]::WriteAllText($target.P, ($runAs -join [Environment]::NewLine),
                [Text.UTF8Encoding]::new($false))
        }
    }
}
if ($PullPlayExport) {
    $remote = (Invoke-DreamSyncAdb $adb $PhoneSerial @(
            'shell', 'sh', '-c', 'ls -t /sdcard/Download/dreamsync-phone-debug-log*.txt 2>/dev/null | head -1'
        ) -AllowFailure).Text.Trim()
    if ($remote) { & $adb -s $PhoneSerial pull $remote $exports | Out-Null }
}
if ($CaptureBugreport) {
    & $adb -s $PhoneSerial bugreport (Join-Path $rawPhone 'bugreport.zip') | Out-Null
    & $adb -s $WatchSerial bugreport (Join-Path $rawWatch 'bugreport.zip') | Out-Null
}

$incompleteReasons = [System.Collections.Generic.List[string]]::new()
foreach ($step in $stepResults.GetEnumerator()) {
    $exitCode = if ($step.Value -is [int]) { $step.Value } else { $step.Value.ExitCode }
    if ($exitCode -ne 0) { $incompleteReasons.Add("$($step.Key): exit $exitCode") }
}
foreach ($required in @(
    (Join-Path $rawPhone 'logcat.txt'),
    (Join-Path $rawWatch 'logcat.txt'),
    (Join-Path $screenshots 'phone.png'),
    (Join-Path $screenshots 'watch.png')
)) {
    if (-not (Test-Path -LiteralPath $required) -or (Get-Item $required).Length -eq 0) {
        $incompleteReasons.Add("Missing or empty: $required")
    }
}
$manifest = [ordered]@{
    schemaVersion = 1
    createdUtc = [DateTime]::UtcNow.ToString('o')
    startedUtc = $startedUtc.ToString('o')
    sourceCommit = (& git -C $projectRoot rev-parse HEAD)
    preflight = $preflight
    durationMinutes = $DurationMinutes
    captureComplete = $incompleteReasons.Count -eq 0
    incompleteReasons = $incompleteReasons.ToArray()
    steps = $stepResults
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $OutputDirectory 'capture-manifest.json') -Encoding UTF8
& (Join-Path $PSScriptRoot 'Analyze-DreamSyncDeviceTest.ps1') -InputDirectory $OutputDirectory
Write-Host "DreamSync evidence saved to $OutputDirectory"
