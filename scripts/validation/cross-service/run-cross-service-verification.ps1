[CmdletBinding()]
param(
    [ValidateRange(1, 1000)][int]$WarmRuns = 100,
    [ValidateRange(100, 30000)][int]$P95ThresholdMs = 5000,
    [ValidateSet('A', 'B', 'C')][string]$Scenario = 'A',
    [string]$ReportPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
. (Join-Path $PSScriptRoot 'cross-service-harness-support.ps1')
$pathComparison = Get-CrossServicePathComparison
$hostPowerShell = (Get-Process -Id $PID).Path

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-CrossServicePath -BasePath $repositoryRoot `
        -ChildPath @('.opsmind', 'reports', 'cross-service-trace.json')
}
$ReportPath = [IO.Path]::GetFullPath($ReportPath)
$reportRoot = [IO.Path]::GetFullPath(
    (Join-CrossServicePath -BasePath $repositoryRoot -ChildPath @('.opsmind', 'reports'))
)
if (-not $ReportPath.StartsWith(
    $reportRoot + [IO.Path]::DirectorySeparatorChar,
    $pathComparison
)) {
    throw 'Cross-service report must stay under .opsmind/reports.'
}

$storageScriptRoot = Join-CrossServicePath -BasePath $repositoryRoot `
    -ChildPath @('scripts', 'storage')
if (Test-CrossServiceWindows) {
    & $hostPowerShell -NoLogo -NoProfile -ExecutionPolicy Bypass `
        -File (Join-CrossServicePath -BasePath $storageScriptRoot `
            -ChildPath @('check-capacity.ps1'))
    if ($LASTEXITCODE -ne 0) { throw 'Storage capacity preflight failed.' }
    & $hostPowerShell -NoLogo -NoProfile -ExecutionPolicy Bypass `
        -File (Join-CrossServicePath -BasePath $storageScriptRoot `
            -ChildPath @('assert-storage-roots.ps1')) -CreateMissing
    if ($LASTEXITCODE -ne 0) { throw 'Storage root preflight failed.' }
}
else {
    $hostShell = (Get-Command sh -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Path
    & $hostShell (Join-CrossServicePath -BasePath $storageScriptRoot `
        -ChildPath @('check-capacity.sh'))
    if ($LASTEXITCODE -ne 0) { throw 'Storage capacity preflight failed.' }
    & $hostShell (Join-CrossServicePath -BasePath $storageScriptRoot `
        -ChildPath @('assert-storage-roots.sh')) --create-missing
    if ($LASTEXITCODE -ne 0) { throw 'Storage root preflight failed.' }
}

$executables = @{
    Docker = (Get-Command docker -CommandType Application | Select-Object -First 1).Path
    Java = (Get-Command java -CommandType Application | Select-Object -First 1).Path
    Keytool = (Get-Command keytool -CommandType Application | Select-Object -First 1).Path
    Node = (Get-Command node -CommandType Application | Select-Object -First 1).Path
    OpenSsl = (Get-Command openssl -CommandType Application | Select-Object -First 1).Path
}
$pythonCandidates = @(
    (Join-CrossServicePath -BasePath $repositoryRoot `
        -ChildPath @('services', 'ai-runtime', '.venv', 'Scripts', 'python.exe')),
    (Join-CrossServicePath -BasePath $repositoryRoot `
        -ChildPath @('services', 'ai-runtime', '.venv', 'bin', 'python'))
)
$python = @($pythonCandidates | Where-Object {
    Test-Path -LiteralPath $_ -PathType Leaf
} | Select-Object -First 1)
if ($python.Count -ne 1) {
    throw 'Required AI Runtime virtual-environment interpreter is missing.'
}
$python = $python[0]
$platformJar = Join-CrossServicePath -BasePath $repositoryRoot `
    -ChildPath @('services', 'platform-api', 'target', 'platform-api.jar')
$gatewayJar = Join-CrossServicePath -BasePath $repositoryRoot `
    -ChildPath @('services', 'tool-gateway', 'target', 'tool-gateway.jar')
foreach ($requiredFile in @($python, $platformJar, $gatewayJar)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required cross-service artifact is missing: $requiredFile"
    }
}

