[CmdletBinding()]
param(
    [string]$DockerPath,
    [string]$JavaPath,
    [string]$MavenPath,
    [string]$GitBashPath = 'C:\Program Files\Git\bin\bash.exe',
    [string]$PostgresImage = 'postgres:18-bookworm'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$platformPom = Join-Path $repositoryRoot 'services\platform-api\pom.xml'
$platformJar = Join-Path $repositoryRoot 'services\platform-api\target\platform-api.jar'
$bootstrapPath = Join-Path $repositoryRoot `
    'services\platform-api\src\main\resources\db\bootstrap\001-create-runtime-role.sh'
$portableContract = Join-Path $repositoryRoot 'scripts\validation\run-phase-04-postgres-contract.sh'
$capacityScript = Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1'
$storageRootsScript = Join-Path $repositoryRoot 'scripts\storage\assert-storage-roots.ps1'
$mavenRepository = Join-Path $repositoryRoot '.opsmind\cache\maven'
$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = "opsmind-phase4-postgres-$runId"
$containerPattern = '^opsmind-phase4-postgres-[0-9a-f]{12}$'
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.opsmind\tmp'))
$temporaryDirectory = Join-Path $temporaryRoot "phase4-postgres-$runId"
$evidenceDirectory = Join-Path $repositoryRoot 'artifacts\verification\phase-04'
$crudEvidencePath = Join-Path $evidenceDirectory 'incident-crud.txt'
$auditEvidencePath = Join-Path $evidenceDirectory 'audit-and-concurrency.txt'
$failureEvidencePath = Join-Path $evidenceDirectory 'phase-04-postgres-failure.txt'
$cleanupErrors = [Collections.Generic.List[string]]::new()
$diagnosticFiles = [Collections.Generic.List[string]]::new()
$capturedDiagnostics = [Collections.Generic.List[string]]::new()
$failure = $null
$testsExitCode = 1
$sqlExitCode = 1
$packageExitCode = 1
$flywayExitCode = 1
$upgradeExitCode = 1
$serverVersion = 'UNKNOWN'
$postgresImageId = 'UNKNOWN'
$mappedPort = $null
$startedAt = [DateTime]::UtcNow
$stopwatch = [Diagnostics.Stopwatch]::StartNew()

function Resolve-Executable {
    param([string]$ExplicitPath, [string[]]$Names, [string]$Description)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $resolved = [IO.Path]::GetFullPath($ExplicitPath)
        if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
    }
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -ne $command) { return $command.Source }
    }
    throw "$Description was not found."
}

function New-EphemeralPassword {
    $bytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Invoke-CapturedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $stdout = Join-Path $temporaryDirectory "$Name.stdout.log"
    $stderr = Join-Path $temporaryDirectory "$Name.stderr.log"
    $diagnosticFiles.Add($stdout)
    $diagnosticFiles.Add($stderr)
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments `
        -WorkingDirectory $repositoryRoot -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    return $process.ExitCode
}

function Invoke-ContainerSql {
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Sql
    )
    $Sql | & $DockerPath exec --interactive $containerName psql `
        --username postgres --dbname $Database --quiet --set ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "Container SQL failed for database $Database." }
}

function Get-SanitizedTail {
    param([string[]]$Paths, [string[]]$Secrets)
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $tail = @(Get-Content -LiteralPath $path -Tail 20 -ErrorAction SilentlyContinue)
        foreach ($rawLine in $tail) {
            $line = ([string]$rawLine -replace '[\r\n]+', ' ').Trim()
            foreach ($secret in $Secrets) {
                if (-not [string]::IsNullOrWhiteSpace($secret)) {
                    $line = $line.Replace($secret, '[REDACTED]')
                }
            }
            $line = $line -replace '(?i)(password|token|secret)=\S+', '$1=[REDACTED]'
            if ($line.Length -gt 500) { $line = $line.Substring(0, 500) }
            if (-not [string]::IsNullOrWhiteSpace($line)) { $lines.Add($line) }
            if ($lines.Count -ge 80) { return $lines.ToArray() }
        }
    }
    return $lines.ToArray()
}

function Write-AtomicEvidence {
    param([string]$Path, [string[]]$Lines)
    $temporaryPath = Join-Path (Split-Path -Parent $Path) `
        ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllLines($temporaryPath, $Lines, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Value)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        return ([BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant())
    }
    finally { $algorithm.Dispose() }
}

