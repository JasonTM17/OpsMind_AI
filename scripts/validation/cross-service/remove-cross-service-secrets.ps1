[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$crossServiceRoot = [IO.Path]::GetFullPath(
    (Join-Path $repositoryRoot '.opsmind\cross-service')
)
$secretNames = @(
    'identity-tls-private.pem',
    'capability-private.pem',
    'operator-access-token.txt',
    'postgres.env'
)

if (-not (Test-Path -LiteralPath $crossServiceRoot -PathType Container)) {
    Write-Output 'CrossServiceSecretCleanup=PASS Removed=0'
    exit 0
}

$removed = 0
$candidates = @(
    Get-ChildItem -LiteralPath $crossServiceRoot -Recurse -File |
        Where-Object { $secretNames -contains $_.Name }
)
foreach ($candidate in $candidates) {
    $fullPath = [IO.Path]::GetFullPath($candidate.FullName)
    if (-not $fullPath.StartsWith(
        $crossServiceRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Refusing cross-service secret cleanup outside the managed root: $fullPath"
    }
    if (($candidate.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing cross-service secret cleanup through a reparse point: $fullPath"
    }
    Remove-Item -LiteralPath $fullPath -Force
    $removed++
}

$remaining = @(
    Get-ChildItem -LiteralPath $crossServiceRoot -Recurse -File |
        Where-Object { $secretNames -contains $_.Name }
)
if ($remaining.Count -ne 0) {
    throw 'One or more managed cross-service secret files survived cleanup.'
}

Write-Output "CrossServiceSecretCleanup=PASS Removed=$removed"
