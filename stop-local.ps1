param(
  [int[]]$Ports = @(5186, 18083, 18092)
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$StatePath = Join-Path $Root "tmp\local-services.json"

function Stop-ProcessId([int]$ProcessId) {
  if ($ProcessId -le 0) {
    return
  }
  try {
    $process = Get-Process -Id $ProcessId -ErrorAction Stop
    Stop-Process -Id $process.Id -Force -ErrorAction Stop
    Write-Host "Stopped PID $ProcessId"
  } catch {
  }
}

function Stop-ProcessesOnPort([int]$Port) {
  $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  $pids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -and $_ -gt 0 })
  foreach ($processId in $pids) {
    Stop-ProcessId $processId
  }
}

if (Test-Path -LiteralPath $StatePath) {
  try {
    $state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
    if ($state.frontendPid) { Stop-ProcessId ([int]$state.frontendPid) }
    if ($state.backendPid) { Stop-ProcessId ([int]$state.backendPid) }
    if ($state.fieldOcrPid) { Stop-ProcessId ([int]$state.fieldOcrPid) }
  } catch {
    Write-Warning "Could not read ${StatePath}: $($_.Exception.Message)"
  }
}

foreach ($port in $Ports) {
  Stop-ProcessesOnPort $port
}

Write-Host "Local OCR demo services stopped."