function Get-SourceManifest {
    $files = [Collections.Generic.List[IO.FileInfo]]::new()
    foreach ($path in @(
        $platformPom,
        (Join-Path $repositoryRoot 'services\platform-api\src\main\resources\application.yaml')
    )) { $files.Add((Get-Item -LiteralPath $path)) }
    foreach ($root in @(
        (Join-Path $repositoryRoot 'services\platform-api\src\main\java'),
        (Join-Path $repositoryRoot 'packages\contracts')
    )) {
        foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -File) { $files.Add($file) }
    }
    $prefixLength = $repositoryRoot.TrimEnd('\', '/').Length + 1
    $manifest = foreach ($file in $files | Sort-Object FullName -Unique) {
        $relative = $file.FullName.Substring($prefixLength).Replace('\', '/')
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative=$digest"
    }
    return @{
        Count = @($manifest).Count
        Digest = Get-TextSha256 (($manifest -join "`n") + "`n")
    }
}

function Get-ToolVersion {
    param([string]$Path, [string[]]$Arguments)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return 'UNAVAILABLE'
    }
    try {
        $line = @(& $Path @Arguments 2>&1 | Select-Object -First 1)
        if ($line.Count -eq 0) { return 'UNAVAILABLE' }
        return (([string]$line[0] -replace '[\r\n]+', ' ').Trim() -replace '\s+', '_')
    }
    catch { return 'UNAVAILABLE' }
}

foreach ($path in @($crudEvidencePath, $auditEvidencePath, $failureEvidencePath)) {
    if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
}

