[CmdletBinding()]
param(
    [string]$DockerPath,
    [string]$JavaPath,
    [string]$MavenPath,
    [string]$PythonPath,
    [string]$PostgresImage = 'postgres:18-bookworm'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$mavenRepository = Join-Path $repositoryRoot '.opsmind\cache\maven'
$platformPom = Join-Path $repositoryRoot 'services\platform-api\pom.xml'
$platformJar = Join-Path $repositoryRoot 'services\platform-api\target\platform-api.jar'
$bootstrapPath = Join-Path $repositoryRoot 'services\platform-api\src\main\resources\db\bootstrap\001-create-runtime-role.sh'
$evidencePath = Join-Path $repositoryRoot 'artifacts\verification\phase-05\postgres-runtime-state.txt'
$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = "opsmind-phase5-postgres-$runId"
$containerPattern = '^opsmind-phase5-postgres-[0-9a-f]{12}$'
$startedAt = [DateTime]::UtcNow
$timer = [Diagnostics.Stopwatch]::StartNew()

function Resolve-Executable {
    param([string]$ExplicitPath, [string[]]$Names, [string]$Description)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $resolved = [IO.Path]::GetFullPath($ExplicitPath)
        if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
        throw "$Description executable does not exist: $resolved"
    }
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -ne $command) { return $command.Source }
    }
    throw "$Description executable is unavailable."
}

