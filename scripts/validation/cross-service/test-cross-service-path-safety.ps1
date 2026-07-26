[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
. (Join-Path $PSScriptRoot 'cross-service-harness-support.ps1')
$testRoot = Join-Path $repositoryRoot (
    '.opsmind/path-safety-tests/' + [guid]::NewGuid().ToString('N')
)
$targetRoot = Join-Path $testRoot 'target'
$linkRoot = Join-Path $testRoot 'managed-link'

try {
    [void](New-Item -ItemType Directory -Path $targetRoot -Force)
    $linkType = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        'Junction'
    }
    else {
        'SymbolicLink'
    }
    [void](New-Item -ItemType $linkType -Path $linkRoot -Target $targetRoot)

    $blocked = $false
    try {
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath (Join-Path $linkRoot 'credential.txt')
    }
    catch {
        $blocked = $_.Exception.Message -match 'reparse ancestor'
    }
    if (-not $blocked) {
        throw 'Cross-service path guard accepted a reparse-point ancestor.'
    }

    Write-Output 'CrossServicePathSafety=PASS ReparseAncestor=BLOCKED'
}
finally {
    if (Test-Path -LiteralPath $linkRoot) {
        Remove-Item -LiteralPath $linkRoot -Force
    }
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        $unsafeChildren = @(
            Get-ChildItem -LiteralPath $testRoot -Force -Recurse |
                Where-Object {
                    ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
                }
        )
        if ($unsafeChildren.Count -eq 0) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}