$adminPassword = New-EphemeralPassword
$migrationPassword = New-EphemeralPassword
$appPassword = New-EphemeralPassword
$dispatcherPassword = New-EphemeralPassword
$aiRuntimePassword = New-EphemeralPassword
$knownPasswords = @($adminPassword, $migrationPassword, $appPassword, $dispatcherPassword)
while ($aiRuntimePassword -in $knownPasswords) {
    $aiRuntimePassword = New-EphemeralPassword
}
$environmentNames = @(
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
    'POSTGRES_APP_USER', 'POSTGRES_APP_PASSWORD',
    'POSTGRES_DISPATCHER_USER', 'POSTGRES_DISPATCHER_PASSWORD',
    'POSTGRES_AI_RUNTIME_USER', 'POSTGRES_AI_RUNTIME_PASSWORD',
    'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
    'OPSMIND_FLYWAY_ENABLED', 'OPSMIND_EPHEMERAL_DB',
    'OPSMIND_PHASE4_DB_INTEGRATION', 'PGHOST', 'PGPORT', 'PGDATABASE', 'JAVA_HOME'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    if ($containerName -notmatch $containerPattern) { throw 'Unsafe container name generated.' }
    if ($env:OPS_DOCKER_STORAGE_VERIFIED -cne 'true') {
        throw 'OPS_DOCKER_STORAGE_VERIFIED=true is required after D-backed Docker storage verification.'
    }
    foreach ($requiredPath in @(
        $platformPom, $bootstrapPath, $portableContract, $capacityScript, $storageRootsScript
    )) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Required Phase 4 input is missing: $requiredPath"
        }
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $capacityScript
    if ($LASTEXITCODE -ne 0) { throw 'Capacity preflight blocked the PostgreSQL contract.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $storageRootsScript -CreateMissing
    if ($LASTEXITCODE -ne 0) { throw 'Storage-root preflight blocked the PostgreSQL contract.' }

    $DockerPath = Resolve-Executable $DockerPath @('docker', 'docker.exe') 'Docker'
    $MavenPath = Resolve-Executable $MavenPath @('mvn', 'mvn.cmd') 'Maven'
    $declaredJdk = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot '.opsmind\cache\tools') `
        -Directory -Filter 'temurin-jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($JavaPath) -and $null -ne $declaredJdk) {
        $JavaPath = Join-Path $declaredJdk.FullName 'bin\java.exe'
    }
    $JavaPath = Resolve-Executable $JavaPath @('java', 'java.exe') 'Java 21'
    if (-not (Test-Path -LiteralPath $GitBashPath -PathType Leaf)) {
        throw 'Git Bash is required for the portable SQL contract.'
    }

    New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
    $env:POSTGRES_DB = 'opsmind_phase4'
    $env:POSTGRES_USER = 'postgres'
    $env:POSTGRES_PASSWORD = $adminPassword
    $env:POSTGRES_APP_USER = 'opsmind_app'
    $env:POSTGRES_APP_PASSWORD = $appPassword
    $env:POSTGRES_DISPATCHER_USER = 'opsmind_dispatcher'
    $env:POSTGRES_DISPATCHER_PASSWORD = $dispatcherPassword
    $env:POSTGRES_AI_RUNTIME_USER = 'opsmind_ai_runtime'
    $env:POSTGRES_AI_RUNTIME_PASSWORD = $aiRuntimePassword

    $mount = "type=bind,source=$bootstrapPath,target=/docker-entrypoint-initdb.d/001-create-runtime-role.sh,readonly"
    & $DockerPath run --detach --name $containerName `
        --label 'ai.opsmind.scope=phase4-postgres-contract' `
        --env POSTGRES_DB --env POSTGRES_USER --env POSTGRES_PASSWORD `
        --env POSTGRES_APP_USER --env POSTGRES_APP_PASSWORD `
        --env POSTGRES_DISPATCHER_USER --env POSTGRES_DISPATCHER_PASSWORD `
        --env POSTGRES_AI_RUNTIME_USER --env POSTGRES_AI_RUNTIME_PASSWORD `
        --mount $mount --tmpfs '/var/lib/postgresql:rw,noexec,nosuid,size=536870912' `
        --publish '127.0.0.1::5432' $PostgresImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL container failed to start.' }

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $pidOneCommand = @(& $DockerPath exec $containerName cat /proc/1/comm 2>$null)
        $pidOneExitCode = $LASTEXITCODE
        $pidOne = if ($pidOneCommand.Count -eq 0) { '' }
            else { $pidOneCommand[0].ToString().Trim() }
        if ($pidOneExitCode -eq 0 -and $pidOne -eq 'postgres') {
            & $DockerPath exec $containerName pg_isready `
                --username postgres --dbname opsmind_phase4 *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready within 60 seconds.' }
    $portOutput = (& $DockerPath port $containerName '5432/tcp' | Select-Object -First 1).ToString()
    if ($portOutput -notmatch ':(?<port>[0-9]{2,5})$') { throw 'Unable to resolve PostgreSQL host port.' }
    $mappedPort = $Matches.port
    $serverVersion = (& $DockerPath exec $containerName psql --username postgres `
        --dbname opsmind_phase4 --tuples-only --no-align -c 'SHOW server_version;' |
        Select-Object -First 1).ToString().Trim()
    if ($serverVersion -notmatch '^18\.') { throw "Expected PostgreSQL 18, got $serverVersion." }
    $imageInspection = @(& $DockerPath image inspect --format '{{.Id}}' $PostgresImage)
    $imageInspectionExitCode = $LASTEXITCODE
    $inspectedImageId = if ($imageInspection.Count -eq 0) { '' }
        else { $imageInspection[0].ToString().Trim() }
    if ($imageInspectionExitCode -ne 0 -or $inspectedImageId -notmatch '^sha256:[0-9a-f]{64}$') {
        throw 'Unable to bind the PostgreSQL container image ID.'
    }
    $postgresImageId = $inspectedImageId

    Invoke-ContainerSql -Database 'opsmind_phase4' -Sql @"
CREATE ROLE opsmind_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    INHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$migrationPassword';
ALTER DATABASE opsmind_phase4 OWNER TO opsmind_migrator;
GRANT opsmind_context_resolver, opsmind_dispatch_resolver TO opsmind_migrator
    WITH INHERIT TRUE, SET TRUE;
GRANT USAGE, CREATE ON SCHEMA public
    TO opsmind_context_resolver, opsmind_dispatch_resolver;
