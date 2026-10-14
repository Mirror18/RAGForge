[CmdletBinding()]
param(
    [string]$ProjectName = "ragforge-p1",
    [int]$ServerPort = 25082,
    [int]$GatewayPort = 25080,
    [int]$WebPort = 25174,
    [switch]$SkipWeb,
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Stop"

$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:RAGFORGE_SCRIPT_ROOT }
if (-not $scriptRoot) {
    throw "Unable to determine the script directory. Run start-local.bat or invoke this .ps1 file directly."
}
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$runtimeDirectory = Join-Path $repoRoot "tmp\local-run"

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

function Assert-NoRunningJarProcess([string]$JarPath, [string]$ApplicationName) {
    $normalizedJarPath = [System.IO.Path]::GetFullPath($JarPath)
    $processes = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$normalizedJarPath*" })
    if ($processes.Count -gt 0) {
        $pids = ($processes | ForEach-Object { $_.ProcessId }) -join ", "
        throw "$ApplicationName 已在运行（PID: $pids）。请先停止现有 JAR 进程，再执行本地启动，避免重复消费或构建产物竞态。"
    }
}

function Wait-ForHttp([string]$Uri, [int]$Attempts = 180, [System.Diagnostics.Process]$Process = $null) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $Uri
            if ($response.StatusCode -eq 200) { return }
        } catch {
            if ($Process -and $Process.HasExited) {
                throw "Process exited before its health endpoint became ready: $Uri (exit code $($Process.ExitCode))"
            }
            if ($attempt -eq $Attempts) { throw "服务未在 $Attempts 秒内就绪：$Uri" }
            Start-Sleep -Seconds 1
        }
    }
}

function Wait-ForLog([string]$LogPath, [string]$Pattern, [int]$Attempts = 90, [System.Diagnostics.Process]$Process = $null) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if (Test-Path -LiteralPath $LogPath) {
            if (Select-String -LiteralPath $LogPath -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue) { return }
        }
        if ($Process -and $Process.HasExited) {
            throw "Process exited before its startup log became ready: $LogPath (exit code $($Process.ExitCode))"
        }
        Start-Sleep -Seconds 1
    }
    throw "服务未在 $Attempts 秒内完成启动：$LogPath"
}

function Wait-ForApplicationPid([string]$LogPath, [string]$ApplicationName, [int]$Attempts = 30) {
    $pattern = "Starting {0}.* using .* with PID (?<pid>\d+)" -f [regex]::Escape($ApplicationName)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if (Test-Path -LiteralPath $LogPath) {
            $match = Select-String -LiteralPath $LogPath -Pattern $pattern -AllMatches -ErrorAction SilentlyContinue | Select-Object -Last 1
            if ($match -and $match.Matches.Count -gt 0) {
                $applicationPid = [int]$match.Matches[0].Groups["pid"].Value
                if (Get-Process -Id $applicationPid -ErrorAction SilentlyContinue) { return $applicationPid }
            }
        }
        Start-Sleep -Seconds 1
    }
    throw "$ApplicationName 未保持运行：$LogPath"
}

function Invoke-MavenPackage([string]$Module, [string]$JarPath, [string]$LogPath) {
    Write-Host "构建 $Module 可执行 JAR（Java 21，完整主编译）..."
    # JAVA_TOOL_OPTIONS prints to stderr; with ErrorActionPreference=Stop that aborts before Maven finishes.
    $previousToolOptions = $env:JAVA_TOOL_OPTIONS
    $previousErrorAction = $ErrorActionPreference
    try {
        Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
        $ErrorActionPreference = "Continue"
        & $maven -f (Join-Path $repoRoot "backend\pom.xml") @mavenJava21Arguments "-pl" $Module "-am" "clean" "compile" "jar:jar" "spring-boot:repackage" *> $LogPath
    } finally {
        $ErrorActionPreference = $previousErrorAction
        if ($null -ne $previousToolOptions -and $previousToolOptions -ne "") {
            $env:JAVA_TOOL_OPTIONS = $previousToolOptions
        }
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "$Module 构建失败，最近日志：" -ForegroundColor Yellow
        Get-Content -LiteralPath $LogPath -Tail 60 -ErrorAction SilentlyContinue
        throw "$Module 构建失败（退出码 $LASTEXITCODE）。"
    }
    if (-not (Test-Path -LiteralPath $JarPath)) {
        throw "$Module 未生成可执行 JAR：$JarPath"
    }
}

Import-LocalEnv (Join-Path $repoRoot ".env.local")
if (-not $env:QDRANT_API_KEY) { $env:QDRANT_API_KEY = "change-me" }
$python = Get-RequiredCommand "python" "请安装 Python 3 并加入 PATH。"
$docker = Get-RequiredCommand "docker" "请安装并启动 Docker Desktop。"
$maven = Get-RequiredCommand "mvn.cmd" "请安装 Maven 并加入 PATH。"
$npm = Get-RequiredCommand "npm.cmd" "请安装 Node.js LTS 并加入 PATH。"
$javaHome = Find-Java21
$java = Join-Path $javaHome "bin\java.exe"

