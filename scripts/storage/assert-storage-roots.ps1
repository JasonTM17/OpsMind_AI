[CmdletBinding()]
param(
    [string]$CacheRoot,
    [string]$ArtifactRoot,
    [string]$DataRoot,
    [string]$ModelRoot,
    [string]$EvidencePath,
    [switch]$CreateMissing
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-Root {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ArgumentValue,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$EnvironmentValue,
        [Parameter(Mandatory = $true)][string]$DefaultValue
    )

    $candidate = $ArgumentValue
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = $EnvironmentValue
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = $DefaultValue
    }
    if (-not [IO.Path]::IsPathRooted($candidate)) {
        throw "Storage roots must be absolute paths: $candidate"
    }

    return [IO.Path]::GetFullPath($candidate)
}

function Test-RootWritable {
    param([Parameter(Mandatory = $true)][string]$Path)

    $probePath = Join-Path $Path ('.opsmind-write-probe-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText($probePath, 'write-probe')
        return $true
    }
    catch {
        return $false
    }
    finally {
        if (Test-Path -LiteralPath $probePath -PathType Leaf) {
            Remove-Item -LiteralPath $probePath -Force
        }
    }
}

function Test-PathContainsReparsePoint {
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

function Write-EvidenceAtomically {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Lines
    )

    $directory = Split-Path -Parent $Path
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporaryPath = Join-Path $directory ('.storage-roots-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    $backupPath = Join-Path $directory ('.storage-roots-{0}.bak' -f [guid]::NewGuid().ToString('N'))
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

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\', '/')
$evidencePathWasExplicit = -not [string]::IsNullOrWhiteSpace($EvidencePath)
$artifactEvidenceRoot = $ArtifactRoot
if ([string]::IsNullOrWhiteSpace($artifactEvidenceRoot)) { $artifactEvidenceRoot = $env:OPS_ARTIFACT_ROOT }
if ([string]::IsNullOrWhiteSpace($artifactEvidenceRoot)) { $artifactEvidenceRoot = Join-Path $repositoryRoot 'artifacts' }
if (-not [IO.Path]::IsPathRooted($artifactEvidenceRoot)) {
    throw 'OPS_ARTIFACT_ROOT must be an absolute path.'
}
$artifactEvidenceRoot = [IO.Path]::GetFullPath($artifactEvidenceRoot)
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $artifactEvidenceRoot 'verification\phase-01\storage-roots.txt'
}
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)

$resolvedRoots = [ordered]@{}
$resolutionFailed = $false
try {
    $resolvedRoots['OPS_CACHE_ROOT'] = Resolve-Root -ArgumentValue $CacheRoot -EnvironmentValue $env:OPS_CACHE_ROOT `
        -DefaultValue (Join-Path $repositoryRoot '.opsmind\cache')
    $resolvedRoots['OPS_ARTIFACT_ROOT'] = Resolve-Root -ArgumentValue $ArtifactRoot -EnvironmentValue $env:OPS_ARTIFACT_ROOT `
        -DefaultValue (Join-Path $repositoryRoot 'artifacts')
    $resolvedRoots['OPS_DATA_ROOT'] = Resolve-Root -ArgumentValue $DataRoot -EnvironmentValue $env:OPS_DATA_ROOT `
        -DefaultValue (Join-Path $repositoryRoot '.opsmind\data')
    $resolvedRoots['OPS_MODEL_ROOT'] = Resolve-Root -ArgumentValue $ModelRoot -EnvironmentValue $env:OPS_MODEL_ROOT `
        -DefaultValue (Join-Path $repositoryRoot '.opsmind\models')
}
catch {
    $resolutionFailed = $true
    $resolvedRoots = [ordered]@{}
}

