[CmdletBinding()]
param(
    [string]$EvidencePath,
    [string]$PlatformArtifactPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath([IO.Path]::Combine($PSScriptRoot, '..', '..'))
$supportPath = [IO.Path]::Combine(
    $repositoryRoot,
    'scripts',
    'validation',
    'keycloak',
    'keycloak-conformance-support.ps1'
)
$profileManifestPath = [IO.Path]::Combine(
    $repositoryRoot,
    'scripts',
    'validation',
    'keycloak',
    'keycloak-conformance-profile-files.txt'
)
. $supportPath

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = [IO.Path]::Combine(
        $repositoryRoot,
        'artifacts',
        'verification',
        'phase-03',
        'identity-delegation.txt'
    )
}
if ([string]::IsNullOrWhiteSpace($PlatformArtifactPath)) {
    $PlatformArtifactPath = [IO.Path]::Combine(
        $repositoryRoot,
        'services',
        'platform-api',
        'target',
        'platform-api.jar'
    )
}
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
$PlatformArtifactPath = [IO.Path]::GetFullPath($PlatformArtifactPath)
if (-not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) {
    throw 'Identity conformance evidence is missing.'
}
if (-not (Test-Path -LiteralPath $PlatformArtifactPath -PathType Leaf)) {
    throw 'The packaged Platform API artifact bound to identity evidence is missing.'
}
if ((Get-Item -LiteralPath $EvidencePath).Length -gt 65536) {
    throw 'Identity conformance evidence exceeds its bounded size.'
}

$fields = @{}
$contents = Get-Content -LiteralPath $EvidencePath -Raw
$evidenceLines = @($contents -split "`r?`n")
if ($evidenceLines.Count -eq 0 `
    -or $evidenceLines[0] -cne 'OpsMind Phase 3 Keycloak OIDC conformance') {
    throw 'Identity conformance evidence title is invalid.'
}
for ($index = 1; $index -lt $evidenceLines.Count; $index++) {
    $line = $evidenceLines[$index]
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    if (-not $line.Contains('=')) {
        throw 'Identity conformance evidence contains an unstructured line.'
    }
    $separator = $line.IndexOf('=')
    $name = $line.Substring(0, $separator)
    $value = $line.Substring($separator + 1)
    if ($name -notmatch '^[A-Za-z][A-Za-z0-9]*$' -or $fields.ContainsKey($name)) {
        throw 'Identity conformance evidence contains an invalid or duplicate field.'
    }
    $fields[$name] = $value
}

$expectedFields = [ordered]@{
    EvidenceSchemaVersion = '2'
    ScenarioVersion = 'phase-03-keycloak-oidc-v2'
    DatasetVersion = 'synthetic-identity-v1'
    ProfileDigestAlgorithm = 'SHA256_FILE_MANIFEST_V1'
    PlatformArtifactDigestAlgorithm = 'SHA256'
    Command = 'pwsh -NoProfile -File scripts/validation/run-phase-03-keycloak-conformance.ps1'
    KeycloakVersion = '26.7.0'
    KeycloakImageDigest = 'sha256:1362a9d9f13ab325231ea133610cc905e12805804abc7acbef552dd613720aa6'
    EvidenceScope = 'REFERENCE_CONFORMANCE_NOT_PRODUCTION'
    RelevantLogs = 'identity-delegation.txt;process-console'
    HttpsDiscovery = 'PASS'
    AuthorizationCodePkceS256 = 'PASS'
    DirectGrantDisabled = 'PASS'
    WrongCodeVerifierDenied = 'PASS'
    TotpEnrollmentNotMfa = 'PASS'
    TotpMfaAmr = 'PASS'
    TotpSameCodeReplayDenied = 'PASS'
    RpInitiatedLogout = 'PASS'
    RefreshAfterLogoutDenied = 'PASS'
    PlatformValidToken = 'PASS'
    PlatformMissingMfaDenied = 'PASS'
    PlatformAnonymousDenied = 'PASS'
    PlatformTamperedSignatureDenied = 'PASS'
    JwksRotationRefresh = 'PASS'
    RefreshTokenRotationReuseDenied = 'PASS'
    RefreshTokenIndependentSessions = 'PASS'
    RefreshTokenPreRevocationControl = 'PASS'
    RefreshTokenRevocation = 'PASS'
    DisabledUserNewLoginDenied = 'PASS'
    ExistingJwtAfterIdpDisable = 'PREISSUED_JWT_STILL_ACCEPTED'
    AccessTokenLifetimeSeconds = '300'
    ConfiguredClockSkewSeconds = '30'
    MaximumResidualAcceptanceSeconds = '330'
    DisableToDenialHorizon = 'NOT_LIVE_MEASURED'
    CleanupVerified = 'PASS'
    RuntimeSecretsPersisted = 'NO'
    Result = 'PASS'
}
foreach ($name in $expectedFields.Keys) {
    if (-not $fields.ContainsKey($name) -or $fields[$name] -cne $expectedFields[$name]) {
        throw "Identity conformance evidence field is missing or invalid: $name"
    }
}
$dynamicFields = @(
    'StartTimestampUtc', 'EndTimestampUtc', 'DurationSeconds',
    'ExecutionEnvironment', 'RuntimeOS', 'RuntimeArchitecture',
    'CodeRevision', 'WorkspaceDirty', 'ContractVersion',
    'ConformanceProfileSha256', 'PlatformArtifactSha256',
    'JavaRuntime', 'MavenRuntime', 'PythonRuntime', 'DockerServerVersion'
)
$allowedFields = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
foreach ($name in $expectedFields.Keys + $dynamicFields) {
    [void]$allowedFields.Add($name)
}
foreach ($name in $fields.Keys) {
    if (-not $allowedFields.Contains($name)) {
        throw "Identity conformance evidence contains an unknown field: $name"
    }
}
foreach ($name in $dynamicFields) {
    if (-not $fields.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($fields[$name]) `
        -or $fields[$name].Length -gt 512) {
        throw "Identity conformance evidence dynamic field is missing or invalid: $name"
    }
}
if ($fields.Count -ne $allowedFields.Count) {
    throw 'Identity conformance evidence does not match the complete schema.'
}

