[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help', 'setup', 'dev', 'test', 'lint', 'build', 'up', 'down', 'migrate', 'seed', 'evaluate', 'security', 'security-scan', 'doctor')]
    [string]$CommandName = 'help',
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Set-Location -LiteralPath $repositoryRoot
$safeEnvironmentNames = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
foreach ($name in @(
    'OPS_CACHE_ROOT', 'OPS_ARTIFACT_ROOT', 'OPS_DATA_ROOT', 'OPS_MODEL_ROOT',
    'OPS_MIN_C_FREE_GB', 'OPS_MIN_D_FREE_GB', 'OPS_MIN_WORKSPACE_FREE_GB',
    'OPERATOR_WEB_PORT', 'PLATFORM_API_PORT', 'OPSMIND_MAX_JSON_BODY_BYTES',
    'OPSMIND_AI_RUNTIME_CLIENT_ENABLED', 'OPSMIND_AI_RUNTIME_ENDPOINT',
    'OPSMIND_AI_RUNTIME_ALLOW_LOCAL_CLEARTEXT', 'OPSMIND_AI_RUNTIME_CONNECT_TIMEOUT',
    'OPSMIND_AI_RUNTIME_REQUEST_TIMEOUT', 'OPSMIND_AI_RUNTIME_MAX_RESPONSE_BODY_BYTES',
    'AI_RUNTIME_MAX_JSON_BODY_BYTES', 'AI_RUNTIME_BODY_RECEIVE_TIMEOUT_SECONDS',
    'AI_RUNTIME_PORT', 'TOOL_GATEWAY_PORT', 'PROMETHEUS_PORT',
    'POSTGRES_PORT', 'REDIS_PORT', 'MINIO_API_PORT', 'MINIO_CONSOLE_PORT',
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_APP_USER', 'POSTGRES_DISPATCHER_USER',
    'POSTGRES_AI_RUNTIME_USER', 'POSTGRES_TOOL_GATEWAY_MIGRATOR_USER',
    'POSTGRES_TOOL_GATEWAY_USER',
    'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME', 'OPSMIND_FLYWAY_ENABLED', 'OPSMIND_DB_POOL_MAX',
    'OPSMIND_DB_CONNECTION_TIMEOUT_MS', 'MINIO_ROOT_USER', 'MINIO_IMAGE',
    'OIDC_ISSUER_URL', 'OIDC_AUDIENCE', 'OIDC_REQUIRED_AMR',
    'OIDC_MAX_TOKEN_LIFETIME', 'OIDC_CLOCK_SKEW', 'OIDC_JWKS_REFRESH_MINIMUM_INTERVAL',
    'DEEPSEEK_API_BASE_URL', 'AI_PROVIDER_ALLOWED_HOSTS',
    'AI_RUNTIME_STATE_BACKEND', 'AI_RUNTIME_DATABASE_HOST',
    'AI_RUNTIME_DATABASE_PORT', 'AI_RUNTIME_DATABASE_NAME', 'AI_RUNTIME_DATABASE_USER',
    'AI_RUNTIME_DB_POOL_MIN', 'AI_RUNTIME_DB_POOL_MAX',
    'AI_RUNTIME_DB_POOL_TIMEOUT_SECONDS', 'AI_RUNTIME_RESERVATION_LEASE_SECONDS',
    'AI_RUNTIME_INVOCATION_RETENTION_DAYS',
    'TOOL_GATEWAY_DATABASE_URL', 'TOOL_GATEWAY_DATABASE_USER',
    'TOOL_GATEWAY_PERSISTENCE_ENABLED', 'TOOL_GATEWAY_FLYWAY_ENABLED',
    'TOOL_GATEWAY_DB_POOL_MAX', 'TOOL_GATEWAY_DB_CONNECTION_TIMEOUT_MS',
    'TOOL_GATEWAY_EXECUTION_LEASE_DURATION',
    'TOOL_GATEWAY_PROMETHEUS_ENABLED', 'TOOL_GATEWAY_PROMETHEUS_BASE_URI',
    'TOOL_GATEWAY_PROMETHEUS_ALLOW_INTERNAL_CLEARTEXT',
    'TOOL_GATEWAY_PROMETHEUS_CONNECT_TIMEOUT', 'TOOL_GATEWAY_PROMETHEUS_REQUEST_TIMEOUT',
    'TOOL_GATEWAY_PROMETHEUS_MAX_RESPONSE_BYTES', 'TOOL_GATEWAY_PROMETHEUS_MAX_SERIES',
    'TOOL_GATEWAY_PROMETHEUS_MAX_POINTS', 'TOOL_GATEWAY_PROMETHEUS_QUERY_WINDOW',
    'TOOL_GATEWAY_PROMETHEUS_QUERY_STEP',
    'OPSMIND_AI_CAPABILITY_ISSUANCE_ENABLED', 'OPSMIND_AI_CAPABILITY_ISSUER',
    'OPSMIND_AI_CAPABILITY_AUDIENCE', 'OPSMIND_AI_CAPABILITY_KEY_ID',
    'OPSMIND_AI_CAPABILITY_PRIVATE_KEY_FILE', 'OPSMIND_AI_CAPABILITY_JWKS_FILE',
    'OPSMIND_AI_CAPABILITY_MAX_LIFETIME', 'OPSMIND_CAPABILITY_MAX_LIFETIME_SECONDS',
    'OPS_ENABLE_DEEPSEEK_EGRESS', 'OPS_ENABLE_WRITE_ACTIONS', 'OPSMIND_SECURITY_MODE',
    'OPSMIND_DISPATCHER_ENABLED',
    'OPS_DOCKER_STORAGE_VERIFIED'
)) { [void]$safeEnvironmentNames.Add($name) }
$runtimeSecretNames = @(
    'POSTGRES_PASSWORD', 'POSTGRES_APP_PASSWORD', 'POSTGRES_DISPATCHER_PASSWORD',
    'POSTGRES_AI_RUNTIME_PASSWORD', 'POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD',
    'POSTGRES_TOOL_GATEWAY_PASSWORD', 'TOOL_GATEWAY_DATABASE_PASSWORD',
    'AI_RUNTIME_DATABASE_PASSWORD',
    'SPRING_DATASOURCE_PASSWORD', 'MINIO_ROOT_PASSWORD', 'DEEPSEEK_API_KEY'
)

function Import-OpsMindEnvironment {
    $environmentPath = Join-Path $repositoryRoot '.env'
    if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) { return }

    foreach ($line in [IO.File]::ReadAllLines($environmentPath)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.StartsWith('#')) { continue }
        if ($line -cnotmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            throw "Invalid .env entry. Expected KEY=VALUE without shell syntax."
        }
        $name = $Matches[1]
        $value = $Matches[2]
        if ($runtimeSecretNames -contains $name) {
            if (-not [string]::IsNullOrEmpty($value)) {
                throw "$name cannot be loaded from .env. Supply runtime secrets through the process environment or an approved secret manager."
            }
            continue
        }
        if (-not $safeEnvironmentNames.Contains($name)) {
            throw "Unsupported .env key: $name. Only the documented non-secret allowlist is accepted."
        }
        if ($null -eq [Environment]::GetEnvironmentVariable($name, 'Process')) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Clear-RuntimeSecrets {
    param([string[]]$Except = @())
    foreach ($name in $runtimeSecretNames) {
        if ($Except -notcontains $name) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
    }
}

$commandLockPath = Join-Path $repositoryRoot '.opsmind\command-locks\heavy'
$commandLockOwnerPath = Join-Path $commandLockPath 'owner.txt'
$commandLockToken = [guid]::NewGuid().ToString('N')
$commandLockOwned = $false

function Enter-CommandLock {
    $lockParent = Split-Path -Parent $commandLockPath
    foreach ($path in @($repositoryRoot, (Join-Path $repositoryRoot '.opsmind'), $lockParent)) {
        if (Test-Path -LiteralPath $path) {
            $pathItem = Get-Item -LiteralPath $path -Force
            if (($pathItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Command-lock path cannot contain a reparse point.'
            }
        }
    }
    [void](New-Item -ItemType Directory -Path $lockParent -Force)
    try {
        [void](New-Item -ItemType Directory -Path $commandLockPath -ErrorAction Stop)
    }
    catch {
        $owner = if (Test-Path -LiteralPath $commandLockOwnerPath -PathType Leaf) {
            ([IO.File]::ReadAllText($commandLockOwnerPath) -replace '[\r\n]+', '; ')
        }
        else { 'owner metadata unavailable' }
        throw "Another heavyweight OpsMind command owns the workspace lock: $owner"
    }
    $script:commandLockOwned = $true
    $ownerLines = @(
        "Token=$commandLockToken",
        "ProcessId=$PID",
        "Command=$CommandName",
        ('StartedUtc={0}' -f [DateTime]::UtcNow.ToString('o'))
    )
    [IO.File]::WriteAllLines($commandLockOwnerPath, $ownerLines, (New-Object Text.UTF8Encoding($false)))
    Write-Output 'CommandLock=ACQUIRED'
}

function Exit-CommandLock {
    if (-not $script:commandLockOwned) { return }
    try {
        if (Test-Path -LiteralPath $commandLockOwnerPath -PathType Leaf) {
            $ownerLines = [IO.File]::ReadAllLines($commandLockOwnerPath)
            if ($ownerLines -notcontains "Token=$commandLockToken") {
                [Console]::Error.WriteLine('Command-lock ownership changed; refusing cleanup.')
                return
            }
            Remove-Item -LiteralPath $commandLockOwnerPath -Force
        }
        [IO.Directory]::Delete($commandLockPath)
        Write-Output 'CommandLock=RELEASED'
    }
    finally {
        $script:commandLockOwned = $false
    }
}

function Exit-OpsMind {
    param([Parameter(Mandatory = $true)][int]$Code)
    Exit-CommandLock
    exit $Code
}

function Invoke-Checked {
    param([Parameter(Mandatory = $true)][string]$Executable, [string[]]$Arguments = @())
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        $exitCode = $LASTEXITCODE
        [Console]::Error.WriteLine("Command failed with exit code ${exitCode}: $Executable")
        Exit-OpsMind -Code $exitCode
    }
}

function Assert-CommandAvailable {
    param([Parameter(Mandatory = $true)][string]$Name)
    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $Name"
    }
}

