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
    throw "Java 21 not found. Set JAVA_HOME to a JDK 21 install directory."
}

Import-LocalEnv (Join-Path $repoRoot ".env.local")
if (-not $env:RAGFORGE_ADMIN_CLI_PASSWORD -or $env:RAGFORGE_ADMIN_CLI_PASSWORD.Length -lt 12) {
    throw "Set RAGFORGE_ADMIN_CLI_PASSWORD (at least 12 chars). Do not pass the password on the command line."
}

$javaHome = Find-Java21
$java = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $serverJar)) {
    throw "Server JAR not found: $serverJar. Run scripts/dev/restart-local.bat or mvn -f backend/pom.xml -pl server -Pragforge-isolated-output package first."
}

if (-not $env:JDBC_DATABASE_URL) {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { throw "JDBC_DATABASE_URL is unset and python was not found to resolve Compose ports." }
    $portsJson = & python -c "import json,sys; sys.path.insert(0, sys.argv[1]); from compose_isolation import project_ports; print(json.dumps(project_ports(sys.argv[2])))" $scriptRoot $ProjectName
    if ($LASTEXITCODE -ne 0) { throw "Failed to resolve Compose port mapping." }
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
if ($LASTEXITCODE -ne 0) { throw "Platform admin CLI failed (exit code $LASTEXITCODE)." }
Write-Host "Platform administrator created: $Email"
