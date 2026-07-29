$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\DreamSync.DeviceLab.psm1'
Import-Module $modulePath -Force

$failures = [System.Collections.Generic.List[string]]::new()

function Assert-Equal {
    param(
        [Parameter(Mandatory)][object]$Actual,
        [Parameter(Mandatory)][object]$Expected,
        [Parameter(Mandatory)][string]$Name
    )
    if ("$Actual" -ne "$Expected") {
        $failures.Add("$Name expected '$Expected' but got '$Actual'")
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Name
    )
    if (-not $Condition) {
        $failures.Add("$Name expected true")
    }
}

Assert-Equal (Get-DreamSyncPackageName -Lane Debug) 'com.remote.DreamSync.debug' 'debug package'
Assert-Equal (Get-DreamSyncPackageName -Lane Play) 'com.remote.DreamSync' 'Play package'
Assert-Equal (Get-DreamSyncMainActivityComponent -Lane Debug) `
    'com.remote.DreamSync.debug/com.remote.DreamSync.MainActivity' 'debug activity component'
Assert-Equal (Get-DreamSyncMainActivityComponent -Lane Play) `
    'com.remote.DreamSync/com.remote.DreamSync.MainActivity' 'Play activity component'
Assert-Equal (Get-DreamSyncDeviceRole -Characteristics 'watch') 'watch' 'watch role'
Assert-Equal (Get-DreamSyncDeviceRole -Characteristics 'phone') 'phone' 'phone role'
Assert-Equal (Get-DreamSyncDeviceRole -Characteristics 'nosdcard,watch') 'watch' 'compound watch role'

$hash = Protect-DreamSyncIdentifier -Value 'PHONE-SERIAL-EXAMPLE'
Assert-True ($hash -match '^[a-f0-9]{16}$') 'identifier hash shape'
Assert-True ($hash -notmatch 'PHONE-SERIAL-EXAMPLE') 'identifier redaction'

$offset = Get-DreamSyncClockOffsetMs -HostUtcMs 10000 -DeviceUtcMs 9250
Assert-Equal $offset 750 'clock offset'

$zeroCallbacks = @'
100 | WATCH_SENSOR_REGISTRATION | accelerometer=true
200 | WATCH_SYSTEM_CHECK_GLOBAL_SENSOR_SILENCE | motionCallbacks=0;heartCallbacks=0
300 | WATCH_SYSTEM_CHECK_RESULT | status=FAIL;motionSamples=0;heartSamples=0
'@
$zeroResult = Get-DreamSyncFindings -Text $zeroCallbacks -Scenario 'watch-system-check'
Assert-Equal ($zeroResult | Where-Object Code -eq 'MOTION_CALLBACKS').State 'FAIL' 'zero motion callbacks'
Assert-Equal ($zeroResult | Where-Object Code -eq 'HEART_CALLBACKS').State 'FAIL' 'zero heart callbacks'

$registrationOnly = @'
100 | WATCH_SENSOR_REGISTRATION | accelerometer=true
200 | WATCH_SYSTEM_CHECK_RESULT | status=FAIL;motionSamples=0
'@
$registrationResult = Get-DreamSyncFindings -Text $registrationOnly -Scenario 'watch-system-check'
Assert-True (-not (($registrationResult | Where-Object Code -eq 'MOTION_CALLBACKS').State -eq 'PASS')) `
    'registration is not evidence of callbacks'

$healthy = @'
100 | WATCH_SYSTEM_CHECK_MOTION_DATA | source=accelerometer;samples=5;x=0.1;y=0.2;z=9.8
200 | WATCH_SYSTEM_CHECK_HEART_READING_CONFIRMED | source=standard;bpm=62;samples=3
300 | WATCH_SYSTEM_CHECK_RESULT | status=PASS;motionSamples=5;heartSamples=3
'@
$healthyResult = Get-DreamSyncFindings -Text $healthy -Scenario 'watch-system-check'
Assert-Equal ($healthyResult | Where-Object Code -eq 'MOTION_CALLBACKS').State 'PASS' 'motion callbacks pass'
Assert-Equal ($healthyResult | Where-Object Code -eq 'HEART_CALLBACKS').State 'PASS' 'heart callbacks pass'

$insufficientHeart = @'
100 | WATCH_SYSTEM_CHECK_HEART_READING_CONFIRMED | source=standard;bpm=62;samples=2
200 | WATCH_SYSTEM_CHECK_RESULT | status=FAIL;motionSamples=5;heartSamples=2
'@
$insufficientHeartResult = Get-DreamSyncFindings -Text $insufficientHeart -Scenario 'watch-system-check'
Assert-Equal ($insufficientHeartResult | Where-Object Code -eq 'HEART_CALLBACKS').State 'FAIL' `
    'fewer than three heart callbacks fail'

