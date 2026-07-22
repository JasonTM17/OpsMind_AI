[CmdletBinding()]
param(
    [string]$JavaPath,
    [string]$MavenPath,
    [string]$PythonPath,
    [string]$KeytoolPath,
    [string]$CurlPath,
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath([IO.Path]::Combine($PSScriptRoot, '..', '..'))
$keycloakScriptDirectory = Join-Path $PSScriptRoot 'keycloak'
$supportPath = Join-Path $keycloakScriptDirectory 'keycloak-conformance-support.ps1'
$profileManifestPath = Join-Path $keycloakScriptDirectory 'keycloak-conformance-profile-files.txt'
$realmPath = Join-Path $keycloakScriptDirectory 'opsmind-conformance-realm.json'
$clientPath = Join-Path $keycloakScriptDirectory 'run_oidc_conformance.py'
$platformPom = [IO.Path]::Combine($repositoryRoot, 'services', 'platform-api', 'pom.xml')
$platformJar = [IO.Path]::Combine($repositoryRoot, 'services', 'platform-api', 'target', 'platform-api.jar')
$mavenRepository = [IO.Path]::Combine($repositoryRoot, '.opsmind', 'cache', 'maven')
$keycloakImage = 'quay.io/keycloak/keycloak@sha256:1362a9d9f13ab325231ea133610cc905e12805804abc7acbef552dd613720aa6'
$realmName = 'opsmind-conformance'
$clientId = 'opsmind-conformance-browser'
$expectedAudience = 'opsmind-platform-api'
$redirectUri = 'http://127.0.0.1:19090/callback'
$mfaUsername = 'opsmind-mfa-user'
$passwordUsername = 'opsmind-password-user'
$containerName = 'opsmind-keycloak-conformance-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ('opsmind-keycloak-conformance-' + [guid]::NewGuid().ToString('N'))
$platformProcess = $null
$executionCompleted = $false
$executionFailureType = $null
$platformArtifactDigest = $null
$platformStdout = $null
$platformStderr = $null
$lastConformanceClientOutput = @()
$cleanupFailures = New-Object 'System.Collections.Generic.List[string]'
$failureDiagnostics = New-Object 'System.Collections.Generic.List[string]'
$tlsPassword = $null
$adminPassword = $null
$userPassword = $null
$initial = $null
$refreshed = $null
$evidenceStartTimestampUtc = [DateTime]::UtcNow
$evidenceStopwatch = [Diagnostics.Stopwatch]::StartNew()

if (-not (Test-Path -LiteralPath $supportPath -PathType Leaf) `
    -or -not (Test-Path -LiteralPath $realmPath -PathType Leaf) `
    -or -not (Test-Path -LiteralPath $clientPath -PathType Leaf) `
    -or -not (Test-Path -LiteralPath $profileManifestPath -PathType Leaf)) {
    throw 'Keycloak conformance support files are missing.'
}
. $supportPath

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = [IO.Path]::Combine(
        $repositoryRoot, 'artifacts', 'verification', 'phase-03', 'identity-delegation.txt'
    )
}
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
$evidenceRoot = [IO.Path]::GetFullPath([IO.Path]::Combine($repositoryRoot, 'artifacts', 'verification'))
$pathComparison = if ($env:OS -eq 'Windows_NT') {
    [StringComparison]::OrdinalIgnoreCase
}
else {
    [StringComparison]::Ordinal
}
$pathSeparators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$evidenceRootPrefix = $evidenceRoot.TrimEnd($pathSeparators) + [IO.Path]::DirectorySeparatorChar
if (-not $EvidencePath.StartsWith($evidenceRootPrefix, $pathComparison)) {
    throw 'Evidence path must stay within artifacts/verification.'
}
$failureEvidencePath = Join-Path `
    (Split-Path -Parent $EvidencePath) `
    (([IO.Path]::GetFileNameWithoutExtension($EvidencePath)) + '-failure' + `
        ([IO.Path]::GetExtension($EvidencePath)))
$failureEvidencePath = [IO.Path]::GetFullPath($failureEvidencePath)
if (-not $failureEvidencePath.StartsWith($evidenceRootPrefix, $pathComparison) `
    -or $failureEvidencePath.Equals($EvidencePath, $pathComparison)) {
    throw 'Failure evidence path must stay within artifacts/verification and differ from success evidence.'
}
if (Test-Path -LiteralPath $EvidencePath) {
    if (-not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) {
        throw 'Evidence path must identify a file.'
    }
    Remove-Item -LiteralPath $EvidencePath -Force
}
if (Test-Path -LiteralPath $failureEvidencePath) {
    if (-not (Test-Path -LiteralPath $failureEvidencePath -PathType Leaf)) {
        throw 'Failure evidence path must identify a file.'
    }
    Remove-Item -LiteralPath $failureEvidencePath -Force
}

$declaredJdk = Get-ChildItem -LiteralPath ([IO.Path]::Combine($repositoryRoot, '.opsmind', 'cache', 'tools')) `
    -Directory -Filter 'temurin-jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($JavaPath) -and $null -ne $declaredJdk) {
    $javaName = if ($env:OS -eq 'Windows_NT') { 'java.exe' } else { 'java' }
    $JavaPath = Join-Path (Join-Path $declaredJdk.FullName 'bin') $javaName
}
$JavaPath = Resolve-OpsMindExecutable -ExplicitPath $JavaPath `
    -Names @('java', 'java.exe') -Description 'Java 21'
$MavenPath = Resolve-OpsMindExecutable -ExplicitPath $MavenPath `
    -Names @('mvn', 'mvn.cmd') -Description 'Maven'
$PythonPath = Resolve-OpsMindExecutable -ExplicitPath $PythonPath `
    -Names @('python', 'python3', 'python.exe') -Description 'Python 3'
$CurlPath = Resolve-OpsMindExecutable -ExplicitPath $CurlPath `
    -Names @('curl', 'curl.exe') -Description 'curl'
$DockerPath = Resolve-OpsMindExecutable -Names @('docker', 'docker.exe') -Description 'Docker'
if ([string]::IsNullOrWhiteSpace($KeytoolPath)) {
    $keytoolName = if ($env:OS -eq 'Windows_NT') { 'keytool.exe' } else { 'keytool' }
    $KeytoolPath = Join-Path (Split-Path -Parent $JavaPath) $keytoolName
}
$KeytoolPath = Resolve-OpsMindExecutable -ExplicitPath $KeytoolPath `
    -Names @('keytool', 'keytool.exe') -Description 'keytool'

$profileFiles = @(Get-OpsMindConformanceProfilePaths `
    -RepositoryRoot $repositoryRoot `
    -ManifestPath $profileManifestPath)
$profileDigest = Get-OpsMindFileSetSha256 -RepositoryRoot $repositoryRoot -Paths $profileFiles
$gitMetadata = Get-OpsMindGitMetadata -RepositoryRoot $repositoryRoot
$openApiContract = Get-Content -LiteralPath ([IO.Path]::Combine(
        $repositoryRoot, 'packages', 'contracts', 'openapi', 'opsmind-v1.yaml'
    )) -Raw
$contractVersionMatch = [regex]::Match($openApiContract, '(?m)^\s{2}version:\s*([^\s]+)\s*$')
if (-not $contractVersionMatch.Success) {
    throw 'Unable to resolve the OpsMind API contract version for evidence.'
}
$contractVersion = 'opsmind-v1@' + $contractVersionMatch.Groups[1].Value
$javaRuntime = Get-OpsMindNativeVersion -Executable $JavaPath -Arguments @('-version')
$mavenRuntime = Get-OpsMindNativeVersion -Executable $MavenPath -Arguments @('--version')
$pythonRuntime = Get-OpsMindNativeVersion -Executable $PythonPath -Arguments @('--version')
$dockerRuntime = Get-OpsMindNativeVersion -Executable $DockerPath `
    -Arguments @('version', '--format', '{{.Server.Version}}')
$executionEnvironment = if ($env:GITHUB_ACTIONS -ceq 'true') {
    'github-actions/' + $(if ([string]::IsNullOrWhiteSpace($env:RUNNER_OS)) { 'unknown' } else { $env:RUNNER_OS })
}
else {
    'local'
}
$runtimeOs = ([Runtime.InteropServices.RuntimeInformation]::OSDescription -replace '[\r\n]+', ' ').Trim()
$runtimeArchitecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()

$environmentNames = @(
    'OPSMIND_KEYCLOAK_TEST_PASSWORD', 'OPSMIND_KEYCLOAK_REFRESH_TOKEN',
    'OPSMIND_SECURITY_MODE', 'OIDC_ISSUER_URL', 'OIDC_AUDIENCE',
    'OIDC_REQUIRED_AMR', 'OIDC_MAX_TOKEN_LIFETIME', 'OIDC_CLOCK_SKEW',
    'OIDC_JWKS_REFRESH_MINIMUM_INTERVAL',
    'PLATFORM_API_PORT'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-ConformanceClient {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$ResultPath,
        [Parameter(Mandatory = $true)][string]$Issuer,
        [Parameter(Mandatory = $true)][string]$CertificatePath
    )

    $output = @()
    $clientExitCode = 1
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $PythonPath $clientPath $Command `
            '--issuer' $Issuer `
            '--client-id' $clientId `
            '--redirect-uri' $redirectUri `
            '--ca-cert' $CertificatePath `
            '--result-file' $ResultPath `
            '--username' $mfaUsername `
            '--password-only-username' $passwordUsername `
            '--expected-audience' $expectedAudience `
            '--totp-algorithm' 'HmacSHA256' 2>&1
        $clientExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $safeOutput = @(Get-OpsMindSanitizedDiagnosticLines `
            -Lines $output `
            -SensitiveValues @($userPassword, $env:OPSMIND_KEYCLOAK_TEST_PASSWORD, `
                $env:OPSMIND_KEYCLOAK_REFRESH_TOKEN) `
            -MaximumLines 50)
    $script:lastConformanceClientOutput = $safeOutput
    $safeOutput | ForEach-Object { Write-Host ([string]$_) }
    if ($clientExitCode -ne 0) {
        throw "OIDC conformance client command failed: $Command"
    }
    if (-not (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
        throw "OIDC conformance client did not create its result file: $Command"
    }
    $result = Get-Content -LiteralPath $ResultPath -Raw | ConvertFrom-Json
    Remove-Item -LiteralPath $ResultPath -Force

    $requiredProperties = switch ($Command) {
        'run' {
            @(
                'validAccessToken', 'validRefreshToken', 'revocationRefreshToken',
                'noMfaAccessToken', 'validKeyId', 'subject', 'summary'
            )
        }
        { $_ -in @('refresh', 'refresh-once') } {
            @('validAccessToken', 'validRefreshToken', 'validKeyId', 'summary')
        }
        default { @('summary') }
    }
    foreach ($propertyName in $requiredProperties) {
        if ($result.PSObject.Properties.Name -notcontains $propertyName) {
            throw "OIDC conformance client returned an invalid result contract for ${Command}: missing $propertyName"
        }
    }
    return $result
}

try {
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    $certificateDirectory = Join-Path $temporaryDirectory 'certificates'
    New-Item -ItemType Directory -Path $certificateDirectory | Out-Null
    $serverKeyStore = Join-Path $certificateDirectory 'keycloak-server.p12'
    $serverCertificate = Join-Path $certificateDirectory 'keycloak-server.crt'
    $javaTrustStore = Join-Path $certificateDirectory 'platform-truststore.p12'
    $platformStdout = Join-Path $temporaryDirectory 'platform-api.stdout.log'
    $platformStderr = Join-Path $temporaryDirectory 'platform-api.stderr.log'
    $discoveryOutput = Join-Path $temporaryDirectory 'openid-configuration.json'
    $initialResultPath = Join-Path $temporaryDirectory 'initial-flow.json'
    $refreshResultPath = Join-Path $temporaryDirectory 'refresh-flow.json'
    $preRevocationResultPath = Join-Path $temporaryDirectory 'pre-revocation-flow.json'
    $revokeResultPath = Join-Path $temporaryDirectory 'revoke-flow.json'
    $disabledResultPath = Join-Path $temporaryDirectory 'disabled-flow.json'

    $tlsPassword = New-OpsMindConformanceSecret
    $adminPassword = New-OpsMindConformanceSecret
    $userPassword = New-OpsMindConformanceSecret
    $keycloakPort = Get-OpsMindAvailableTcpPort
    $platformPort = Get-OpsMindAvailableTcpPort
    while ($platformPort -eq $keycloakPort) {
        $platformPort = Get-OpsMindAvailableTcpPort
    }
    $keycloakBaseUrl = "https://127.0.0.1:$keycloakPort"
    $issuer = "$keycloakBaseUrl/realms/$realmName"

    & $KeytoolPath -genkeypair -noprompt `
        -alias opsmind-keycloak-conformance `
        -dname 'CN=127.0.0.1, OU=OpsMind Conformance, O=OpsMind, C=VN' `
        -ext 'SAN=ip:127.0.0.1,dns:localhost' `
        -validity 2 -keyalg RSA -keysize 2048 -sigalg SHA256withRSA `
        -storetype PKCS12 -keystore $serverKeyStore `
        -storepass $tlsPassword -keypass $tlsPassword | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to generate the ephemeral Keycloak TLS key store.' }
    & $KeytoolPath -exportcert -rfc -noprompt `
        -alias opsmind-keycloak-conformance -keystore $serverKeyStore `
        -storepass $tlsPassword -file $serverCertificate | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to export the ephemeral Keycloak certificate.' }
    & $KeytoolPath -importcert -noprompt `
        -alias opsmind-keycloak-conformance -file $serverCertificate `
        -storetype PKCS12 -keystore $javaTrustStore -storepass changeit | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the ephemeral Platform API trust store.' }

    Push-Location $repositoryRoot
    try {
        & $MavenPath --batch-mode --no-transfer-progress `
            "-Dmaven.repo.local=$mavenRepository" '-DskipTests' `
            '-f' $platformPom 'clean' 'package'
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $platformJar -PathType Leaf)) {
            throw 'Current Platform API packaging failed.'
        }
        $platformArtifactDigest = (Get-FileHash `
            -LiteralPath $platformJar `
            -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    finally {
        Pop-Location
    }

    & $DockerPath image inspect $keycloakImage | Out-Null 2>&1
    if ($LASTEXITCODE -ne 0) {
        & $DockerPath pull $keycloakImage | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to pull the pinned Keycloak image.' }
    }

    $realmMount = "type=bind,source=$realmPath,target=/opt/keycloak/data/import/opsmind-conformance-realm.json,readonly"
    $certificateMount = "type=bind,source=$certificateDirectory,target=/opt/keycloak/conf/opsmind-conformance,readonly"
    & $DockerPath run -d --name $containerName `
        --label 'ai.opsmind.scope=phase3-keycloak-conformance' `
        --memory 1g `
        -p "127.0.0.1:${keycloakPort}:8443" `
        --mount $realmMount --mount $certificateMount `
        -e 'KC_BOOTSTRAP_ADMIN_USERNAME=opsmind-conformance-admin' `
        -e "KC_BOOTSTRAP_ADMIN_PASSWORD=$adminPassword" `
        -e 'KC_HTTP_ENABLED=true' `
        -e 'KC_HTTPS_PORT=8443' `
        -e 'KC_HTTPS_KEY_STORE_FILE=/opt/keycloak/conf/opsmind-conformance/keycloak-server.p12' `
        -e "KC_HTTPS_KEY_STORE_PASSWORD=$tlsPassword" `
        -e "KC_HOSTNAME=$keycloakBaseUrl" `
        -e 'KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true' `
        -e 'JAVA_OPTS_KC_HEAP=-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=20' `
        $keycloakImage start-dev --import-realm | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the isolated Keycloak container.' }
    $keycloakReady = $false
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        try {
            & $CurlPath --silent --show-error --fail `
                --connect-timeout 2 --max-time 3 `
                --cacert $serverCertificate `
                --output $discoveryOutput `
                "$issuer/.well-known/openid-configuration" 2>$null
            $curlExit = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($curlExit -eq 0) {
            $keycloakReady = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $keycloakReady) {
        throw 'Keycloak did not expose its HTTPS discovery document before the deadline.'
    }
    $discovery = Get-Content -LiteralPath $discoveryOutput -Raw | ConvertFrom-Json
    if ($discovery.issuer -cne $issuer `
        -or $discovery.code_challenge_methods_supported -notcontains 'S256' `
        -or [string]::IsNullOrWhiteSpace($discovery.jwks_uri)) {
        throw 'Keycloak discovery metadata does not satisfy the reference profile.'
    }

    Invoke-OpsMindKeycloakAdmin -ContainerName $containerName -DockerPath $DockerPath -Arguments @(
        'config', 'credentials', '--server', 'http://127.0.0.1:8080',
        '--realm', 'master', '--user', 'opsmind-conformance-admin', '--password', $adminPassword
    ) | Out-Null

    $users = @(
        @{ username = $mfaUsername; requiredActions = @('CONFIGURE_TOTP') },
        @{ username = $passwordUsername; requiredActions = @() }
    )
    foreach ($userDefinition in $users) {
        $userPayload = @{
            username = $userDefinition.username
            enabled = $true
            emailVerified = $true
            email = "$($userDefinition.username)@example.test"
            firstName = 'OpsMind'
            lastName = 'Conformance'
            requiredActions = $userDefinition.requiredActions
            credentials = @(@{ type = 'password'; value = $userPassword; temporary = $false })
        } | ConvertTo-Json -Depth 8 -Compress
        $userPayload | & $DockerPath exec -i $containerName /opt/keycloak/bin/kcadm.sh `
            create users -r $realmName -f - | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to create a Keycloak conformance user.' }
    }

    $executionJson = Invoke-OpsMindKeycloakAdmin -ContainerName $containerName `
        -DockerPath $DockerPath -Arguments @(
        'get', 'authentication/flows/browser/executions', '-r', $realmName
    )
    $executions = ($executionJson -join [Environment]::NewLine) | ConvertFrom-Json
    $amrReferences = @{
        'auth-username-password-form' = 'pwd'
        'auth-otp-form' = 'mfa'
    }
    foreach ($providerId in $amrReferences.Keys) {
        $execution = $executions | Where-Object {
            $_.PSObject.Properties.Name -contains 'providerId' -and $_.providerId -ceq $providerId
        } | Select-Object -First 1
        if ($null -eq $execution) { throw "Keycloak browser execution is missing: $providerId" }
        $configPayload = @{
            alias = "opsmind-$providerId-amr"
            config = @{
                'default.reference.value' = $amrReferences[$providerId]
                'default.reference.maxAge' = '300'
            }
        } | ConvertTo-Json -Depth 6 -Compress
        $configPayload | & $DockerPath exec -i $containerName /opt/keycloak/bin/kcadm.sh `
            create "authentication/executions/$($execution.id)/config" -r $realmName -f - | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to bind the Keycloak AMR execution: $providerId" }
    }

    $env:OPSMIND_SECURITY_MODE = 'oidc'
    $env:OIDC_ISSUER_URL = $issuer
    $env:OIDC_AUDIENCE = $expectedAudience
    $env:OIDC_REQUIRED_AMR = 'mfa'
    $env:OIDC_MAX_TOKEN_LIFETIME = 'PT5M'
    $env:OIDC_CLOCK_SKEW = 'PT30S'
    $env:OIDC_JWKS_REFRESH_MINIMUM_INTERVAL = 'PT1S'
    $env:PLATFORM_API_PORT = [string]$platformPort
    $javaArguments = @(
        "-Djavax.net.ssl.trustStore=$javaTrustStore",
        '-Djavax.net.ssl.trustStorePassword=changeit',
        '-Djavax.net.ssl.trustStoreType=PKCS12',
        '-jar', $platformJar
    )
    $startParameters = @{
        FilePath = $JavaPath
        ArgumentList = $javaArguments
        WorkingDirectory = $repositoryRoot
        RedirectStandardOutput = $platformStdout
        RedirectStandardError = $platformStderr
        PassThru = $true
    }
    if ($env:OS -eq 'Windows_NT') {
        $startParameters.WindowStyle = 'Hidden'
    }
    $platformProcess = Start-Process @startParameters
    Wait-OpsMindHttpReady -Uri "http://127.0.0.1:$platformPort/actuator/health" -Process $platformProcess

    $env:OPSMIND_KEYCLOAK_TEST_PASSWORD = $userPassword
    $initial = Invoke-ConformanceClient -Command run -ResultPath $initialResultPath `
        -Issuer $issuer -CertificatePath $serverCertificate
    if ([int]$initial.summary.tokenLifetimeSeconds -ne 300) {
        throw 'Keycloak did not issue the expected 300-second access token.'
    }
    if (-not $initial.summary.independentRefreshSessions) {
        throw 'Keycloak did not create independent sessions for refresh-token conformance.'
    }
    $validResponse = Invoke-OpsMindPlatformRequest `
        -Uri "http://127.0.0.1:$platformPort/api/v1/me" -AccessToken $initial.validAccessToken
    if ($validResponse.Status -ne 200) { throw 'Platform API rejected the valid Keycloak MFA token.' }
    $principal = $validResponse.Body | ConvertFrom-Json
    if ($principal.issuer -cne $issuer -or $principal.subject -cne $initial.subject) {
        throw 'Platform API principal mapping did not preserve issuer and subject.'
    }
    $noMfaResponse = Invoke-OpsMindPlatformRequest `
        -Uri "http://127.0.0.1:$platformPort/api/v1/me" -AccessToken $initial.noMfaAccessToken
    if ($noMfaResponse.Status -ne 401) { throw 'Platform API accepted a real Keycloak token without MFA AMR.' }
    $anonymousResponse = Invoke-OpsMindPlatformRequest -Uri "http://127.0.0.1:$platformPort/api/v1/me"
    if ($anonymousResponse.Status -ne 401) { throw 'Platform API accepted an anonymous identity request.' }
    $tokenParts = ([string]$initial.validAccessToken).Split('.')
    if ($tokenParts.Count -ne 3 -or [string]::IsNullOrWhiteSpace($tokenParts[2])) {
        throw 'OIDC conformance client returned a malformed JWT access token.'
    }
    $signature = $tokenParts[2]
    $mutationIndex = [int][Math]::Floor($signature.Length / 2)
    $replacement = if ($signature[$mutationIndex] -ceq 'A') { 'B' } else { 'A' }
    $tokenParts[2] = $signature.Substring(0, $mutationIndex) + $replacement + `
        $signature.Substring($mutationIndex + 1)
    $tamperedResponse = Invoke-OpsMindPlatformRequest `
        -Uri "http://127.0.0.1:$platformPort/api/v1/me" -AccessToken ($tokenParts -join '.')
    if ($tamperedResponse.Status -ne 401) { throw 'Platform API accepted a token with a tampered signature.' }

    $realmJson = Invoke-OpsMindKeycloakAdmin -ContainerName $containerName -DockerPath $DockerPath `
        -Arguments @('get', "realms/$realmName")
    $realm = ($realmJson -join [Environment]::NewLine) | ConvertFrom-Json
    $keyProviderPayload = @{
        name = 'opsmind-conformance-rotated-rsa'
        providerId = 'rsa-generated'
        providerType = 'org.keycloak.keys.KeyProvider'
        parentId = $realm.id
        config = @{
            priority = @('200')
            enabled = @('true')
            active = @('true')
            algorithm = @('RS256')
            keySize = @('2048')
        }
    } | ConvertTo-Json -Depth 8 -Compress
    $keyProviderPayload | & $DockerPath exec -i $containerName /opt/keycloak/bin/kcadm.sh `
        create components -r $realmName -f - | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to rotate the Keycloak signing key.' }
    Start-Sleep -Milliseconds 1100

    $env:OPSMIND_KEYCLOAK_REFRESH_TOKEN = [string]$initial.validRefreshToken
    $refreshed = Invoke-ConformanceClient -Command refresh -ResultPath $refreshResultPath `
        -Issuer $issuer -CertificatePath $serverCertificate
    if ([int]$refreshed.summary.tokenLifetimeSeconds -ne 300) {
        throw 'Keycloak refresh did not preserve the 300-second access-token lifetime.'
    }
    if ($refreshed.summary.previousRefreshReuseDenied -cne 'invalid_grant') {
        throw 'Keycloak accepted a rotated refresh token after its first use.'
    }
    if ($refreshed.validKeyId -ceq $initial.validKeyId) {
        throw 'Keycloak refresh did not use the rotated active signing key.'
    }
    $rotatedResponse = Invoke-OpsMindPlatformRequest `
        -Uri "http://127.0.0.1:$platformPort/api/v1/me" -AccessToken $refreshed.validAccessToken
    if ($rotatedResponse.Status -ne 200) {
        throw 'Platform API did not refresh JWKS for the rotated Keycloak signing key.'
    }

    $env:OPSMIND_KEYCLOAK_REFRESH_TOKEN = [string]$initial.revocationRefreshToken
    $preRevocation = Invoke-ConformanceClient -Command 'refresh-once' `
        -ResultPath $preRevocationResultPath -Issuer $issuer -CertificatePath $serverCertificate
    if ([int]$preRevocation.summary.tokenLifetimeSeconds -ne 300) {
        throw 'Pre-revocation refresh-token positive control returned an invalid access token.'
    }

    $env:OPSMIND_KEYCLOAK_REFRESH_TOKEN = [string]$preRevocation.validRefreshToken
    $revoked = Invoke-ConformanceClient -Command revoke -ResultPath $revokeResultPath `
        -Issuer $issuer -CertificatePath $serverCertificate
    if ($revoked.summary.refreshAfterRevocation -cne 'invalid_grant') {
        throw 'Keycloak accepted a refresh token after explicit revocation.'
    }

    $userJson = Invoke-OpsMindKeycloakAdmin -ContainerName $containerName `
        -DockerPath $DockerPath -Arguments @(
        'get', 'users', '-r', $realmName, '-q', "username=$mfaUsername"
    )
    $matchedUsers = ($userJson -join [Environment]::NewLine) | ConvertFrom-Json
    $matchedUser = @($matchedUsers | Where-Object { $_.username -ceq $mfaUsername })
    if ($matchedUser.Count -ne 1) { throw 'Unable to resolve the exact Keycloak conformance user.' }
    Invoke-OpsMindKeycloakAdmin -ContainerName $containerName -DockerPath $DockerPath -Arguments @(
        'update', "users/$($matchedUser[0].id)", '-r', $realmName, '-s', 'enabled=false'
    ) | Out-Null
    $disabled = Invoke-ConformanceClient -Command 'assert-disabled' -ResultPath $disabledResultPath `
        -Issuer $issuer -CertificatePath $serverCertificate
    if (-not $disabled.summary.disabledUserLoginDenied) {
        throw 'Disabled Keycloak user denial was not proven.'
    }
    $boundedJwtResponse = Invoke-OpsMindPlatformRequest `
        -Uri "http://127.0.0.1:$platformPort/api/v1/me" -AccessToken $refreshed.validAccessToken
    if ($boundedJwtResponse.Status -ne 200) {
        throw 'Unexpected JWT behavior after IdP deprovisioning invalidated the bounded-lifetime contract.'
    }
    $executionCompleted = $true
}
catch {
    $executionException = $_.Exception
    $executionFailureType = $executionException.GetType().FullName
    try {
        $sensitiveValues = @(
            $tlsPassword,
            $adminPassword,
            $userPassword,
            [Environment]::GetEnvironmentVariable('OPSMIND_KEYCLOAK_TEST_PASSWORD', 'Process'),
            [Environment]::GetEnvironmentVariable('OPSMIND_KEYCLOAK_REFRESH_TOKEN', 'Process')
        )
        foreach ($line in @(Get-OpsMindSanitizedDiagnosticLines `
                    -Lines @($executionException.Message) `
                    -SensitiveValues $sensitiveValues `
                    -MaximumLines 10)) {
            $failureDiagnostics.Add("Exception=$line")
        }
        foreach ($line in @($lastConformanceClientOutput)) {
            $failureDiagnostics.Add("ConformanceClient=$line")
        }
        foreach ($logSource in @(
                @{ Name = 'PlatformStdout'; Path = $platformStdout },
                @{ Name = 'PlatformStderr'; Path = $platformStderr }
            )) {
            if (-not [string]::IsNullOrWhiteSpace([string]$logSource.Path) `
                -and (Test-Path -LiteralPath $logSource.Path -PathType Leaf)) {
                $logLines = @(Get-Content -LiteralPath $logSource.Path -Tail 100)
                foreach ($line in @(Get-OpsMindSanitizedDiagnosticLines `
                            -Lines $logLines `
                            -SensitiveValues $sensitiveValues `
                            -MaximumLines 100)) {
                    $failureDiagnostics.Add("$($logSource.Name)=$line")
                }
            }
        }
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $keycloakLogs = @(& $DockerPath logs --tail 100 $containerName 2>&1)
            $keycloakLogExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($keycloakLogExitCode -eq 0) {
            foreach ($line in @(Get-OpsMindSanitizedDiagnosticLines `
                        -Lines $keycloakLogs `
                        -SensitiveValues $sensitiveValues `
                        -MaximumLines 100)) {
                $failureDiagnostics.Add("Keycloak=$line")
            }
        }
    }
    catch {
        $failureDiagnostics.Add('DiagnosticCollection=UNAVAILABLE')
    }
}
finally {
    foreach ($name in $environmentNames) {
        try {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
            $restored = [Environment]::GetEnvironmentVariable($name, 'Process')
            if (-not [object]::Equals($restored, $previousEnvironment[$name])) {
                $cleanupFailures.Add("environment:$name")
            }
        }
        catch {
            $cleanupFailures.Add("environment:$name")
        }
    }
    try {
        if ($null -ne $platformProcess) {
            $platformProcess.Refresh()
            if (-not $platformProcess.HasExited) {
                Stop-Process -Id $platformProcess.Id -ErrorAction SilentlyContinue
                if (-not $platformProcess.WaitForExit(10000)) {
                    Stop-Process -Id $platformProcess.Id -Force -ErrorAction SilentlyContinue
                    [void]$platformProcess.WaitForExit(10000)
                }
                $platformProcess.Refresh()
                if (-not $platformProcess.HasExited) {
                    $cleanupFailures.Add('platform-process')
                }
            }
        }
    }
    catch {
        $cleanupFailures.Add('platform-process')
    }
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $DockerPath rm -f $containerName 2>&1 | Out-Null
        $residualContainer = @(& $DockerPath ps -aq --filter "name=^/$containerName$" 2>$null)
        $containerQueryExitCode = $LASTEXITCODE
    }
    catch {
        $containerQueryExitCode = 1
        $residualContainer = @('unknown')
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($containerQueryExitCode -ne 0 `
        -or @($residualContainer | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -ne 0) {
        $cleanupFailures.Add('keycloak-container')
    }
    try {
        Remove-OpsMindValidatedTempDirectory -Path $temporaryDirectory
        if (Test-Path -LiteralPath $temporaryDirectory) {
            $cleanupFailures.Add('temporary-directory')
        }
    }
    catch {
        $cleanupFailures.Add('temporary-directory')
    }
    $tlsPassword = $null
    $adminPassword = $null
    $userPassword = $null
    $initial = $null
    $refreshed = $null
}

if (-not $executionCompleted -or $cleanupFailures.Count -ne 0) {
    $evidenceStopwatch.Stop()
    $failureEndTimestampUtc = [DateTime]::UtcNow
    if ($cleanupFailures.Count -ne 0) {
        $failureDiagnostics.Add('CleanupFailures=' + ($cleanupFailures -join ','))
    }
    $boundedFailureDiagnostics = @(Get-OpsMindSanitizedDiagnosticLines `
            -Lines $failureDiagnostics.ToArray() `
            -MaximumLines 100)
    $failureEvidenceDirectory = Split-Path -Parent $failureEvidencePath
    New-Item -ItemType Directory -Force -Path $failureEvidenceDirectory | Out-Null
    $failureEvidenceLines = New-Object 'System.Collections.Generic.List[string]'
    $failureEvidenceLines.Add('OpsMind Phase 3 Keycloak OIDC conformance failure')
    $failureEvidenceLines.Add('FailureEvidenceSchemaVersion=1')
    $failureEvidenceLines.Add('StartTimestampUtc=' + $evidenceStartTimestampUtc.ToString('o'))
    $failureEvidenceLines.Add('EndTimestampUtc=' + $failureEndTimestampUtc.ToString('o'))
    $failureEvidenceLines.Add('DurationSeconds=' + $evidenceStopwatch.Elapsed.TotalSeconds.ToString(
            'F3', [Globalization.CultureInfo]::InvariantCulture
        ))
    $failureEvidenceLines.Add('ExecutionFailureType=' + $(if ([string]::IsNullOrWhiteSpace(
                $executionFailureType
            )) { 'NONE' } else { $executionFailureType }))
    $failureEvidenceLines.Add('CleanupVerified=' + $(if ($cleanupFailures.Count -eq 0) { 'PASS' } else { 'BLOCK' }))
    $failureEvidenceLines.Add('ConformanceProfileSha256=' + $profileDigest)
    $failureEvidenceLines.Add('PlatformArtifactSha256=' + $(if ([string]::IsNullOrWhiteSpace(
                $platformArtifactDigest
            )) { 'UNAVAILABLE' } else { $platformArtifactDigest }))
    $failureEvidenceLines.Add('DiagnosticsSanitized=YES')
    $failureEvidenceLines.Add('DiagnosticLineCount=' + $boundedFailureDiagnostics.Count)
    for ($index = 0; $index -lt $boundedFailureDiagnostics.Count; $index++) {
        $failureEvidenceLines.Add(('Diagnostic{0:D4}={1}' -f ($index + 1), $boundedFailureDiagnostics[$index]))
    }
    $failureEvidenceLines.Add('RuntimeSecretsPersisted=NO')
    $failureEvidenceLines.Add('Result=BLOCK')
    $temporaryFailureEvidencePath = Join-Path $failureEvidenceDirectory `
        ('.identity-delegation-failure-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllLines(
            $temporaryFailureEvidencePath,
            $failureEvidenceLines,
            [Text.UTF8Encoding]::new($false)
        )
        Move-Item -LiteralPath $temporaryFailureEvidencePath `
            -Destination $failureEvidencePath -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporaryFailureEvidencePath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryFailureEvidencePath -Force
        }
    }
    if (-not $executionCompleted) {
        throw "Keycloak conformance execution failed with a bounded error type: $executionFailureType"
    }
    throw ('Keycloak conformance cleanup verification failed: ' + ($cleanupFailures -join ','))
}

$currentProfileDigest = Get-OpsMindFileSetSha256 `
    -RepositoryRoot $repositoryRoot `
    -Paths $profileFiles
$currentArtifactDigest = (Get-FileHash `
    -LiteralPath $platformJar `
    -Algorithm SHA256).Hash.ToLowerInvariant()
if ($currentProfileDigest -cne $profileDigest) {
    throw 'Conformance profile inputs changed while the harness was running.'
}
if ([string]::IsNullOrWhiteSpace($platformArtifactDigest) `
    -or $currentArtifactDigest -cne $platformArtifactDigest) {
    throw 'The packaged Platform API artifact changed while the harness was running.'
}

$evidenceStopwatch.Stop()
$evidenceEndTimestampUtc = [DateTime]::UtcNow
$evidenceDirectory = Split-Path -Parent $EvidencePath
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$evidenceLines = @(
    'OpsMind Phase 3 Keycloak OIDC conformance',
    'EvidenceSchemaVersion=2',
    ('StartTimestampUtc={0}' -f $evidenceStartTimestampUtc.ToString('o')),
    ('EndTimestampUtc={0}' -f $evidenceEndTimestampUtc.ToString('o')),
    ('DurationSeconds={0}' -f $evidenceStopwatch.Elapsed.TotalSeconds.ToString(
            'F3', [Globalization.CultureInfo]::InvariantCulture
        )),
    ('ExecutionEnvironment={0}' -f $executionEnvironment),
    ('RuntimeOS={0}' -f $runtimeOs),
    ('RuntimeArchitecture={0}' -f $runtimeArchitecture),
    ('CodeRevision={0}' -f $gitMetadata.Revision),
    ('WorkspaceDirty={0}' -f $gitMetadata.Dirty),
    ('ContractVersion={0}' -f $contractVersion),
    'ScenarioVersion=phase-03-keycloak-oidc-v2',
    'DatasetVersion=synthetic-identity-v1',
    'ProfileDigestAlgorithm=SHA256_FILE_MANIFEST_V1',
    ('ConformanceProfileSha256={0}' -f $profileDigest),
    'PlatformArtifactDigestAlgorithm=SHA256',
    ('PlatformArtifactSha256={0}' -f $platformArtifactDigest),
    'Command=pwsh -NoProfile -File scripts/validation/run-phase-03-keycloak-conformance.ps1',
    ('JavaRuntime={0}' -f $javaRuntime),
    ('MavenRuntime={0}' -f $mavenRuntime),
    ('PythonRuntime={0}' -f $pythonRuntime),
    ('DockerServerVersion={0}' -f $dockerRuntime),
    'KeycloakVersion=26.7.0',
    'KeycloakImageDigest=sha256:1362a9d9f13ab325231ea133610cc905e12805804abc7acbef552dd613720aa6',
    'EvidenceScope=REFERENCE_CONFORMANCE_NOT_PRODUCTION',
    'RelevantLogs=identity-delegation.txt;process-console',
    'HttpsDiscovery=PASS',
    'AuthorizationCodePkceS256=PASS',
    'DirectGrantDisabled=PASS',
    'WrongCodeVerifierDenied=PASS',
    'TotpEnrollmentNotMfa=PASS',
    'TotpMfaAmr=PASS',
    'TotpSameCodeReplayDenied=PASS',
    'RpInitiatedLogout=PASS',
    'RefreshAfterLogoutDenied=PASS',
    'PlatformValidToken=PASS',
    'PlatformMissingMfaDenied=PASS',
    'PlatformAnonymousDenied=PASS',
    'PlatformTamperedSignatureDenied=PASS',
    'JwksRotationRefresh=PASS',
    'RefreshTokenRotationReuseDenied=PASS',
    'RefreshTokenIndependentSessions=PASS',
    'RefreshTokenPreRevocationControl=PASS',
    'RefreshTokenRevocation=PASS',
    'DisabledUserNewLoginDenied=PASS',
    'ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED',
    'AccessTokenLifetimeSeconds=300',
    'ConfiguredClockSkewSeconds=30',
    'MaximumResidualAcceptanceSeconds=330',
    'DisableToDenialHorizon=NOT_LIVE_MEASURED',
    'CleanupVerified=PASS',
    'RuntimeSecretsPersisted=NO',
    'Result=PASS'
)
$temporaryEvidencePath = Join-Path $evidenceDirectory `
    ('.identity-delegation-' + [guid]::NewGuid().ToString('N') + '.tmp')
try {
    [IO.File]::WriteAllLines(
        $temporaryEvidencePath,
        $evidenceLines,
        [Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryEvidencePath -Destination $EvidencePath -Force
}
finally {
    if (Test-Path -LiteralPath $temporaryEvidencePath -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryEvidencePath -Force
    }
}
$evidenceLines | Write-Output