function Assert-SetupToolchain {
    $expectedNode = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.node-version')).Trim()
    $actualNode = (& node --version).Trim().TrimStart('v')
    if ($actualNode -ne $expectedNode) {
        [Console]::Error.WriteLine("Node version mismatch. Expected=$expectedNode Actual=$actualNode")
        Exit-OpsMind -Code 2
    }

    $expectedPnpm = ((Get-Content -Raw package.json | ConvertFrom-Json).packageManager -split '@')[-1]
    $actualPnpm = (& corepack pnpm --version 2>$null | Select-Object -Last 1).Trim()
    if ($actualPnpm -ne $expectedPnpm) {
        [Console]::Error.WriteLine("pnpm version mismatch. Expected=$expectedPnpm Actual=$actualPnpm")
        Exit-OpsMind -Code 2
    }

    $expectedJava = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.java-version')).Trim()
    $javaLine = (& java --version | Select-Object -First 1).ToString()
    $actualJava = if ($javaLine -match '(?:version\s+)?"?(\d+)') { $Matches[1] } else { 'MISSING' }
    if ($actualJava -ne $expectedJava) {
        [Console]::Error.WriteLine("Java version mismatch. Expected=$expectedJava Actual=$actualJava")
        Exit-OpsMind -Code 2
    }

    $expectedMaven = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.maven-version')).Trim()
    $mavenLine = (& mvn --version 2>$null | Select-Object -First 1).ToString()
    $actualMaven = if ($mavenLine -match 'Apache Maven ([0-9.]+)') { $Matches[1] } else { 'MISSING' }
    if ($actualMaven -ne $expectedMaven) {
        [Console]::Error.WriteLine("Maven version mismatch. Expected=$expectedMaven Actual=$actualMaven")
        Exit-OpsMind -Code 2
    }
}