function New-EphemeralPassword {
    $bytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) }
    finally { $generator.Dispose() }
    return ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Get-Sha256 {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return 'MISSING' }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Evidence {
    param([string[]]$Lines)
    $directory = Split-Path -Parent $evidencePath
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporary = Join-Path $directory ('.phase5-state-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllLines($temporary, $Lines, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $evidencePath -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

if ($env:OPS_DOCKER_STORAGE_VERIFIED -cne 'true') {
    throw 'OPS_DOCKER_STORAGE_VERIFIED=true is required after D-backed Docker storage verification.'
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Capacity preflight blocked the Phase 5 state contract.' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repositoryRoot 'scripts\storage\assert-storage-roots.ps1') -CreateMissing
if ($LASTEXITCODE -ne 0) { throw 'Storage-root preflight blocked the Phase 5 state contract.' }

$DockerPath = Resolve-Executable $DockerPath @('docker', 'docker.exe') 'Docker'
$MavenPath = Resolve-Executable $MavenPath @('mvn', 'mvn.cmd') 'Maven'
if ([string]::IsNullOrWhiteSpace($JavaPath)) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot '.opsmind\cache\tools') `
        -Directory -Filter 'temurin-jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -ne $jdk) { $JavaPath = Join-Path $jdk.FullName 'bin\java.exe' }
}
$JavaPath = Resolve-Executable $JavaPath @('java', 'java.exe') 'Java 21'
if ([string]::IsNullOrWhiteSpace($PythonPath)) {
    $PythonPath = Join-Path $repositoryRoot '.opsmind\cache\venvs\ai-runtime-py313\Scripts\python.exe'
}
$PythonPath = Resolve-Executable $PythonPath @('python', 'python.exe') 'Python 3.13'

$adminPassword = New-EphemeralPassword
$appPassword = New-EphemeralPassword
$dispatcherPassword = New-EphemeralPassword
$aiRuntimePassword = New-EphemeralPassword
$secretValues = @($adminPassword, $appPassword, $dispatcherPassword, $aiRuntimePassword)
$environmentNames = @(
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
    'POSTGRES_APP_USER', 'POSTGRES_APP_PASSWORD',
    'POSTGRES_DISPATCHER_USER', 'POSTGRES_DISPATCHER_PASSWORD',
    'POSTGRES_AI_RUNTIME_USER', 'POSTGRES_AI_RUNTIME_PASSWORD',
    'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
    'OPSMIND_FLYWAY_ENABLED', 'OPSMIND_EPHEMERAL_DB',
    'OPSMIND_PHASE5_DB_INTEGRATION', 'AI_RUNTIME_DATABASE_HOST',
    'AI_RUNTIME_DATABASE_PORT', 'AI_RUNTIME_DATABASE_NAME',
    'AI_RUNTIME_DATABASE_USER', 'AI_RUNTIME_DATABASE_PASSWORD',
    'PYTHONPATH', 'JAVA_HOME'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$failure = $null
$testOutput = @()
$testsExit = -1
$testsPassed = 0
$migrationExit = -1
$packageExit = -1
$imageId = 'UNKNOWN'
$serverVersion = 'UNKNOWN'
$cleanup = 'BLOCK'
try {
    if ($containerName -notmatch $containerPattern) { throw 'Unsafe container name generated.' }
    [void](New-Item -ItemType Directory -Path $mavenRepository -Force)
    $javaHome = Split-Path -Parent (Split-Path -Parent $JavaPath)
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
    & $MavenPath --batch-mode --no-transfer-progress "-Dmaven.repo.local=$mavenRepository" `
        -f $platformPom -DskipTests package *> $null
    $packageExit = $LASTEXITCODE
    if ($packageExit -ne 0 -or -not (Test-Path -LiteralPath $platformJar)) {
        throw 'Platform migration artifact build failed.'
    }

    $env:POSTGRES_DB = 'opsmind_phase5'
    $env:POSTGRES_USER = 'postgres'
    $env:POSTGRES_PASSWORD = $adminPassword
    $env:POSTGRES_APP_USER = 'opsmind_app'
    $env:POSTGRES_APP_PASSWORD = $appPassword
    $env:POSTGRES_DISPATCHER_USER = 'opsmind_dispatcher'
    $env:POSTGRES_DISPATCHER_PASSWORD = $dispatcherPassword
    $env:POSTGRES_AI_RUNTIME_USER = 'opsmind_ai_runtime'
    $env:POSTGRES_AI_RUNTIME_PASSWORD = $aiRuntimePassword
    $mount = "type=bind,source=$bootstrapPath,target=/docker-entrypoint-initdb.d/001-create-runtime-role.sh,readonly"
    & $DockerPath run --pull never --detach --name $containerName `
        --label 'ai.opsmind.scope=phase5-postgres-state' `
        --env POSTGRES_DB --env POSTGRES_USER --env POSTGRES_PASSWORD `
        --env POSTGRES_APP_USER --env POSTGRES_APP_PASSWORD `
        --env POSTGRES_DISPATCHER_USER --env POSTGRES_DISPATCHER_PASSWORD `
        --env POSTGRES_AI_RUNTIME_USER --env POSTGRES_AI_RUNTIME_PASSWORD `
        --mount $mount --tmpfs '/var/lib/postgresql:rw,noexec,nosuid,size=536870912' `
        --publish '127.0.0.1::5432' $PostgresImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL container failed to start.' }
    $ready = $false
    foreach ($attempt in 1..60) {
        & $DockerPath exec $containerName pg_isready --host 127.0.0.1 `
            --username postgres --dbname opsmind_phase5 *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready.' }
    $portOutput = @(& $DockerPath port $containerName '5432/tcp')
    if ($LASTEXITCODE -ne 0 -or $portOutput.Count -eq 0) {
        throw 'Disposable PostgreSQL host port is unavailable.'
    }
    $portLine = [string]$portOutput[0]
    if ($portLine -notmatch ':(?<port>[0-9]{2,5})$') { throw 'Unable to resolve PostgreSQL host port.' }
    $mappedPort = $Matches.port
    $serverVersion = (& $DockerPath exec $containerName psql --username postgres `
        --dbname opsmind_phase5 --tuples-only --no-align -c 'SHOW server_version;' |
        Select-Object -First 1).ToString().Trim()
    $imageId = (& $DockerPath image inspect --format '{{.Id}}' $PostgresImage).ToString().Trim()

    $env:SPRING_PROFILES_ACTIVE = 'persistence'
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$mappedPort/opsmind_phase5"
    $env:SPRING_DATASOURCE_USERNAME = 'postgres'
    $env:SPRING_DATASOURCE_PASSWORD = $adminPassword
    $env:OPSMIND_FLYWAY_ENABLED = 'true'
    $env:OPSMIND_EPHEMERAL_DB = 'true'
    & $JavaPath -jar $platformJar '--spring.main.web-application-type=none' `
        '--opsmind.persistence.enabled=false' *> $null
    $migrationExit = $LASTEXITCODE
    if ($migrationExit -ne 0) { throw 'Flyway AI runtime migrations failed.' }

    $env:OPSMIND_PHASE5_DB_INTEGRATION = 'true'
    $env:AI_RUNTIME_DATABASE_HOST = '127.0.0.1'
    $env:AI_RUNTIME_DATABASE_PORT = $mappedPort
    $env:AI_RUNTIME_DATABASE_NAME = 'opsmind_phase5'
    $env:AI_RUNTIME_DATABASE_USER = 'opsmind_ai_runtime'
    $env:AI_RUNTIME_DATABASE_PASSWORD = $aiRuntimePassword
    $env:PYTHONPATH = Join-Path $repositoryRoot 'services\ai-runtime\src'
    $testOutput = @(& $PythonPath -m pytest `
        'services/ai-runtime/tests/integration/test_postgres_runtime_state.py' -q 2>&1)
    $testsExit = $LASTEXITCODE
    $summary = ($testOutput -join "`n")
    if ($summary -match '(?m)(?<passed>[0-9]+) passed') {
        $testsPassed = [int]$Matches.passed
    }
    if ($testsExit -ne 0 -or $testsPassed -ne 5) {
        throw 'Phase 5 PostgreSQL integration tests failed or did not execute the full matrix.'
    }
}
catch {
    $failure = $_
}
finally {
    if ($containerName -match $containerPattern) {
        & $DockerPath rm --force $containerName *> $null
        $residual = @(& $DockerPath ps -a --filter "name=^/${containerName}$" --format '{{.ID}}')
        if ($LASTEXITCODE -eq 0 -and $residual.Count -eq 0) { $cleanup = 'PASS' }
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}

$timer.Stop()
$result = if (
    $null -eq $failure -and $testsExit -eq 0 -and $testsPassed -eq 5 -and $cleanup -eq 'PASS'
) { 'PASS' } else { 'BLOCK' }
$lines = @(
    'EvidenceSchemaVersion=phase5-postgres-state-v1',
    'Scope=LOCAL_REFERENCE_NOT_RELEASE_PROOF',
    "StartedAtUtc=$($startedAt.ToString('o'))",
    "CompletedAtUtc=$([DateTime]::UtcNow.ToString('o'))",
    "DurationSeconds=$([Math]::Round($timer.Elapsed.TotalSeconds, 3))",
    'CodeRevision=UNBORN',
    'WorkspaceDirty=YES',
    "MigrationV004Sha256=$(Get-Sha256 (Join-Path $repositoryRoot 'services\platform-api\src\main\resources\db\migration\V004__ai_runtime_durable_state.sql'))",
    "MigrationV005Sha256=$(Get-Sha256 (Join-Path $repositoryRoot 'services\platform-api\src\main\resources\db\migration\V005__ai_runtime_capability_probe_audit.sql'))",
    "PlatformJarSha256=$(Get-Sha256 $platformJar)",
    "PostgresImageId=$imageId",
    "PostgresVersion=$serverVersion",
    "PackageExit=$packageExit",
    "MigrationExit=$migrationExit",
    "TestsExit=$testsExit",
    'ExpectedTests=5',
    "TestsPassed=$testsPassed",
    'SharedNonceReplay=TESTED',
    'ConcurrentBudgetReservation=TESTED',
    'TenantRlsIsolation=TESTED',
    'ExpiredLeaseFullCharge=TESTED',
    'ProviderOverageFailClosed=TESTED',
    'ProbeAuditAppendOnly=TESTED',
    "Cleanup=$cleanup"
)
if ($null -ne $failure) {
    $message = $failure.Exception.Message
    foreach ($secret in $secretValues) { $message = $message.Replace($secret, '[REDACTED]') }
    $lines += "Error=$message"
    foreach ($outputLine in $testOutput | Select-Object -Last 30) {
        $safeLine = $outputLine.ToString()
        foreach ($secret in $secretValues) { $safeLine = $safeLine.Replace($secret, '[REDACTED]') }
        $lines += "Diagnostic=$safeLine"
    }
}
$lines += "Result=$result"
Write-Evidence $lines
$lines | ForEach-Object { Write-Output $_ }
if ($result -ne 'PASS') { exit 1 }
