[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ScriptProcess {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments | Out-Host
    return [int]$LASTEXITCODE
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Expected -ne $Actual) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-FileContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Expected evidence file was not created: $Path"
    }
    if (-not (Select-String -LiteralPath $Path -SimpleMatch $Value -Quiet)) {
        throw "Evidence file does not contain '$Value': $Path"
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
$testRoot = Join-Path $repositoryRoot ('.opsmind\storage-guard-tests\{0}' -f [guid]::NewGuid().ToString('N'))
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create the storage-guard test directory outside the repository.'
}
if (Test-ReparsePointInRepositoryPath -Path $resolvedTestRoot -RepositoryRoot $repositoryRoot) {
    throw 'Refusing to create storage-guard test data through a reparse path.'
}

$capacityScript = Join-Path $PSScriptRoot 'check-capacity.ps1'
$rootsScript = Join-Path $PSScriptRoot 'assert-storage-roots.ps1'

try {
    [void](New-Item -ItemType Directory -Path $resolvedTestRoot -Force)

    $forcedBlockEvidence = Join-Path $resolvedTestRoot 'capacity-block.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $capacityScript -Arguments @(
        '-MinCFreeGb', '1000000',
        '-MinDFreeGb', '0',
        '-EvidencePath', $forcedBlockEvidence
    )
    Assert-Equal -Expected 3 -Actual $exitCode -Message 'Capacity guard must fail closed.'
    Assert-FileContains -Path $forcedBlockEvidence -Value 'Result=BLOCK'

    $passEvidence = Join-Path $resolvedTestRoot 'capacity-pass.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $capacityScript -Arguments @(
        '-MinCFreeGb', '0',
        '-MinDFreeGb', '0',
        '-EvidencePath', $passEvidence
    )
    Assert-Equal -Expected 0 -Actual $exitCode -Message 'Capacity guard should pass at zero thresholds.'
    Assert-FileContains -Path $passEvidence -Value 'Result=PASS'

    $previousArtifactRoot = $env:OPS_ARTIFACT_ROOT
    try {
        $env:OPS_ARTIFACT_ROOT = $repositoryRoot
        $unsafeArtifactEvidence = Join-Path $resolvedTestRoot 'capacity-unsafe-artifact-root.txt'
        $exitCode = Invoke-ScriptProcess -ScriptPath $capacityScript -Arguments @(
            '-MinCFreeGb', '0',
            '-MinDFreeGb', '0',
            '-EvidencePath', $unsafeArtifactEvidence
        )
        Assert-Equal -Expected 3 -Actual $exitCode -Message 'Capacity guard must block an artifact root containing the repository.'
        Assert-FileContains -Path $unsafeArtifactEvidence -Value 'artifact-root-contains-repository'
    }
    finally {
        $env:OPS_ARTIFACT_ROOT = $previousArtifactRoot
    }

    $rootsBase = Join-Path $resolvedTestRoot 'roots'
    $rootsEvidence = Join-Path $resolvedTestRoot 'roots-pass.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', (Join-Path $rootsBase 'cache'),
        '-ArtifactRoot', (Join-Path $rootsBase 'artifacts'),
        '-DataRoot', (Join-Path $rootsBase 'data'),
        '-ModelRoot', (Join-Path $rootsBase 'models'),
        '-EvidencePath', $rootsEvidence,
        '-CreateMissing'
    )
    Assert-Equal -Expected 0 -Actual $exitCode -Message 'Safe D-backed roots should pass.'
    Assert-FileContains -Path $rootsEvidence -Value 'Result=PASS'

    $unsafeEvidence = Join-Path $resolvedTestRoot 'roots-block.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', 'C:\OpsMindUnsafe\cache',
        '-ArtifactRoot', (Join-Path $rootsBase 'artifacts-2'),
        '-DataRoot', (Join-Path $rootsBase 'data-2'),
        '-ModelRoot', (Join-Path $rootsBase 'models-2'),
        '-EvidencePath', $unsafeEvidence
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'System-volume roots must be blocked.'
    Assert-FileContains -Path $unsafeEvidence -Value 'system-volume-disallowed'

    $overlapEvidence = Join-Path $resolvedTestRoot 'roots-overlap-block.txt'
    $overlapBase = Join-Path $rootsBase 'overlap'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', $overlapBase,
        '-ArtifactRoot', (Join-Path $overlapBase 'artifacts'),
        '-DataRoot', (Join-Path $rootsBase 'data-3'),
        '-ModelRoot', (Join-Path $rootsBase 'models-3'),
        '-EvidencePath', $overlapEvidence,
        '-CreateMissing'
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'Overlapping roots must be blocked.'
    Assert-FileContains -Path $overlapEvidence -Value 'overlapping-root'

    $relativeEvidence = Join-Path $resolvedTestRoot 'roots-relative-block.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', 'relative\cache',
        '-ArtifactRoot', (Join-Path $rootsBase 'artifacts'),
        '-DataRoot', (Join-Path $rootsBase 'data'),
        '-ModelRoot', (Join-Path $rootsBase 'models'),
        '-EvidencePath', $relativeEvidence
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'Relative roots must fail closed with evidence.'
    Assert-FileContains -Path $relativeEvidence -Value 'invalid-root-configuration'

    $deviceEvidence = Join-Path $resolvedTestRoot 'roots-device-block.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', '\\?\C:\OpsMindUnsafe\cache',
        '-ArtifactRoot', (Join-Path $rootsBase 'artifacts'),
        '-DataRoot', (Join-Path $rootsBase 'data'),
        '-ModelRoot', (Join-Path $rootsBase 'models'),
        '-EvidencePath', $deviceEvidence
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'Device namespace roots must be blocked.'
    Assert-FileContains -Path $deviceEvidence -Value 'device-namespace-disallowed'

    $nonDVolumeEvidence = Join-Path $resolvedTestRoot 'roots-non-d-volume-block.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', 'E:\OpsMindUnsafe\cache',
        '-ArtifactRoot', (Join-Path $rootsBase 'artifacts-4'),
        '-DataRoot', (Join-Path $rootsBase 'data-4'),
        '-ModelRoot', (Join-Path $rootsBase 'models-4'),
        '-EvidencePath', $nonDVolumeEvidence
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'Unmonitored Windows volumes must be blocked.'
    Assert-FileContains -Path $nonDVolumeEvidence -Value 'non-d-volume-disallowed'

    $uncEvidence = Join-Path $resolvedTestRoot 'roots-unc-block.txt'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', '\\localhost\C$\OpsMindUnsafe\cache',
        '-ArtifactRoot', (Join-Path $rootsBase 'artifacts-5'),
        '-DataRoot', (Join-Path $rootsBase 'data-5'),
        '-ModelRoot', (Join-Path $rootsBase 'models-5'),
        '-EvidencePath', $uncEvidence
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'UNC aliases must be blocked.'
    Assert-FileContains -Path $uncEvidence -Value 'unc-path-disallowed'

    $canonicalEvidence = Join-Path $resolvedTestRoot 'roots-canonical-overlap-block.txt'
    $canonicalBase = Join-Path $rootsBase 'canonical'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', (Join-Path $canonicalBase 'alias\..\shared'),
        '-ArtifactRoot', (Join-Path $canonicalBase 'shared'),
        '-DataRoot', (Join-Path $rootsBase 'data-6'),
        '-ModelRoot', (Join-Path $rootsBase 'models-6'),
        '-EvidencePath', $canonicalEvidence,
        '-CreateMissing'
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'Canonical path aliases must be blocked as overlap.'
    Assert-FileContains -Path $canonicalEvidence -Value 'overlapping-root'

    $missingDefaultArtifactRoot = Join-Path $rootsBase 'missing-default-artifacts'
    $exitCode = Invoke-ScriptProcess -ScriptPath $rootsScript -Arguments @(
        '-CacheRoot', (Join-Path $rootsBase 'cache'),
        '-ArtifactRoot', $missingDefaultArtifactRoot,
        '-DataRoot', (Join-Path $rootsBase 'data'),
        '-ModelRoot', (Join-Path $rootsBase 'models')
    )
    Assert-Equal -Expected 4 -Actual $exitCode -Message 'A missing default artifact root must remain blocked.'
    if (Test-Path -LiteralPath $missingDefaultArtifactRoot) {
        throw 'Root validation created a missing artifact root without CreateMissing authorization.'
    }

    Write-Output 'Storage guard tests: PASS (12/12)'
}
finally {
    if (Test-Path -LiteralPath $resolvedTestRoot -PathType Container) {
        $validatedRoot = [IO.Path]::GetFullPath($resolvedTestRoot)
        if ($validatedRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            if (Test-ReparsePointInRepositoryPath -Path $validatedRoot -RepositoryRoot $repositoryRoot) {
                throw 'Refusing to clean storage-guard test data through a reparse path.'
            }
            Remove-Item -LiteralPath $validatedRoot -Recurse -Force
        }
    }
}
