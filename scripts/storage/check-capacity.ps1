[CmdletBinding()]
param(
    [string]$MinCFreeGb,
    [string]$MinDFreeGb,
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-Threshold {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$ArgumentValue,
        [AllowEmptyString()][string]$EnvironmentValue,
        [Parameter(Mandatory = $true)][double]$DefaultValue
    )

    $rawValue = $ArgumentValue
    if ([string]::IsNullOrWhiteSpace($rawValue)) {
        $rawValue = $EnvironmentValue
    }
    if ([string]::IsNullOrWhiteSpace($rawValue)) {
        return $DefaultValue
    }

    $parsed = 0.0
    $style = [Globalization.NumberStyles]::Float
    $culture = [Globalization.CultureInfo]::InvariantCulture
    if (-not [double]::TryParse($rawValue, $style, $culture, [ref]$parsed)) {
        throw "$Name must be a number expressed in GB."
    }
    if ($parsed -lt 0 -or [double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "$Name must be a finite number greater than or equal to zero."
    }

    return $parsed
}

function Write-EvidenceAtomically {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Lines
    )

    $directory = Split-Path -Parent $Path
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporaryPath = Join-Path $directory ('.capacity-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    $backupPath = Join-Path $directory ('.capacity-{0}.bak' -f [guid]::NewGuid().ToString('N'))
    $encoding = New-Object Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllText($temporaryPath, ($Lines -join [Environment]::NewLine) + [Environment]::NewLine, $encoding)
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
        if ($null -eq $parent -or $parent.FullName -eq $currentPath) { break }
        $currentPath = $parent.FullName
    }
    return $false
}

function Get-UnsafeArtifactRootReason {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactRoot,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    if (-not (Test-Path -LiteralPath $ArtifactRoot -PathType Container)) {
        return $null
    }
    if (Test-PathContainsAnyReparsePoint -Path $ArtifactRoot) {
        return 'artifact-root-reparse-path'
    }
    $normalizedArtifactRoot = [IO.Path]::GetFullPath($ArtifactRoot).TrimEnd('\', '/')
    $normalizedRepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
    $volumeRoot = [IO.Path]::GetPathRoot($normalizedArtifactRoot).TrimEnd('\', '/')
    if ($normalizedArtifactRoot.Equals($volumeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return 'artifact-volume-root-disallowed'
    }
    if ($normalizedRepositoryRoot.Equals($normalizedArtifactRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedRepositoryRoot.StartsWith(
            $normalizedArtifactRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        return 'artifact-root-contains-repository'
    }
    return $null
}

$minimums = @{
    'C:' = Resolve-Threshold -Name 'MinCFreeGb' -ArgumentValue $MinCFreeGb `
        -EnvironmentValue $env:OPS_MIN_C_FREE_GB -DefaultValue 10
    'D:' = Resolve-Threshold -Name 'MinDFreeGb' -ArgumentValue $MinDFreeGb `
        -EnvironmentValue $env:OPS_MIN_D_FREE_GB -DefaultValue 20
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$evidencePathWasExplicit = -not [string]::IsNullOrWhiteSpace($EvidencePath)
$artifactRootWasConfigured = -not [string]::IsNullOrWhiteSpace($env:OPS_ARTIFACT_ROOT)
$artifactRoot = if ([string]::IsNullOrWhiteSpace($env:OPS_ARTIFACT_ROOT)) {
    Join-Path $repositoryRoot 'artifacts'
}
else {
    if (-not [IO.Path]::IsPathRooted($env:OPS_ARTIFACT_ROOT)) {
        throw 'OPS_ARTIFACT_ROOT must be an absolute path.'
    }
    [IO.Path]::GetFullPath($env:OPS_ARTIFACT_ROOT)
}
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $artifactRoot 'verification\phase-01\disk-preflight.txt'
}
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
$unsafeArtifactRootReason = Get-UnsafeArtifactRootReason -ArtifactRoot $artifactRoot -RepositoryRoot $repositoryRoot
$artifactRootExists = Test-Path -LiteralPath $artifactRoot -PathType Container
$artifactRootUsable = $artifactRootExists -and [string]::IsNullOrWhiteSpace($unsafeArtifactRootReason)

$fixedDisks = @()
$capacityQueryFailed = $false
try {
    $fixedDisks = @(Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3')
}
catch {
    $capacityQueryFailed = $true
}

$rows = @()
$hasFailure = -not [string]::IsNullOrWhiteSpace($unsafeArtifactRootReason) -or
    ($artifactRootWasConfigured -and -not $artifactRootExists)
foreach ($deviceId in @('C:', 'D:')) {
    $disk = $fixedDisks | Where-Object { $_.DeviceID -eq $deviceId } | Select-Object -First 1
    if ($null -eq $disk) {
        $rows += [pscustomobject]@{
            Drive = $deviceId
            FreeGb = 'unavailable'
            MinimumGb = ('{0:F2}' -f $minimums[$deviceId])
            Status = 'BLOCK'
            Reason = if ($capacityQueryFailed) { 'capacity-query-failed' } else { 'drive-unavailable' }
        }
        $hasFailure = $true
        continue
    }

    $freeBytes = [double]$disk.FreeSpace
    $freeGb = [math]::Round($freeBytes / 1GB, 2)
    $minimumBytes = $minimums[$deviceId] * 1GB
    $status = if ($freeBytes -ge $minimumBytes) { 'PASS' } else { 'BLOCK' }
    if ($status -eq 'BLOCK') {
        $hasFailure = $true
    }

    $rows += [pscustomobject]@{
        Drive = $deviceId
        FreeGb = ('{0:F2}' -f $freeGb)
        MinimumGb = ('{0:F2}' -f $minimums[$deviceId])
        Status = $status
        Reason = if ($status -eq 'PASS') { 'available' } else { 'below-threshold' }
    }
}

if (-not [string]::IsNullOrWhiteSpace($unsafeArtifactRootReason)) {
    $rows += [pscustomobject]@{
        Drive = 'OPS_ARTIFACT_ROOT'; FreeGb = 'n/a'; MinimumGb = 'n/a'
        Status = 'BLOCK'; Reason = $unsafeArtifactRootReason
    }
}
elseif ($artifactRootWasConfigured -and -not $artifactRootExists) {
    $rows += [pscustomobject]@{
        Drive = 'OPS_ARTIFACT_ROOT'; FreeGb = 'unavailable'; MinimumGb = 'n/a'
        Status = 'BLOCK'; Reason = 'configured-artifact-root-missing'
    }
}

$lines = @(
    'OpsMind storage capacity preflight',
    ('TimestampUtc={0}' -f [DateTime]::UtcNow.ToString('o')),
    'Drive FreeGb MinimumGb Status Reason'
)
foreach ($row in $rows) {
    $lines += ('{0} {1} {2} {3} {4}' -f $row.Drive, $row.FreeGb, $row.MinimumGb, $row.Status, $row.Reason)
}
$canPublishEvidence = $evidencePathWasExplicit -or $artifactRootUsable
if (-not $canPublishEvidence) {
    $lines += 'EvidencePublication=STDOUT_ONLY_ARTIFACT_ROOT_NOT_CREATED'
}
$lines += if ($hasFailure) { 'Result=BLOCK' } else { 'Result=PASS' }

if ($canPublishEvidence) {
    Write-EvidenceAtomically -Path $EvidencePath -Lines $lines
}
$lines | Write-Output

if ($hasFailure) {
    exit 3
}

exit 0
