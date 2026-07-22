[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-ReparsePointInRepositoryPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $currentPath = [IO.Path]::GetFullPath($Path)
    while ($currentPath.StartsWith($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $currentPath) {
            $item = Get-Item -LiteralPath $currentPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
        }
        if ($currentPath.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent) { break }
        $currentPath = $parent.FullName
    }
    return $false
}

function Get-OptionalFileFingerprint {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return 'ABSENT' }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Invoke-DefaultEvidenceCase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Arguments,
        [Parameter(Mandatory = $true)][int]$ExpectedExitCode,
        [Parameter(Mandatory = $true)][string]$ExpectedMarker,
        [Parameter(Mandatory = $true)][string]$DefaultEvidencePath
    )

    $beforeFingerprint = Get-OptionalFileFingerprint -Path $DefaultEvidencePath
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1)
    $actualExitCode = [int]$LASTEXITCODE
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "$Name returned an unexpected exit code. Expected=$ExpectedExitCode Actual=$actualExitCode"
    }
    if (-not (($output -join [Environment]::NewLine).Contains($ExpectedMarker))) {
        throw "$Name did not report the expected evidence-publication marker: $ExpectedMarker"
    }
    $afterFingerprint = Get-OptionalFileFingerprint -Path $DefaultEvidencePath
    if ($afterFingerprint -cne $beforeFingerprint) {
        throw "$Name created or modified default evidence through an invalid artifact root."
    }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\', '/')
$testRoot = Join-Path $repositoryRoot ('.opsmind\evidence-safety-tests\{0}' -f [guid]::NewGuid().ToString('N'))
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
$allowedPrefix = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.opsmind\evidence-safety-tests')).TrimEnd('\', '/') +
    [IO.Path]::DirectorySeparatorChar
if (-not $resolvedTestRoot.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    (Test-ReparsePointInRepositoryPath -Path $resolvedTestRoot -RepositoryRoot $repositoryRoot)) {
    throw 'Refusing to create evidence-safety test data outside the verified repository path.'
}

$contractValidator = Join-Path $PSScriptRoot 'validate-product-production-contract.ps1'
$documentationValidator = Join-Path $PSScriptRoot 'validate-documentation.ps1'
$secretScanner = Join-Path $PSScriptRoot 'scan-project-secrets.ps1'
$contractPath = Join-Path $repositoryRoot 'docs\decisions\product-production-contract.json'
$schemaPath = Join-Path $repositoryRoot 'docs\decisions\product-production-contract.schema.json'
$previousArtifactRoot = $env:OPS_ARTIFACT_ROOT

try {
    [void](New-Item -ItemType Directory -Path $resolvedTestRoot -Force)
    $missingArtifactRoot = Join-Path $resolvedTestRoot 'missing-artifacts'
    $cases = @(
        [pscustomobject]@{
            Name = 'contract validator'; Script = $contractValidator
            Arguments = @('-ContractPath', $contractPath, '-SchemaPath', $schemaPath, '-AllowPending')
            ExitCode = 6; RelativeEvidence = 'verification\phase-01\product-production-contract.txt'
            Marker = 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT'
        },
        [pscustomobject]@{
            Name = 'documentation validator'; Script = $documentationValidator
            Arguments = @(); ExitCode = 8; RelativeEvidence = 'verification\phase-01\doc-links.txt'
            Marker = 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT'
        },
        [pscustomobject]@{
            Name = 'secret scanner'; Script = $secretScanner
            Arguments = @('-RepositoryRoot', $repositoryRoot); ExitCode = 7
            RelativeEvidence = 'verification\phase-01\doc-secret-scan.txt'
            Marker = 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT'
        }
    )

    foreach ($artifactRootCase in @($missingArtifactRoot, $repositoryRoot)) {
        $env:OPS_ARTIFACT_ROOT = $artifactRootCase
        foreach ($case in $cases) {
            Invoke-DefaultEvidenceCase -Name ("{0} with artifact root {1}" -f $case.Name, $artifactRootCase) `
                -ScriptPath $case.Script -Arguments $case.Arguments -ExpectedExitCode $case.ExitCode `
                -ExpectedMarker $case.Marker -DefaultEvidencePath (Join-Path $artifactRootCase $case.RelativeEvidence)
        }
    }

    if (Test-Path -LiteralPath $missingArtifactRoot) {
        throw 'Default evidence writers created the missing artifact root.'
    }
    Write-Output 'Default evidence safety tests: PASS (6/6)'
}
finally {
    $env:OPS_ARTIFACT_ROOT = $previousArtifactRoot
    if (Test-Path -LiteralPath $resolvedTestRoot -PathType Container) {
        if (-not $resolvedTestRoot.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            (Test-ReparsePointInRepositoryPath -Path $resolvedTestRoot -RepositoryRoot $repositoryRoot)) {
            throw 'Refusing to clean evidence-safety test data through an unsafe path.'
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
