[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Validator {
    param(
        [Parameter(Mandatory = $true)][string]$ValidatorPath,
        [Parameter(Mandatory = $true)][string]$ContractPath,
        [Parameter(Mandatory = $true)][string]$SchemaPath,
        [Parameter(Mandatory = $true)][string]$EvidencePath,
        [Parameter(Mandatory = $true)][int]$ExpectedExitCode,
        [switch]$AllowPending
    )

    $arguments = @(
        '-NoLogo',
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $ValidatorPath,
        '-ContractPath', $ContractPath,
        '-SchemaPath', $SchemaPath,
        '-EvidencePath', $EvidencePath
    )
    if ($AllowPending) {
        $arguments += '-AllowPending'
    }
    & powershell.exe @arguments | Out-Host
    $actualExitCode = [int]$LASTEXITCODE
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "Unexpected contract-validator exit. Expected=$ExpectedExitCode Actual=$actualExitCode"
    }
}

function Write-Contract {
    param(
        [Parameter(Mandatory = $true)]$Contract,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, ($Contract | ConvertTo-Json -Depth 20) + [Environment]::NewLine, $encoding)
}

function New-ApprovedDecisionValue {
    param([Parameter(Mandatory = $true)][string]$DecisionKey)

    switch ($DecisionKey) {
        'deploymentArchetype' {
            return [pscustomobject]@{ mode = 'internal-single-organization'; rationale = 'Initial production scope limits operational and tenancy risk.' }
        }
        'targetEnvironment' {
            return [pscustomobject]@{ substrate = 'managed-kubernetes'; region = 'ap-southeast-1'; residency = 'Singapore'; environmentClass = 'production' }
        }
        'tenantModel' {
            return [pscustomobject]@{ organizationMode = 'single'; isolationMode = 'logical'; maxOrganizations = 1; maxProjectsPerOrganization = 100 }
        }
        'identityProfile' {
            return [pscustomobject]@{ protocol = 'oidc'; provider = 'enterprise-oidc'; authorizationFlow = 'authorization-code-pkce'; mfaRequired = $true; breakGlassOwner = 'security-operations' }
        }
        'deepseekEgressPolicy' {
            return [pscustomobject]@{
                mode = 'allowlisted-redacted'; allowedDataClasses = @('redacted-metrics', 'redacted-log-summaries')
                residencyPolicy = 'approved-provider-region'; providerRetentionAllowed = $false
                redactionRequired = $true; monthlyBudgetUsd = 1000; fallbackMode = 'human-only'
            }
        }
        'firstLiveIntegration' {
            return [pscustomobject]@{ connectorType = 'prometheus'; endpointClass = 'synthetic-non-production'; readOnly = $true; owner = 'site-reliability-team' }
        }
        'evidenceArtifactStore' {
            return [pscustomobject]@{ localBackend = 'minio'; productionBackend = 's3-compatible'; kmsBoundary = 'production-kms'; retentionOwner = 'platform-security'; restoreTargetHours = 4 }
        }
        'loadEnvelope' {
            return [pscustomobject]@{ maxOrganizations = 1; maxConcurrentInvestigations = 25; evidenceEventsPerSecond = 500; modelRequestsPerMinute = 120 }
        }
        'serviceObjectives' {
            return [pscustomobject]@{ availabilityPercent = 99.9; p95ApiLatencyMs = 500; rtoMinutes = 120; rpoMinutes = 15 }
        }
        'dataLifecycle' {
            return [pscustomobject]@{
                incidentRetentionDays = 365; evidenceRetentionDays = 90; auditRetentionDays = 730
                deletionSlaHours = 24; trainingEligibility = 'opt-in-only'; residency = 'Singapore'
            }
        }
        'operationalOwnership' {
            return [pscustomobject]@{
                platformOwner = 'platform-team'; onCallOwner = 'site-reliability-team'
                securityRiskOwner = 'security-team'; privacyOwner = 'privacy-team'
                connectorOwner = 'integrations-team'; databaseOwner = 'database-team'
                workflowOwner = 'workflow-team'; providerSpendOwner = 'product-finance-owner'
            }
        }
        'deliveryCapacity' {
            return [pscustomobject]@{
                contributors = 6; targetMonths = 9; budgetApproved = $true
                skillCoverage = @('product', 'frontend', 'backend', 'ai-ml', 'sre-platform', 'security-privacy')
            }
        }
        default { throw "No approved test value contract exists for: $DecisionKey" }
    }
}

function Test-ReparsePointInRepositoryPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $currentPath = [IO.Path]::GetFullPath($Path)
    while ($currentPath.StartsWith($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $currentPath) {
            $item = Get-Item -LiteralPath $currentPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                return $true
            }
        }
        if ($currentPath.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent) { break }
        $currentPath = $parent.FullName
    }
    return $false
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\', '/')
$validatorPath = Join-Path $PSScriptRoot 'validate-product-production-contract.ps1'
$sourceContractPath = Join-Path $repositoryRoot 'docs\decisions\product-production-contract.json'
$schemaPath = Join-Path $repositoryRoot 'docs\decisions\product-production-contract.schema.json'
$testRoot = Join-Path $repositoryRoot ('.opsmind\governance-tests\{0}' -f [guid]::NewGuid().ToString('N'))
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)

if (-not $resolvedTestRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create governance test data outside the repository.'
}
if (Test-ReparsePointInRepositoryPath -Path $resolvedTestRoot -RepositoryRoot $repositoryRoot) {
    throw 'Refusing to create governance test data through a reparse path.'
}

try {
    [void](New-Item -ItemType Directory -Path $resolvedTestRoot -Force)

    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $sourceContractPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'pending.txt') -ExpectedExitCode 0

    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $sourceContractPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'pending-structure.txt') `
        -ExpectedExitCode 0 -AllowPending

    $rejectedContract = Get-Content -LiteralPath $sourceContractPath -Raw | ConvertFrom-Json
    $rejectedContract.decisions.deploymentArchetype.state = 'rejected'
    $rejectedPath = Join-Path $resolvedTestRoot 'rejected.json'
    Write-Contract -Contract $rejectedContract -Path $rejectedPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $rejectedPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'rejected.txt') `
        -ExpectedExitCode 6 -AllowPending

    $contract = Get-Content -LiteralPath $sourceContractPath -Raw | ConvertFrom-Json
    foreach ($property in $contract.decisions.PSObject.Properties) {
        $decision = $property.Value
        $decision.state = 'approved'
        $decision.value = New-ApprovedDecisionValue -DecisionKey $property.Name
        $decision.approvedBy = 'qa-product-owner'
        $decision.approvedAt = '2026-07-19T00:00:00Z'
        $decision.evidence = "test://decision/$($property.Name)"
    }

    $blockedGlobalPath = Join-Path $resolvedTestRoot 'blocked-global.json'
    Write-Contract -Contract $contract -Path $blockedGlobalPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $blockedGlobalPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'blocked-global.txt') -ExpectedExitCode 0

    $contract.status = 'approved'
    $contract.approvedBy = 'qa-accountable-owner'
    $contract.approvedAt = '2026-07-19T00:00:00Z'
    $approvedPath = Join-Path $resolvedTestRoot 'approved.json'
    Write-Contract -Contract $contract -Path $approvedPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $approvedPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'approved.txt') -ExpectedExitCode 0

    $approvedJson = [IO.File]::ReadAllText($approvedPath)
    $utf16ContractPath = Join-Path $resolvedTestRoot 'invalid-utf16-contract.json'
    [IO.File]::WriteAllText($utf16ContractPath, $approvedJson, [Text.Encoding]::Unicode)
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $utf16ContractPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-utf16-contract.txt') `
        -ExpectedExitCode 6

    $statusPropertyPattern = New-Object Text.RegularExpressions.Regex('"status"\s*:\s*')
    $nonJsonWhitespace = $statusPropertyPattern.Replace($approvedJson, ('"status":' + [char]0x00A0), 1)
    $nonJsonWhitespacePath = Join-Path $resolvedTestRoot 'invalid-non-json-whitespace.json'
    [IO.File]::WriteAllText($nonJsonWhitespacePath, $nonJsonWhitespace, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $nonJsonWhitespacePath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-non-json-whitespace.txt') `
        -ExpectedExitCode 6

    $placeholderContract = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $placeholderContract.decisions.deploymentArchetype.value.rationale = 'approved-test-value placeholder'
    $placeholderPath = Join-Path $resolvedTestRoot 'invalid-placeholder-value.json'
    Write-Contract -Contract $placeholderContract -Path $placeholderPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $placeholderPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-placeholder-value.txt') `
        -ExpectedExitCode 6

    $placeholderOwnerContract = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $placeholderOwnerContract.decisions.deploymentArchetype.ownerRole = 'TBD'
    $placeholderOwnerPath = Join-Path $resolvedTestRoot 'invalid-placeholder-owner.json'
    Write-Contract -Contract $placeholderOwnerContract -Path $placeholderOwnerPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $placeholderOwnerPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-placeholder-owner.txt') `
        -ExpectedExitCode 6

    $numericContract = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $numericContract.decisions.serviceObjectives.value.availabilityPercent = 101
    $numericPath = Join-Path $resolvedTestRoot 'invalid-numeric-bound.json'
    Write-Contract -Contract $numericContract -Path $numericPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $numericPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-numeric-bound.txt') `
        -ExpectedExitCode 6

    $nanJson = $approvedJson -creplace '"evidenceEventsPerSecond"\s*:\s*500', '"evidenceEventsPerSecond":NaN'
    $nanPath = Join-Path $resolvedTestRoot 'invalid-nan-number.json'
    [IO.File]::WriteAllText($nanPath, $nanJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $nanPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-nan-number.txt') `
        -ExpectedExitCode 6

    $infinityJson = $approvedJson -creplace '"restoreTargetHours"\s*:\s*4', '"restoreTargetHours":Infinity'
    $infinityPath = Join-Path $resolvedTestRoot 'invalid-infinity-number.json'
    [IO.File]::WriteAllText($infinityPath, $infinityJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $infinityPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-infinity-number.txt') `
        -ExpectedExitCode 6

    $signedNumberJson = $approvedJson -creplace '"evidenceEventsPerSecond"\s*:\s*500', '"evidenceEventsPerSecond":+500'
    $signedNumberPath = Join-Path $resolvedTestRoot 'invalid-leading-plus-number.json'
    [IO.File]::WriteAllText($signedNumberPath, $signedNumberJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $signedNumberPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-leading-plus-number.txt') `
        -ExpectedExitCode 6

    $exponentNumberJson = $approvedJson -creplace '"evidenceEventsPerSecond"\s*:\s*500', '"evidenceEventsPerSecond":5e2'
    $exponentNumberPath = Join-Path $resolvedTestRoot 'invalid-exponent-number.json'
    [IO.File]::WriteAllText($exponentNumberPath, $exponentNumberJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $exponentNumberPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-exponent-number.txt') `
        -ExpectedExitCode 6

    $precisionNumberJson = $approvedJson -creplace '"availabilityPercent"\s*:\s*99\.9', '"availabilityPercent":100.0000001'
    $precisionNumberPath = Join-Path $resolvedTestRoot 'invalid-overprecision-number.json'
    [IO.File]::WriteAllText($precisionNumberPath, $precisionNumberJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $precisionNumberPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-overprecision-number.txt') `
        -ExpectedExitCode 6

    $singleQuotedPropertyJson = $approvedJson -creplace '"status"\s*:', "'status':"
    $singleQuotedPropertyPath = Join-Path $resolvedTestRoot 'invalid-single-quoted-property.json'
    [IO.File]::WriteAllText($singleQuotedPropertyPath, $singleQuotedPropertyJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $singleQuotedPropertyPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-single-quoted-property.txt') `
        -ExpectedExitCode 6

    $booleanConstantContract = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $booleanConstantContract.decisions.firstLiveIntegration.value.readOnly = 1
    $booleanConstantPath = Join-Path $resolvedTestRoot 'invalid-boolean-constant-type.json'
    Write-Contract -Contract $booleanConstantContract -Path $booleanConstantPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $booleanConstantPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-boolean-constant-type.txt') `
        -ExpectedExitCode 6

    $shortArrayItemJson = $approvedJson -creplace '"allowedDataClasses"\s*:\s*\[\s*"redacted-metrics"\s*,\s*"redacted-log-summaries"\s*\]', '"allowedDataClasses":["x"]'
    $shortArrayItemPath = Join-Path $resolvedTestRoot 'invalid-short-array-item.json'
    [IO.File]::WriteAllText($shortArrayItemPath, $shortArrayItemJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $shortArrayItemPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-short-array-item.txt') `
        -ExpectedExitCode 6

    $evidenceContract = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $evidenceContract.decisions.firstLiveIntegration.evidence = 'bare-placeholder-reference'
    $evidenceReferencePath = Join-Path $resolvedTestRoot 'invalid-evidence-reference.json'
    Write-Contract -Contract $evidenceContract -Path $evidenceReferencePath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $evidenceReferencePath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-evidence-reference.txt') `
        -ExpectedExitCode 6

    $uppercaseEvidenceContract = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $uppercaseEvidenceContract.decisions.firstLiveIntegration.evidence = 'HTTPS://decisions.invalid/approval'
    $uppercaseEvidencePath = Join-Path $resolvedTestRoot 'invalid-uppercase-evidence-scheme.json'
    Write-Contract -Contract $uppercaseEvidenceContract -Path $uppercaseEvidencePath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $uppercaseEvidencePath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-uppercase-evidence-scheme.txt') `
        -ExpectedExitCode 6

    $duplicateTopJson = $approvedJson -creplace '"status"\s*:\s*"approved"\s*,', '"status":"blocked","status":"approved",'
    $duplicateTopPath = Join-Path $resolvedTestRoot 'invalid-duplicate-top-property.json'
    [IO.File]::WriteAllText($duplicateTopPath, $duplicateTopJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $duplicateTopPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-duplicate-top-property.txt') `
        -ExpectedExitCode 6

    $duplicateNestedJson = $approvedJson -creplace '"mode"\s*:\s*"internal-single-organization"\s*,', '"mode":"managed-saas","mode":"internal-single-organization",'
    $duplicateNestedPath = Join-Path $resolvedTestRoot 'invalid-duplicate-nested-property.json'
    [IO.File]::WriteAllText($duplicateNestedPath, $duplicateNestedJson, (New-Object Text.UTF8Encoding($false)))
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $duplicateNestedPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-duplicate-nested-property.txt') `
        -ExpectedExitCode 6

    $contract | Add-Member -NotePropertyName unexpectedBypassField -NotePropertyValue 'must-fail'
    $invalidPath = Join-Path $resolvedTestRoot 'invalid-extra-property.json'
    Write-Contract -Contract $contract -Path $invalidPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $invalidPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid.txt') -ExpectedExitCode 6

    $contract.PSObject.Properties.Remove('unexpectedBypassField')
    $contract.contractVersion = 1
    $invalidTypePath = Join-Path $resolvedTestRoot 'invalid-type.json'
    Write-Contract -Contract $contract -Path $invalidTypePath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $invalidTypePath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-type.txt') -ExpectedExitCode 6

    $contract.contractVersion = '0.1.0'
    $contractVersionInjection = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $contractVersionInjection.contractVersion = "0.1.0`nResult=PASS"
    $contractVersionInjectionPath = Join-Path $resolvedTestRoot 'invalid-contract-version-injection.json'
    $contractVersionInjectionEvidence = Join-Path $resolvedTestRoot 'invalid-contract-version-injection.txt'
    Write-Contract -Contract $contractVersionInjection -Path $contractVersionInjectionPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $contractVersionInjectionPath `
        -SchemaPath $schemaPath -EvidencePath $contractVersionInjectionEvidence -ExpectedExitCode 6
    if (@(Get-Content -LiteralPath $contractVersionInjectionEvidence | Where-Object { $_ -match '^Result=' }).Count -ne 1 -or
        -not (Select-String -LiteralPath $contractVersionInjectionEvidence -SimpleMatch '\u000AResult=PASS' -Quiet)) {
        throw 'Contract-version evidence injection was not safely encoded.'
    }

    $unknownKeyInjection = Get-Content -LiteralPath $approvedPath -Raw | ConvertFrom-Json
    $unknownKeyInjection | Add-Member -NotePropertyName "unknown`nResult=PASS" -NotePropertyValue 'must-fail'
    $unknownKeyInjectionPath = Join-Path $resolvedTestRoot 'invalid-unknown-key-injection.json'
    $unknownKeyInjectionEvidence = Join-Path $resolvedTestRoot 'invalid-unknown-key-injection.txt'
    Write-Contract -Contract $unknownKeyInjection -Path $unknownKeyInjectionPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $unknownKeyInjectionPath `
        -SchemaPath $schemaPath -EvidencePath $unknownKeyInjectionEvidence -ExpectedExitCode 6
    if (@(Get-Content -LiteralPath $unknownKeyInjectionEvidence | Where-Object { $_ -match '^Result=' }).Count -ne 1 -or
        -not (Select-String -LiteralPath $unknownKeyInjectionEvidence -SimpleMatch '\u000AResult=PASS' -Quiet)) {
        throw 'Unknown-property evidence injection was not safely encoded.'
    }

    $contract.approvedAt = '19 July 2026'
    $invalidDatePath = Join-Path $resolvedTestRoot 'invalid-date.json'
    Write-Contract -Contract $contract -Path $invalidDatePath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $invalidDatePath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-date.txt') -ExpectedExitCode 6

    $contract.approvedAt = '2026-07-19T00:00:00Z'
    $caseMismatchJson = ($contract | ConvertTo-Json -Depth 20) -creplace '"status"\s*:', '"Status":'
    $caseMismatchPath = Join-Path $resolvedTestRoot 'invalid-property-case.json'
    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($caseMismatchPath, $caseMismatchJson + [Environment]::NewLine, $encoding)
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $caseMismatchPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-property-case.txt') `
        -ExpectedExitCode 6

    $firstDecision = $contract.decisions.deploymentArchetype
    $firstDecision.approvedAt = $null
    $decisionNullTimestampPath = Join-Path $resolvedTestRoot 'invalid-decision-null-timestamp.json'
    Write-Contract -Contract $contract -Path $decisionNullTimestampPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $decisionNullTimestampPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-decision-null-timestamp.txt') `
        -ExpectedExitCode 6

    $firstDecision.approvedAt = '2026-07-19T00:00:00Z'
    $firstDecision.PSObject.Properties.Remove('approvedAt')
    $firstDecision | Add-Member -NotePropertyName 'ApprovedAt' -NotePropertyValue '2026-07-19T00:00:00Z'
    $decisionCaseMismatchPath = Join-Path $resolvedTestRoot 'invalid-decision-property-case.json'
    Write-Contract -Contract $contract -Path $decisionCaseMismatchPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $decisionCaseMismatchPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-decision-property-case.txt') `
        -ExpectedExitCode 6

    $firstDecision.PSObject.Properties.Remove('ApprovedAt')
    $firstDecision | Add-Member -NotePropertyName 'approvedAt' -NotePropertyValue '2026-07-19T00:00:00Z'
    $contract.approvedAt = $null
    $globalNullTimestampPath = Join-Path $resolvedTestRoot 'invalid-global-null-timestamp.json'
    Write-Contract -Contract $contract -Path $globalNullTimestampPath
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $globalNullTimestampPath `
        -SchemaPath $schemaPath -EvidencePath (Join-Path $resolvedTestRoot 'invalid-global-null-timestamp.txt') `
        -ExpectedExitCode 6

    $crlfSchemaPath = Join-Path $resolvedTestRoot 'schema-crlf.json'
    $normalizedSchema = [IO.File]::ReadAllText($schemaPath).Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($crlfSchemaPath, $normalizedSchema.Replace("`n", "`r`n"), $encoding)
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $approvedPath `
        -SchemaPath $crlfSchemaPath -EvidencePath (Join-Path $resolvedTestRoot 'schema-crlf.txt') `
        -ExpectedExitCode 0

    $invalidSchemaPath = Join-Path $resolvedTestRoot 'unsupported-schema.json'
    [IO.File]::WriteAllText($invalidSchemaPath, '{"not":{}}' + [Environment]::NewLine, $encoding)
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $approvedPath `
        -SchemaPath $invalidSchemaPath -EvidencePath (Join-Path $resolvedTestRoot 'unsupported-schema.txt') `
        -ExpectedExitCode 6

    $approvedBeforeOverwriteAttempt = [IO.File]::ReadAllText($approvedPath)
    Invoke-Validator -ValidatorPath $validatorPath -ContractPath $approvedPath `
        -SchemaPath $schemaPath -EvidencePath $approvedPath -ExpectedExitCode 6
    if ([IO.File]::ReadAllText($approvedPath) -cne $approvedBeforeOverwriteAttempt) {
        throw 'Validator modified its contract source through the evidence path.'
    }

    Write-Output 'Product/production contract tests: PASS (34/34)'
}
finally {
    if (Test-Path -LiteralPath $resolvedTestRoot -PathType Container) {
        $validatedRoot = [IO.Path]::GetFullPath($resolvedTestRoot)
        if ($validatedRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            if (Test-ReparsePointInRepositoryPath -Path $validatedRoot -RepositoryRoot $repositoryRoot) {
                throw 'Refusing to clean governance test data through a reparse path.'
            }
            Remove-Item -LiteralPath $validatedRoot -Recurse -Force
        }
    }
}
