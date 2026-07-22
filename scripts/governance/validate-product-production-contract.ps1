[CmdletBinding()]
param(
    [string]$ContractPath,
    [string]$SchemaPath,
    [string]$EvidencePath,
    [switch]$AllowPending
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PropertyNames {
    param($InputObject)

    if ($null -eq $InputObject) {
        return @()
    }
    return @($InputObject.PSObject.Properties.Name)
}

function Get-ExactPropertyValue {
    param(
        [Parameter(Mandatory = $true)]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $property = $InputObject.PSObject.Properties |
        Where-Object { $_.Name -ceq $Name } |
        Select-Object -First 1
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Test-IsoCompatibleDate {
    param([AllowNull()][object]$Value)

    if ($Value -isnot [string] -or
        $Value -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$') {
        return $false
    }

    $parsed = [DateTimeOffset]::MinValue
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $style = [Globalization.DateTimeStyles]::RoundtripKind
    return [DateTimeOffset]::TryParse($Value, $culture, $style, [ref]$parsed)
}

function Write-EvidenceAtomically {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Lines
    )

    $directory = Split-Path -Parent $Path
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporaryPath = Join-Path $directory ('.contract-validation-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    $backupPath = Join-Path $directory ('.contract-validation-{0}.bak' -f [guid]::NewGuid().ToString('N'))
    $encoding = New-Object Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllText(
            $temporaryPath,
            ($Lines -join [Environment]::NewLine) + [Environment]::NewLine,
            $encoding
        )
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            [IO.File]::Replace($temporaryPath, $Path, $backupPath)
        }
        else {
            [IO.File]::Move($temporaryPath, $Path)
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
        if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
            Remove-Item -LiteralPath $backupPath -Force
        }
    }
}

function Test-PathContainsAnyReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Path)

    $currentPath = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($currentPath)) {
        if (Test-Path -LiteralPath $currentPath) {
            $item = Get-Item -LiteralPath $currentPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                return $true
            }
        }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent -or $parent.FullName -eq $currentPath) {
            break
        }
        $currentPath = $parent.FullName
    }
    return $false
}

function Test-ArtifactRootSafeForDefaultEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactRoot,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    if (-not (Test-Path -LiteralPath $ArtifactRoot -PathType Container) -or
        (Test-PathContainsAnyReparsePoint -Path $ArtifactRoot)) {
        return $false
    }
    $normalizedArtifactRoot = [IO.Path]::GetFullPath($ArtifactRoot).TrimEnd('\', '/')
    $normalizedRepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
    $volumeRoot = [IO.Path]::GetPathRoot($normalizedArtifactRoot).TrimEnd('\', '/')
    if ($normalizedArtifactRoot.Equals($volumeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    return -not (
        $normalizedRepositoryRoot.Equals($normalizedArtifactRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedRepositoryRoot.StartsWith(
            $normalizedArtifactRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )
    )
}

function ConvertTo-SafeEvidenceLine {
    param([AllowEmptyString()][string]$Line)

    $builder = New-Object Text.StringBuilder
    foreach ($character in $Line.ToCharArray()) {
        $codePoint = [int][char]$character
        if ($codePoint -lt 0x20 -or $codePoint -eq 0x7F -or
            $codePoint -eq 0x85 -or $codePoint -eq 0x2028 -or $codePoint -eq 0x2029) {
            [void]$builder.Append(('\u{0:X4}' -f $codePoint))
        }
        else {
            [void]$builder.Append($character)
        }
    }
    return $builder.ToString()
}

function Read-Utf8TextStrict {
    param([Parameter(Mandatory = $true)][string]$Path)

    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 4 -and
        (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE -and $bytes[2] -eq 0x00 -and $bytes[3] -eq 0x00) -or
         ($bytes[0] -eq 0x00 -and $bytes[1] -eq 0x00 -and $bytes[2] -eq 0xFE -and $bytes[3] -eq 0xFF))) {
        throw 'Governance JSON must use UTF-8, not UTF-32.'
    }
    if ($bytes.Length -ge 2 -and
        (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) -or
         ($bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF))) {
        throw 'Governance JSON must use UTF-8, not UTF-16.'
    }
    $offset = if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { 3 } else { 0 }
    $encoding = New-Object Text.UTF8Encoding($false, $true)
    return $encoding.GetString($bytes, $offset, $bytes.Length - $offset)
}

function Get-NormalizedTextSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    $normalizedText = (Read-Utf8TextStrict -Path $Path).Replace("`r`n", "`n").Replace("`r", "`n")
    $encoding = New-Object Text.UTF8Encoding($false)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $algorithm.ComputeHash($encoding.GetBytes($normalizedText))
        return ([BitConverter]::ToString($hashBytes) -replace '-', '')
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-DuplicateJsonProperties {
    param([Parameter(Mandatory = $true)][string]$Json)

    $containers = New-Object Collections.ArrayList
    $duplicates = @()
    for ($index = 0; $index -lt $Json.Length; $index++) {
        $character = $Json[$index]
        if ($character -eq '{') {
            $container = [pscustomobject]@{
                Type = 'object'
                Keys = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
            }
            [void]$containers.Add($container)
            continue
        }
        if ($character -eq '[') {
            [void]$containers.Add([pscustomobject]@{ Type = 'array'; Keys = $null })
            continue
        }
        if ($character -eq '}' -or $character -eq ']') {
            if ($containers.Count -gt 0) { $containers.RemoveAt($containers.Count - 1) }
            continue
        }
        if ($character -ne '"') { continue }

        $tokenStart = $index
        $closed = $false
        $index++
        while ($index -lt $Json.Length) {
            if ($Json[$index] -eq '\') {
                $index += 2
                continue
            }
            if ($Json[$index] -eq '"') {
                $closed = $true
                break
            }
            $index++
        }
        if (-not $closed) { break }

        $lookAhead = $index + 1
        while ($lookAhead -lt $Json.Length -and [char]::IsWhiteSpace($Json[$lookAhead])) { $lookAhead++ }
        if ($lookAhead -ge $Json.Length -or $Json[$lookAhead] -ne ':' -or $containers.Count -eq 0) {
            continue
        }
        $container = $containers[$containers.Count - 1]
        if ($container.Type -ne 'object') { continue }

        $token = $Json.Substring($tokenStart, $index - $tokenStart + 1)
        try { $propertyName = ConvertFrom-Json -InputObject $token }
        catch { continue }
        if (-not $container.Keys.Add([string]$propertyName)) {
            $duplicates += [string]$propertyName
        }
    }
    return @($duplicates)
}

function Assert-StrictJsonLexicalForm {
    param([Parameter(Mandatory = $true)][string]$Json)

    $index = 0
    while ($index -lt $Json.Length) {
        $character = $Json[$index]
        if (@([char]0x20, [char]0x09, [char]0x0A, [char]0x0D) -ccontains $character -or
            @('{', '}', '[', ']', ':', ',') -ccontains [string]$character) {
            $index++
            continue
        }

        if ($character -eq '"') {
            $index++
            $closed = $false
            while ($index -lt $Json.Length) {
                $stringCharacter = $Json[$index]
                if ([int][char]$stringCharacter -lt 0x20) {
                    throw "Contract JSON contains an unescaped control character at offset $index."
                }
                if ($stringCharacter -eq '\') {
                    if ($index + 1 -ge $Json.Length) {
                        throw "Contract JSON contains an incomplete escape at offset $index."
                    }
                    $escape = $Json[$index + 1]
                    if ($escape -eq 'u') {
                        if ($index + 5 -ge $Json.Length -or $Json.Substring($index + 2, 4) -notmatch '^[0-9A-Fa-f]{4}$') {
                            throw "Contract JSON contains an invalid Unicode escape at offset $index."
                        }
                        $index += 6
                        continue
                    }
                    if (-not (@('"', '\', '/', 'b', 'f', 'n', 'r', 't') -ccontains [string]$escape)) {
                        throw "Contract JSON contains an invalid escape at offset $index."
                    }
                    $index += 2
                    continue
                }
                if ($stringCharacter -eq '"') {
                    $closed = $true
                    $index++
                    break
                }
                $index++
            }
            if (-not $closed) { throw 'Contract JSON contains an unterminated string.' }
            continue
        }

        $tokenStart = $index
        while ($index -lt $Json.Length -and
            -not (@([char]0x20, [char]0x09, [char]0x0A, [char]0x0D) -ccontains $Json[$index]) -and
            -not (@(',', ']', '}') -ccontains [string]$Json[$index])) {
            $index++
        }
        $token = $Json.Substring($tokenStart, $index - $tokenStart)
        if ($character -eq '-' -or [char]::IsDigit($character)) {
            if ($token -notmatch '^-?(0|[1-9][0-9]{0,14})(\.[0-9]{1,6})?$') {
                throw "Contract JSON contains a non-canonical number at offset $tokenStart."
            }
            continue
        }
        if (-not (@('true', 'false', 'null') -ccontains $token)) {
            throw "Contract JSON contains a non-standard token at offset $tokenStart."
        }
    }
}

function Test-JsonInteger {
    param([AllowNull()][object]$Value)

    if ($Value -is [decimal]) { return [decimal]::Truncate($Value) -eq $Value }
    if ($Value -is [single]) {
        return -not [single]::IsNaN($Value) -and -not [single]::IsInfinity($Value) -and
            [single]::Truncate($Value) -eq $Value
    }
    if ($Value -is [double]) {
        return -not [double]::IsNaN($Value) -and -not [double]::IsInfinity($Value) -and
            [Math]::Truncate($Value) -eq $Value
    }
    return ($Value -is [sbyte] -or $Value -is [byte] -or $Value -is [int16] -or
        $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64])
}

function Get-UnicodeScalarCount {
    param([Parameter(Mandatory = $true)][string]$Value)

    $count = 0
    for ($index = 0; $index -lt $Value.Length; $index++) {
        $character = $Value[$index]
        if ([char]::IsHighSurrogate($character)) {
            if ($index + 1 -ge $Value.Length -or -not [char]::IsLowSurrogate($Value[$index + 1])) {
                return -1
            }
            $index++
        }
        elseif ([char]::IsLowSurrogate($character)) {
            return -1
        }
        $count++
    }
    return $count
}

function Test-JsonNumber {
    param([AllowNull()][object]$Value)

    if (Test-JsonInteger $Value) { return $true }
    if ($Value -is [single]) {
        return -not [single]::IsNaN($Value) -and -not [single]::IsInfinity($Value)
    }
    if ($Value -is [double]) {
        return -not [double]::IsNaN($Value) -and -not [double]::IsInfinity($Value)
    }
    return $Value -is [decimal]
}

function Test-DecisionValue {
    param(
        [Parameter(Mandatory = $true)][string]$DecisionKey,
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][hashtable]$Contract
    )

    $validationErrors = @()
    if ($Value -isnot [PSCustomObject]) {
        return @("$DecisionKey.value must be a typed JSON object when approved")
    }

    $propertyNames = Get-PropertyNames -InputObject $Value
    foreach ($field in $Contract.Required) {
        if (-not ($propertyNames -ccontains $field)) { $validationErrors += "$DecisionKey.value missing property: $field" }
    }
    foreach ($field in $propertyNames) {
        if (-not ($Contract.Required -ccontains $field)) { $validationErrors += "$DecisionKey.value has unknown property: $field" }
    }

    foreach ($field in $Contract.Strings.Keys) {
        if (-not ($propertyNames -ccontains $field)) { continue }
        $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
        $minimumLength = [int]$Contract.Strings[$field]
        if ($fieldValue -isnot [string] -or (Get-UnicodeScalarCount -Value $fieldValue.Trim()) -lt $minimumLength) {
            $validationErrors += "$DecisionKey.value.$field must be a string of at least $minimumLength characters"
        }
        elseif ($fieldValue -match '(?i)(approved-test-value|placeholder|\bTBD\b|\bTODO\b|changeme)') {
            $validationErrors += "$DecisionKey.value.$field contains a placeholder"
        }
    }
    foreach ($field in $Contract.Enums.Keys) {
        if (-not ($propertyNames -ccontains $field)) { continue }
        $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
        if ($fieldValue -isnot [string] -or -not ($Contract.Enums[$field] -ccontains $fieldValue)) {
            $validationErrors += "$DecisionKey.value.$field is outside its allowed values"
        }
    }
    foreach ($field in $Contract.Constants.Keys) {
        if (-not ($propertyNames -ccontains $field)) { continue }
        $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
        $expectedValue = $Contract.Constants[$field]
        $constantMatches = if ($expectedValue -is [bool]) {
            $fieldValue -is [bool] -and $fieldValue -eq $expectedValue
        }
        elseif ($expectedValue -is [string]) {
            $fieldValue -is [string] -and $fieldValue -ceq $expectedValue
        }
        else {
            $null -ne $fieldValue -and $fieldValue.GetType() -eq $expectedValue.GetType() -and
                $fieldValue -eq $expectedValue
        }
        if (-not $constantMatches) {
            $validationErrors += "$DecisionKey.value.$field must equal $($Contract.Constants[$field])"
        }
    }
    foreach ($field in $Contract.Booleans) {
        if ($propertyNames -ccontains $field) {
            $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
            if ($fieldValue -isnot [bool]) { $validationErrors += "$DecisionKey.value.$field must be a boolean" }
        }
    }
    foreach ($field in $Contract.Integers.Keys) {
        if (-not ($propertyNames -ccontains $field)) { continue }
        $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
        $limits = $Contract.Integers[$field]
        if (-not (Test-JsonInteger $fieldValue)) {
            $validationErrors += "$DecisionKey.value.$field must be an integer"
            continue
        }
        if ($limits.ContainsKey('Minimum') -and $fieldValue -lt $limits.Minimum) {
            $validationErrors += "$DecisionKey.value.$field must be at least $($limits.Minimum)"
        }
        if ($limits.ContainsKey('Maximum') -and $fieldValue -gt $limits.Maximum) {
            $validationErrors += "$DecisionKey.value.$field must be at most $($limits.Maximum)"
        }
    }
    foreach ($field in $Contract.Numbers.Keys) {
        if (-not ($propertyNames -ccontains $field)) { continue }
        $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
        $limits = $Contract.Numbers[$field]
        if (-not (Test-JsonNumber $fieldValue)) {
            $validationErrors += "$DecisionKey.value.$field must be a number"
            continue
        }
        if ($limits.ContainsKey('ExclusiveMinimum') -and $fieldValue -le $limits.ExclusiveMinimum) {
            $validationErrors += "$DecisionKey.value.$field must be greater than $($limits.ExclusiveMinimum)"
        }
        if ($limits.ContainsKey('Minimum') -and $fieldValue -lt $limits.Minimum) {
            $validationErrors += "$DecisionKey.value.$field must be at least $($limits.Minimum)"
        }
        if ($limits.ContainsKey('Maximum') -and $fieldValue -gt $limits.Maximum) {
            $validationErrors += "$DecisionKey.value.$field must be at most $($limits.Maximum)"
        }
    }
    foreach ($field in $Contract.Arrays.Keys) {
        if (-not ($propertyNames -ccontains $field)) { continue }
        $fieldValue = Get-ExactPropertyValue -InputObject $Value -Name $field
        $arrayContract = $Contract.Arrays[$field]
        if ($fieldValue -isnot [Array]) {
            $validationErrors += "$DecisionKey.value.$field must be an array"
            continue
        }
        if ($fieldValue.Count -lt $arrayContract.MinimumItems) {
            $validationErrors += "$DecisionKey.value.$field must contain at least $($arrayContract.MinimumItems) items"
        }
        $seenItems = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        foreach ($item in $fieldValue) {
            $minimumItemLength = if ($arrayContract.ContainsKey('MinimumItemLength')) {
                [int]$arrayContract.MinimumItemLength
            }
            else { 1 }
            if ($item -isnot [string] -or (Get-UnicodeScalarCount -Value $item.Trim()) -lt $minimumItemLength) {
                $validationErrors += "$DecisionKey.value.$field items must be strings of at least $minimumItemLength characters"
                continue
            }
            if ($item -match '(?i)(approved-test-value|placeholder|\bTBD\b|\bTODO\b|changeme)') {
                $validationErrors += "$DecisionKey.value.$field contains a placeholder item"
            }
            if (-not $seenItems.Add($item)) { $validationErrors += "$DecisionKey.value.$field items must be unique" }
            if ($arrayContract.Allowed.Count -gt 0 -and -not ($arrayContract.Allowed -ccontains $item)) {
                $validationErrors += "$DecisionKey.value.$field contains an unsupported item"
            }
        }
    }

    if ($DecisionKey -eq 'deepseekEgressPolicy' -and
        (Get-ExactPropertyValue -InputObject $Value -Name 'mode') -ceq 'allowlisted-redacted') {
        $allowedDataClasses = @(Get-ExactPropertyValue -InputObject $Value -Name 'allowedDataClasses')
        if ($allowedDataClasses.Count -lt 1) {
            $validationErrors += 'deepseekEgressPolicy.value.allowedDataClasses cannot be empty when egress is enabled'
        }
    }
    return @($validationErrors)
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$expectedSchemaSha256 = 'B624EC06E590E2171948917F6424042B94DEFAAAF919D287B96F3757A9605AAB'
$evidencePathWasExplicit = -not [string]::IsNullOrWhiteSpace($EvidencePath)
$artifactRoot = if ([string]::IsNullOrWhiteSpace($env:OPS_ARTIFACT_ROOT)) {
    Join-Path $repositoryRoot 'artifacts'
}
else {
    if (-not [IO.Path]::IsPathRooted($env:OPS_ARTIFACT_ROOT)) {
        throw 'OPS_ARTIFACT_ROOT must be an absolute path.'
    }
    [IO.Path]::GetFullPath($env:OPS_ARTIFACT_ROOT)
}
if ([string]::IsNullOrWhiteSpace($ContractPath)) {
    $ContractPath = Join-Path $repositoryRoot 'docs\decisions\product-production-contract.json'
}
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $artifactRoot 'verification\phase-01\product-production-contract.txt'
}
$ContractPath = [IO.Path]::GetFullPath($ContractPath)
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
if ([string]::IsNullOrWhiteSpace($SchemaPath)) {
    $SchemaPath = Join-Path (Split-Path -Parent $ContractPath) 'product-production-contract.schema.json'
}
$SchemaPath = [IO.Path]::GetFullPath($SchemaPath)
$artifactRootUsable = Test-ArtifactRootSafeForDefaultEvidence `
    -ArtifactRoot $artifactRoot -RepositoryRoot $repositoryRoot
if ($EvidencePath.Equals($ContractPath, [StringComparison]::OrdinalIgnoreCase) -or
    $EvidencePath.Equals($SchemaPath, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Output 'Error=Evidence path must not overwrite the contract or schema source.'
    Write-Output 'Result=BLOCK_INVALID'
    exit 6
}

$requiredDecisionKeys = @(
    'deploymentArchetype',
    'targetEnvironment',
    'tenantModel',
    'identityProfile',
    'deepseekEgressPolicy',
    'firstLiveIntegration',
    'evidenceArtifactStore',
    'loadEnvelope',
    'serviceObjectives',
    'dataLifecycle',
    'operationalOwnership',
    'deliveryCapacity'
)
$topLevelRequired = @('contractVersion', 'status', 'approvedBy', 'approvedAt', 'decisions')
$topLevelAllowed = @('$schema') + $topLevelRequired
$decisionRequired = @('state', 'value', 'recommended', 'ownerRole', 'approvedBy', 'approvedAt', 'evidence')
$decisionValueContracts = @{
    deploymentArchetype = @{
        Required = @('mode', 'rationale')
        Strings = @{ rationale = 12 }
        Enums = @{ mode = @('internal-single-organization', 'managed-saas', 'customer-hosted') }
        Constants = @{}; Booleans = @(); Integers = @{}; Numbers = @{}; Arrays = @{}
    }
    targetEnvironment = @{
        Required = @('substrate', 'region', 'residency', 'environmentClass')
        Strings = @{ substrate = 2; region = 2; residency = 2 }
        Enums = @{}; Constants = @{ environmentClass = 'production' }
        Booleans = @(); Integers = @{}; Numbers = @{}; Arrays = @{}
    }
    tenantModel = @{
        Required = @('organizationMode', 'isolationMode', 'maxOrganizations', 'maxProjectsPerOrganization')
        Strings = @{}
        Enums = @{ organizationMode = @('single', 'multi'); isolationMode = @('logical', 'physical', 'hybrid') }
        Constants = @{}; Booleans = @()
        Integers = @{ maxOrganizations = @{ Minimum = 1 }; maxProjectsPerOrganization = @{ Minimum = 1 } }
        Numbers = @{}; Arrays = @{}
    }
    identityProfile = @{
        Required = @('protocol', 'provider', 'authorizationFlow', 'mfaRequired', 'breakGlassOwner')
        Strings = @{ provider = 2; breakGlassOwner = 2 }
        Enums = @{}; Constants = @{ protocol = 'oidc'; authorizationFlow = 'authorization-code-pkce' }
        Booleans = @('mfaRequired'); Integers = @{}; Numbers = @{}; Arrays = @{}
    }
    deepseekEgressPolicy = @{
        Required = @('mode', 'allowedDataClasses', 'residencyPolicy', 'providerRetentionAllowed', 'redactionRequired', 'monthlyBudgetUsd', 'fallbackMode')
        Strings = @{ residencyPolicy = 2 }
        Enums = @{ mode = @('disabled', 'allowlisted-redacted'); fallbackMode = @('fail-closed', 'local-model', 'human-only') }
        Constants = @{ redactionRequired = $true }; Booleans = @('providerRetentionAllowed')
        Integers = @{}; Numbers = @{ monthlyBudgetUsd = @{ Minimum = 0 } }
        Arrays = @{ allowedDataClasses = @{ MinimumItems = 0; MinimumItemLength = 2; Allowed = @() } }
    }
    firstLiveIntegration = @{
        Required = @('connectorType', 'endpointClass', 'readOnly', 'owner')
        Strings = @{ owner = 2 }
        Enums = @{
            connectorType = @('prometheus', 'loki', 'opensearch', 'github', 'gitlab', 'kubernetes')
            endpointClass = @('synthetic-non-production', 'non-production', 'production')
        }
        Constants = @{ readOnly = $true }; Booleans = @(); Integers = @{}; Numbers = @{}; Arrays = @{}
    }
    evidenceArtifactStore = @{
        Required = @('localBackend', 'productionBackend', 'kmsBoundary', 'retentionOwner', 'restoreTargetHours')
        Strings = @{ localBackend = 2; productionBackend = 2; kmsBoundary = 2; retentionOwner = 2 }
        Enums = @{}; Constants = @{}; Booleans = @(); Integers = @{}
        Numbers = @{ restoreTargetHours = @{ ExclusiveMinimum = 0 } }; Arrays = @{}
    }
    loadEnvelope = @{
        Required = @('maxOrganizations', 'maxConcurrentInvestigations', 'evidenceEventsPerSecond', 'modelRequestsPerMinute')
        Strings = @{}; Enums = @{}; Constants = @{}; Booleans = @()
        Integers = @{
            maxOrganizations = @{ Minimum = 1 }
            maxConcurrentInvestigations = @{ Minimum = 1 }
            modelRequestsPerMinute = @{ Minimum = 1 }
        }
        Numbers = @{ evidenceEventsPerSecond = @{ ExclusiveMinimum = 0 } }; Arrays = @{}
    }
    serviceObjectives = @{
        Required = @('availabilityPercent', 'p95ApiLatencyMs', 'rtoMinutes', 'rpoMinutes')
        Strings = @{}; Enums = @{}; Constants = @{}; Booleans = @()
        Integers = @{
            p95ApiLatencyMs = @{ Minimum = 1 }
            rtoMinutes = @{ Minimum = 0 }
            rpoMinutes = @{ Minimum = 0 }
        }
        Numbers = @{ availabilityPercent = @{ ExclusiveMinimum = 0; Maximum = 100 } }; Arrays = @{}
    }
    dataLifecycle = @{
        Required = @('incidentRetentionDays', 'evidenceRetentionDays', 'auditRetentionDays', 'deletionSlaHours', 'trainingEligibility', 'residency')
        Strings = @{ residency = 2 }; Enums = @{}; Constants = @{ trainingEligibility = 'opt-in-only' }; Booleans = @()
        Integers = @{
            incidentRetentionDays = @{ Minimum = 1 }
            evidenceRetentionDays = @{ Minimum = 1 }
            auditRetentionDays = @{ Minimum = 1 }
            deletionSlaHours = @{ Minimum = 1 }
        }
        Numbers = @{}; Arrays = @{}
    }
    operationalOwnership = @{
        Required = @('platformOwner', 'onCallOwner', 'securityRiskOwner', 'privacyOwner', 'connectorOwner', 'databaseOwner', 'workflowOwner', 'providerSpendOwner')
        Strings = @{
            platformOwner = 2; onCallOwner = 2; securityRiskOwner = 2; privacyOwner = 2
            connectorOwner = 2; databaseOwner = 2; workflowOwner = 2; providerSpendOwner = 2
        }
        Enums = @{}; Constants = @{}; Booleans = @(); Integers = @{}; Numbers = @{}; Arrays = @{}
    }
    deliveryCapacity = @{
        Required = @('contributors', 'targetMonths', 'budgetApproved', 'skillCoverage')
        Strings = @{}; Enums = @{}; Constants = @{ budgetApproved = $true }; Booleans = @()
        Integers = @{ contributors = @{ Minimum = 1 }; targetMonths = @{ Minimum = 1; Maximum = 24 } }
        Numbers = @{}
        Arrays = @{
            skillCoverage = @{
                MinimumItems = 4
                MinimumItemLength = 1
                Allowed = @('product', 'frontend', 'backend', 'ai-ml', 'sre-platform', 'security-privacy', 'data', 'qa')
            }
        }
    }
}

$errors = @()
if (-not $evidencePathWasExplicit -and -not $artifactRootUsable) {
    $errors += 'default artifact root is unavailable or unsafe; run the storage-root preflight first'
}
$pending = @()
$rejected = @()
$decisionRows = @()
$contract = $null
$contractVersion = 'unavailable'
$contractStatus = 'unavailable'
$schemaSha256 = 'unavailable'

try {
    if (-not (Test-Path -LiteralPath $ContractPath -PathType Leaf)) {
        throw "Contract file not found: $ContractPath"
    }
    if (-not (Test-Path -LiteralPath $SchemaPath -PathType Leaf)) {
        throw "Contract schema not found: $SchemaPath"
    }
    if ((Get-Item -LiteralPath $ContractPath).Length -gt 1MB) {
        throw 'Contract file exceeds the 1 MB governance limit.'
    }
    if ((Get-Item -LiteralPath $SchemaPath).Length -gt 1MB) {
        throw 'Contract schema exceeds the 1 MB governance limit.'
    }

    $schemaJson = Read-Utf8TextStrict -Path $SchemaPath
    [void]($schemaJson | ConvertFrom-Json)
    $schemaSha256 = Get-NormalizedTextSha256 -Path $SchemaPath
    if ($schemaSha256 -cne $expectedSchemaSha256) {
        throw 'Contract schema fingerprint does not match the validator-supported schema.'
    }
    $contractJson = Read-Utf8TextStrict -Path $ContractPath
    Assert-StrictJsonLexicalForm -Json $contractJson
    $duplicateProperties = @(Get-DuplicateJsonProperties -Json $contractJson)
    if ($duplicateProperties.Count -gt 0) {
        throw ('Contract contains duplicate JSON properties: {0}' -f ($duplicateProperties -join ','))
    }
    $contract = $contractJson | ConvertFrom-Json
    if ($contract -is [Array] -or $contract -is [string] -or $contract -is [ValueType]) {
        throw 'Contract root must be a JSON object.'
    }

    $topLevelKeys = Get-PropertyNames -InputObject $contract
    foreach ($key in $topLevelRequired) {
        if (-not ($topLevelKeys -ccontains $key)) {
            $errors += "missing top-level property: $key"
        }
    }
    foreach ($key in $topLevelKeys) {
        if (-not ($topLevelAllowed -ccontains $key)) {
            $errors += "unknown top-level property: $key"
        }
    }

    if ($topLevelKeys -ccontains 'contractVersion') {
        $contractVersionValue = Get-ExactPropertyValue -InputObject $contract -Name 'contractVersion'
        if ($contractVersionValue -isnot [string]) {
            $errors += 'contractVersion must be a string'
        }
        else {
            $contractVersion = $contractVersionValue
            if ($contractVersion -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$') {
                $errors += 'contractVersion must be a canonical semantic version'
            }
        }
    }
    if ($topLevelKeys -ccontains 'status') {
        $contractStatusValue = Get-ExactPropertyValue -InputObject $contract -Name 'status'
        if ($contractStatusValue -isnot [string]) {
            $errors += 'status must be a string'
        }
        else {
            $contractStatus = $contractStatusValue
        }
        if (-not (@('blocked', 'approved') -ccontains $contractStatus)) {
            $errors += 'status must be blocked or approved'
        }
    }
    if ($topLevelKeys -ccontains '$schema') {
        $schemaReference = Get-ExactPropertyValue -InputObject $contract -Name '$schema'
        if ($schemaReference -isnot [string] -or [string]::IsNullOrWhiteSpace($schemaReference)) {
            $errors += '$schema must be a non-empty string when present'
        }
    }
    foreach ($propertyName in @('approvedBy', 'approvedAt')) {
        if ($topLevelKeys -ccontains $propertyName) {
            $propertyValue = Get-ExactPropertyValue -InputObject $contract -Name $propertyName
            if ($null -ne $propertyValue -and $propertyValue -isnot [string]) {
                $errors += "$propertyName must be a string or null"
            }
        }
    }
    $contractApprovedAt = Get-ExactPropertyValue -InputObject $contract -Name 'approvedAt'
    if ($topLevelKeys -ccontains 'approvedAt' -and $null -ne $contractApprovedAt -and
        -not (Test-IsoCompatibleDate $contractApprovedAt)) {
        $errors += 'approvedAt must be an RFC 3339 timestamp or null'
    }

    $decisions = if ($topLevelKeys -ccontains 'decisions') {
        Get-ExactPropertyValue -InputObject $contract -Name 'decisions'
    }
    else {
        $null
    }
    if ($null -ne $decisions -and $decisions -isnot [PSCustomObject]) {
        $errors += 'decisions must be a JSON object'
        $decisions = $null
    }
    $availableKeys = Get-PropertyNames -InputObject $decisions
    foreach ($key in $requiredDecisionKeys) {
        if (-not ($availableKeys -ccontains $key)) {
            $errors += "missing decision: $key"
        }
    }
    foreach ($key in $availableKeys) {
        if (-not ($requiredDecisionKeys -ccontains $key)) {
            $errors += "unknown decision: $key"
        }
    }

    foreach ($key in $requiredDecisionKeys) {
        if (-not ($availableKeys -ccontains $key)) {
            continue
        }

        $decision = Get-ExactPropertyValue -InputObject $decisions -Name $key
        if ($null -eq $decision -or $decision -isnot [PSCustomObject]) {
            $errors += "$key must be a JSON object"
            continue
        }
        $propertyNames = Get-PropertyNames -InputObject $decision
        foreach ($propertyName in $decisionRequired) {
            if (-not ($propertyNames -ccontains $propertyName)) {
                $errors += "$key missing property: $propertyName"
            }
        }
        foreach ($propertyName in $propertyNames) {
            if (-not ($decisionRequired -ccontains $propertyName)) {
                $errors += "$key has unknown property: $propertyName"
            }
        }

        $decisionStateValue = Get-ExactPropertyValue -InputObject $decision -Name 'state'
        $state = if ($propertyNames -ccontains 'state' -and $decisionStateValue -is [string]) {
            $decisionStateValue
        }
        else {
            'invalid'
        }
        if ($propertyNames -ccontains 'state' -and $decisionStateValue -isnot [string]) {
            $errors += "$key.state must be a string"
        }
        if (-not (@('pending', 'approved', 'rejected') -ccontains $state)) {
            $errors += "$key.state must be pending, approved, or rejected"
        }
        foreach ($propertyName in @('recommended', 'ownerRole')) {
            if ($propertyNames -ccontains $propertyName) {
                $propertyValue = Get-ExactPropertyValue -InputObject $decision -Name $propertyName
                if ($propertyValue -isnot [string] -or [string]::IsNullOrWhiteSpace($propertyValue)) {
                    $errors += "$key.$propertyName must be a non-empty string"
                }
                elseif ($propertyValue -match '(?i)(approved-test-value|placeholder|\bTBD\b|\bTODO\b|changeme)') {
                    $errors += "$key.$propertyName contains a placeholder"
                }
            }
        }
        foreach ($propertyName in @('approvedBy', 'approvedAt', 'evidence')) {
            if ($propertyNames -ccontains $propertyName) {
                $propertyValue = Get-ExactPropertyValue -InputObject $decision -Name $propertyName
                if ($null -ne $propertyValue -and $propertyValue -isnot [string]) {
                    $errors += "$key.$propertyName must be a string or null"
                }
            }
        }
        $decisionApprovedAt = Get-ExactPropertyValue -InputObject $decision -Name 'approvedAt'
        if ($propertyNames -ccontains 'approvedAt' -and $null -ne $decisionApprovedAt -and
            -not (Test-IsoCompatibleDate $decisionApprovedAt)) {
            $errors += "$key.approvedAt must be an RFC 3339 timestamp or null"
        }
        $decisionEvidence = Get-ExactPropertyValue -InputObject $decision -Name 'evidence'
        if ($null -ne $decisionEvidence -and
            ($decisionEvidence -isnot [string] -or $decisionEvidence -cnotmatch '^[a-z][a-z0-9+.-]*://[^\s]+$')) {
            $errors += "$key.evidence must be an absolute evidence reference or null"
        }
        $decisionValue = Get-ExactPropertyValue -InputObject $decision -Name 'value'
        if ($null -ne $decisionValue) {
            $errors += @(Test-DecisionValue -DecisionKey $key -Value $decisionValue -Contract $decisionValueContracts[$key])
        }

        if ($state -eq 'approved') {
            if ($propertyNames -ccontains 'value') {
                if ($null -eq $decisionValue) {
                    $errors += "$key.value is required when approved"
                }
            }
            $decisionApprovedBy = Get-ExactPropertyValue -InputObject $decision -Name 'approvedBy'
            if ($propertyNames -ccontains 'approvedBy' -and
                ($decisionApprovedBy -isnot [string] -or (Get-UnicodeScalarCount -Value $decisionApprovedBy.Trim()) -lt 2)) {
                $errors += "$key.approvedBy is required when approved"
            }
            elseif ([string]$decisionApprovedBy -match '(?i)(approved-test-value|placeholder|\bTBD\b|\bTODO\b|changeme)') {
                $errors += "$key.approvedBy contains a placeholder"
            }
            if ($propertyNames -ccontains 'approvedAt' -and $null -eq $decisionApprovedAt) {
                $errors += "$key.approvedAt is required when approved"
            }
            if ($propertyNames -ccontains 'evidence' -and
                [string]::IsNullOrWhiteSpace([string]$decisionEvidence)) {
                $errors += "$key.evidence is required when approved"
            }
        }
        else {
            $pending += $key
            if ($state -eq 'rejected') {
                $rejected += $key
            }
        }

        $decisionRows += ('{0}={1}' -f $key, $state)
    }

    if ($contractStatus -eq 'approved') {
        if ($pending.Count -gt 0) {
            $errors += 'global status cannot be approved while decisions remain pending or rejected'
        }
        $contractApprovedBy = Get-ExactPropertyValue -InputObject $contract -Name 'approvedBy'
        if ($topLevelKeys -ccontains 'approvedBy' -and
            ($contractApprovedBy -isnot [string] -or (Get-UnicodeScalarCount -Value $contractApprovedBy.Trim()) -lt 2)) {
            $errors += 'approvedBy is required when global status is approved'
        }
        elseif ([string]$contractApprovedBy -match '(?i)(approved-test-value|placeholder|\bTBD\b|\bTODO\b|changeme)') {
            $errors += 'approvedBy contains a placeholder'
        }
        if ($topLevelKeys -ccontains 'approvedAt' -and $null -eq $contractApprovedAt) {
            $errors += 'approvedAt is required when global status is approved'
        }
    }
}
catch {
    $errors += $_.Exception.Message
}

$approvalIncomplete = $contractStatus -ne 'approved' -or $pending.Count -gt 0
$result = 'PASS'
$exitCode = 0
if ($errors.Count -gt 0) {
    $result = 'BLOCK_INVALID'
    $exitCode = 6
}
elseif ($rejected.Count -gt 0) {
    $result = 'BLOCK_REJECTED_DECISIONS'
    $exitCode = 5
}
elseif ($approvalIncomplete) {
    if ($AllowPending) {
        $result = 'STRUCTURE_VALID_PENDING'
        $exitCode = 10
    }
    else {
        $result = 'BLOCK_PENDING_DECISIONS'
        $exitCode = 5
    }
}

$lines = @(
    'OpsMind product/production contract validation',
    ('TimestampUtc={0}' -f [DateTime]::UtcNow.ToString('o')),
    ('SchemaSha256={0}' -f $schemaSha256),
    ('ContractVersion={0}' -f $contractVersion),
    ('ContractStatus={0}' -f $contractStatus)
)
$lines += $decisionRows
if ($pending.Count -gt 0) {
    $lines += ('Pending={0}' -f ($pending -join ','))
}
if ($rejected.Count -gt 0) {
    $lines += ('Rejected={0}' -f ($rejected -join ','))
}
if ($errors.Count -gt 0) {
    $lines += $errors | ForEach-Object { 'Error={0}' -f $_ }
}
$lines += ('Result={0}' -f $result)
$lines = @($lines | ForEach-Object { ConvertTo-SafeEvidenceLine -Line ([string]$_) })

if ($evidencePathWasExplicit -or $artifactRootUsable) {
    Write-EvidenceAtomically -Path $EvidencePath -Lines $lines
}
else {
    $lines = @($lines[0..($lines.Count - 2)]) + 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT' + $lines[-1]
}
$lines | Write-Output
exit $exitCode
