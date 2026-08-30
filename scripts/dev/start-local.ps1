[CmdletBinding()]
param(
    [string]$ProjectName = "ragforge-p1",
    [int]$ServerPort = 25082,
    [int]$WebPort = 25174,
    [switch]$SkipWeb,
    [switch]$SkipModelCheck,
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Stop"

$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:RAGFORGE_SCRIPT_ROOT }
if (-not $scriptRoot) {
    throw "Unable to determine the script directory. Run start-local.bat or invoke this .ps1 file directly."
}
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$runtimeDirectory = Join-Path $repoRoot "tmp\local-run"
$requiredOllamaModels = @("qwen3.5:9b", "nomic-embed-text:latest")

function Get-RequiredCommand([string]$Name, [string]$InstallHint) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "未找到 $Name。$InstallHint" }
    return $command.Source
}

function Import-LocalEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { continue }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

function Find-Java21 {
    $candidates = @($env:JAVA_HOME, "C:\Program Files\Java\jdk-21")
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $candidateJava = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path $candidateJava)) { continue }
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $candidateVersion = (& $candidateJava -version 2>&1 | Out-String)
        $ErrorActionPreference = $previousErrorActionPreference
        if ($candidateVersion -like "*21.*") { return $candidate }
    }
    throw "未找到 Java 21。请设置 JAVA_HOME 为 JDK 21 安装目录。"
}

function Assert-PortAvailable([int]$Port, [string]$ParameterName) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) { throw "端口 $Port 已被占用。请停止现有服务或通过 -$ParameterName 选择其他端口。" }
}

function Wait-ForHttp([string]$Uri, [int]$Attempts = 180, [System.Diagnostics.Process]$Process = $null) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if ($Process -and $Process.HasExited) {
            throw "Process exited before its health endpoint became ready: $Uri (exit code $($Process.ExitCode))"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $Uri
            if ($response.StatusCode -eq 200) { return }
        } catch {
            if ($attempt -eq $Attempts) { throw "服务未在 $Attempts 秒内就绪：$Uri" }
            Start-Sleep -Seconds 1
        }
    }
}

function Assert-OllamaModels {
    try {
        $response = Invoke-RestMethod -TimeoutSec 5 -Uri "http://127.0.0.1:11434/api/tags"
    } catch {
        throw "本机 Ollama 未运行（http://127.0.0.1:11434）。请先启动 Ollama；项目不会静默切换到云模型。"
    }
    $installed = @($response.models | ForEach-Object { $_.name })
    $missing = @($requiredOllamaModels | Where-Object { $_ -notin $installed })
    if ($missing.Count -gt 0) {
        $pullCommands = ($missing | ForEach-Object { "ollama pull $_" }) -join [Environment]::NewLine
        throw "缺少本地模型：$($missing -join ', ')。请先执行：$([Environment]::NewLine)$pullCommands"
    }
}

Import-LocalEnv (Join-Path $repoRoot ".env.local")
if (-not $env:QDRANT_API_KEY) { $env:QDRANT_API_KEY = "change-me" }
$python = Get-RequiredCommand "python" "请安装 Python 3 并加入 PATH。"
$docker = Get-RequiredCommand "docker" "请安装并启动 Docker Desktop。"
$maven = Get-RequiredCommand "mvn.cmd" "请安装 Maven 并加入 PATH。"
$npm = Get-RequiredCommand "npm.cmd" "请安装 Node.js LTS 并加入 PATH。"
$javaHome = Find-Java21

& $docker info --format "{{.ServerVersion}}" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker Engine 不可用，请确认 Docker Desktop 已启动。" }
if (-not $SkipModelCheck) { Assert-OllamaModels }

$portsJson = & $python -c "import json,sys; sys.path.insert(0, sys.argv[1]); from compose_isolation import project_ports; print(json.dumps(project_ports(sys.argv[2])))" $scriptRoot $ProjectName
if ($LASTEXITCODE -ne 0) { throw "无法解析 Compose 端口映射。" }
$ports = $portsJson | ConvertFrom-Json

Assert-PortAvailable $ServerPort "ServerPort"
if (-not $SkipWeb) { Assert-PortAvailable $WebPort "WebPort" }

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

