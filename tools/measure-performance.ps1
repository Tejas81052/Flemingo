param(
    [string]$Package = "me.thimmaiah.ebors.debug",
    [string]$Activity = "me.thimmaiah.ebors.MainActivity",
    [int]$Runs = 5
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    if (-not (Test-Path -LiteralPath $localProperties)) {
        throw "adb is not on PATH and local.properties was not found."
    }
    $sdkLine = Get-Content -LiteralPath $localProperties |
        Where-Object { $_ -like "sdk.dir=*" } |
        Select-Object -First 1
    if (-not $sdkLine) {
        throw "sdk.dir is missing from local.properties."
    }
    $sdk = $sdkLine.Substring("sdk.dir=".Length).Replace("\:", ":").Replace("\\", "\")
    $candidate = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $candidate)) {
        throw "adb was not found at $candidate"
    }
    return $candidate
}

$adb = Resolve-Adb
$component = "$Package/$Activity"

& $adb get-state | Out-Null

$results = @()
for ($run = 1; $run -le $Runs; $run++) {
    & $adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 350
    $output = & $adb shell am start -W -n $component
    $total = [int](($output | Select-String "TotalTime:").Line -replace "\D", "")
    $wait = [int](($output | Select-String "WaitTime:").Line -replace "\D", "")
    $results += [pscustomobject]@{
        Run = $run
        TotalTimeMs = $total
        WaitTimeMs = $wait
    }
}

$results | Format-Table -AutoSize

$sorted = @($results.TotalTimeMs | Sort-Object)
$median = if ($sorted.Count % 2 -eq 1) {
    $sorted[[int][Math]::Floor($sorted.Count / 2)]
} else {
    ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2
}
$average = [Math]::Round(($results.TotalTimeMs | Measure-Object -Average).Average, 1)
Write-Output "Cold-start median: $median ms"
Write-Output "Cold-start average: $average ms"

Write-Output ""
Write-Output "Memory snapshot"
& $adb shell dumpsys meminfo $Package |
    Select-String -Pattern "Java Heap:|Native Heap:|Graphics:|TOTAL PSS:|TOTAL RSS:"

Write-Output ""
Write-Output "Frame summary"
& $adb shell dumpsys gfxinfo $Package |
    Select-String -Pattern "Total frames rendered:|Janky frames:|50th percentile:|90th percentile:|95th percentile:|99th percentile:"