& $docker info --format "{{.ServerVersion}}" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker Engine 不可用，请确认 Docker Desktop 已启动。" }
$portsJson = & $python -c "import json,sys; sys.path.insert(0, sys.argv[1]); from compose_isolation import project_ports; print(json.dumps(project_ports(sys.argv[2])))" $scriptRoot $ProjectName
if ($LASTEXITCODE -ne 0) { throw "无法解析 Compose 端口映射。" }
$ports = $portsJson | ConvertFrom-Json

Assert-PortAvailable $ServerPort "ServerPort"
Assert-PortAvailable $GatewayPort "GatewayPort"
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
    $javaTmpDirectory = Join-Path $runtimeDirectory "jvm-tmp"
    New-Item -ItemType Directory -Force -Path $javaTmpDirectory | Out-Null
    $env:LOGGING_FILE_PATH = $runtimeDirectory
    $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$javaTmpDirectory"
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
    $env:RAGFORGE_OUTBOX_RELAY_ENABLED = "true"
    $env:RAGFORGE_RUN_EVENT_FANOUT_ENABLED = "true"
    $env:RAGFORGE_OPERATIONS_ENABLED = "true"
    $env:RAGFORGE_PHASE6_OPERATIONS_ENABLED = "true"
    $env:SPRING_PROFILES_ACTIVE = "dev"
    $mavenJava21Arguments = @(
        "-Pragforge-isolated-output",
        "-Dmaven.compiler.release=21",
        "-Dmaven.compiler.source=21",
        "-Dmaven.compiler.target=21",
        "-Dmaven.compiler.compilerVersion=21",
        "-Dmaven.compiler.useIncrementalCompilation=false"
    )
    $serverJar = Join-Path $repoRoot "backend\server\target-ragforge\ragforge-server-0.1.0-SNAPSHOT.jar"
    $gatewayJar = Join-Path $repoRoot "backend\gateway\target-ragforge\ragforge-gateway-0.1.0-SNAPSHOT.jar"
    $workerJar = Join-Path $repoRoot "backend\ingestion-worker\target-ragforge\ragforge-ingestion-worker-0.1.0-SNAPSHOT.jar"

    Assert-NoRunningJarProcess $serverJar "Server"
    Assert-NoRunningJarProcess $gatewayJar "Gateway"
    Assert-NoRunningJarProcess $workerJar "Worker"

    Write-Host "[2/5] 启动 Server（完整本地 adapter 配置）..."
    Invoke-MavenPackage "server" $serverJar (Join-Path $runtimeDirectory "server-compile.log")
    # Run the immutable repackaged JAR so IDE/Maven output refreshes cannot
    # replace classes after the build has completed.
    $server = Start-Process -FilePath $java -ArgumentList "-Djava.io.tmpdir=`"$javaTmpDirectory`"", "-jar", "`"$serverJar`"" -WorkingDirectory $runtimeDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "server.log") -RedirectStandardError (Join-Path $runtimeDirectory "server.err.log") -PassThru
    Set-Content -Path (Join-Path $runtimeDirectory "server.pid") -Value $server.Id
    try {
        Wait-ForHttp "http://127.0.0.1:$ServerPort/actuator/health" 180 $server
    } catch {
        Write-Host "Server 最近日志：" -ForegroundColor Yellow
        Get-Content (Join-Path $runtimeDirectory "server.log") -Tail 40 -ErrorAction SilentlyContinue
        Get-Content (Join-Path $runtimeDirectory "server.err.log") -Tail 40 -ErrorAction SilentlyContinue
        throw
    }
    $serverApplicationPid = Wait-ForApplicationPid (Join-Path $runtimeDirectory "server.log") "RagForgeServerApplication"
    Set-Content -Path (Join-Path $runtimeDirectory "server.pid") -Value $serverApplicationPid

    Write-Host "[3/5] 启动 Gateway（浏览器唯一 API 入口）..."
    $env:GATEWAY_PORT = "$GatewayPort"
    $env:RAGFORGE_GATEWAY_UPSTREAM = "http://127.0.0.1:$ServerPort"
    Invoke-MavenPackage "gateway" $gatewayJar (Join-Path $runtimeDirectory "gateway-compile.log")
    $gateway = Start-Process -FilePath $java -ArgumentList "-Djava.io.tmpdir=`"$javaTmpDirectory`"", "-jar", "`"$gatewayJar`"" -WorkingDirectory $runtimeDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "gateway.log") -RedirectStandardError (Join-Path $runtimeDirectory "gateway.err.log") -PassThru
    Set-Content -Path (Join-Path $runtimeDirectory "gateway.pid") -Value $gateway.Id
    try {
        Wait-ForHttp "http://127.0.0.1:$GatewayPort/actuator/health" 120 $gateway
    } catch {
        Write-Host "Gateway 最近日志：" -ForegroundColor Yellow
        Get-Content (Join-Path $runtimeDirectory "gateway.log") -Tail 40 -ErrorAction SilentlyContinue
        Get-Content (Join-Path $runtimeDirectory "gateway.err.log") -Tail 40 -ErrorAction SilentlyContinue
        throw
    }
    $gatewayApplicationPid = Wait-ForApplicationPid (Join-Path $runtimeDirectory "gateway.log") "RagForgeGatewayApplication"
    Set-Content -Path (Join-Path $runtimeDirectory "gateway.pid") -Value $gatewayApplicationPid

    Write-Host "[4/5] 启动 Worker..."
    $env:RAGFORGE_INGESTION_ENABLED = "true"
    $env:RAGFORGE_RABBITMQ_HOST = "127.0.0.1"
    $env:RAGFORGE_RABBITMQ_PORT = "$($ports.RABBITMQ_PORT)"
    $env:RAGFORGE_RABBITMQ_USER = "ragforge"
    $env:RAGFORGE_RABBITMQ_PASSWORD = "change-me"
    Invoke-MavenPackage "ingestion-worker" $workerJar (Join-Path $runtimeDirectory "worker-compile.log")
    $worker = Start-Process -FilePath $java -ArgumentList "-Djava.io.tmpdir=`"$javaTmpDirectory`"", "-jar", "`"$workerJar`"", "--ragforge.ingestion.enabled=true" -WorkingDirectory $runtimeDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "worker.log") -RedirectStandardError (Join-Path $runtimeDirectory "worker.err.log") -PassThru
    Set-Content -Path (Join-Path $runtimeDirectory "worker.pid") -Value $worker.Id
    try {
        Wait-ForLog (Join-Path $runtimeDirectory "worker.log") "Started IngestionWorkerApplication in " 90 $worker
    } catch {
        Write-Host "Worker 最近日志：" -ForegroundColor Yellow
        Get-Content (Join-Path $runtimeDirectory "worker.log") -Tail 40 -ErrorAction SilentlyContinue
        Get-Content (Join-Path $runtimeDirectory "worker.err.log") -Tail 40 -ErrorAction SilentlyContinue
        throw
    }
    $workerApplicationPid = Wait-ForApplicationPid (Join-Path $runtimeDirectory "worker.log") "IngestionWorkerApplication"
    Set-Content -Path (Join-Path $runtimeDirectory "worker.pid") -Value $workerApplicationPid

    if (-not $SkipWeb) {
        Write-Host "启动 Web..."
        if (-not (Test-Path (Join-Path $repoRoot "frontend\ragforge-web\node_modules"))) {
            & $npm --prefix frontend/ragforge-web ci
            if ($LASTEXITCODE -ne 0) { throw "Web 依赖安装失败。" }
        }
        $env:VITE_SERVER_TARGET = "http://127.0.0.1:$GatewayPort"
        $web = Start-Process -FilePath $npm -ArgumentList "--prefix", "frontend/ragforge-web", "run", "dev", "--", "--host", "127.0.0.1", "--port", "$WebPort", "--cacheDir", (Join-Path $runtimeDirectory "vite") -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "web.log") -RedirectStandardError (Join-Path $runtimeDirectory "web.err.log") -PassThru
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

    Write-Host "[5/5] 本地运行环境已就绪。" -ForegroundColor Green
    Write-Host "  Web:       $(if ($SkipWeb) { '已跳过' } else { "http://127.0.0.1:$WebPort" })"
    Write-Host "  Gateway:   http://127.0.0.1:$GatewayPort"
    Write-Host "  Server:    http://127.0.0.1:$ServerPort (仅本机/排障)"
    Write-Host "  Worker:    PID $workerApplicationPid"
    Write-Host "  Health:    http://127.0.0.1:$GatewayPort/actuator/health"
    Write-Host "  RabbitMQ:  http://127.0.0.1:$($ports.RABBITMQ_MANAGEMENT_PORT)"
    Write-Host "  MinIO:     http://127.0.0.1:$($ports.S3_CONSOLE_PORT)"
    Write-Host "  Qdrant:    http://127.0.0.1:$($ports.QDRANT_PORT)/dashboard"
    Write-Host "  Logs:      $runtimeDirectory"
    if ($OpenBrowser -and -not $SkipWeb) { Start-Process "http://127.0.0.1:$WebPort" }
} finally {
    Pop-Location
}