Push-Location $repoRoot
try {
    Write-Host "[1/4] 启动 Docker core（PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO）..."
    & $python scripts/dev/core.py --project-name $ProjectName up
    if ($LASTEXITCODE -ne 0) { throw "Compose core 启动失败。" }
    & $python scripts/dev/core.py --project-name $ProjectName health
    if ($LASTEXITCODE -ne 0) { throw "Compose core 健康检查失败。" }

    New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
    $env:SERVER_PORT = "$ServerPort"
    $env:JDBC_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($ports.POSTGRES_PORT)/ragforge"
    $env:JDBC_DATABASE_USERNAME = "ragforge"
    $env:JDBC_DATABASE_PASSWORD = "change-me"
    $env:VALKEY_URL = "redis://:change-me@127.0.0.1:$($ports.VALKEY_PORT)"
    $env:SPRING_RABBITMQ_HOST = "127.0.0.1"
    $env:SPRING_RABBITMQ_PORT = "$($ports.RABBITMQ_PORT)"
    $env:SPRING_RABBITMQ_USERNAME = "ragforge"
    $env:SPRING_RABBITMQ_PASSWORD = "change-me"
    $env:RAGFORGE_RABBIT_HEALTH_ENABLED = "true"
    $env:RAGFORGE_OBJECT_STORAGE_ENABLED = "true"
    $env:S3_ENDPOINT = "http://127.0.0.1:$($ports.S3_PORT)"
    $env:S3_ACCESS_KEY = "ragforge"
    $env:S3_SECRET_KEY = "change-me-minio-secret"
    $env:S3_BUCKET = "ragforge"
    $env:S3_PREFIX = "local"
    $env:QDRANT_URL = "http://127.0.0.1:$($ports.QDRANT_PORT)"
    $env:OLLAMA_ENDPOINT = "http://127.0.0.1:11434"
    $env:RAGFORGE_OUTBOX_RELAY_ENABLED = "true"
    $env:RAGFORGE_RUN_EVENT_FANOUT_ENABLED = "true"
    $env:RAGFORGE_PHASE6_OPERATIONS_ENABLED = "true"

    Write-Host "[2/4] 启动 Server（完整本地 adapter 配置）..."
    $server = Start-Process -FilePath $maven -ArgumentList "-pl", "apps/server", "spring-boot:run" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "server.log") -RedirectStandardError (Join-Path $runtimeDirectory "server.err.log") -PassThru
    Set-Content -Path (Join-Path $runtimeDirectory "server.pid") -Value $server.Id
    try {
        Wait-ForHttp "http://127.0.0.1:$ServerPort/actuator/health" 180 $server
    } catch {
        Write-Host "Server 最近日志：" -ForegroundColor Yellow
        Get-Content (Join-Path $runtimeDirectory "server.log") -Tail 40 -ErrorAction SilentlyContinue
        Get-Content (Join-Path $runtimeDirectory "server.err.log") -Tail 40 -ErrorAction SilentlyContinue
        throw
    }

    Write-Host "[3/4] 启动 Worker..."
    $env:RAGFORGE_INGESTION_ENABLED = "true"
    $env:RAGFORGE_RABBITMQ_HOST = "127.0.0.1"
    $env:RAGFORGE_RABBITMQ_PORT = "$($ports.RABBITMQ_PORT)"
    $env:RAGFORGE_RABBITMQ_USER = "ragforge"
    $env:RAGFORGE_RABBITMQ_PASSWORD = "change-me"
    $worker = Start-Process -FilePath $maven -ArgumentList "-pl", "apps/ingestion-worker", "spring-boot:run" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "worker.log") -RedirectStandardError (Join-Path $runtimeDirectory "worker.err.log") -PassThru
    Set-Content -Path (Join-Path $runtimeDirectory "worker.pid") -Value $worker.Id

    if (-not $SkipWeb) {
        Write-Host "启动 Web..."
        if (-not (Test-Path (Join-Path $repoRoot "apps\web\node_modules"))) {
            & $npm --prefix apps/web ci
            if ($LASTEXITCODE -ne 0) { throw "Web 依赖安装失败。" }
        }
        $env:VITE_SERVER_TARGET = "http://127.0.0.1:$ServerPort"
        $web = Start-Process -FilePath $npm -ArgumentList "--prefix", "apps/web", "run", "dev", "--", "--host", "127.0.0.1", "--port", "$WebPort" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "web.log") -RedirectStandardError (Join-Path $runtimeDirectory "web.err.log") -PassThru
        Set-Content -Path (Join-Path $runtimeDirectory "web.pid") -Value $web.Id
        try {
            Wait-ForHttp "http://127.0.0.1:$WebPort/" 90 $web
        } catch {
            Write-Host "Web 最近日志：" -ForegroundColor Yellow
            Get-Content (Join-Path $runtimeDirectory "web.log") -Tail 40 -ErrorAction SilentlyContinue
            Get-Content (Join-Path $runtimeDirectory "web.err.log") -Tail 40 -ErrorAction SilentlyContinue
            throw
        }
    } else {
        Write-Host "已按参数跳过 Web。"
    }

    Write-Host "[4/4] 本地运行环境已就绪。" -ForegroundColor Green
    Write-Host "  Web:       $(if ($SkipWeb) { '已跳过' } else { "http://127.0.0.1:$WebPort" })"
    Write-Host "  Server:    http://127.0.0.1:$ServerPort"
    Write-Host "  Worker:    PID $($worker.Id)"
    Write-Host "  Health:    http://127.0.0.1:$ServerPort/actuator/health"
    Write-Host "  RabbitMQ:  http://127.0.0.1:$($ports.RABBITMQ_MANAGEMENT_PORT)"
    Write-Host "  MinIO:     http://127.0.0.1:$($ports.S3_CONSOLE_PORT)"
    Write-Host "  Qdrant:    http://127.0.0.1:$($ports.QDRANT_PORT)/dashboard"
    Write-Host "  Ollama:    http://127.0.0.1:11434"
    Write-Host "  Logs:      $runtimeDirectory"
    if ($OpenBrowser -and -not $SkipWeb) { Start-Process "http://127.0.0.1:$WebPort" }
} finally {
    Pop-Location
}