function Resolve-PinnedPythonBootstrap {
    param([Parameter(Mandatory = $true)][string]$RequiredVersion)

    $candidates = @(
        @{ Executable = 'py'; Arguments = @("-$RequiredVersion") },
        @{ Executable = "python$RequiredVersion"; Arguments = @() },
        @{ Executable = 'python3'; Arguments = @() },
        @{ Executable = 'python'; Arguments = @() }
    )
    foreach ($candidate in $candidates) {
        if ($null -eq (Get-Command $candidate.Executable -ErrorAction SilentlyContinue)) { continue }
        $versionArguments = @($candidate.Arguments) + @('-c', 'import sys; print(sys.version_info.major, sys.version_info.minor, sep=chr(46))')
        try {
            $actualVersion = (& $candidate.Executable @versionArguments 2>$null | Select-Object -Last 1)
        } catch {
            continue
        }
        if ($LASTEXITCODE -eq 0 -and $actualVersion.Trim() -eq $RequiredVersion) {
            return [PSCustomObject]@{
                Executable = $candidate.Executable
                Arguments = @($candidate.Arguments)
            }
        }
    }
    throw "Python $RequiredVersion is required but no matching interpreter was found."
}

function Assert-ApplicationComposeConfiguration {
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
        throw 'POSTGRES_PASSWORD must be supplied through the process environment or an approved secret manager.'
    }
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_APP_PASSWORD)) {
        throw 'POSTGRES_APP_PASSWORD must be supplied through the process environment or an approved secret manager.'
    }
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_DISPATCHER_PASSWORD)) {
        throw 'POSTGRES_DISPATCHER_PASSWORD must be supplied through the process environment or an approved secret manager.'
    }
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_AI_RUNTIME_PASSWORD)) {
        throw 'POSTGRES_AI_RUNTIME_PASSWORD must be supplied through the process environment or an approved secret manager.'
    }
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD)) {
        throw 'POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD must be supplied through the process environment or an approved secret manager.'
    }
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_TOOL_GATEWAY_PASSWORD)) {
        throw 'POSTGRES_TOOL_GATEWAY_PASSWORD must be supplied through the process environment or an approved secret manager.'
    }
    $databasePasswords = @(
        $env:POSTGRES_PASSWORD,
        $env:POSTGRES_APP_PASSWORD,
        $env:POSTGRES_DISPATCHER_PASSWORD,
        $env:POSTGRES_AI_RUNTIME_PASSWORD,
        $env:POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD,
        $env:POSTGRES_TOOL_GATEWAY_PASSWORD
    )
    if (($databasePasswords | Select-Object -Unique).Count -ne $databasePasswords.Count) {
        throw 'Migration and runtime-role passwords must be pairwise different.'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_APP_USER) -and $env:POSTGRES_APP_USER -cne 'opsmind_app') {
        throw 'POSTGRES_APP_USER must remain opsmind_app; migration grants are intentionally explicit.'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_DISPATCHER_USER) -and $env:POSTGRES_DISPATCHER_USER -cne 'opsmind_dispatcher') {
        throw 'POSTGRES_DISPATCHER_USER must remain opsmind_dispatcher; migration grants are intentionally explicit.'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_AI_RUNTIME_USER) -and $env:POSTGRES_AI_RUNTIME_USER -cne 'opsmind_ai_runtime') {
        throw 'POSTGRES_AI_RUNTIME_USER must remain opsmind_ai_runtime; migration grants are intentionally explicit.'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_TOOL_GATEWAY_MIGRATOR_USER) -and $env:POSTGRES_TOOL_GATEWAY_MIGRATOR_USER -cne 'opsmind_tool_gateway_migrator') {
        throw 'POSTGRES_TOOL_GATEWAY_MIGRATOR_USER must remain opsmind_tool_gateway_migrator; schema ownership is intentionally explicit.'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_TOOL_GATEWAY_USER) -and $env:POSTGRES_TOOL_GATEWAY_USER -cne 'opsmind_tool_gateway') {
        throw 'POSTGRES_TOOL_GATEWAY_USER must remain opsmind_tool_gateway; migration grants are intentionally explicit.'
    }
    if ($env:OPS_DOCKER_STORAGE_VERIFIED -cne 'true') {
        throw 'OPS_DOCKER_STORAGE_VERIFIED=true is required after verifying Docker daemon/build storage is on an approved monitored volume.'
    }
}

