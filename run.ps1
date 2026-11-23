#!/usr/bin/env pwsh
# Reusable launcher so Kidoo logs are always visible in the IDE terminal AND saved to logs/.
# Everything the process prints streams live to this terminal and is tee'd to a timestamped
# logs/<target>-<stamp>.log you can open or tail in the IDE (a "logcat").
#
# Usage (run in the IDE terminal):
#   ./run.ps1 kidoo              # Spring Boot app  (gradlew bootRun)
#   ./run.ps1 kidoo -h2          # app with the h2 dev profile
#   ./run.ps1 capture            # netmirror tools/netmirror-capture/run-capture.mjs
#   ./run.ps1 capture --no-auto-click
#   ./run.ps1 discover-ott-feed  # any tools/netmirror-capture/<name>.mjs (name, with or without .mjs)
# Extra args after the target are passed straight through to the process.

param(
  [Parameter(Mandatory = $true)][string]$Target,
  [switch]$h2,
  [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# Run a native command while it streams live to this terminal AND is recorded to $log.
# 2>&1 merges the exe's stderr into the pipeline; on PowerShell 5.1 those lines arrive as
# ErrorRecord objects, so we flatten each to its plain string ("$_") before Tee-Object. That
# avoids the decorated NativeCommandError blocks and keeps the real exit code in $LASTEXITCODE
# (the earlier exit-1 came from $ErrorActionPreference='Stop' making those records fatal).
function Invoke-Logged([string]$exe, [string[]]$cmdArgs, [string]$log) {
  Write-Host "[run] $exe $($cmdArgs -join ' ')" -ForegroundColor Cyan
  Write-Host "[run] logcat -> $log" -ForegroundColor Cyan
  & $exe @cmdArgs 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $log
}

switch ($Target.ToLower()) {
  'kidoo' {
    $gradle = Join-Path $root 'gradlew.bat'
    $gargs = @('bootRun')
    if ($h2) { $gargs += @('-Ph2', '--args=--spring.profiles.active=h2') }
    if ($Rest) { $gargs += $Rest }
    Invoke-Logged $gradle $gargs (Join-Path $logDir "kidoo-$stamp.log")
  }
  default {
    $name = if ($Target -eq 'capture') { 'run-capture' } else { $Target }
    if ($name -notmatch '\.mjs$') { $name = "$name.mjs" }
    $script = Join-Path $root (Join-Path 'tools/netmirror-capture' $name)
    if (-not (Test-Path $script)) {
      Write-Host "Unknown target '$Target'. Use 'kidoo', 'capture', or a tools/netmirror-capture/<name>.mjs script." -ForegroundColor Red
      exit 1
    }
    $base = [System.IO.Path]::GetFileNameWithoutExtension($script)
    Invoke-Logged 'node' (@($script) + $Rest) (Join-Path $logDir "$base-$stamp.log")
  }
}
