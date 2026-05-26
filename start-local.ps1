param(
  [int]$BackendPort = 18083,
  [int]$FrontendPort = 5186,
  [int]$FieldOcrPort = 18092,
  [switch]$SkipBuild,
  [switch]$NoSidecar,
  [switch]$KeepExisting
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
$StatePath = Join-Path $Root "tmp\local-services.json"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StatePath) | Out-Null

function Repair-PathEnvironment {
  $pathValue = [Environment]::GetEnvironmentVariable("Path", "Process")
  if ([string]::IsNullOrWhiteSpace($pathValue)) {
    $pathValue = [Environment]::GetEnvironmentVariable("PATH", "Process")
  }
  if (-not [string]::IsNullOrWhiteSpace($pathValue)) {
    [Environment]::SetEnvironmentVariable("PATH", $null, "Process")
    [Environment]::SetEnvironmentVariable("Path", $pathValue, "Process")
  }
}

function Import-CmdSetFile([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) {
    return
  }
  Get-Content -LiteralPath $Path | ForEach-Object {
    $line = $_.Trim()
    if ($line -match '^set\s+"?([^=\s"]+)=(.*)"?\s*$') {
      $name = $matches[1].Trim()
      $value = $matches[2]
      if ($value.EndsWith('"')) {
        $value = $value.Substring(0, $value.Length - 1)
      }
      [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
  }
}

function Stop-ProcessesOnPort([int]$Port) {
  $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  $pids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -and $_ -gt 0 })
  foreach ($processId in $pids) {
    try {
      Stop-Process -Id $processId -Force -ErrorAction Stop
      Write-Host "Stopped process $processId on port $Port"
    } catch {
      Write-Warning "Could not stop process $processId on port ${Port}: $($_.Exception.Message)"
    }
  }
}

function Wait-Http([string]$Name, [string]$Url, [int]$Seconds) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
      if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
        Write-Host "$Name ready: $Url"
        return $true
      }
    } catch {
      Start-Sleep -Seconds 1
    }
  }
  Write-Warning "$Name did not become ready within ${Seconds}s: $Url"
  return $false
}

Repair-PathEnvironment
Import-CmdSetFile (Join-Path $Root "llm.local.cmd")
Import-CmdSetFile (Join-Path $Root "baidu-ocr.local.cmd")

if (-not $env:LLM_API_KEY -and -not $env:DASHSCOPE_API_KEY) {
  throw "Set LLM_API_KEY or DASHSCOPE_API_KEY, or create llm.local.cmd."
}

$env:SERVER_PORT = [string]$BackendPort
$env:FRONTEND_PORT = [string]$FrontendPort
$env:VITE_BACKEND_ORIGIN = "http://127.0.0.1:$BackendPort"
$env:BACKEND_ORIGIN = $env:VITE_BACKEND_ORIGIN
$env:RAG_ENABLED = "false"
$env:FIELD_OCR_ENABLED = "true"
$env:FIELD_OCR_BASE_URL = "http://127.0.0.1:$FieldOcrPort"
$env:FIELD_OCR_PORT = [string]$FieldOcrPort
$env:npm_config_cache = Join-Path $Root "frontend\.npm-cache"

if (-not $KeepExisting) {
  Stop-ProcessesOnPort $FrontendPort
  Stop-ProcessesOnPort $BackendPort
  if (-not $NoSidecar) {
    Stop-ProcessesOnPort $FieldOcrPort
  }
}

if (-not $SkipBuild) {
  Write-Host "Packaging backend jar..."
  Push-Location (Join-Path $Root "backend")
  try {
    & mvn.cmd -DskipTests package
    if ($LASTEXITCODE -ne 0) {
      throw "Backend package failed with exit code $LASTEXITCODE."
    }
  } finally {
    Pop-Location
  }
}

$backendOut = Join-Path $LogDir "backend-$BackendPort-$Stamp.out.log"
$backendErr = Join-Path $LogDir "backend-$BackendPort-$Stamp.err.log"
$frontendOut = Join-Path $LogDir "frontend-$FrontendPort-$Stamp.out.log"
$frontendErr = Join-Path $LogDir "frontend-$FrontendPort-$Stamp.err.log"
$sidecarOut = Join-Path $LogDir "field-ocr-$FieldOcrPort-$Stamp.out.log"
$sidecarErr = Join-Path $LogDir "field-ocr-$FieldOcrPort-$Stamp.err.log"