$runId = [guid]::NewGuid().ToString('N')
$runRoot = Join-CrossServicePath -BasePath $repositoryRoot `
    -ChildPath @('.opsmind', 'cross-service', $runId)
foreach ($managedPath in @($reportRoot, $ReportPath, $runRoot)) {
    Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
        -CandidatePath $managedPath
}
$containerName = "opsmind-cross-service-postgres-$($runId.Substring(0, 12))"
$success = $false
$primaryFailure = $null
$cleanupFailure = $null
$postgresStarted = $false
$secretFiles = New-Object 'System.Collections.Generic.List[string]'

$scenarioScope = @{
    A = @{
        OrganizationId = '10000000-0000-4000-8000-000000000801'
        ProjectId = '10000000-0000-4000-8000-000000000802'
        IncidentId = '10000000-0000-4000-8000-000000000814'
    }
    B = @{
        OrganizationId = '20000000-0000-4000-8000-000000000801'
        ProjectId = '20000000-0000-4000-8000-000000000802'
        IncidentId = '20000000-0000-4000-8000-000000000814'
    }
    C = @{
        OrganizationId = '30000000-0000-4000-8000-000000000801'
        ProjectId = '30000000-0000-4000-8000-000000000802'
        IncidentId = '30000000-0000-4000-8000-000000000814'
    }
}[$Scenario]
$organizationId = $scenarioScope.OrganizationId
$projectId = $scenarioScope.ProjectId
$userId = '11111111-1111-4111-8111-111111111111'
$incidentId = $scenarioScope.IncidentId
$operatorSubject = 'cross-service-operator'
$foreignOrganizationId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
$foreignProjectId = 'bbbbbbb1-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
$foreignUserId = '22222222-2222-4222-8222-222222222222'
$foreignIncidentId = '70000000-0000-4000-8000-000000000002'
$postgresImage = 'pgvector/pgvector:0.8.2-pg17-trixie@sha256:' +
    '5c97c57367a485a8e99389548db67d441ab1a878f5492c3df04989f34ecf3c75'
$scenarioCounts = @{
    A = @{ AnalysisPerRun = 2; EvidencePerRun = 1; ReceiptsPerRun = 1 }
    B = @{ AnalysisPerRun = 1; EvidencePerRun = 0; ReceiptsPerRun = 0 }
    C = @{ AnalysisPerRun = 2; EvidencePerRun = 2; ReceiptsPerRun = 2 }
}[$Scenario]
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
    foreach ($managedPath in @($reportRoot, $ReportPath, $runRoot)) {
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath $managedPath
    }
    if (Test-Path -LiteralPath $ReportPath -PathType Leaf) {
        $archiveRoot = Join-Path $reportRoot 'archive'
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath $archiveRoot
        [void](New-Item -ItemType Directory -Path $archiveRoot -Force)
        $archiveName = 'cross-service-trace-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '.json'
        $archivePath = Join-Path $archiveRoot $archiveName
        foreach ($managedPath in @($ReportPath, $archiveRoot, $archivePath)) {
            Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
                -CandidatePath $managedPath
        }
        Move-Item -LiteralPath $ReportPath -Destination $archivePath
    }

    $tlsKey = Join-Path $runRoot 'identity-tls-private.pem'
    $tlsCertificate = Join-Path $runRoot 'identity-tls-certificate.pem'
    $capabilityKey = Join-Path $runRoot 'capability-private.pem'
    $capabilityJwks = Join-Path $runRoot 'capability-jwks.json'
    $trustStore = Join-Path $runRoot 'java-truststore.p12'
    $javaHostsFile = Join-Path $runRoot 'java-hosts.txt'
    $postgresEnvironment = Join-Path $runRoot 'postgres.env'
    $operatorTokenFile = Join-Path $runRoot 'operator-access-token.txt'
    $connectorManifest = Join-CrossServicePath -BasePath $repositoryRoot -ChildPath @(
        'services', 'tool-gateway', 'src', 'main', 'resources', 'tool-manifests',
        'observability-metrics-query-prometheus-v1.json'
    )
    $evaluationRoleSql = Join-Path $PSScriptRoot 'create-evaluation-export-roles.sql'
    $evaluationScopeRegistrationSql =
        Join-Path $PSScriptRoot 'register-evaluation-export-scope.sql'
    $evaluationExportSql = Join-Path $PSScriptRoot 'cross-service-evaluation-export.sql'
    $evaluationScopeProofSql = Join-Path $PSScriptRoot 'prove-evaluation-export-scope.sql'
    $projector = Join-CrossServicePath -BasePath $repositoryRoot -ChildPath @(
        'evaluation', 'runner', 'project-cross-service-evaluation-export.mjs'
    )
    foreach ($requiredEvaluationFile in @(
        $connectorManifest, $evaluationRoleSql, $evaluationScopeRegistrationSql,
        $evaluationExportSql, $evaluationScopeProofSql, $projector
    )) {
        if (-not (Test-Path -LiteralPath $requiredEvaluationFile -PathType Leaf)) {
            throw "Required cross-service evaluation file is missing: $requiredEvaluationFile"
        }
    }
    $connectorManifestByteDigest = 'sha256:' +
        (Get-FileHash -LiteralPath $connectorManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    $queryManifestByteDigest = 'sha256:' +
        (Get-FileHash -LiteralPath $evaluationExportSql -Algorithm SHA256).Hash.ToLowerInvariant()
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
        --publish "127.0.0.1:${databasePort}:5432" $postgresImage
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

    Invoke-CrossServiceSqlFile -DockerPath $executables.Docker `
        -ContainerName $containerName -DatabaseUser 'opsmind_migrator' `
        -SqlPath $evaluationRoleSql `
        -StdoutPath (Join-Path $runRoot 'evaluation-role-create.stdout.log') `
        -StderrPath (Join-Path $runRoot 'evaluation-role-create.stderr.log')

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
INSERT INTO organizations (id, slug, name)
VALUES ('$foreignOrganizationId', 'cross-service-foreign', 'Foreign scope proof');
INSERT INTO platform_users (id, issuer, subject, display_name)
VALUES ('$foreignUserId', '$issuer', 'cross-service-foreign', 'Foreign scope actor');
INSERT INTO organization_memberships (organization_id, user_id, role)
VALUES ('$foreignOrganizationId', '$foreignUserId', 'SRE');
INSERT INTO projects (id, organization_id, slug, name)
VALUES ('$foreignProjectId', '$foreignOrganizationId', 'foreign-api', 'Foreign API');
INSERT INTO project_memberships (organization_id, project_id, user_id, role)
VALUES ('$foreignOrganizationId', '$foreignProjectId', '$foreignUserId', 'SRE');
INSERT INTO incidents (
    id, organization_id, project_id, title, description, severity, status,
    created_by, updated_by
) VALUES (
    '$foreignIncidentId', '$foreignOrganizationId', '$foreignProjectId',
    'Foreign scope sentinel',
    'Synthetic sentinel used only to prove cross-tenant evaluator isolation.',
    'SEV4', 'OPEN', '$foreignUserId', '$foreignUserId'
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
    Invoke-CrossServiceProcess -Executable $executables.Node -Arguments @(
        (Join-Path $PSScriptRoot 'probe-fixture-identity.mjs'),
        "--opsmind-cross-service-run-id=$runId"
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'identity-probe.stdout.log') `
        -StderrPath (Join-Path $runRoot 'identity-probe.stderr.log') `
        -Environment @{
            OPSMIND_IDENTITY_PROBE_URL =
                "https://127.0.0.1:$identityPort/__opsmind/status"
            OPSMIND_IDENTITY_PROBE_CA_FILE = $tlsCertificate
        }
    if (-not (Test-Path -LiteralPath $capabilityJwks -PathType Leaf)) {
        throw 'Fixture identity did not publish capability JWKS.'
    }

    $providerProcess = Start-CrossServiceProcess -Executable $python -Arguments @(
        (Join-Path $PSScriptRoot 'fixture-provider.py'), '--host', '127.0.0.1',
        '--port', "$providerPort", '--scenario', $Scenario,
        '--opsmind-cross-service-run-id', $runId
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
        PYTHONPATH = (Join-CrossServicePath -BasePath $repositoryRoot `
            -ChildPath @('services', 'ai-runtime', 'src'))
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
    ) -WorkingDirectory (Join-CrossServicePath -BasePath $repositoryRoot `
        -ChildPath @('services', 'ai-runtime')) `
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
        OPSMIND_CROSS_SERVICE_SCENARIO = $Scenario
        OPSMIND_ENVIRONMENT = 'local-disposable-cross-service'
        OPSMIND_TRACE_REPORT = $ReportPath
    }
    Invoke-CrossServiceProcess -Executable $executables.Node -Arguments @(
        (Join-Path $PSScriptRoot 'run-investigation-slice.mjs')
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'runner.stdout.log') `
        -StderrPath (Join-Path $runRoot 'runner.stderr.log') `
        -Environment $runnerEnvironment

    $traceBytes = (Get-Item -LiteralPath $ReportPath).Length
    if ($traceBytes -lt 2 -or $traceBytes -gt 67108864) {
        throw 'Unharvested cross-service trace is empty or oversized.'
    }
    $traceDocument = [IO.File]::ReadAllText($ReportPath) | ConvertFrom-Json
    $traceRuns = @($traceDocument.runs)
    if ($traceDocument.scenario -ne $Scenario -or
        $traceDocument.warmRuns -ne $WarmRuns -or
        $traceRuns.Count -ne $WarmRuns) {
        throw 'Unharvested cross-service trace does not match the invocation.'
    }

    for ($index = 0; $index -lt $traceRuns.Count; $index++) {
        $traceRun = $traceRuns[$index]
        foreach ($identity in @(
            [string]$traceRun.organizationId,
            [string]$traceRun.projectId,
            [string]$traceRun.incidentId,
            [string]$traceRun.runId
        )) {
            if ($identity -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') {
                throw 'Cross-service trace contains an invalid scoped identifier.'
            }
        }
        if ([string]$traceRun.organizationId -ne $organizationId -or
            [string]$traceRun.projectId -ne $projectId -or
            [string]$traceRun.incidentId -ne $incidentId) {
            throw 'Cross-service trace contains a foreign scope.'
        }
        Invoke-CrossServiceSqlFile -DockerPath $executables.Docker `
            -ContainerName $containerName -DatabaseUser 'opsmind_migrator' `
            -SqlPath $evaluationScopeRegistrationSql `
            -StdoutPath (Join-Path $runRoot "evaluation-scope-register-$index.stdout.log") `
            -StderrPath (Join-Path $runRoot "evaluation-scope-register-$index.stderr.log") `
            -Variables @{
                scope_organization_id = [string]$traceRun.organizationId
                scope_project_id = [string]$traceRun.projectId
                scope_incident_id = [string]$traceRun.incidentId
                scope_run_id = [string]$traceRun.runId
                scope_actor_id = $userId
            }
    }

    $firstRun = $traceRuns[0]
    $scopeProofOutput = Join-Path $runRoot 'evaluation-scope-proof.stdout.log'
    Invoke-CrossServiceSqlFile -DockerPath $executables.Docker `
        -ContainerName $containerName -DatabaseUser 'opsmind_evaluator' `
        -SqlPath $evaluationScopeProofSql -StdoutPath $scopeProofOutput `
        -StderrPath (Join-Path $runRoot 'evaluation-scope-proof.stderr.log') `
        -Variables @{
            foreign_organization_id = $foreignOrganizationId
            foreign_actor_id = $foreignUserId
            scope_project_id = [string]$firstRun.projectId
            scope_incident_id = [string]$firstRun.incidentId
            scope_run_id = [string]$firstRun.runId
        }
    $scopeProofLines = @(
        [IO.File]::ReadAllLines($scopeProofOutput) |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_.Length -gt 0 }
    )
    if ($scopeProofLines.Count -ne 1 -or
        $scopeProofLines[0] -ne 'CROSS_TENANT_PROOF_PASS') {
        throw 'Disposable evaluator cross-tenant proof failed.'
    }

    $exportPaths = New-Object 'System.Collections.Generic.List[string]'
    for ($index = 0; $index -lt $traceRuns.Count; $index++) {
        $traceRun = $traceRuns[$index]
        foreach ($identity in @(
            [string]$traceRun.organizationId,
            [string]$traceRun.projectId,
            [string]$traceRun.incidentId,
            [string]$traceRun.runId
        )) {
            if ($identity -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') {
                throw 'Cross-service trace contains an invalid scoped identifier.'
            }
        }
        if ([string]$traceRun.organizationId -ne $organizationId -or
            [string]$traceRun.projectId -ne $projectId -or
            [string]$traceRun.incidentId -ne $incidentId) {
            throw 'Cross-service trace contains a foreign scope.'
        }

        $exportPath = Join-Path $runRoot (
            'evaluation-export-{0:D4}-{1}.json' -f ($index + 1), $traceRun.runId
        )
        Assert-CrossServiceManagedPath -NodePath $executables.Node `
            -RepositoryRoot $repositoryRoot -ManagedRoot $runRoot -Path $exportPath `
            -StdoutPath (Join-Path $runRoot "evaluation-path-$index.stdout.log") `
            -StderrPath (Join-Path $runRoot "evaluation-path-$index.stderr.log")
        Invoke-CrossServiceSqlFile -DockerPath $executables.Docker `
            -ContainerName $containerName -DatabaseUser 'opsmind_evaluator' `
            -SqlPath $evaluationExportSql -StdoutPath $exportPath `
            -StderrPath (Join-Path $runRoot "evaluation-export-$index.stderr.log") `
            -Variables @{
                query_manifest_byte_digest = $queryManifestByteDigest
                scope_organization_id = [string]$traceRun.organizationId
                scope_project_id = [string]$traceRun.projectId
                scope_incident_id = [string]$traceRun.incidentId
                scope_run_id = [string]$traceRun.runId
                scope_actor_id = $userId
            }

        $exportBytes = (Get-Item -LiteralPath $exportPath).Length
        if ($exportBytes -lt 2 -or $exportBytes -gt 4194305) {
            throw 'Bounded evaluator export is empty or oversized.'
        }
        $exportText = [IO.File]::ReadAllText($exportPath)
        $exportLines = @(
            $exportText -split '\r?\n' |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_.Length -gt 0 }
        )
        if ($exportLines.Count -ne 1) {
            throw 'Evaluator export must contain exactly one JSON document.'
        }
        try {
            $exportDocument = $exportLines[0] | ConvertFrom-Json
        }
        catch {
            throw 'Evaluator export is not one valid JSON object.'
        }
        if ($null -eq $exportDocument -or
            $exportDocument.schema_version -ne
                'opsmind-cross-service-evaluation-export-v1' -or
            $exportDocument.evidence_classification -ne
                'TRANSIENT_SYNTHETIC_CROSS_SERVICE_EXPORT') {
            throw 'Evaluator export envelope is invalid.'
        }
        $exportPaths.Add($exportPath)
    }

    $enrichedTrace = Join-Path $runRoot 'evaluation-enriched-trace.json'
    Assert-CrossServiceManagedPath -NodePath $executables.Node `
        -RepositoryRoot $repositoryRoot -ManagedRoot $runRoot -Path $enrichedTrace `
        -StdoutPath (Join-Path $runRoot 'evaluation-output-path.stdout.log') `
        -StderrPath (Join-Path $runRoot 'evaluation-output-path.stderr.log')
    $projectorArguments = New-Object 'System.Collections.Generic.List[string]'
    foreach ($argument in @($projector, '--trace', $ReportPath, '--output', $enrichedTrace)) {
        $projectorArguments.Add($argument)
    }
    foreach ($exportPath in $exportPaths) {
        $projectorArguments.Add('--export')
        $projectorArguments.Add($exportPath)
    }
    Invoke-CrossServiceProcess -Executable $executables.Node `
        -Arguments $projectorArguments.ToArray() -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'evaluation-projector.stdout.log') `
        -StderrPath (Join-Path $runRoot 'evaluation-projector.stderr.log') `
        -Environment @{}

    foreach ($managedPath in @($runRoot, $enrichedTrace, $reportRoot, $ReportPath)) {
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath $managedPath
    }
    Invoke-CrossServiceProcess -Executable $executables.Node -Arguments @(
        (Join-Path $PSScriptRoot 'manage-evaluation-files.mjs'),
        'publish',
        '--managed-root', $reportRoot,
        '--source', $enrichedTrace,
        '--destination', $ReportPath
    ) -WorkingDirectory $repositoryRoot `
        -StdoutPath (Join-Path $runRoot 'evaluation-publication.stdout.log') `
        -StderrPath (Join-Path $runRoot 'evaluation-publication.stderr.log') `
        -Environment @{}

    foreach ($exportPath in $exportPaths) {
        Remove-Item -LiteralPath $exportPath -Force
    }
    $survivingRawExports = @(
        Get-ChildItem -LiteralPath $runRoot -File -Filter 'evaluation-export-*.json'
    )
    if ($survivingRawExports.Count -ne 0) {
        throw 'Transient evaluator exports survived successful projection.'
    }

    $finalizeEnvironment = @{
        OPSMIND_TRACE_REPORT = $ReportPath
        OPSMIND_CROSS_SERVICE_SCENARIO = $Scenario
        OPSMIND_COUNT_INVESTIGATION_RUNS = "$WarmRuns"
        OPSMIND_COUNT_EVIDENCE_RECORDS =
            "$($WarmRuns * $scenarioCounts.EvidencePerRun)"
        OPSMIND_COUNT_ANALYSIS_INVOCATIONS =
            "$($WarmRuns * $scenarioCounts.AnalysisPerRun)"
        OPSMIND_COUNT_TOOL_RECEIPTS =
            "$($WarmRuns * $scenarioCounts.ReceiptsPerRun)"
        OPSMIND_COUNT_TOOL_AUDIT_EVENTS =
            "$($WarmRuns * $scenarioCounts.ReceiptsPerRun)"
        OPSMIND_PLATFORM_JAR = $platformJar
        OPSMIND_GATEWAY_JAR = $gatewayJar
        OPSMIND_JAVA_EXECUTABLE = $executables.Java
        OPSMIND_NODE_EXECUTABLE = $executables.Node
        OPSMIND_PYTHON_EXECUTABLE = $python
        OPSMIND_FIXTURE_PROVIDER_SOURCE = (Join-Path $PSScriptRoot 'fixture-provider.py')
        OPSMIND_INVESTIGATION_RUNNER_SOURCE =
            (Join-Path $PSScriptRoot 'run-investigation-slice.mjs')
        OPSMIND_EXPORT_QUERY_SOURCE = $evaluationExportSql
        OPSMIND_PROJECTOR_SOURCE = $projector
        OPSMIND_CONNECTOR_MANIFEST_SOURCE = $connectorManifest
        OPSMIND_POSTGRES_IMAGE = $postgresImage
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
    Write-Output (
        "CrossServiceVerification=PASS Scenario={0} WarmRuns={1} Report={2}" -f
            $Scenario, $WarmRuns, $ReportPath
    )
}
catch {
    $primaryFailure = $_
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
    try {
        & $hostPowerShell @cleanupArguments
        if ($LASTEXITCODE -ne 0) {
            throw 'Cross-service cleanup helper failed.'
        }
    }
    catch {
        $cleanupFailure = $_
    }
    $migrationPassword = $null
    $appPassword = $null
    $dispatcherPassword = $null
    $aiPassword = $null
    $gatewayMigratorPassword = $null
    $gatewayPassword = $null
    $runnerClientSecret = $null
    $workloadClientSecret = $null
    $operatorToken = $null
}
if ($null -ne $primaryFailure) {
    if ($null -ne $cleanupFailure) {
        Write-Warning (
            'Secondary cleanup failure did not replace the primary harness failure: ' +
            $cleanupFailure.Exception.Message
        )
    }
    throw $primaryFailure
}
if ($null -ne $cleanupFailure) {
    throw $cleanupFailure
}