try {
    $startTimestamp = [DateTimeOffset]::Parse(
        $fields['StartTimestampUtc'],
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind
    )
    $endTimestamp = [DateTimeOffset]::Parse(
        $fields['EndTimestampUtc'],
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind
    )
    $durationSeconds = [double]::Parse(
        $fields['DurationSeconds'],
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture
    )
}
catch {
    throw 'Identity conformance evidence timing fields are invalid.'
}
$wallDurationSeconds = ($endTimestamp - $startTimestamp).TotalSeconds
if ($durationSeconds -le 0 -or [double]::IsNaN($durationSeconds) `
    -or [double]::IsInfinity($durationSeconds) -or $wallDurationSeconds -le 0 `
    -or [Math]::Abs($wallDurationSeconds - $durationSeconds) -gt 5) {
    throw 'Identity conformance evidence duration is inconsistent.'
}
if ($fields['ExecutionEnvironment'] -notmatch '^(?:local|github-actions/[A-Za-z0-9._-]+)$' `
    -or $fields['CodeRevision'] -notmatch '^(?:UNBORN|UNAVAILABLE|[0-9a-f]{40}|[0-9a-f]{64})$' `
    -or $fields['WorkspaceDirty'] -notmatch '^(?:YES|NO|UNKNOWN)$' `
    -or $fields['ContractVersion'] -notmatch '^opsmind-v1@[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$' `
    -or $fields['ConformanceProfileSha256'] -notmatch '^[0-9a-f]{64}$' `
    -or $fields['PlatformArtifactSha256'] -notmatch '^[0-9a-f]{64}$') {
    throw 'Identity conformance evidence metadata is invalid.'
}

$profilePaths = @(Get-OpsMindConformanceProfilePaths `
    -RepositoryRoot $repositoryRoot `
    -ManifestPath $profileManifestPath)
$currentProfileDigest = Get-OpsMindFileSetSha256 `
    -RepositoryRoot $repositoryRoot `
    -Paths $profilePaths
$currentArtifactDigest = (Get-FileHash `
    -LiteralPath $PlatformArtifactPath `
    -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not $fields.ContainsKey('ConformanceProfileSha256') `
    -or $fields['ConformanceProfileSha256'] -cne $currentProfileDigest) {
    throw 'Identity conformance evidence is stale for the current profile inputs.'
}
if (-not $fields.ContainsKey('PlatformArtifactSha256') `
    -or $fields['PlatformArtifactSha256'] -cne $currentArtifactDigest) {
    throw 'Identity conformance evidence is stale for the packaged Platform API artifact.'
}
if ($contents -match 'eyJ[A-Za-z0-9_-]{10,}\.') {
    throw 'Identity conformance evidence contains a JWT-shaped value.'
}

Write-Output 'IdentityEvidenceProfileDigest=PASS'
Write-Output 'IdentityEvidenceArtifactDigest=PASS'
Write-Output 'IdentityEvidenceVerification=PASS'
