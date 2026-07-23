[CmdletBinding()]
param(
    [ValidateRange(1, 1000)][int]$WarmRuns = 100,
    [ValidateRange(100, 30000)][int]$P95ThresholdMs = 5000,
    [string]$ReportPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
. (Join-Path $PSScriptRoot 'cross-service-harness-support.ps1')

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $repositoryRoot '.opsmind\reports\cross-service-trace.json'
}
$ReportPath = [IO.Path]::GetFullPath($ReportPath)
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.opsmind\reports'))
if (-not $ReportPath.StartsWith(
    $reportRoot + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw 'Cross-service report must stay under .opsmind/reports.'
}

& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Storage capacity preflight failed.' }
& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $repositoryRoot 'scripts\storage\assert-storage-roots.ps1') -CreateMissing
if ($LASTEXITCODE -ne 0) { throw 'Storage root preflight failed.' }

$executables = @{
    Docker = (Get-Command docker -CommandType Application | Select-Object -First 1).Path
    Java = (Get-Command java -CommandType Application | Select-Object -First 1).Path
    Keytool = (Get-Command keytool -CommandType Application | Select-Object -First 1).Path
    Node = (Get-Command node -CommandType Application | Select-Object -First 1).Path
    OpenSsl = (Get-Command openssl -CommandType Application | Select-Object -First 1).Path
}
$python = Join-Path $repositoryRoot 'services\ai-runtime\.venv\Scripts\python.exe'
$platformJar = Join-Path $repositoryRoot 'services\platform-api\target\platform-api.jar'
$gatewayJar = Join-Path $repositoryRoot 'services\tool-gateway\target\tool-gateway.jar'
foreach ($requiredFile in @($python, $platformJar, $gatewayJar)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required cross-service artifact is missing: $requiredFile"
    }
}

$runId = [guid]::NewGuid().ToString('N')
$runRoot = Join-Path $repositoryRoot ".opsmind\cross-service\$runId"
$containerName = "opsmind-cross-service-postgres-$($runId.Substring(0, 12))"
$success = $false
$postgresStarted = $false
$secretFiles = New-Object 'System.Collections.Generic.List[string]'

$organizationId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
$projectId = 'aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
$userId = '11111111-1111-4111-8111-111111111111'
$incidentId = '70000000-0000-4000-8000-000000000001'
$operatorSubject = 'cross-service-operator'
$reservedPorts = @(Get-CrossServiceAvailablePorts -Count 7)
if ($reservedPorts.Count -ne 7 -or @($reservedPorts | Sort-Object -Unique).Count -ne 7) {
    throw 'Unable to reserve seven distinct cross-service ports.'
}
$databasePort, $identityPort, $providerPort, $prometheusPort,
    $aiPort, $gatewayPort, $platformPort = $reservedPorts

$migrationPassword = New-CrossServiceSecret
$appPassword = New-CrossServiceSecret
$dispatcherPassword = New-CrossServiceSecret
$aiPassword = New-CrossServiceSecret
$gatewayMigratorPassword = New-CrossServiceSecret
$gatewayPassword = New-CrossServiceSecret
$runnerClientSecret = New-CrossServiceSecret
$workloadClientSecret = New-CrossServiceSecret
$issuer = "https://127.0.0.1:$identityPort/opsmind"
$capabilityIssuer = "https://127.0.0.1:$identityPort/opsmind-capability"

