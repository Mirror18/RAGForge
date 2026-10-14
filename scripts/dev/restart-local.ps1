[CmdletBinding()]
param(
    [string]$ProjectName = "ragforge-p1",
    [int]$ServerPort = 25082,
    [int]$GatewayPort = 25080,
    [int]$WebPort = 25174,
    [switch]$SkipWeb,
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Continue"
$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:RAGFORGE_SCRIPT_ROOT }
if (-not $scriptRoot) {
    throw "Unable to determine the script directory. Run restart-local.bat or invoke this .ps1 file directly."
}
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$runtimeDirectory = Join-Path $repoRoot "tmp\local-run"
$serverJar = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "backend\server\target-ragforge\ragforge-server-0.1.0-SNAPSHOT.jar"))
$gatewayJar = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "backend\gateway\target-ragforge\ragforge-gateway-0.1.0-SNAPSHOT.jar"))
$workerJar = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "backend\ingestion-worker\target-ragforge\ragforge-ingestion-worker-0.1.0-SNAPSHOT.jar"))

function Stop-PidFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($raw in Get-Content -LiteralPath $Path) {
        $processId = 0
        if ([int]::TryParse("$raw".Trim(), [ref]$processId) -and $processId -gt 0) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
}

function Stop-JavaJar([string]$JarPath) {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$JarPath*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Stop-Listeners([int[]]$Ports) {
    foreach ($port in $Ports) {
        Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    }
}

Write-Host "停止本地 Server / Gateway / Worker / Web..."
Stop-PidFile (Join-Path $runtimeDirectory "server.pid")
Stop-PidFile (Join-Path $runtimeDirectory "gateway.pid")
Stop-PidFile (Join-Path $runtimeDirectory "worker.pid")
Stop-PidFile (Join-Path $runtimeDirectory "web.pid")
Stop-JavaJar $serverJar
Stop-JavaJar $gatewayJar
Stop-JavaJar $workerJar
$portsToFree = @($ServerPort, $GatewayPort)
if (-not $SkipWeb) { $portsToFree += $WebPort }
Stop-Listeners $portsToFree
Start-Sleep -Seconds 1

$forward = @{
    ProjectName = $ProjectName
    ServerPort = $ServerPort
    GatewayPort = $GatewayPort
    WebPort = $WebPort
}
if ($SkipWeb) { $forward.SkipWeb = $true }
if ($OpenBrowser) { $forward.OpenBrowser = $true }
& (Join-Path $scriptRoot "start-local.ps1") @forward
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
