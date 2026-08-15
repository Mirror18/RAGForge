[CmdletBinding()]
param(
    [string]$ProjectName = "ragforge-p1",
    [int]$ServerPort = 18082,
    [int]$WebPort = 5174,
    [switch]$SkipWeb
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$python = (Get-Command python -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$javaHome = $null
foreach ($candidate in @($env:JAVA_HOME, "C:\Program Files\Java\jdk-21")) {
    if (-not $candidate) { continue }
    $candidateJava = Join-Path $candidate "bin\java.exe"
    if (-not (Test-Path $candidateJava)) { continue }
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $candidateVersion = (& $candidateJava -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorActionPreference
    if ($candidateVersion -like "*21.*") {
        $javaHome = $candidate
        break
    }
}

if (-not $javaHome) {
    throw "未找到 Java 21。请设置 JAVA_HOME 为 JDK 21 安装目录。"
}

function Assert-PortAvailable([int]$Port, [string]$Name) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "$Name 端口 $Port 已被占用。请停止现有服务或通过 -$Name`Port 选择其他端口。"
    }
}

function Wait-ForHttp([string]$Uri, [int]$Attempts = 30) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $Uri
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            if ($attempt -eq $Attempts) {
                throw "服务未在 $Attempts 秒内就绪：$Uri"
            }
            Start-Sleep -Seconds 1
        }
    }
}

$portsJson = & $python -c "import json,sys; sys.path.insert(0, sys.argv[1]); from compose_isolation import project_ports; print(json.dumps(project_ports(sys.argv[2])))" $PSScriptRoot $ProjectName
if ($LASTEXITCODE -ne 0) {
    throw "无法解析 Compose 端口映射。"
}
$ports = $portsJson | ConvertFrom-Json

Assert-PortAvailable $ServerPort "Server"
if (-not $SkipWeb) {
    Assert-PortAvailable $WebPort "Web"
}

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

Push-Location $repoRoot
try {
    & $python scripts/dev/core.py --project-name $ProjectName up
    if ($LASTEXITCODE -ne 0) { throw "Compose core 启动失败。" }
    & $python scripts/dev/core.py --project-name $ProjectName health
    if ($LASTEXITCODE -ne 0) { throw "Compose core 健康检查失败。" }

    $runtimeDirectory = Join-Path $repoRoot "tmp\local-run"
    New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null

    $env:SERVER_PORT = "$ServerPort"
    $env:JDBC_DATABASE_URL = "jdbc:postgresql://localhost:$($ports.POSTGRES_PORT)/ragforge"
    $env:JDBC_DATABASE_USERNAME = "ragforge"
    $env:JDBC_DATABASE_PASSWORD = "change-me"
    $env:VALKEY_URL = "redis://:change-me@localhost:$($ports.VALKEY_PORT)"
    $env:SPRING_RABBITMQ_HOST = "localhost"
    $env:SPRING_RABBITMQ_PORT = "$($ports.RABBITMQ_PORT)"
    $env:SPRING_RABBITMQ_USERNAME = "ragforge"
    $env:SPRING_RABBITMQ_PASSWORD = "change-me"
    $server = Start-Process -FilePath $maven -ArgumentList "-pl", "apps/server", "spring-boot:run" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "server.log") -RedirectStandardError (Join-Path $runtimeDirectory "server.err.log") -PassThru
    Wait-ForHttp "http://127.0.0.1:$ServerPort/actuator/health"

    if (-not $SkipWeb) {
        $env:VITE_SERVER_TARGET = "http://127.0.0.1:$ServerPort"
        $web = Start-Process -FilePath $npm -ArgumentList "--prefix", "apps/web", "run", "dev", "--", "--host", "127.0.0.1", "--port", "$WebPort" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory "web.log") -RedirectStandardError (Join-Path $runtimeDirectory "web.err.log") -PassThru
        Wait-ForHttp "http://127.0.0.1:$WebPort/"
    }

    Write-Host "RAGForge 已启动。"
    Write-Host "  Server: http://127.0.0.1:$ServerPort"
    Write-Host "  Health: http://127.0.0.1:$ServerPort/actuator/health"
    if (-not $SkipWeb) { Write-Host "  Web:    http://127.0.0.1:$WebPort" }
    Write-Host "  Logs:   $runtimeDirectory"
} finally {
    Pop-Location
}
