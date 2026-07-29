Set-StrictMode -Version Latest

function Get-DreamSyncPackageName {
    [CmdletBinding()]
    param([Parameter(Mandatory)][ValidateSet('Debug', 'Play')][string]$Lane)
    if ($Lane -eq 'Debug') { return 'com.remote.DreamSync.debug' }
    return 'com.remote.DreamSync'
}

function Get-DreamSyncMainActivityComponent {
    [CmdletBinding()]
    param([Parameter(Mandatory)][ValidateSet('Debug', 'Play')][string]$Lane)
    $package = Get-DreamSyncPackageName $Lane
    return "$package/com.remote.DreamSync.MainActivity"
}

function Get-DreamSyncDeviceRole {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Characteristics)
    if ($Characteristics -match '(^|,)watch(,|$)') { return 'watch' }
    return 'phone'
}

function Protect-DreamSyncIdentifier {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return (($digest | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 16)
}

function Get-DreamSyncClockOffsetMs {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][long]$HostUtcMs,
        [Parameter(Mandatory)][long]$DeviceUtcMs
    )
    return $HostUtcMs - $DeviceUtcMs
}

function New-DreamSyncFinding {
    param(
        [string]$Code,
        [ValidateSet('PASS', 'EXPECTED_BLOCK', 'FAIL', 'NO_EVIDENCE')][string]$State,
        [string]$Reason
    )
    [pscustomobject]@{ Code = $Code; State = $State; Reason = $Reason }
}

function Get-DreamSyncFindings {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][string]$Scenario
    )
    $findings = [System.Collections.Generic.List[object]]::new()

    if ($Scenario -eq 'watch-system-check') {
        $motionPass = $Text -match 'WATCH_SYSTEM_CHECK_MOTION_DATA[^\r\n]*samples=([5-9]|[1-9][0-9]+)'
        $motionFail = $Text -match 'GLOBAL_SENSOR_SILENCE|motionSamples=0|MOTION_PROBES_EXHAUSTED'
        $findings.Add((New-DreamSyncFinding 'MOTION_CALLBACKS' `
                    $(if ($motionPass) { 'PASS' } elseif ($motionFail) { 'FAIL' } else { 'NO_EVIDENCE' }) `
                    $(if ($motionPass) { 'At least five valid motion callbacks were recorded.' }
                        elseif ($motionFail) { 'Motion registration produced no qualifying callbacks.' }
                        else { 'No qualifying motion callback evidence was captured.' })))

        $heartPass = $Text -match 'WATCH_SYSTEM_CHECK_HEART_READING_CONFIRMED[^\r\n]*samples=([1-9][0-9]*)'
        $heartFail = $Text -match 'GLOBAL_SENSOR_SILENCE|heartSamples=0|HEART_PROBES_EXHAUSTED'
        $findings.Add((New-DreamSyncFinding 'HEART_CALLBACKS' `
                    $(if ($heartPass) { 'PASS' } elseif ($heartFail) { 'FAIL' } else { 'NO_EVIDENCE' }) `
                    $(if ($heartPass) { 'A valid heart-rate callback was recorded.' }
                        elseif ($heartFail) { 'Heart-rate acquisition produced no valid callbacks.' }
                        else { 'No valid heart-rate callback evidence was captured.' })))

        $acquiring = $Text -match 'ACQUIRING[^\r\n]*(heartSamples=0|heartReadings=0)'
        $findings.Add((New-DreamSyncFinding 'HEART_SERVICES' `
                    $(if ($acquiring) { 'FAIL' } elseif ($heartPass) { 'PASS' } else { 'NO_EVIDENCE' }) `
                    $(if ($acquiring) { 'Health Services remained ACQUIRING with no readings.' }
                        elseif ($heartPass) { 'Heart acquisition completed with a valid reading.' }
                        else { 'Health Services completion was not demonstrated.' })))
    }

    if ($Scenario -eq 'samsung-policy') {
        $policyFailure = $Text -match '(?i)(WATCH_SAMSUNG_SENSOR_ERROR|policy error|signature is not registered)'
        $verified = $Text -match 'advancedSensorState=VERIFIED'
        $findings.Add((New-DreamSyncFinding 'SAMSUNG_POLICY' `
                    $(if ($policyFailure) { 'FAIL' } elseif ($verified) { 'PASS' } else { 'NO_EVIDENCE' }) `
                    $(if ($policyFailure) { 'Samsung sensor access reported a package/signature policy error.' }
                        elseif ($verified) { 'Samsung advanced sensors returned verified live evidence.' }
                        else { 'No Samsung policy result was captured.' })))
    }

    if ($Scenario -in @('off-wrist', 'disconnect', 'power-saving', 'sensors-off')) {
        $blocked = $Text -match '(?i)(CUE_REJECTED|BLOCKED|DISABLED|PHONE_LOST)'
        $findings.Add((New-DreamSyncFinding 'FAIL_CLOSED' `
                    $(if ($blocked) { 'EXPECTED_BLOCK' } else { 'NO_EVIDENCE' }) `
                    $(if ($blocked) { 'The safety condition blocked cueing as expected.' }
                        else { 'No fail-closed event was captured.' })))
    }

    return $findings.ToArray()
}

function Merge-DreamSyncTimeline {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][object[]]$Events,
        [Parameter(Mandatory)][long]$WatchClockOffsetMs
    )
    $adjusted = foreach ($event in $Events) {
        $utc = [long]$event.UtcMs
        if ($event.Device -eq 'watch') { $utc += $WatchClockOffsetMs }
        [pscustomobject]@{
            Device = $event.Device
            UtcMs = [long]$event.UtcMs
            AdjustedUtcMs = $utc
            Message = $event.Message
        }
    }
    return @($adjusted | Sort-Object AdjustedUtcMs, Device)
}

function Format-DreamSyncFindingLine {
    [CmdletBinding()]
    param([Parameter(Mandatory)][object]$Finding)
    return "- **$($Finding.State)** $($Finding.Code): $($Finding.Reason)"
}

function Find-DreamSyncAdb {
    [CmdletBinding()]
    param()
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

function Invoke-DreamSyncAdb {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = & $Adb -s $Serial @Arguments 2>&1
    $code = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb failed for device $(Protect-DreamSyncIdentifier $Serial) ($code): $text"
    }
    [pscustomobject]@{ ExitCode = $code; Text = $text }
}

Export-ModuleMember -Function @(
    'Find-DreamSyncAdb',
    'Format-DreamSyncFindingLine',
    'Get-DreamSyncClockOffsetMs',
    'Get-DreamSyncDeviceRole',
    'Get-DreamSyncFindings',
    'Get-DreamSyncMainActivityComponent',
    'Get-DreamSyncPackageName',
    'Invoke-DreamSyncAdb',
    'Merge-DreamSyncTimeline',
    'Protect-DreamSyncIdentifier'
)