"@
    Invoke-ContainerSql -Database 'opsmind_phase4' -Sql @'
DO $$
DECLARE
    inherited_resolver_memberships integer;
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname = 'opsmind_migrator'
           AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole
                OR rolreplication OR NOT rolinherit OR NOT rolcanlogin)
    ) THEN
        RAISE EXCEPTION 'opsmind_migrator has unsafe role attributes';
    END IF;

    SELECT count(*) INTO inherited_resolver_memberships
      FROM pg_auth_members membership
      JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
      JOIN pg_roles member_role ON member_role.oid = membership.member
     WHERE member_role.rolname = 'opsmind_migrator'
       AND granted_role.rolname IN (
           'opsmind_context_resolver', 'opsmind_dispatch_resolver'
       )
       AND membership.inherit_option
       AND membership.set_option;
    IF inherited_resolver_memberships <> 2 THEN
        RAISE EXCEPTION 'opsmind_migrator resolver ownership handoff is unsafe';
    END IF;
END
$$;
'@
    Invoke-ContainerSql -Database 'postgres' -Sql `
        'CREATE DATABASE opsmind_phase4_upgrade OWNER opsmind_migrator;'
    Invoke-ContainerSql -Database 'opsmind_phase4_upgrade' -Sql @'
GRANT USAGE, CREATE ON SCHEMA public
    TO opsmind_context_resolver, opsmind_dispatch_resolver;
'@

    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $JavaPath)
    $packageExitCode = Invoke-CapturedProcess $MavenPath @(
        '--batch-mode', '--no-transfer-progress', "-Dmaven.repo.local=$mavenRepository",
        '-DskipTests', '-f', 'services/platform-api/pom.xml', 'package'
    ) 'maven-package'
    if ($packageExitCode -ne 0 -or -not (Test-Path -LiteralPath $platformJar -PathType Leaf)) {
        throw "Platform API package failed with exit $packageExitCode."
    }

    $env:SPRING_PROFILES_ACTIVE = 'persistence'
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$mappedPort/opsmind_phase4_upgrade"
    $env:SPRING_DATASOURCE_USERNAME = 'opsmind_migrator'
    $env:SPRING_DATASOURCE_PASSWORD = $migrationPassword
    $env:OPSMIND_FLYWAY_ENABLED = 'true'
    $upgradeV2ExitCode = Invoke-CapturedProcess $JavaPath @(
        '-jar', $platformJar, '--spring.main.web-application-type=none',
        '--opsmind.persistence.enabled=false', '--spring.flyway.target=2'
    ) 'flyway-upgrade-v2'
    if ($upgradeV2ExitCode -ne 0) {
        throw "Flyway upgrade fixture V001/V002 failed with exit $upgradeV2ExitCode."
    }
    Invoke-ContainerSql -Database 'opsmind_phase4_upgrade' -Sql @'
INSERT INTO organizations (id, slug, name)
VALUES ('c4000001-4444-4444-8444-444444444444', 'upgrade-org', 'Upgrade Org');
INSERT INTO platform_users (id, issuer, subject, display_name)
VALUES ('c4000002-4444-4444-8444-444444444444',
        'https://idp.example.test/opsmind', 'upgrade-actor', 'Upgrade Actor');
INSERT INTO organization_memberships (organization_id, user_id, role)
VALUES ('c4000001-4444-4444-8444-444444444444',
        'c4000002-4444-4444-8444-444444444444', 'SRE');
INSERT INTO audit_events (
    event_id, organization_id, actor_id, action, resource_type, resource_id,
    correlation_id, occurred_at, payload, event_digest
) VALUES (
    'c4000003-4444-4444-8444-444444444444',
    'c4000001-4444-4444-8444-444444444444',
    'c4000002-4444-4444-8444-444444444444',
    'incident.created', 'incident', 'c4000004-4444-4444-8444-444444444444',
    'c4000005-4444-4444-8444-444444444444', clock_timestamp(), '{}'::jsonb,
    digest(convert_to('legacy-placeholder', 'UTF8'), 'sha256')
);
'@
    $upgradeExitCode = Invoke-CapturedProcess $JavaPath @(
        '-jar', $platformJar, '--spring.main.web-application-type=none',
        '--opsmind.persistence.enabled=false'
    ) 'flyway-upgrade-v3'
    if ($upgradeExitCode -ne 0) { throw "V002 to V003 upgrade failed with exit $upgradeExitCode." }
    Invoke-ContainerSql -Database 'opsmind_phase4_upgrade' -Sql @'
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history
         WHERE script = 'V003__incident_control_plane.sql' AND success
    ) OR NOT EXISTS (
        SELECT 1 FROM audit_events event_row
         WHERE event_row.event_id = 'c4000003-4444-4444-8444-444444444444'
           AND event_row.tenant_sequence_no = 1
           AND event_row.schema_version = 'legacy-v1'
           AND event_row.previous_digest IS NULL
           AND event_row.event_digest = public.opsmind_compute_audit_digest(
               event_row.tenant_sequence_no, event_row.schema_version,
               event_row.event_id, event_row.organization_id, event_row.actor_id,
               event_row.action, event_row.resource_type, event_row.resource_id,
               event_row.correlation_id, event_row.occurred_at, event_row.payload,
               event_row.previous_digest
           )
    ) THEN
        RAISE EXCEPTION 'V003 did not re-chain the existing audit row';
    END IF;
    IF NOT (SELECT relforcerowsecurity FROM pg_class WHERE oid = 'audit_events'::regclass) THEN
        RAISE EXCEPTION 'V003 did not restore FORCE RLS after audit backfill';
    END IF;
END
$$;
REVOKE CREATE ON SCHEMA public
    FROM opsmind_context_resolver, opsmind_dispatch_resolver;
'@

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$mappedPort/opsmind_phase4"
    $flywayExitCode = Invoke-CapturedProcess $JavaPath @(
        '-jar', $platformJar, '--spring.main.web-application-type=none',
        '--opsmind.persistence.enabled=false'
    ) 'flyway-fresh'
    if ($flywayExitCode -ne 0) { throw "Flyway failed with exit $flywayExitCode." }
    Invoke-ContainerSql -Database 'opsmind_phase4' -Sql @'
REVOKE CREATE ON SCHEMA public
    FROM opsmind_context_resolver, opsmind_dispatch_resolver;
'@

    $env:OPSMIND_EPHEMERAL_DB = 'true'
    $env:OPSMIND_PHASE4_DB_INTEGRATION = 'true'
    $env:PGHOST = '127.0.0.1'
    $env:PGPORT = $mappedPort
    $env:PGDATABASE = 'opsmind_phase4'
    $testsExitCode = Invoke-CapturedProcess $MavenPath @(
        '--batch-mode', '--no-transfer-progress', "-Dmaven.repo.local=$mavenRepository",
        ('-Dtest=MigrationContractTest,IncidentControlPlaneIntegrationTest,' +
            'IncidentHttpPersistenceIntegrationTest,IncidentAuthorizationRevocationIntegrationTest,' +
            'IncidentTransactionalRollbackIntegrationTest,AuditLedgerIntegrationTest'),
        '-f', 'services/platform-api/pom.xml', 'test'
    ) 'maven-tests'
    if ($testsExitCode -ne 0) { throw "Phase 4 guarded tests failed with exit $testsExitCode." }

    $sqlExitCode = Invoke-CapturedProcess $GitBashPath @(
        'scripts/validation/run-phase-04-postgres-contract.sh'
    ) 'sql-contract'
    if ($sqlExitCode -ne 0) { throw "Portable SQL contract failed with exit $sqlExitCode." }
}
catch {
    $failure = $_
}
finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if (-not [string]::IsNullOrWhiteSpace($DockerPath) `
        -and (Test-Path -LiteralPath $DockerPath -PathType Leaf) `
        -and $containerName -match $containerPattern) {
        $createdContainer = @(& $DockerPath ps --all --quiet --filter "name=^/$containerName`$")
        if ($LASTEXITCODE -ne 0) {
            $cleanupErrors.Add('Container cleanup discovery failed.')
        }
        elseif (@($createdContainer | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        }).Count -gt 0) {
            if (Test-Path -LiteralPath $temporaryDirectory -PathType Container) {
                $containerStdout = Join-Path $temporaryDirectory 'postgres-container.stdout.log'
                $containerStderr = Join-Path $temporaryDirectory 'postgres-container.stderr.log'
                $diagnosticFiles.Add($containerStdout)
                $diagnosticFiles.Add($containerStderr)
                try {
                    $logProcess = Start-Process -FilePath $DockerPath `
                        -ArgumentList @('logs', '--tail', '200', $containerName) `
                        -Wait -PassThru -NoNewWindow `
                        -RedirectStandardOutput $containerStdout `
                        -RedirectStandardError $containerStderr
                    if ($logProcess.ExitCode -ne 0) {
                        $cleanupErrors.Add('Container diagnostic capture failed.')
                    }
                }
                catch { $cleanupErrors.Add('Container diagnostic capture failed.') }
            }
            & $DockerPath rm --force $containerName *> $null
            if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add('Container cleanup failed.') }
        }
    }
    $diagnosticPaths = $diagnosticFiles.ToArray()
    [Array]::Reverse($diagnosticPaths)
    foreach ($line in Get-SanitizedTail $diagnosticPaths @(
        $adminPassword, $migrationPassword, $appPassword, $dispatcherPassword,
        $aiRuntimePassword
    )) { $capturedDiagnostics.Add($line) }
    if (Test-Path -LiteralPath $temporaryDirectory) {
        $resolvedTemporary = [IO.Path]::GetFullPath($temporaryDirectory)
        $temporaryPrefix = $temporaryRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if ($resolvedTemporary.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            try { Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force }
            catch { $cleanupErrors.Add('Temporary-directory cleanup failed.') }
        }
        else { $cleanupErrors.Add('Unsafe temporary cleanup target refused.') }
    }
}