$sidecarProcess = $null
if (-not $NoSidecar) {
  $sidecarScript = Join-Path $Root "ocr-service\run-dev.cmd"
  if (Test-Path -LiteralPath $sidecarScript) {
    Write-Host "Starting field OCR sidecar on port $FieldOcrPort..."
    $sidecarProcess = Start-Process -FilePath $sidecarScript `
      -WorkingDirectory (Join-Path $Root "ocr-service") `
      -WindowStyle Hidden `
      -RedirectStandardOutput $sidecarOut `
      -RedirectStandardError $sidecarErr `
      -PassThru
  }
}

Write-Host "Starting backend on port $BackendPort..."
$backendProcess = Start-Process -FilePath "java.exe" `
  -ArgumentList @("-jar", "target\baidu-full-page-ocr-backend-0.1.0.jar") `
  -WorkingDirectory (Join-Path $Root "backend") `
  -WindowStyle Hidden `
  -RedirectStandardOutput $backendOut `
  -RedirectStandardError $backendErr `
  -PassThru

Write-Host "Starting frontend on port $FrontendPort..."
$vite = Join-Path $Root "frontend\node_modules\.bin\vite.cmd"
if (-not (Test-Path -LiteralPath $vite)) {
  throw "Vite executable not found. Run npm install in frontend first."
}
$frontendProcess = Start-Process -FilePath $vite `
  -ArgumentList @("--host", "127.0.0.1", "--port", [string]$FrontendPort, "--strictPort") `
  -WorkingDirectory (Join-Path $Root "frontend") `
  -WindowStyle Hidden `
  -RedirectStandardOutput $frontendOut `
  -RedirectStandardError $frontendErr `
  -PassThru

$backendReady = Wait-Http "Backend" "http://127.0.0.1:$BackendPort/api/llm/models" 60
$frontendReady = Wait-Http "Frontend" "http://127.0.0.1:$FrontendPort/" 30
$sidecarReady = $true
if (-not $NoSidecar -and $sidecarProcess) {
  $sidecarReady = Wait-Http "Field OCR sidecar" "http://127.0.0.1:$FieldOcrPort/health" 20
}

$fieldOcrUrl = $null
$fieldOcrOutLog = $null
$fieldOcrErrLog = $null
if (-not $NoSidecar) {
  $fieldOcrUrl = "http://127.0.0.1:$FieldOcrPort"
}
if ($sidecarProcess) {
  $fieldOcrOutLog = $sidecarOut
  $fieldOcrErrLog = $sidecarErr
}

$state = [ordered]@{
  startedAt = (Get-Date).ToString("o")
  frontendUrl = "http://127.0.0.1:$FrontendPort/"
  backendUrl = "http://127.0.0.1:$BackendPort"
  fieldOcrUrl = $fieldOcrUrl
  frontendPid = $frontendProcess.Id
  backendPid = $backendProcess.Id
  fieldOcrPid = $(if ($sidecarProcess) { $sidecarProcess.Id } else { $null })
  logs = [ordered]@{
    backendOut = $backendOut
    backendErr = $backendErr
    frontendOut = $frontendOut
    frontendErr = $frontendErr
    fieldOcrOut = $fieldOcrOutLog
    fieldOcrErr = $fieldOcrErrLog
  }
}
$state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $StatePath -Encoding UTF8

if (-not ($backendReady -and $frontendReady)) {
  Write-Host "Backend log: $backendOut"
  Write-Host "Frontend log: $frontendOut"
  throw "Local services did not start cleanly."
}

Write-Host ""
Write-Host "Local OCR demo is ready."
Write-Host "Frontend: http://127.0.0.1:$FrontendPort/"
Write-Host "Backend : http://127.0.0.1:$BackendPort"
if (-not $NoSidecar) {
  Write-Host "Field OCR sidecar: http://127.0.0.1:$FieldOcrPort (ready=$sidecarReady)"
}
Write-Host "State: $StatePath"
