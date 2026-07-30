[CmdletBinding()]
param([Parameter(Mandatory)][string]$InputDirectory)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'DreamSync.DeviceLab.psm1') -Force
$InputDirectory = [IO.Path]::GetFullPath($InputDirectory)
$capturePath = Join-Path $InputDirectory 'capture-manifest.json'
if (-not (Test-Path -LiteralPath $capturePath)) { throw "Missing $capturePath" }
$capture = Get-Content -Raw $capturePath | ConvertFrom-Json
$scenario = $capture.preflight.scenario

$sourceFiles = @(
    @{ Device = 'phone'; Path = Join-Path $InputDirectory 'raw\phone\logcat.txt' },
    @{ Device = 'watch'; Path = Join-Path $InputDirectory 'raw\watch\logcat.txt' },
    @{ Device = 'phone'; Path = Join-Path $InputDirectory 'exports\phone-app-debug.log' },
    @{ Device = 'watch'; Path = Join-Path $InputDirectory 'exports\watch-app-debug.log' }
)
$playExports = Get-ChildItem -LiteralPath (Join-Path $InputDirectory 'exports') `
    -Filter 'dreamsync-phone-debug-log*.txt' -File -ErrorAction SilentlyContinue
foreach ($playExport in $playExports) {
    $sourceFiles += @{ Device = 'phone'; Path = $playExport.FullName }
}
$events = [System.Collections.Generic.List[object]]::new()
$allText = [Text.StringBuilder]::new()
foreach ($source in $sourceFiles) {
    if (-not (Test-Path -LiteralPath $source.Path)) { continue }
    $text = Get-Content -Raw $source.Path
    [void]$allText.AppendLine($text)
    foreach ($line in ($text -split '\r?\n')) {
        if (-not $line.Trim()) { continue }
        $utcMs = 0L
        if ($line -match '^(\d{10})(?:\.(\d{1,3}))?') {
            $millis = if ($Matches[2]) { $Matches[2].PadRight(3, '0') } else { '000' }
            $utcMs = ([long]$Matches[1] * 1000L) + [long]$millis
        } elseif ($line -match '^(\d{13})\s+\|') {
            $utcMs = [long]$Matches[1]
        }
        if ($utcMs -gt 0) {
            $events.Add([pscustomobject]@{
                Device = $source.Device; UtcMs = $utcMs; Message = $line
            })
        }
    }
}

$offset = 0L
$statePath = Join-Path $env:LOCALAPPDATA 'DreamSync\device-lab.json'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -Raw $statePath | ConvertFrom-Json
    if ($state.watch.clockOffsetMs -ne $null) { $offset = [long]$state.watch.clockOffsetMs }
}
$timeline = Merge-DreamSyncTimeline -Events $events.ToArray() -WatchClockOffsetMs $offset
$timeline | Export-Csv (Join-Path $InputDirectory 'timeline.csv') -NoTypeInformation -Encoding UTF8

$findings = @(Get-DreamSyncFindings -Text $allText.ToString() -Scenario $scenario)
if ($findings.Count -eq 0) {
    $findings = @([pscustomobject]@{
        Code = 'SCENARIO_EVIDENCE'; State = 'NO_EVIDENCE'
        Reason = "No automated classifier is defined for $scenario; review the timeline."
    })
}
$findings | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $InputDirectory 'failures.json') -Encoding UTF8

$summary = [Text.StringBuilder]::new()
[void]$summary.AppendLine("# DreamSync device test: $scenario")
[void]$summary.AppendLine()
[void]$summary.AppendLine("- Lane: $($capture.preflight.lane)")
[void]$summary.AppendLine("- Package: $($capture.preflight.package)")
[void]$summary.AppendLine("- Phone: $($capture.preflight.phoneModel), version code $($capture.preflight.phoneVersionCode)")
[void]$summary.AppendLine("- Watch: $($capture.preflight.watchModel), version code $($capture.preflight.watchVersionCode)")
[void]$summary.AppendLine("- Source commit: $($capture.sourceCommit)")
[void]$summary.AppendLine("- Capture complete: $($capture.captureComplete)")
[void]$summary.AppendLine()
[void]$summary.AppendLine('## Findings')
foreach ($finding in $findings) {
    [void]$summary.AppendLine((Format-DreamSyncFindingLine $finding))
}
[void]$summary.AppendLine()
[void]$summary.AppendLine('Sensor registration alone is not a pass. Wrist-sensor outputs are probable control states, not EEG/AASM staging.')
[IO.File]::WriteAllText((Join-Path $InputDirectory 'summary.md'), $summary.ToString(),
    [Text.UTF8Encoding]::new($false))

$publicManifest = [ordered]@{
    schemaVersion = 1
    runId = Split-Path -Leaf $InputDirectory
    createdUtc = $capture.createdUtc
    sourceCommit = $capture.sourceCommit
    lane = $capture.preflight.lane
    scenario = $scenario
    package = $capture.preflight.package
    phoneHash = $capture.preflight.phoneHash
    watchHash = $capture.preflight.watchHash
    phoneModel = $capture.preflight.phoneModel
    watchModel = $capture.preflight.watchModel
    phoneVersionCode = $capture.preflight.phoneVersionCode
    watchVersionCode = $capture.preflight.watchVersionCode
    captureComplete = $capture.captureComplete
    findingCounts = [ordered]@{
        pass = @($findings | Where-Object State -eq 'PASS').Count
        expectedBlock = @($findings | Where-Object State -eq 'EXPECTED_BLOCK').Count
        fail = @($findings | Where-Object State -eq 'FAIL').Count
        noEvidence = @($findings | Where-Object State -eq 'NO_EVIDENCE').Count
    }
}
$publicManifest | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $InputDirectory 'manifest.json') -Encoding UTF8

$hashLines = foreach ($file in Get-ChildItem -LiteralPath $InputDirectory -File -Recurse |
        Where-Object Name -ne 'checksums.sha256' | Sort-Object FullName) {
    $relative = $file.FullName.Substring($InputDirectory.Length).TrimStart('\')
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $relative"
}
[IO.File]::WriteAllLines((Join-Path $InputDirectory 'checksums.sha256'), $hashLines,
    [Text.UTF8Encoding]::new($false))
Write-Host "Analysis written to $InputDirectory"