$stopwatch.Stop()
$residualContainers = 0
if (-not [string]::IsNullOrWhiteSpace($DockerPath) `
    -and (Test-Path -LiteralPath $DockerPath -PathType Leaf)) {
    $residual = @(& $DockerPath ps --all --quiet --filter "name=^/$containerName`$")
    if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add('Residual-container verification failed.') }
    else { $residualContainers = @($residual | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count }
}

$savedErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
try {
    $revision = (& git -C $repositoryRoot rev-parse --verify HEAD 2>$null | Select-Object -First 1)
    $revisionExitCode = $LASTEXITCODE
    $gitStatus = @(& git -C $repositoryRoot status --porcelain 2>$null)
    $gitStatusExitCode = $LASTEXITCODE
}
finally { $ErrorActionPreference = $savedErrorActionPreference }
if ($revisionExitCode -ne 0 -or $revision -notmatch '^[0-9a-fA-F]{40,64}$') {
    $revision = 'UNBORN'
}
else { $revision = $revision.ToLowerInvariant() }
$dirty = if ($gitStatusExitCode -ne 0) { 'UNKNOWN' }
    elseif ($gitStatus.Count -gt 0) { 'YES' }
    else { 'NO' }
$migrationDigest = (Get-FileHash -Algorithm SHA256 (Join-Path $repositoryRoot `
    'services\platform-api\src\main\resources\db\migration\V003__incident_control_plane.sql')).Hash.ToLowerInvariant()
$sourceManifest = Get-SourceManifest
$jarDigest = if (Test-Path -LiteralPath $platformJar -PathType Leaf) {
    (Get-FileHash -Algorithm SHA256 $platformJar).Hash.ToLowerInvariant()
}
else { 'NOT_BUILT' }
$completedAt = [DateTime]::UtcNow
$gitCommand = Get-Command git -ErrorAction SilentlyContinue
$gitPath = if ($null -eq $gitCommand) { $null } else { $gitCommand.Source }
$metadata = @(
    'EvidenceSchemaVersion=2',
    'Scope=LOCAL_REFERENCE_NOT_RELEASE_PROOF',
    "StartedAtUtc=$($startedAt.ToString('o'))",
    "CompletedAtUtc=$($completedAt.ToString('o'))",
    "DurationSeconds=$([Math]::Round($stopwatch.Elapsed.TotalSeconds, 3))",
    "CodeRevision=$revision",
    "WorkspaceDirty=$dirty",
    "SourceFilesHashed=$($sourceManifest.Count)",
    "SourceManifestSha256=$($sourceManifest.Digest)",
    "MigrationV003Sha256=$migrationDigest",
    "PlatformJarSha256=$jarDigest",
    "JavaVersion=$(Get-ToolVersion $JavaPath @('--version'))",
    "MavenVersion=$(Get-ToolVersion $MavenPath @('--version'))",
    "DockerVersion=$(Get-ToolVersion $DockerPath @('--version'))",
    "GitVersion=$(Get-ToolVersion $gitPath @('--version'))",
    'PackageCommand=mvn --batch-mode --no-transfer-progress -DskipTests package',
    'MigrationCommand=java -jar platform-api.jar --spring.main.web-application-type=none',
    'TestCommand=mvn --batch-mode --no-transfer-progress -Dtest=<guarded-phase4-matrix> test',
    'SqlContractCommand=bash scripts/validation/run-phase-04-postgres-contract.sh'
)

if ($null -ne $failure -or $cleanupErrors.Count -gt 0 -or $residualContainers -ne 0) {
    New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
    $failureLines = [Collections.Generic.List[string]]::new()
    foreach ($line in $metadata) { $failureLines.Add($line) }
    $failureLines.Add('EvidenceKind=FAILURE')
    $failureLines.Add("ContainerName=$containerName")
    $failureLines.Add("PackageExit=$packageExitCode")
    $failureLines.Add("FlywayExit=$flywayExitCode")
    $failureLines.Add("UpgradeExit=$upgradeExitCode")
    $failureLines.Add("TestsExit=$testsExitCode")
    $failureLines.Add("SqlContractExit=$sqlExitCode")
    $failureLines.Add("ResidualContainers=$residualContainers")
    if ($null -ne $failure) {
        $safeMessage = $failure.Exception.Message
        foreach ($secret in @(
            $adminPassword, $migrationPassword, $appPassword, $dispatcherPassword,
            $aiRuntimePassword
        )) {
            $safeMessage = $safeMessage.Replace($secret, '[REDACTED]')
        }
        $failureLines.Add("Error=$safeMessage")
    }
    foreach ($cleanupError in $cleanupErrors) { $failureLines.Add("CleanupError=$cleanupError") }
    foreach ($line in $capturedDiagnostics) { $failureLines.Add("Diagnostic=$line") }
    $failureLines.Add('Result=BLOCK')
    Write-AtomicEvidence $failureEvidencePath $failureLines.ToArray()
    Write-Output 'Result=BLOCK'
    exit 1
}

$common = @(
    $metadata
    "PostgresImage=$PostgresImage",
    "PostgresImageId=$postgresImageId",
    "PostgresVersion=$serverVersion",
    'MigrationRole=NON_SUPERUSER_NON_BYPASSRLS',
    'ResolverOwnershipMembership=INHERIT_TRUE',
    "PackageExit=$packageExitCode",
    "FlywayExit=$flywayExitCode",
    "UpgradeExit=$upgradeExitCode",
    "TestsExit=$testsExitCode",
    "SqlContractExit=$sqlExitCode",
    "ResidualContainers=$residualContainers",
    'Cleanup=PASS'
)
Write-AtomicEvidence $crudEvidencePath ($common + @(
    'IncidentForcedRls=PASS', 'IncidentCrudSql=PASS', 'WrongProjectDenied=PASS',
    'VersionConflictDenied=PASS', 'IllegalTransitionDenied=PASS',
    'TimelineAppendOnly=PASS', 'DispatcherIncidentAccess=DENIED', 'Result=PASS'
))
Write-AtomicEvidence $auditEvidencePath ($common + @(
    'AuditDigestRecomputed=PASS', 'AuditCallerForgeryOverridden=PASS',
    'AuditChainLinear=PASS', 'AuditConcurrentAppend=PASS',
    'AuditUpdateDeleteTruncate=DENIED', 'Result=PASS'
))
Write-Output 'Result=PASS'