try {
    [void](New-Item -ItemType Directory -Path $runRoot -Force)
    [void](New-Item -ItemType Directory -Path $reportRoot -Force)
    if (Test-Path -LiteralPath $ReportPath -PathType Leaf) {
        $archiveRoot = Join-Path $reportRoot 'archive'
        [void](New-Item -ItemType Directory -Path $archiveRoot -Force)
        $archiveName = 'cross-service-trace-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '.json'
        Move-Item -LiteralPath $ReportPath -Destination (Join-Path $archiveRoot $archiveName)
    }

    $tlsKey = Join-Path $runRoot 'identity-tls-private.pem'
    $tlsCertificate = Join-Path $runRoot 'identity-tls-certificate.pem'
    $capabilityKey = Join-Path $runRoot 'capability-private.pem'
    $capabilityJwks = Join-Path $runRoot 'capability-jwks.json'
    $trustStore = Join-Path $runRoot 'java-truststore.p12'
    $javaHostsFile = Join-Path $runRoot 'java-hosts.txt'
    $postgresEnvironment = Join-Path $runRoot 'postgres.env'
    $operatorTokenFile = Join-Path $runRoot 'operator-access-token.txt'
    foreach ($secretPath in @(
        $tlsKey, $capabilityKey, $postgresEnvironment, $operatorTokenFile
    )) {
        $secretFiles.Add($secretPath)
    }

    Invoke-CrossServiceNativeQuiet -Executable $executables.OpenSsl -Arguments @(
        'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
        '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
        '-keyout', $tlsKey, '-out', $tlsCertificate
    ) -FailureMessage 'Unable to generate fixture TLS material.'
    Invoke-CrossServiceNativeQuiet -Executable $executables.OpenSsl -Arguments @(
        'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
        '-out', $capabilityKey
    ) -FailureMessage 'Unable to generate capability signing material.'
    Invoke-CrossServiceNativeQuiet -Executable $executables.Keytool -Arguments @(
        '-importcert', '-noprompt', '-alias', 'opsmind-cross-service',
        '-file', $tlsCertificate, '-keystore', $trustStore,
        '-storetype', 'PKCS12', '-storepass', 'changeit'
    ) -FailureMessage 'Unable to generate the Java trust store.'
    [IO.File]::WriteAllText(
        $javaHostsFile,
        "127.0.0.1 prometheus.opsmind.internal`n",
        [Text.UTF8Encoding]::new($false)
    )

    $postgresLines = @(
        'POSTGRES_DB=opsmind',
        'POSTGRES_USER=opsmind_migrator',
        "POSTGRES_PASSWORD=$migrationPassword"
    )
    [IO.File]::WriteAllLines($postgresEnvironment, $postgresLines, [Text.UTF8Encoding]::new($false))
    $containerId = & $executables.Docker run --detach --rm --name $containerName `
        --env-file $postgresEnvironment `
        --tmpfs '/var/lib/postgresql/data:rw,noexec,nosuid,size=512m' `
        --publish "127.0.0.1:${databasePort}:5432" postgres:17-alpine
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw 'Unable to start the disposable PostgreSQL container.'
    }
    $postgresStarted = $true
    $postgresDeadline = [DateTime]::UtcNow.AddSeconds(60)
    $consecutiveReadyChecks = 0
    do {
        & $executables.Docker exec $containerName pg_isready -U opsmind_migrator -d opsmind *> $null
        if ($LASTEXITCODE -eq 0) {
            $consecutiveReadyChecks++
            if ($consecutiveReadyChecks -ge 3) { break }
        }
        else {
            $consecutiveReadyChecks = 0
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $postgresDeadline)
    if ($consecutiveReadyChecks -lt 3) { throw 'Disposable PostgreSQL did not become stably ready.' }

    $roleSql = @"
CREATE ROLE opsmind_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$appPassword';
CREATE ROLE opsmind_context_resolver NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE opsmind_dispatcher LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$dispatcherPassword';
CREATE ROLE opsmind_dispatch_resolver NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE opsmind_ai_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$aiPassword';
CREATE ROLE opsmind_tool_gateway_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$gatewayMigratorPassword';
CREATE ROLE opsmind_tool_gateway LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$gatewayPassword';
CREATE SCHEMA tool_gateway AUTHORIZATION opsmind_tool_gateway_migrator;
REVOKE ALL ON SCHEMA tool_gateway FROM PUBLIC;
"@
    [void](Invoke-CrossServiceSql -DockerPath $executables.Docker `
        -ContainerName $containerName -Sql $roleSql)

    $jdbcUrl = "jdbc:postgresql://127.0.0.1:$databasePort/opsmind"
    $migrationEnvironment = @{
        OPSMIND_SECURITY_MODE = 'fail-closed'
        SPRING_PROFILES_ACTIVE = 'persistence'
        SPRING_DATASOURCE_URL = $jdbcUrl
        SPRING_DATASOURCE_USERNAME = 'opsmind_migrator'
        SPRING_DATASOURCE_PASSWORD = $migrationPassword
        OPSMIND_FLYWAY_ENABLED = 'true'
        OPSMIND_PERSISTENCE_ENABLED = 'false'
    }
    Invoke-CrossServiceProcess -Executable $executables.Java -Arguments @(
        '-jar', $platformJar, '--spring.main.web-application-type=none'
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'platform-migrate.stdout.log') `
        -StderrPath (Join-Path $runRoot 'platform-migrate.stderr.log') `
        -Environment $migrationEnvironment

    $gatewayMigrationEnvironment = @{
        SPRING_PROFILES_ACTIVE = 'persistence'
        TOOL_GATEWAY_DATABASE_URL = $jdbcUrl
        TOOL_GATEWAY_DATABASE_USER = 'opsmind_tool_gateway_migrator'
        TOOL_GATEWAY_DATABASE_PASSWORD = $gatewayMigratorPassword
        TOOL_GATEWAY_PERSISTENCE_ENABLED = 'true'
        TOOL_GATEWAY_FLYWAY_ENABLED = 'true'
    }
    Invoke-CrossServiceProcess -Executable $executables.Java -Arguments @(
        '-jar', $gatewayJar, '--spring.main.web-application-type=none'
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'gateway-migrate.stdout.log') `
        -StderrPath (Join-Path $runRoot 'gateway-migrate.stderr.log') `
        -Environment $gatewayMigrationEnvironment

    $seedSql = @"
