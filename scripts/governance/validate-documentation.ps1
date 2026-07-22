[CmdletBinding()]
param(
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-PathContainsReparsePoint {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $currentPath = [IO.Path]::GetFullPath($Path)
    while ($currentPath.StartsWith($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        $item = Get-Item -LiteralPath $currentPath -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
        if ($currentPath.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent) { break }
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
    $temporaryPath = Join-Path $directory ('.doc-validation-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    $backupPath = Join-Path $directory ('.doc-validation-{0}.bak' -f [guid]::NewGuid().ToString('N'))
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
        foreach ($cleanupPath in @($temporaryPath, $backupPath)) {
            if (Test-Path -LiteralPath $cleanupPath -PathType Leaf) {
                Remove-Item -LiteralPath $cleanupPath -Force
            }
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

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
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
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $artifactRoot 'verification\phase-01\doc-links.txt'
}
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
$artifactRootUsable = Test-ArtifactRootSafeForDefaultEvidence `
    -ArtifactRoot $artifactRoot -RepositoryRoot $repositoryRoot

$requiredPaths = @(
    'README.md',
    'docs\project-overview-pdr.md',
    'docs\system-architecture.md',
    'docs\local-development.md',
    'docs\deployment-guide.md',
    'docs\testing-strategy.md',
    'docs\evaluation-strategy.md',
    'docs\dataset-governance.md',
    'docs\security-model.md',
    'docs\code-standards.md',
    'docs\project-roadmap.md',
    'docs\blockers.md',
    'docs\progress.md',
    'docs\decisions\product-production-contract.md',
    'docs\decisions\product-production-contract.json',
    'docs\decisions\product-production-contract.schema.json',
    'docs\adr\README.md',
    'docs\adr\ADR-0001-platform-topology.md',
    'docs\adr\ADR-0002-contract-and-repository-ownership.md',
    'docs\adr\ADR-0003-evidence-artifact-storage.md'
)

$errors = @()
if (-not $evidencePathWasExplicit -and -not $artifactRootUsable) {
    $errors += 'default-artifact-root-unavailable-or-unsafe'
}
foreach ($relativePath in $requiredPaths) {
    $fullPath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        $errors += "missing-required-file:$relativePath"
    }
}

$markdownFiles = @()
$readmePath = Join-Path $repositoryRoot 'README.md'
if (Test-Path -LiteralPath $readmePath -PathType Leaf) {
    $markdownFiles += Get-Item -LiteralPath $readmePath
}
foreach ($directory in @('docs', 'plans\260719-1747-opsmind-ai-production-platform')) {
    $path = Join-Path $repositoryRoot $directory
    if (Test-Path -LiteralPath $path -PathType Container) {
        $markdownFiles += Get-ChildItem -LiteralPath $path -Recurse -File -Filter '*.md'
    }
}

$readableMarkdownFiles = @()
$maximumMarkdownBytes = 2MB
foreach ($file in $markdownFiles) {
    $relativeSource = $file.FullName.Substring($repositoryRoot.Length + 1)
    if ($file.Length -gt $maximumMarkdownBytes) {
        $errors += "oversized-markdown:$relativeSource"
        continue
    }
    if (Test-PathContainsReparsePoint -Path $file.FullName -RepositoryRoot $repositoryRoot) {
        $errors += "reparse-markdown:$relativeSource"
        continue
    }
    $readableMarkdownFiles += $file
}

$linkCount = 0
$linkPattern = [regex]'\[[^\]]+\]\((?<target>[^)]+)\)'
foreach ($file in $readableMarkdownFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($match in $linkPattern.Matches($content)) {
        $target = $match.Groups['target'].Value.Trim().Trim('<', '>')
        if ($target -match '^(https?://|mailto:|#)') {
            continue
        }

        $pathPart = ($target -split '#', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathPart)) {
            continue
        }
        try {
            $pathPart = [Uri]::UnescapeDataString($pathPart)
        }
        catch {
            $relativeSource = $file.FullName.Substring($repositoryRoot.Length + 1)
            $errors += "invalid-link-encoding:$relativeSource->$target"
            continue
        }
        if ([IO.Path]::IsPathRooted($pathPart)) {
            $resolvedTarget = [IO.Path]::GetFullPath($pathPart)
            $relativeSource = $file.FullName.Substring($repositoryRoot.Length + 1)
            $errors += "absolute-local-link:$relativeSource->$target"
            continue
        }
        else {
            $resolvedTarget = [IO.Path]::GetFullPath((Join-Path $file.DirectoryName $pathPart))
        }

        $linkCount++
        if (-not $resolvedTarget.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
            -not $resolvedTarget.Equals($repositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
            $relativeSource = $file.FullName.Substring($repositoryRoot.Length + 1)
            $errors += "link-outside-repository:$relativeSource->$target"
        }
        elseif (-not (Test-Path -LiteralPath $resolvedTarget)) {
            $relativeSource = $file.FullName.Substring($repositoryRoot.Length + 1)
            $errors += "broken-link:$relativeSource->$target"
        }
    }
}

$projectDocs = @($readableMarkdownFiles | Where-Object {
    $_.FullName -eq $readmePath -or $_.FullName.StartsWith((Join-Path $repositoryRoot 'docs') + [IO.Path]::DirectorySeparatorChar)
})
foreach ($file in $projectDocs) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    if ($content -match '(?im)\b(TODO|TBD|PLACEHOLDER)\b') {
        $relativeSource = $file.FullName.Substring($repositoryRoot.Length + 1)
        $errors += "unfinished-marker:$relativeSource"
    }
}

$result = if ($errors.Count -eq 0) { 'PASS' } else { 'BLOCK' }
$lines = @(
    'OpsMind documentation validation',
    ('TimestampUtc={0}' -f [DateTime]::UtcNow.ToString('o')),
    ('MarkdownFiles={0}' -f $markdownFiles.Count),
    ('MarkdownFilesValidated={0}' -f $readableMarkdownFiles.Count),
    ('LocalLinksChecked={0}' -f $linkCount),
    ('Errors={0}' -f $errors.Count)
)
$lines += $errors | ForEach-Object { 'Error={0}' -f $_ }
$lines += ('Result={0}' -f $result)
$lines = @($lines | ForEach-Object { ConvertTo-SafeEvidenceLine -Line ([string]$_) })

if ($evidencePathWasExplicit -or $artifactRootUsable) {
    Write-EvidenceAtomically -Path $EvidencePath -Lines $lines
}
else {
    $lines = @($lines[0..($lines.Count - 2)]) + 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT' + $lines[-1]
}
$lines | Write-Output

if ($errors.Count -gt 0) {
    exit 8
}
exit 0