$normalizedSystemRoot = ([IO.Path]::GetPathRoot([Environment]::SystemDirectory)).TrimEnd('\', '/')
$allowedWorkspaceDrive = 'D:'
$logicalDisks = @()
$volumeIdentityQueryFailed = $false
try {
    $logicalDisks = @(Get-CimInstance -ClassName Win32_LogicalDisk)
}
catch {
    $volumeIdentityQueryFailed = $true
}
$substDrives = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
try {
    $substOutput = @(& subst.exe 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw 'subst query failed'
    }
    foreach ($line in $substOutput) {
        if ($line -match '^([A-Za-z]:)\\: =>') {
            [void]$substDrives.Add($Matches[1])
        }
    }
}
catch {
    $volumeIdentityQueryFailed = $true
}
$overlappingKeys = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$rootEntries = @($resolvedRoots.GetEnumerator())
for ($leftIndex = 0; $leftIndex -lt $rootEntries.Count; $leftIndex++) {
    for ($rightIndex = $leftIndex + 1; $rightIndex -lt $rootEntries.Count; $rightIndex++) {
        $leftPath = $rootEntries[$leftIndex].Value.TrimEnd('\', '/')
        $rightPath = $rootEntries[$rightIndex].Value.TrimEnd('\', '/')
        $leftPrefix = $leftPath + [IO.Path]::DirectorySeparatorChar
        $rightPrefix = $rightPath + [IO.Path]::DirectorySeparatorChar
        if ($leftPath.Equals($rightPath, [StringComparison]::OrdinalIgnoreCase) -or
            $leftPrefix.StartsWith($rightPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            $rightPrefix.StartsWith($leftPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            [void]$overlappingKeys.Add($rootEntries[$leftIndex].Key)
            [void]$overlappingKeys.Add($rootEntries[$rightIndex].Key)
        }
    }
}
$rows = @()
$hasFailure = $resolutionFailed

if ($resolutionFailed) {
    $rows += [pscustomobject]@{
        Name = 'STORAGE_ROOT_CONFIGURATION'
        Path = 'unavailable'
        Status = 'BLOCK'
        Reason = 'invalid-root-configuration'
    }
}

foreach ($entry in $resolvedRoots.GetEnumerator()) {
    $rootPath = $entry.Value
    $normalizedRootPath = $rootPath.TrimEnd('\', '/')
    $rootDrive = ([IO.Path]::GetPathRoot($rootPath)).TrimEnd('\', '/')
    $status = 'PASS'
    $reason = 'writable'

    $logicalDisk = $logicalDisks | Where-Object { $_.DeviceID -eq $rootDrive } | Select-Object -First 1

    if ($rootPath -match '^\\\\[?.]\\') {
        $status = 'BLOCK'
        $reason = 'device-namespace-disallowed'
    }
    elseif ($rootPath -match '^\\\\') {
        $status = 'BLOCK'
        $reason = 'unc-path-disallowed'
    }
    elseif ($normalizedRootPath -eq $rootDrive) {
        $status = 'BLOCK'
        $reason = 'volume-root-disallowed'
    }
    elseif ($rootDrive -eq $normalizedSystemRoot) {
        $status = 'BLOCK'
        $reason = 'system-volume-disallowed'
    }
    elseif (-not $rootDrive.Equals($allowedWorkspaceDrive, [StringComparison]::OrdinalIgnoreCase)) {
        $status = 'BLOCK'
        $reason = 'non-d-volume-disallowed'
    }
    elseif ($volumeIdentityQueryFailed) {
        $status = 'BLOCK'
        $reason = 'volume-identity-unavailable'
    }
    elseif ($null -eq $logicalDisk -or [int]$logicalDisk.DriveType -ne 3 -or
        -not [string]::IsNullOrWhiteSpace([string]$logicalDisk.ProviderName)) {
        $status = 'BLOCK'
        $reason = 'non-fixed-volume-disallowed'
    }
    elseif ($substDrives.Contains($rootDrive)) {
        $status = 'BLOCK'
        $reason = 'substituted-volume-disallowed'
    }
    elseif ($overlappingKeys.Contains($entry.Key)) {
        $status = 'BLOCK'
        $reason = 'overlapping-root'
    }
    elseif (-not (Test-Path -LiteralPath $rootPath -PathType Container)) {
        if ($CreateMissing) {
            try {
                [void](New-Item -ItemType Directory -Path $rootPath -Force)
            }
            catch {
                $status = 'BLOCK'
                $reason = 'create-failed'
            }
        }
        else {
            $status = 'BLOCK'
            $reason = 'missing'
        }
    }

    if ($status -eq 'PASS' -and (Test-PathContainsReparsePoint -Path $rootPath)) {
        $status = 'BLOCK'
        $reason = 'reparse-point-disallowed'
    }
    elseif ($status -eq 'PASS' -and -not (Test-RootWritable -Path $rootPath)) {
        $status = 'BLOCK'
        $reason = 'not-writable'
    }
    if ($status -eq 'BLOCK') {
        $hasFailure = $true
    }

    $rows += [pscustomobject]@{
        Name = $entry.Key
        Path = $rootPath
        Status = $status
        Reason = $reason
    }
}

$artifactRow = $rows | Where-Object { $_.Name -eq 'OPS_ARTIFACT_ROOT' } | Select-Object -First 1
$canPublishEvidence = $evidencePathWasExplicit -or ($null -ne $artifactRow -and $artifactRow.Status -eq 'PASS')
$lines = @(
    'OpsMind storage root preflight',
    ('TimestampUtc={0}' -f [DateTime]::UtcNow.ToString('o')),
    'Name Path Status Reason'
)
foreach ($row in $rows) {
    $lines += ('{0} "{1}" {2} {3}' -f $row.Name, $row.Path, $row.Status, $row.Reason)
}
if (-not $canPublishEvidence) {
    $lines += 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT'
}
$lines += if ($hasFailure) { 'Result=BLOCK' } else { 'Result=PASS' }

if ($canPublishEvidence) {
    Write-EvidenceAtomically -Path $EvidencePath -Lines $lines
}
$lines | Write-Output

if ($hasFailure) {
    exit 4
}

exit 0