INSERT INTO organizations (id, slug, name)
VALUES ('$organizationId', 'cross-service', 'Cross-service verification');
INSERT INTO platform_users (id, issuer, subject, display_name)
VALUES ('$userId', '$issuer', '$operatorSubject', 'Cross-service operator');
INSERT INTO organization_memberships (organization_id, user_id, role)
VALUES ('$organizationId', '$userId', 'SRE');
INSERT INTO projects (id, organization_id, slug, name)
VALUES ('$projectId', '$organizationId', 'opsmind-api', 'OpsMind API');
INSERT INTO project_memberships (organization_id, project_id, user_id, role)
VALUES ('$organizationId', '$projectId', '$userId', 'SRE');
INSERT INTO incidents (
    id, organization_id, project_id, title, description, severity, status,
    created_by, updated_by
) VALUES (
    '$incidentId', '$organizationId', '$projectId',
    'Checkout latency regression',
    'Checkout requests slowed immediately after the payment-router deployment.',
    'SEV2', 'OPEN', '$userId', '$userId'
);
"@
    [void](Invoke-CrossServiceSql -DockerPath $executables.Docker `
        -ContainerName $containerName -Sql $seedSql)

    $identityEnvironment = @{
        OPSMIND_IDENTITY_HOST = '127.0.0.1'
        OPSMIND_IDENTITY_PORT = "$identityPort"
        OPSMIND_IDENTITY_TLS_KEY_FILE = $tlsKey
        OPSMIND_IDENTITY_TLS_CERT_FILE = $tlsCertificate
        OPSMIND_CAPABILITY_PRIVATE_KEY_FILE = $capabilityKey
        OPSMIND_CAPABILITY_JWKS_FILE = $capabilityJwks
        OPSMIND_RUNNER_CLIENT_SECRET = $runnerClientSecret
        OPSMIND_WORKLOAD_CLIENT_SECRET = $workloadClientSecret
    }
    $identityProcess = Start-CrossServiceProcess -Executable $executables.Node `
        -Arguments @(
            (Join-Path $PSScriptRoot 'fixture-identity.mjs'),
            "--opsmind-cross-service-run-id=$runId"
        ) `
        -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'identity.stdout.log') `
        -StderrPath (Join-Path $runRoot 'identity.stderr.log') `
        -Environment $identityEnvironment
    Wait-CrossServiceTcp -Port $identityPort -Process $identityProcess
    Wait-CrossServiceHttps `
        -Uri "https://127.0.0.1:$identityPort/__opsmind/status" `
        -Process $identityProcess
    if (-not (Test-Path -LiteralPath $capabilityJwks -PathType Leaf)) {
        throw 'Fixture identity did not publish capability JWKS.'
    }

    $providerProcess = Start-CrossServiceProcess -Executable $python -Arguments @(
        (Join-Path $PSScriptRoot 'fixture-provider.py'), '--host', '127.0.0.1',
        '--port', "$providerPort", '--opsmind-cross-service-run-id', $runId
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'provider.stdout.log') `
        -StderrPath (Join-Path $runRoot 'provider.stderr.log') `
        -Environment @{}
    Wait-CrossServiceTcp -Port $providerPort -Process $providerProcess

    $prometheusProcess = Start-CrossServiceProcess -Executable $executables.Node `
        -Arguments @(
            (Join-Path $PSScriptRoot 'fixture-prometheus.mjs'),
            "--opsmind-cross-service-run-id=$runId"
        ) `
        -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'prometheus.stdout.log') `
        -StderrPath (Join-Path $runRoot 'prometheus.stderr.log') `
        -Environment @{
            OPSMIND_PROMETHEUS_HOST = '127.0.0.1'
            OPSMIND_PROMETHEUS_PORT = "$prometheusPort"
        }
    Wait-CrossServiceHttp -Uri "http://127.0.0.1:$prometheusPort/-/ready" `
        -Process $prometheusProcess

    $egressPolicy = Join-Path $runRoot 'ai-egress-policy.json'
    [IO.File]::WriteAllText(
        $egressPolicy,
        '{"version":"egress-policy-v1","rules":[{"tenant_id":"' + $organizationId +
        '","purpose":"incident_investigation","provider":"fixture","region":"sg-local",' +
        '"data_classes":["redacted_incident_summary","redacted_metrics"]}]}',
        [Text.UTF8Encoding]::new($false)
    )
    $aiEnvironment = @{
        PYTHONPATH = (Join-Path $repositoryRoot 'services\ai-runtime\src')
        AI_PROVIDER = 'fixture'
        AI_FIXTURE_PROVIDER_ENABLED = 'true'
        DEEPSEEK_API_BASE_URL = "http://127.0.0.1:$providerPort/v1"
        DEEPSEEK_MODEL = 'deepseek-v4-flash'
        OPS_ENABLE_DEEPSEEK_EGRESS = 'true'
        AI_ALLOWED_DATA_CLASSES = 'redacted_incident_summary,redacted_metrics'
        AI_PROVIDER_REGION = 'sg-local'
        AI_EGRESS_POLICY_FILE = $egressPolicy
        AI_INPUT_COST_USD_PER_MILLION = '0.1'
        AI_OUTPUT_COST_USD_PER_MILLION = '0.1'
        AI_RUNTIME_STATE_BACKEND = 'postgres'
        AI_RUNTIME_DATABASE_HOST = '127.0.0.1'
        AI_RUNTIME_DATABASE_PORT = "$databasePort"
        AI_RUNTIME_DATABASE_NAME = 'opsmind'
        AI_RUNTIME_DATABASE_USER = 'opsmind_ai_runtime'
        AI_RUNTIME_DATABASE_PASSWORD = $aiPassword
        OPSMIND_AI_CAPABILITY_ISSUER = $capabilityIssuer
        OPSMIND_AI_CAPABILITY_AUDIENCE = 'opsmind-ai-runtime'
        OPSMIND_AI_CAPABILITY_JWKS_FILE = $capabilityJwks
        OPSMIND_CROSS_SERVICE_AI_PORT = "$aiPort"
    }
    $aiProcess = Start-CrossServiceProcess -Executable $python -Arguments @(
        (Join-Path $PSScriptRoot 'run-ai-runtime.py'),
        "--opsmind-cross-service-run-id=$runId"
    ) -WorkingDirectory (Join-Path $repositoryRoot 'services\ai-runtime') `
        -StdoutPath (Join-Path $runRoot 'ai-runtime.stdout.log') `
        -StderrPath (Join-Path $runRoot 'ai-runtime.stderr.log') `
        -Environment $aiEnvironment
    Wait-CrossServiceHttp -Uri "http://127.0.0.1:$aiPort/ready" -Process $aiProcess

    $gatewayEnvironment = @{
        TOOL_GATEWAY_PORT = "$gatewayPort"
        SPRING_PROFILES_ACTIVE = 'persistence,prometheus'
        TOOL_GATEWAY_DATABASE_URL = $jdbcUrl
        TOOL_GATEWAY_DATABASE_USER = 'opsmind_tool_gateway'
        TOOL_GATEWAY_DATABASE_PASSWORD = $gatewayPassword
        TOOL_GATEWAY_PERSISTENCE_ENABLED = 'true'
        TOOL_GATEWAY_FLYWAY_ENABLED = 'false'
        TOOL_GATEWAY_PROMETHEUS_ENABLED = 'true'
        TOOL_GATEWAY_PROMETHEUS_BASE_URI = "http://prometheus.opsmind.internal:$prometheusPort"
        TOOL_GATEWAY_PROMETHEUS_ALLOW_INTERNAL_CLEARTEXT = 'true'
        TOOL_GATEWAY_CAPABILITY_ISSUER = $capabilityIssuer
        TOOL_GATEWAY_CAPABILITY_JWK_SET_URI = "$issuer/capability-jwks"
        TOOL_GATEWAY_WORKLOAD_ISSUER = $issuer
        TOOL_GATEWAY_WORKLOAD_JWK_SET_URI = "$issuer/jwks"
    }
    $gatewayProcess = Start-CrossServiceProcess -Executable $executables.Java `
        -Arguments @(
            "-Djavax.net.ssl.trustStore=$trustStore",
            '-Djavax.net.ssl.trustStorePassword=changeit',
            "-Djdk.net.hosts.file=$javaHostsFile",
            '-jar',
            $gatewayJar,
            "--opsmind-cross-service-run-id=$runId"
        ) `
        -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'tool-gateway.stdout.log') `
        -StderrPath (Join-Path $runRoot 'tool-gateway.stderr.log') `
        -Environment $gatewayEnvironment
    Wait-CrossServiceHttp -Uri "http://127.0.0.1:$gatewayPort/ready" -Process $gatewayProcess

    $platformEnvironment = @{
        PLATFORM_API_PORT = "$platformPort"
        OPSMIND_SECURITY_MODE = 'oidc'
        OIDC_ISSUER_URL = $issuer
        OIDC_AUDIENCE = 'opsmind-platform-api'
        OIDC_REQUIRED_AMR = 'mfa'
        OIDC_MAX_TOKEN_LIFETIME = 'PT5M'
        OIDC_CLOCK_SKEW = 'PT30S'
        OIDC_JWKS_REFRESH_MINIMUM_INTERVAL = 'PT1S'
        SPRING_PROFILES_ACTIVE = 'persistence'
        SPRING_DATASOURCE_URL = $jdbcUrl
        SPRING_DATASOURCE_USERNAME = 'opsmind_app'
        SPRING_DATASOURCE_PASSWORD = $appPassword
        OPSMIND_FLYWAY_ENABLED = 'false'
        OPSMIND_INVESTIGATION_V1_ENABLED = 'true'
        OPSMIND_INVESTIGATION_FIXTURE = 'false'
        OPSMIND_INVESTIGATION_STORE = 'postgres'
        OPSMIND_AI_RUNTIME_CLIENT_ENABLED = 'true'
        OPSMIND_AI_RUNTIME_ENDPOINT = "http://127.0.0.1:$aiPort/api/v1/analysis"
        OPSMIND_AI_RUNTIME_ALLOW_LOCAL_CLEARTEXT = 'true'
        OPSMIND_AI_CAPABILITY_ISSUANCE_ENABLED = 'true'
        OPSMIND_AI_CAPABILITY_ISSUER = $capabilityIssuer
        OPSMIND_AI_CAPABILITY_AUDIENCE = 'opsmind-ai-runtime'
        OPSMIND_AI_CAPABILITY_KEY_ID = 'cross-service-capability-v1'
        OPSMIND_AI_CAPABILITY_PRIVATE_KEY_FILE = $capabilityKey
        OPSMIND_TOOL_GATEWAY_CLIENT_ENABLED = 'true'
        OPSMIND_TOOL_GATEWAY_ENDPOINT = "http://127.0.0.1:$gatewayPort/internal/v1/tools/execute"
        OPSMIND_TOOL_GATEWAY_ALLOW_LOCAL_CLEARTEXT = 'true'
        OPSMIND_TOOL_CAPABILITY_ISSUANCE_ENABLED = 'true'
        OPSMIND_TOOL_CAPABILITY_ISSUER = $capabilityIssuer
        OPSMIND_TOOL_CAPABILITY_AUDIENCE = 'opsmind-tool-gateway'
        OPSMIND_TOOL_CAPABILITY_AUTHORIZED_PARTY = 'opsmind-platform-api'
        OPSMIND_TOOL_CAPABILITY_KEY_ID = 'cross-service-capability-v1'
        OPSMIND_TOOL_CAPABILITY_PRIVATE_KEY_FILE = $capabilityKey
        OPSMIND_TOOL_WORKLOAD_AUTH_ENABLED = 'true'
        OPSMIND_TOOL_WORKLOAD_ISSUER = $issuer
        OPSMIND_TOOL_WORKLOAD_TOKEN_ENDPOINT = "$issuer/oauth2/token"
        OPSMIND_TOOL_WORKLOAD_ALLOW_LOCAL_CLEARTEXT = 'false'
        OPSMIND_TOOL_WORKLOAD_AUDIENCE = 'opsmind-tool-gateway-workload'
        OPSMIND_TOOL_WORKLOAD_CLIENT_ID = 'opsmind-platform-api'
        OPSMIND_TOOL_WORKLOAD_CLIENT_SECRET = $workloadClientSecret
        OPSMIND_TOOL_WORKLOAD_SCOPE = 'tool.execute'
    }
    $platformProcess = Start-CrossServiceProcess -Executable $executables.Java `
        -Arguments @(
            "-Djavax.net.ssl.trustStore=$trustStore",
            '-Djavax.net.ssl.trustStorePassword=changeit',
            '-jar',
            $platformJar,
            "--opsmind-cross-service-run-id=$runId"
        ) `
        -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'platform-api.stdout.log') `
        -StderrPath (Join-Path $runRoot 'platform-api.stderr.log') `
        -Environment $platformEnvironment
    Wait-CrossServiceHttp -Uri "http://127.0.0.1:$platformPort/actuator/health" `
        -Process $platformProcess

    $tokenEnvironment = @{
        OPSMIND_TOKEN_ENDPOINT = "$issuer/oauth2/token"
        OPSMIND_TOKEN_CA_FILE = $tlsCertificate
        OPSMIND_TOKEN_CLIENT_ID = 'opsmind-cross-service-runner'
        OPSMIND_TOKEN_CLIENT_SECRET = $runnerClientSecret
        OPSMIND_TOKEN_SCOPE = 'incident:read incident:analyze'
        OPSMIND_TOKEN_OUTPUT_FILE = $operatorTokenFile
    }
    Invoke-CrossServiceProcess -Executable $executables.Node -Arguments @(
        (Join-Path $PSScriptRoot 'fetch-access-token.mjs')
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'token-fetch.stdout.log') `
        -StderrPath (Join-Path $runRoot 'token-fetch.stderr.log') `
        -Environment $tokenEnvironment
    $operatorToken = [IO.File]::ReadAllText($operatorTokenFile).Trim()
    if ($operatorToken -notmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$') {
        throw 'Fixture operator token is malformed.'
    }

    $runnerEnvironment = @{
        NODE_EXTRA_CA_CERTS = $tlsCertificate
        OPSMIND_PLATFORM_BASE_URL = "http://127.0.0.1:$platformPort"
        OPSMIND_ACCESS_TOKEN = $operatorToken
        OPSMIND_ORGANIZATION_ID = $organizationId
        OPSMIND_PROJECT_ID = $projectId
        OPSMIND_INCIDENT_ID = $incidentId
        OPSMIND_IDENTITY_STATUS_URL = "https://127.0.0.1:$identityPort/__opsmind/status"
        OPSMIND_PROVIDER_STATUS_URL = "http://127.0.0.1:$providerPort/__opsmind/status"
        OPSMIND_PROMETHEUS_STATUS_URL = "http://127.0.0.1:$prometheusPort/__opsmind/status"
        OPSMIND_WARM_RUNS = "$WarmRuns"
        OPSMIND_P95_THRESHOLD_MS = "$P95ThresholdMs"
        OPSMIND_ENVIRONMENT = 'local-disposable-cross-service'
        OPSMIND_TRACE_REPORT = $ReportPath
    }
    Invoke-CrossServiceProcess -Executable $executables.Node -Arguments @(
        (Join-Path $PSScriptRoot 'run-investigation-slice.mjs')
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'runner.stdout.log') `
        -StderrPath (Join-Path $runRoot 'runner.stderr.log') `
        -Environment $runnerEnvironment

    $countSql = @"
SELECT
    (SELECT count(*) FROM investigation_runs)::text || '|' ||
    (SELECT count(*) FROM evidence_records)::text || '|' ||
    (SELECT count(*) FROM ai_runtime.analysis_invocations)::text || '|' ||
    (SELECT count(*) FROM tool_gateway.execution_receipts)::text || '|' ||
    (SELECT count(*) FROM tool_gateway.tool_audit_events)::text;
"@
    $countOutput = @(Invoke-CrossServiceSql -DockerPath $executables.Docker `
        -ContainerName $containerName -Sql $countSql) |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ -match '^\d+\|\d+\|\d+\|\d+\|\d+$' } |
        Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($countOutput)) {
        throw 'Unable to read durable cross-service counts.'
    }
    $counts = $countOutput.Split('|')
    $finalizeEnvironment = @{
        OPSMIND_TRACE_REPORT = $ReportPath
        OPSMIND_COUNT_INVESTIGATION_RUNS = $counts[0]
        OPSMIND_COUNT_EVIDENCE_RECORDS = $counts[1]
        OPSMIND_COUNT_ANALYSIS_INVOCATIONS = $counts[2]
        OPSMIND_COUNT_TOOL_RECEIPTS = $counts[3]
        OPSMIND_COUNT_TOOL_AUDIT_EVENTS = $counts[4]
    }
    Invoke-CrossServiceProcess -Executable $executables.Node -Arguments @(
        (Join-Path $PSScriptRoot 'finalize-cross-service-report.mjs')
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'finalizer.stdout.log') `
        -StderrPath (Join-Path $runRoot 'finalizer.stderr.log') `
        -Environment $finalizeEnvironment

    $logFiles = @(Get-ChildItem -LiteralPath $runRoot -File -Filter '*.log')
    $prohibitedLogPattern = '(?i)(authorization:\s*(?:bearer|basic)|api[_-]?key\s*[:=]|' +
        'client[_-]?secret\s*[:=]|password\s*[:=]|reasoning_content|-----BEGIN .*PRIVATE KEY-----)'
    foreach ($logFile in $logFiles) {
        if (Select-String -LiteralPath $logFile.FullName -Pattern $prohibitedLogPattern -Quiet) {
            throw "A managed service log contains prohibited material: $($logFile.Name)"
        }
    }

    $success = $true
    Write-Output "CrossServiceVerification=PASS WarmRuns=$WarmRuns Report=$ReportPath"
}
finally {
    $cleanupArguments = @(
        '-NoLogo',
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        (Join-Path $PSScriptRoot 'cleanup-cross-service-run.ps1'),
        '-RunId',
        $runId
    )
    if ($success) {
        $cleanupArguments += '-RemoveRunDirectory'
    }
    & powershell.exe @cleanupArguments
    $cleanupExitCode = $LASTEXITCODE
    $migrationPassword = $null
    $appPassword = $null
    $dispatcherPassword = $null
    $aiPassword = $null
    $gatewayMigratorPassword = $null
    $gatewayPassword = $null
    $runnerClientSecret = $null
    $workloadClientSecret = $null
    $operatorToken = $null
    if ($cleanupExitCode -ne 0) {
        $success = $false
        throw 'Cross-service cleanup helper failed.'
    }
}