Assert-Equal (Get-DreamSyncProviderState -Available $false -PermissionGranted $true `
        -PolicyBlocked $false -Registered $false -CallbackCount 0 -ValidSampleCount 0) `
    'UNAVAILABLE' 'provider unavailable'
Assert-Equal (Get-DreamSyncProviderState -Available $true -PermissionGranted $false `
        -PolicyBlocked $false -Registered $false -CallbackCount 0 -ValidSampleCount 0) `
    'PERMISSION_BLOCKED' 'provider permission blocked'
Assert-Equal (Get-DreamSyncProviderState -Available $true -PermissionGranted $true `
        -PolicyBlocked $true -Registered $false -CallbackCount 0 -ValidSampleCount 0) `
    'POLICY_BLOCKED' 'provider policy blocked'
Assert-Equal (Get-DreamSyncProviderState -Available $true -PermissionGranted $true `
        -PolicyBlocked $false -Registered $false -CallbackCount 0 -ValidSampleCount 0) `
    'REGISTRATION_REJECTED' 'provider registration rejected'
Assert-Equal (Get-DreamSyncProviderState -Available $true -PermissionGranted $true `
        -PolicyBlocked $false -Registered $true -CallbackCount 0 -ValidSampleCount 0) `
    'REGISTERED_SILENT' 'provider registered silent'
Assert-Equal (Get-DreamSyncProviderState -Available $true -PermissionGranted $true `
        -PolicyBlocked $false -Registered $true -CallbackCount 4 -ValidSampleCount 3) `
    'DELIVERING_VALID_SAMPLES' 'provider valid samples'

Assert-True (Test-DreamSyncLanePair -Lane Debug -PhonePackage 'com.remote.DreamSync.debug' `
        -WatchPackage 'com.remote.DreamSync.debug') 'matching debug pair'
Assert-True (-not (Test-DreamSyncLanePair -Lane Debug -PhonePackage 'com.remote.DreamSync.debug' `
            -WatchPackage 'com.remote.DreamSync')) 'mixed pair rejected'

$silentProvider = @'
100 | PROVIDER_PROBE_RESULT | provider=samsung-heart;available=true;permissionGranted=true;policyBlocked=false;registered=true;callbacks=0;validSamples=0
'@
$silentProviderResult = Get-DreamSyncFindings -Text $silentProvider -Scenario 'sensor-isolation'
Assert-Equal ($silentProviderResult | Where-Object Code -eq 'PROVIDER_SAMSUNG_HEART').State `
    'FAIL' 'registered silent provider fails'

$liveProvider = @'
100 | PROVIDER_PROBE_RESULT | provider=samsung-heart;available=true;permissionGranted=true;policyBlocked=false;registered=true;callbacks=4;validSamples=3
'@
$liveProviderResult = Get-DreamSyncFindings -Text $liveProvider -Scenario 'sensor-isolation'
Assert-Equal ($liveProviderResult | Where-Object Code -eq 'PROVIDER_SAMSUNG_HEART').State `
    'PASS' 'live provider passes'

$acquiring = '100 | WATCH_SYSTEM_CHECK_PROGRESS | measureAvailability=ACQUIRING;heartSamples=0'
$acquiringResult = Get-DreamSyncFindings -Text $acquiring -Scenario 'watch-system-check'
Assert-Equal ($acquiringResult | Where-Object Code -eq 'HEART_SERVICES').State 'FAIL' 'Health Services acquiring'

$policy = '100 | WATCH_SAMSUNG_SENSOR_ERROR | policy error: package signature is not registered'
$policyResult = Get-DreamSyncFindings -Text $policy -Scenario 'samsung-policy'
Assert-Equal ($policyResult | Where-Object Code -eq 'SAMSUNG_POLICY').State 'FAIL' 'Samsung policy error'

$blocked = '100 | CUE_REJECTED | reason=off wrist'
$blockedResult = Get-DreamSyncFindings -Text $blocked -Scenario 'off-wrist'
Assert-Equal ($blockedResult | Where-Object Code -eq 'FAIL_CLOSED').State 'EXPECTED_BLOCK' 'off-wrist block'

$findingLine = Format-DreamSyncFindingLine -Finding ([pscustomobject]@{
    Code = 'MOTION_CALLBACKS'
    State = 'FAIL'
    Reason = 'No callbacks.'
})
Assert-Equal $findingLine '- **FAIL** MOTION_CALLBACKS: No callbacks.' 'finding Markdown'
Assert-True ($findingLine -notmatch '\$\(') 'finding Markdown has no literal expression'

$events = @(
    [pscustomobject]@{ Device = 'watch'; UtcMs = 900; Message = 'watch event' },
    [pscustomobject]@{ Device = 'phone'; UtcMs = 1000; Message = 'phone event' }
)
$merged = Merge-DreamSyncTimeline -Events $events -WatchClockOffsetMs 100
Assert-Equal $merged[0].AdjustedUtcMs 1000 'watch clock adjustment'
Assert-Equal $merged[1].AdjustedUtcMs 1000 'phone clock unchanged'

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$phoneGradle = Get-Content -Raw (Join-Path $projectRoot 'phone\build.gradle')
$wearGradle = Get-Content -Raw (Join-Path $projectRoot 'wear\build.gradle')
foreach ($entry in @(
    @{ Name = 'phone'; Text = $phoneGradle },
    @{ Name = 'wear'; Text = $wearGradle }
)) {
    Assert-True ($entry.Text -match "applicationIdSuffix '\.debug'") "$($entry.Name) debug suffix"
    Assert-True ($entry.Text -match "versionNameSuffix '-dev'") "$($entry.Name) debug version suffix"
    Assert-True ($entry.Text -match "resValue 'string', 'app_name', 'DreamSync Dev'") `
        "$($entry.Name) debug label"
}

$connectScript = Join-Path $projectRoot 'tools\Connect-DreamSyncDevices.ps1'
$startScript = Join-Path $projectRoot 'tools\Start-DreamSyncDeviceTest.ps1'
$analyzeScript = Join-Path $projectRoot 'tools\Analyze-DreamSyncDeviceTest.ps1'
Assert-True (Test-Path -LiteralPath $connectScript) 'connect script exists'
Assert-True (Test-Path -LiteralPath $startScript) 'capture script exists'
Assert-True (Test-Path -LiteralPath $analyzeScript) 'analysis script exists'

if (Test-Path -LiteralPath $startScript) {
    $startText = Get-Content -Raw $startScript
    foreach ($parameter in @('Lane', 'Scenario', 'PhoneSerial', 'WatchSerial',
            'Install', 'DurationMinutes', 'OutputDirectory', 'PreflightOnly',
            'ProbeProvider')) {
        Assert-True ($startText -match "\`$$parameter\b") "capture parameter $parameter"
    }
    Assert-True ($startText -match "'sensor-isolation'") 'sensor isolation scenario'
    Assert-True ($startText -match 'dreamsync://watch/system-check.+provider=') `
        'isolated provider deep link'
    Assert-True ($startText -match 'Debug installs cannot target the Play package') `
        'mixed install safety message'
    Assert-True ($startText -match 'controlled-cue.+RequireControlledCueApproval') `
        'controlled cue approval gate'
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host 'DreamSync.DeviceLab tests passed.'