Import-OpsMindEnvironment
if ($CommandName -eq 'migrate') {
    Clear-RuntimeSecrets -Except @('SPRING_DATASOURCE_PASSWORD')
}
elseif (@('dev', 'up') -notcontains $CommandName) {
    Clear-RuntimeSecrets
}
$cacheRoot = if ($env:OPS_CACHE_ROOT) { [IO.Path]::GetFullPath($env:OPS_CACHE_ROOT) } else { Join-Path $repositoryRoot '.opsmind\cache' }
$artifactRoot = if ($env:OPS_ARTIFACT_ROOT) { [IO.Path]::GetFullPath($env:OPS_ARTIFACT_ROOT) } else { Join-Path $repositoryRoot 'artifacts' }
$requiredPythonVersion = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.python-version')).Trim()
$pythonVersionTag = $requiredPythonVersion.Replace('.', '')
$pythonEnvironment = Join-Path $cacheRoot "venvs\ai-runtime-py$pythonVersionTag"
$pythonExecutable = Join-Path $pythonEnvironment 'Scripts\python.exe'
$actionlintVersion = '1.7.12'
$actionlintExecutable = Join-Path $cacheRoot "tools\actionlint\$actionlintVersion\actionlint.exe"
$uvVersion = '0.11.29'
$uvToolEnvironment = Join-Path $cacheRoot "tools\uv-$uvVersion"
$uvToolPython = Join-Path $uvToolEnvironment 'Scripts\python.exe'
$uvExecutable = Join-Path $uvToolEnvironment 'Scripts\uv.exe'
$uvCache = Join-Path $cacheRoot 'uv'
$aiRuntimeRoot = Join-Path $repositoryRoot 'services\ai-runtime'
$mavenRepository = Join-Path $cacheRoot 'maven'
$dependencyCheckData = Join-Path $cacheRoot 'owasp-dependency-check'
$pnpmStore = Join-Path $cacheRoot 'pnpm-store'
$processTempRoot = Join-Path $cacheRoot 'temp'
$phaseEvidenceRoot = Join-Path $artifactRoot 'verification\phase-02'
$env:COREPACK_HOME = Join-Path $cacheRoot 'corepack'

