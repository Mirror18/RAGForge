[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Email,
    [string]$DisplayName = "Platform Administrator",
    [string]$ProjectName = "ragforge-p1"
)

$ErrorActionPreference = "Stop"

$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:RAGFORGE_SCRIPT_ROOT }
if (-not $scriptRoot) {
    throw "Unable to determine the script directory. Run create-platform-admin.bat or invoke this .ps1 directly."
}
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$serverJar = Join-Path $repoRoot "backend\server\target-ragforge\ragforge-server-0.1.0-SNAPSHOT.jar"

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

Import-LocalEnv (Join-Path $repoRoot ".env.local")
if (-not $env:RAGFORGE_ADMIN_CLI_PASSWORD -or $env:RAGFORGE_ADMIN_CLI_PASSWORD.Length -lt 12) {
    throw "请设置环境变量 RAGFORGE_ADMIN_CLI_PASSWORD（至少 12 位），不要在命令行参数中传递密码。"
}

$javaHome = Find-Java21
$java = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $serverJar)) {
    throw "未找到 Server JAR：$serverJar。请先运行 scripts/dev/restart-local.bat 或 mvn -f backend/pom.xml -pl server -Pragforge-isolated-output package。"
}

if (-not $env:JDBC_DATABASE_URL) {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { throw "未设置 JDBC_DATABASE_URL，且未找到 python 以解析 Compose 端口。" }
    $portsJson = & python -c "import json,sys; sys.path.insert(0, sys.argv[1]); from compose_isolation import project_ports; print(json.dumps(project_ports(sys.argv[2])))" $scriptRoot $ProjectName
    if ($LASTEXITCODE -ne 0) { throw "无法解析 Compose 端口映射。" }
    $ports = $portsJson | ConvertFrom-Json
    $env:JDBC_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($ports.POSTGRES_PORT)/ragforge"
    $env:JDBC_DATABASE_USERNAME = "ragforge"
    $env:JDBC_DATABASE_PASSWORD = "change-me"
    $env:VALKEY_URL = "redis://:change-me@127.0.0.1:$($ports.VALKEY_PORT)"
}

& $java -jar $serverJar `
    --ragforge-create-platform-admin `
    --ragforge-admin-email=$Email `
    --ragforge-admin-display-name=$DisplayName
if ($LASTEXITCODE -ne 0) { throw "平台管理员 CLI 失败（退出码 $LASTEXITCODE）。" }
Write-Host "平台管理员已创建：$Email"
