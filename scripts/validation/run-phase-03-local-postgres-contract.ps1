[CmdletBinding()]
param(
    [string]$PsqlPath,
    [string]$JavaPath,
    [string]$MavenPath,
    [string]$GitBashPath = 'C:\Program Files\Git\bin\bash.exe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$migrationPath = Join-Path $repositoryRoot 'services\platform-api\src\main\resources\db\migration\V001__identity_tenant_foundation.sql'
$dispatcherMigrationPath = Join-Path $repositoryRoot 'services\platform-api\src\main\resources\db\migration\V002__outbox_dispatcher_workload.sql'
$contractPath = Join-Path $repositoryRoot 'scripts\validation\run-phase-03-postgres-contract.sh'
$platformJarPath = Join-Path $repositoryRoot 'services\platform-api\target\platform-api.jar'
$poolContractPath = Join-Path $repositoryRoot 'services\platform-api\src\test\java\ai\opsmind\platform\tenancy\TenantRlsPoolIntegrationTest.java'
$outboxContractPath = Join-Path $repositoryRoot 'services\platform-api\src\test\java\ai\opsmind\platform\messaging\TransactionalOutboxIntegrationTest.java'
$inboxContractPath = Join-Path $repositoryRoot 'services\platform-api\src\test\java\ai\opsmind\platform\messaging\TransactionalInboxIntegrationTest.java'
$identityStatusContractPath = Join-Path $repositoryRoot 'services\platform-api\src\test\java\ai\opsmind\platform\identity\PlatformUserStatusVerifierIntegrationTest.java'
$dispatcherContractPath = Join-Path $repositoryRoot 'services\platform-api\src\test\java\ai\opsmind\platform\messaging\OutboxDispatcherWorkloadIntegrationTest.java'
$mavenRepository = Join-Path $repositoryRoot '.opsmind\cache\maven'
$databaseName = 'opsmind_phase3_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$databaseNamePattern = '^opsmind_phase3_[0-9a-f]{12}$'
$rolesCreated = $false
$databaseCreated = $false
$contractExit = 1
$poolContractExit = 1
$packageExit = 1
$failure = $null
$cleanupErrors = [Collections.Generic.List[string]]::new()

if ([string]::IsNullOrWhiteSpace($PsqlPath)) {
    $psqlCommand = Get-Command psql.exe -ErrorAction Stop
    $PsqlPath = $psqlCommand.Source
}
$declaredJdk = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot '.opsmind\cache\tools') `
    -Directory -Filter 'temurin-jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($JavaPath) -and $null -ne $declaredJdk) {
    $JavaPath = Join-Path $declaredJdk.FullName 'bin\java.exe'
}
if ([string]::IsNullOrWhiteSpace($JavaPath)) {
    $JavaPath = (Get-Command java.exe -ErrorAction Stop).Source
}
if ([string]::IsNullOrWhiteSpace($MavenPath)) {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
    $MavenPath = $mavenCommand.Source
}
$JavaPath = [IO.Path]::GetFullPath($JavaPath)
$PsqlPath = [IO.Path]::GetFullPath($PsqlPath)
$MavenPath = [IO.Path]::GetFullPath($MavenPath)
if (-not (Test-Path -LiteralPath $PsqlPath -PathType Leaf)) {
    throw 'psql.exe was not found.'
}
if (-not (Test-Path -LiteralPath $GitBashPath -PathType Leaf)) {
    throw 'Git Bash is required for the portable PostgreSQL contract runner.'
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw 'Java 21 was not found.'
}
if (-not (Test-Path -LiteralPath $MavenPath -PathType Leaf)) {
    throw 'Maven was not found.'
}
$migrationExists = Test-Path -LiteralPath $migrationPath -PathType Leaf
$dispatcherMigrationExists = Test-Path -LiteralPath $dispatcherMigrationPath -PathType Leaf
$contractExists = Test-Path -LiteralPath $contractPath -PathType Leaf
$poolContractExists = Test-Path -LiteralPath $poolContractPath -PathType Leaf
$outboxContractExists = Test-Path -LiteralPath $outboxContractPath -PathType Leaf
$inboxContractExists = Test-Path -LiteralPath $inboxContractPath -PathType Leaf
$identityStatusContractExists = Test-Path -LiteralPath $identityStatusContractPath -PathType Leaf
$dispatcherContractExists = Test-Path -LiteralPath $dispatcherContractPath -PathType Leaf
if (-not $migrationExists -or -not $dispatcherMigrationExists -or -not $contractExists `
    -or -not $poolContractExists -or -not $outboxContractExists -or -not $inboxContractExists `
    -or -not $identityStatusContractExists -or -not $dispatcherContractExists) {
    throw 'Phase 3 migration or contract runner is missing.'
}
if ($databaseName -notmatch $databaseNamePattern) {
    throw 'Generated an unsafe ephemeral database name.'
}

function New-EphemeralPassword {
    $passwordBytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($passwordBytes)
    }
    finally {
        $random.Dispose()
    }
    return ($passwordBytes | ForEach-Object { $_.ToString('x2') }) -join ''
}
$appPassword = New-EphemeralPassword
$dispatcherPassword = New-EphemeralPassword
$aiRuntimePassword = New-EphemeralPassword
while ($dispatcherPassword -ceq $appPassword) {
    $dispatcherPassword = New-EphemeralPassword
}
$adminPassword = if ([string]::IsNullOrWhiteSpace($env:PGPASSWORD)) {
    'placeholder-local-trust-only'
}
else {
    $env:PGPASSWORD
}
while ($appPassword -ceq $adminPassword) {
    $appPassword = New-EphemeralPassword
}
while ($dispatcherPassword -ceq $adminPassword -or $dispatcherPassword -ceq $appPassword) {
    $dispatcherPassword = New-EphemeralPassword
}
while ($aiRuntimePassword -in @($adminPassword, $appPassword, $dispatcherPassword)) {
    $aiRuntimePassword = New-EphemeralPassword
}

$environmentNames = @(
    'OPSMIND_EPHEMERAL_DB', 'PGHOST', 'PGPORT', 'PGDATABASE',
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
    'POSTGRES_APP_USER', 'POSTGRES_APP_PASSWORD',
    'POSTGRES_DISPATCHER_USER', 'POSTGRES_DISPATCHER_PASSWORD',
    'POSTGRES_AI_RUNTIME_USER', 'POSTGRES_AI_RUNTIME_PASSWORD',
    'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
    'OPSMIND_FLYWAY_ENABLED', 'OPSMIND_PHASE3_DB_INTEGRATION',
    'JAVA_HOME'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-AdminPsql {
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $PsqlPath -h 127.0.0.1 -p 5432 -U postgres -d $Database --quiet -v ON_ERROR_STOP=1 @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed for database $Database with exit $LASTEXITCODE."
    }
}

Write-Output "EphemeralDatabase=$databaseName"
try {
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $JavaPath)
    Push-Location $repositoryRoot
    try {
        & $MavenPath `
            '--batch-mode' `
            '--no-transfer-progress' `
            "-Dmaven.repo.local=$mavenRepository" `
            '-DskipTests' `
            '-f' 'services/platform-api/pom.xml' `
            'package'
        $packageExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($packageExit -ne 0 -or -not (Test-Path -LiteralPath $platformJarPath -PathType Leaf)) {
        throw "Current Platform API packaging failed with exit $packageExit."
    }

    $existingRoles = & $PsqlPath -h 127.0.0.1 -p 5432 -U postgres -d postgres `
        --tuples-only --no-align -v ON_ERROR_STOP=1 `
        -c "SELECT count(*) FROM pg_roles WHERE rolname IN ('opsmind_app','opsmind_context_resolver','opsmind_dispatcher','opsmind_dispatch_resolver','opsmind_ai_runtime');"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect local PostgreSQL roles.'
    }
    if ([int]$existingRoles -ne 0) {
        throw 'Refusing the ephemeral test because fixed OpsMind roles already exist.'
    }

    $roleSql = "CREATE ROLE opsmind_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$appPassword'; " +
        'CREATE ROLE opsmind_context_resolver NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS; ' +
        "CREATE ROLE opsmind_dispatcher LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$dispatcherPassword'; " +
        'CREATE ROLE opsmind_dispatch_resolver NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS; ' +
        "CREATE ROLE opsmind_ai_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$aiRuntimePassword';"
    $roleSql | & $PsqlPath -h 127.0.0.1 -p 5432 -U postgres -d postgres --quiet -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) {
        throw 'Ephemeral role provisioning failed.'
    }
    $rolesCreated = $true

    Invoke-AdminPsql -Database postgres -Arguments @('-c', "CREATE DATABASE `"$databaseName`";")
    $databaseCreated = $true

    $env:SPRING_PROFILES_ACTIVE = 'persistence'
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5432/$databaseName"
    $env:SPRING_DATASOURCE_USERNAME = 'postgres'
    $env:SPRING_DATASOURCE_PASSWORD = $adminPassword
    $env:OPSMIND_FLYWAY_ENABLED = 'true'
    & $JavaPath -jar $platformJarPath `
        '--spring.main.web-application-type=none' `
        '--opsmind.persistence.enabled=false'
    if ($LASTEXITCODE -ne 0) {
        throw 'The packaged Flyway migration failed in the ephemeral database.'
    }

    $env:OPSMIND_EPHEMERAL_DB = 'true'
    $env:PGHOST = '127.0.0.1'
    $env:PGPORT = '5432'
    $env:PGDATABASE = $databaseName
    $env:POSTGRES_DB = $databaseName
    $env:POSTGRES_USER = 'postgres'
    $env:POSTGRES_PASSWORD = $adminPassword
    $env:POSTGRES_APP_USER = 'opsmind_app'
    $env:POSTGRES_APP_PASSWORD = $appPassword
    $env:POSTGRES_DISPATCHER_USER = 'opsmind_dispatcher'
    $env:POSTGRES_DISPATCHER_PASSWORD = $dispatcherPassword
    $env:POSTGRES_AI_RUNTIME_USER = 'opsmind_ai_runtime'
    $env:POSTGRES_AI_RUNTIME_PASSWORD = $aiRuntimePassword
    $env:OPSMIND_PHASE3_DB_INTEGRATION = 'true'

    Push-Location $repositoryRoot
    try {
        & $MavenPath `
            '--batch-mode' `
            '--no-transfer-progress' `
            "-Dmaven.repo.local=$mavenRepository" `
            '-Dtest=TenantRlsPoolIntegrationTest,TransactionalOutboxIntegrationTest,TransactionalInboxIntegrationTest,PlatformUserStatusVerifierIntegrationTest,OutboxDispatcherWorkloadIntegrationTest' `
            '-f' 'services/platform-api/pom.xml' `
            'test'
        $poolContractExit = $LASTEXITCODE
        if ($poolContractExit -ne 0) {
            throw "PostgreSQL Java integration contracts failed with exit $poolContractExit."
        }

        & $GitBashPath 'scripts/validation/run-phase-03-postgres-contract.sh'
        $contractExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($contractExit -ne 0) {
        throw "PostgreSQL contract failed with exit $contractExit."
    }
}
catch {
    $failure = $_
}
finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }

    if ($databaseCreated) {
        if ($databaseName -notmatch $databaseNamePattern) {
            $cleanupErrors.Add('Refused unsafe database cleanup target.')
        }
        else {
            try {
                Invoke-AdminPsql -Database postgres -Arguments @(
                    '-c',
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$databaseName' AND pid <> pg_backend_pid();"
                )
                Invoke-AdminPsql -Database postgres -Arguments @('-c', "DROP DATABASE `"$databaseName`";")
            }
            catch {
                $cleanupErrors.Add($_.Exception.Message)
            }
        }
    }
    if ($rolesCreated) {
        try {
            Invoke-AdminPsql -Database postgres -Arguments @(
                '-c',
                'DROP ROLE IF EXISTS opsmind_ai_runtime; DROP ROLE IF EXISTS opsmind_dispatcher; DROP ROLE IF EXISTS opsmind_app; DROP ROLE IF EXISTS opsmind_dispatch_resolver; DROP ROLE IF EXISTS opsmind_context_resolver;'
            )
        }
        catch {
            $cleanupErrors.Add($_.Exception.Message)
        }
    }
}

$residualObjects = & $PsqlPath -h 127.0.0.1 -p 5432 -U postgres -d postgres `
    --tuples-only --no-align -v ON_ERROR_STOP=1 `
    -c "SELECT (SELECT count(*) FROM pg_database WHERE datname='$databaseName') + (SELECT count(*) FROM pg_roles WHERE rolname IN ('opsmind_app','opsmind_context_resolver','opsmind_dispatcher','opsmind_dispatch_resolver','opsmind_ai_runtime'));"
if ($LASTEXITCODE -ne 0) {
    $cleanupErrors.Add('Unable to verify ephemeral PostgreSQL cleanup.')
}

Write-Output "ContractExit=$contractExit"
Write-Output "PoolContractExit=$poolContractExit"
Write-Output "PackageExit=$packageExit"
Write-Output "ResidualObjects=$residualObjects"
foreach ($cleanupError in $cleanupErrors) {
    [Console]::Error.WriteLine("CleanupError=$cleanupError")
}
if ($null -ne $failure) {
    [Console]::Error.WriteLine("ContractError=$($failure.Exception.Message)")
}
if ($null -ne $failure -or $cleanupErrors.Count -gt 0 -or [int]$residualObjects -ne 0) {
    Write-Output 'Result=BLOCK'
    exit 1
}
Write-Output 'Result=PASS'