function Initialize-BoundedProcessEnvironment {
    [void](New-Item -ItemType Directory -Path $processTempRoot -Force)
    $env:TEMP = $processTempRoot
    $env:TMP = $processTempRoot
    $env:TMPDIR = $processTempRoot
    if ([string]::IsNullOrWhiteSpace($env:NODE_OPTIONS)) {
        $env:NODE_OPTIONS = '--max-old-space-size=1536'
    }
    if ([string]::IsNullOrWhiteSpace($env:MAVEN_OPTS)) {
        $env:MAVEN_OPTS = "-Xmx768m -XX:MaxMetaspaceSize=384m -Djava.io.tmpdir=`"$processTempRoot`""
    }
    $env:UV_CACHE_DIR = $uvCache
    $env:UV_PROJECT_ENVIRONMENT = $pythonEnvironment
    $env:UV_PYTHON = $requiredPythonVersion
    $env:UV_PYTHON_DOWNLOADS = 'never'
}

function Invoke-StoragePreflight {
    param([switch]$CreateMissing)
    $capacityScript = Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1'
    $rootScript = Join-Path $repositoryRoot 'scripts\storage\assert-storage-roots.ps1'

    $configuredArtifactRoot = $env:OPS_ARTIFACT_ROOT
    $repositoryArtifactRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'artifacts'))
    $mayCreateConfiguredDefault = $CreateMissing -and
        -not [string]::IsNullOrWhiteSpace($configuredArtifactRoot) -and
        [IO.Path]::GetFullPath($configuredArtifactRoot).Equals($repositoryArtifactRoot, [StringComparison]::OrdinalIgnoreCase) -and
        -not (Test-Path -LiteralPath $repositoryArtifactRoot)
    if ($mayCreateConfiguredDefault) { Remove-Item Env:OPS_ARTIFACT_ROOT }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $capacityScript
    if ($mayCreateConfiguredDefault) { $env:OPS_ARTIFACT_ROOT = $configuredArtifactRoot }
    if ($LASTEXITCODE -ne 0) { throw 'Capacity preflight blocked the command.' }
    $rootArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $rootScript)
    if ($CreateMissing) { $rootArguments += '-CreateMissing' }
    & powershell.exe @rootArguments
    if ($LASTEXITCODE -ne 0) { throw 'Storage-root preflight blocked the command.' }

    [void](New-Item -ItemType Directory -Path $phaseEvidenceRoot -Force)
    Invoke-Checked powershell.exe @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $capacityScript, '-EvidencePath', (Join-Path $phaseEvidenceRoot 'capacity-windows.txt'))
    $verifiedRootArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $rootScript, '-EvidencePath', (Join-Path $phaseEvidenceRoot 'storage-roots-windows.txt'))
    Invoke-Checked powershell.exe $verifiedRootArguments
    Write-Output 'Preflight=PASS'
}

function Invoke-CapacityGuard {
    $capacityScript = Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1'
    Invoke-Checked powershell.exe @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $capacityScript)
    Write-Output 'CapacityGuard=PASS'
}

function Invoke-RepositoryLayoutValidation {
    $previousEvidencePath = $env:OPS_LAYOUT_EVIDENCE_PATH
    try {
        $env:OPS_LAYOUT_EVIDENCE_PATH = Join-Path $phaseEvidenceRoot 'repository-layout.txt'
        Invoke-Checked node @('.\scripts\validation\validate-repository-layout.mjs')
    }
    finally {
        $env:OPS_LAYOUT_EVIDENCE_PATH = $previousEvidencePath
    }
}

function Write-DoctorResult {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Expected,
        [Parameter(Mandatory = $true)][string]$Actual,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [Parameter(Mandatory = $true)]$Failures
    )
    $status = if ($Passed) { 'PASS' } else { 'BLOCK' }
    Write-Output "Tool.${Name}=${status} Expected=${Expected} Actual=${Actual}"
    if (-not $Passed) { [void]$Failures.Add($Name) }
}

function Show-Help {
    @'
OpsMind command surface
Usage: powershell -File scripts/dev/opsmind.ps1 <command> [-DryRun]

Commands: setup dev test lint build up down migrate seed evaluate security
          security-scan doctor help

setup/dev/test/lint/build/up/migrate/seed/evaluate/security run storage
preflight before side effects. down remains available during low-space events.
migrate applies the Phase 3 Flyway schema; seed remains unavailable until its
deterministic fixture owner lands. evaluate becomes available in Phase 8.
'@ | Write-Output
}

try {
    $heavyCommands = @('setup', 'dev', 'test', 'lint', 'build', 'up', 'migrate', 'seed', 'evaluate', 'security', 'security-scan')
    $lockCommands = $heavyCommands + @('doctor')
    if ($lockCommands -contains $CommandName) { Enter-CommandLock }
    if ($heavyCommands -contains $CommandName) {
        Invoke-StoragePreflight -CreateMissing:($CommandName -eq 'setup')
        Initialize-BoundedProcessEnvironment
    }

$commandPlan = @{
    setup = 'install pinned workspace dependencies into configured D-backed caches'
    dev = 'build and start the application Compose profile in the foreground'
    test = 'run governance, layout, frontend, Java, and Python tests'
    lint = 'run repository, frontend, Java compile, Python lint and type checks'
    build = 'build frontend and Java artifacts; compile Python sources'
    up = 'build and start the application Compose profile with health waits'
    down = 'stop the application Compose profile without a capacity gate'
    migrate = 'package the Platform API and apply its Flyway migrations with the migration role'
    seed = 'unavailable until Phase 3 owns deterministic seed data'
    evaluate = 'unavailable until Phase 8 owns the evaluation harness'
    security = 'run repository secret and ecosystem dependency scans'
    'security-scan' = 'alias for security'
    doctor = 'run preflight and report required tool availability'
}
if ($DryRun) {
    Write-Output "Command=$CommandName"
    Write-Output "CommandPlan=$($commandPlan[$CommandName])"
    Exit-OpsMind -Code 0
}

if ($CommandName -eq 'help') { Show-Help; Exit-OpsMind -Code 0 }
if ($CommandName -eq 'doctor') {
    Invoke-StoragePreflight
    $doctorFailures = New-Object 'Collections.Generic.List[string]'

    $expectedNode = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.node-version')).Trim()
    $actualNode = if (Get-Command node -ErrorAction SilentlyContinue) { (& node --version).Trim().TrimStart('v') } else { 'MISSING' }
    Write-DoctorResult 'Node' $expectedNode $actualNode ($actualNode -eq $expectedNode) $doctorFailures

    $expectedPnpm = ((Get-Content -Raw package.json | ConvertFrom-Json).packageManager -split '@')[-1]
    $actualPnpm = if (Get-Command corepack -ErrorAction SilentlyContinue) { (& corepack pnpm --version 2>$null | Select-Object -Last 1).Trim() } else { 'MISSING' }
    Write-DoctorResult 'Pnpm' $expectedPnpm $actualPnpm ($actualPnpm -eq $expectedPnpm) $doctorFailures

    $expectedJava = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.java-version')).Trim()
    $javaLine = if (Get-Command java -ErrorAction SilentlyContinue) { (& java --version | Select-Object -First 1).ToString() } else { '' }
    $actualJava = if ($javaLine -match '(?:version\s+)?"?(\d+)') { $Matches[1] } else { 'MISSING' }
    Write-DoctorResult 'Java' $expectedJava $actualJava ($actualJava -eq $expectedJava) $doctorFailures

    $expectedMaven = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.maven-version')).Trim()
    $mavenLine = if (Get-Command mvn -ErrorAction SilentlyContinue) { (& mvn --version 2>$null | Select-Object -First 1).ToString() } else { '' }
    $actualMaven = if ($mavenLine -match 'Apache Maven ([0-9.]+)') { $Matches[1] } else { 'MISSING' }
    Write-DoctorResult 'Maven' $expectedMaven $actualMaven ($actualMaven -eq $expectedMaven) $doctorFailures

    try {
        $pythonBootstrap = Resolve-PinnedPythonBootstrap -RequiredVersion $requiredPythonVersion
        $pythonVersionArguments = @($pythonBootstrap.Arguments) + @('-c', 'import sys; print(sys.version_info.major, sys.version_info.minor, sep=chr(46))')
        $actualPython = (& $pythonBootstrap.Executable @pythonVersionArguments 2>$null | Select-Object -Last 1).Trim()
    }
    catch { $actualPython = 'MISSING' }
    Write-DoctorResult 'Python' $requiredPythonVersion $actualPython ($actualPython -eq $requiredPythonVersion) $doctorFailures

    $actualUv = if (Test-Path -LiteralPath $uvExecutable -PathType Leaf) {
        ((& $uvExecutable --version 2>$null | Select-Object -Last 1) -replace '^uv\s+', '' -replace '\s+.*$', '').Trim()
    }
    else { 'MISSING' }
    Write-DoctorResult 'Uv' $uvVersion $actualUv ($actualUv -eq $uvVersion) $doctorFailures

    $actualActionlint = if (Test-Path -LiteralPath $actionlintExecutable -PathType Leaf) {
        (& $actionlintExecutable -version 2>$null | Select-Object -First 1).Trim()
    }
    else { 'MISSING' }
    Write-DoctorResult 'Actionlint' $actionlintVersion $actualActionlint ($actualActionlint -eq $actionlintVersion) $doctorFailures

    $actualDocker = if (Get-Command docker -ErrorAction SilentlyContinue) { (& docker --version 2>$null | Select-Object -First 1).ToString() } else { 'MISSING' }
    Write-DoctorResult 'Docker' 'available' $actualDocker ($actualDocker -ne 'MISSING') $doctorFailures

    if ($doctorFailures.Count -gt 0) {
        Write-Output "Doctor=BLOCK Failures=$($doctorFailures -join ',')"
        Exit-OpsMind -Code 6
    }
    Write-Output 'Doctor=PASS'
    Exit-OpsMind -Code 0
}
if (@('seed', 'evaluate') -contains $CommandName) {
    [Console]::Error.WriteLine("CommandUnavailable=$($commandPlan[$CommandName])")
    Exit-OpsMind -Code 3
}
if ($CommandName -eq 'security-scan') { $CommandName = 'security' }

$mavenCommon = @('-q', "-Dmaven.repo.local=$mavenRepository")
$platformPom = Join-Path $repositoryRoot 'services\platform-api\pom.xml'
$gatewayPom = Join-Path $repositoryRoot 'services\tool-gateway\pom.xml'

switch ($CommandName) {
    'setup' {
        foreach ($tool in @('node', 'corepack', 'java', 'mvn')) { Assert-CommandAvailable $tool }
        Assert-SetupToolchain
        $pythonBootstrap = Resolve-PinnedPythonBootstrap -RequiredVersion $requiredPythonVersion
        [void](New-Item -ItemType Directory -Path $cacheRoot -Force)
        Invoke-Checked node @('.\scripts\dev\install-pinned-actionlint.mjs', '--cache-root', $cacheRoot)
        Invoke-Checked corepack @('pnpm', '--config.ci=true', "--config.store-dir=$pnpmStore", 'install', '--frozen-lockfile')
        Invoke-CapacityGuard
        if (-not (Test-Path -LiteralPath $uvToolPython)) {
            Invoke-Checked $pythonBootstrap.Executable ($pythonBootstrap.Arguments + @('-m', 'venv', $uvToolEnvironment))
        }
        if (-not (Test-Path -LiteralPath $uvExecutable)) {
            Invoke-Checked $uvToolPython @(
                '-m', 'pip', 'install', '--disable-pip-version-check',
                '--cache-dir', (Join-Path $cacheRoot 'pip'), "uv==$uvVersion"
            )
        }
        Invoke-Checked $uvExecutable @('sync', '--project', $aiRuntimeRoot, '--locked')
        Invoke-CapacityGuard
        Invoke-Checked mvn ($mavenCommon + @('-f', $platformPom, 'dependency:go-offline'))
        Invoke-Checked mvn ($mavenCommon + @('-f', $gatewayPom, 'dependency:go-offline'))
        Invoke-CapacityGuard
    }
    'dev' { Assert-ApplicationComposeConfiguration; Invoke-Checked docker @('compose', '--profile', 'application', 'up', '--build') }
    'up' { Assert-ApplicationComposeConfiguration; Invoke-Checked docker @('compose', '--profile', 'application', 'up', '--build', '--detach', '--wait') }
    'down' { Invoke-Checked docker @('compose', '--profile', 'application', 'down') }
    'migrate' {
        if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_PASSWORD)) {
            [Console]::Error.WriteLine('SPRING_DATASOURCE_PASSWORD must be supplied through the process environment or an approved secret manager.')
            exit 2
        }
        Assert-CommandAvailable 'java'
        Assert-CommandAvailable 'mvn'
        if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) {
            $env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/opsmind'
        }
        if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME)) {
            $env:SPRING_DATASOURCE_USERNAME = 'opsmind_migrator'
        }
        Invoke-Checked mvn ($mavenCommon + @('-f', $platformPom, '-DskipTests', 'package'))
        Invoke-CapacityGuard
        Invoke-Checked java @(
            '-jar', (Join-Path $repositoryRoot 'services\platform-api\target\platform-api.jar'),
            '--spring.main.web-application-type=none', '--spring.profiles.active=persistence'
        )
        Invoke-CapacityGuard
    }
    'test' {
        Invoke-Checked powershell.exe @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\governance\test-phase-01-governance.ps1')
        Invoke-CapacityGuard
        Invoke-RepositoryLayoutValidation
        Invoke-Checked corepack @('pnpm', "--config.store-dir=$pnpmStore", '--filter', '@opsmind/operator-web', 'test')
        Invoke-CapacityGuard
        Invoke-Checked mvn ($mavenCommon + @('-f', $platformPom, 'test'))
        Invoke-CapacityGuard
        Invoke-Checked mvn ($mavenCommon + @('-f', $gatewayPom, 'test'))
        Invoke-Checked $pythonExecutable @('-m', 'pytest', 'services\ai-runtime\tests')
        Invoke-CapacityGuard
    }
    'lint' {
        Invoke-RepositoryLayoutValidation
        Invoke-Checked corepack @('pnpm', "--config.store-dir=$pnpmStore", '--filter', '@opsmind/operator-web', 'lint')
        Invoke-Checked corepack @('pnpm', "--config.store-dir=$pnpmStore", '--filter', '@opsmind/operator-web', 'typecheck')
        Invoke-CapacityGuard
        Invoke-Checked mvn ($mavenCommon + @('-f', $platformPom, '-DskipTests', 'compile'))
        Invoke-Checked mvn ($mavenCommon + @('-f', $gatewayPom, '-DskipTests', 'compile'))
        Invoke-CapacityGuard
        Invoke-Checked $uvExecutable @('lock', '--project', $aiRuntimeRoot, '--check')
        Invoke-Checked $pythonExecutable @('-m', 'ruff', 'check', 'services\ai-runtime')
        Invoke-Checked $pythonExecutable @('-m', 'mypy', 'services\ai-runtime\src')
        Invoke-CapacityGuard
    }
    'build' {
        Invoke-Checked corepack @('pnpm', "--config.store-dir=$pnpmStore", '--filter', '@opsmind/operator-web', 'build')
        Invoke-CapacityGuard
        Invoke-Checked mvn ($mavenCommon + @('-f', $platformPom, '-DskipTests', 'package'))
        Invoke-CapacityGuard
        Invoke-Checked mvn ($mavenCommon + @('-f', $gatewayPom, '-DskipTests', 'package'))
        Invoke-Checked $pythonExecutable @('-m', 'compileall', '-q', 'services\ai-runtime\src')
        Invoke-CapacityGuard
    }
    'security' {
        Invoke-Checked powershell.exe @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\governance\scan-project-secrets.ps1')
        Invoke-Checked corepack @('pnpm', "--config.store-dir=$pnpmStore", 'audit', '--audit-level', 'moderate')
        Invoke-CapacityGuard
        Invoke-Checked $pythonExecutable @('-m', 'pip_audit')
        Invoke-CapacityGuard
        foreach ($pom in @($platformPom, $gatewayPom)) {
            $dependencyCheckArguments = @(
                "-DdataDirectory=$dependencyCheckData",
                '-DfailBuildOnCVSS=7',
                '-DfailOnError=true',
                '-Dformat=JSON'
            )
            Invoke-Checked mvn ($mavenCommon + $dependencyCheckArguments + @(
                '-f', $pom, 'org.owasp:dependency-check-maven:12.2.2:check'
            ))
            Invoke-CapacityGuard
        }
    }
}
}
finally {
    Exit-CommandLock
}
