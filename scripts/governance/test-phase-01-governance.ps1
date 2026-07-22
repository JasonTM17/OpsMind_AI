[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-CheckedScript {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string[]]$Arguments = @(),
        [int]$ExpectedExitCode = 0
    )

    & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $Path @Arguments | Out-Host
    $actualExitCode = [int]$LASTEXITCODE
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "Unexpected exit code for $Path. Expected=$ExpectedExitCode Actual=$actualExitCode"
    }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\storage\assert-storage-roots.ps1') `
    -Arguments @('-CreateMissing')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\storage\test-storage-guards.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\validate-documentation.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\test-default-evidence-safety.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\scan-project-secrets.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\test-project-secret-scan.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\test-product-production-contract.ps1')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\validate-product-production-contract.ps1') `
    -Arguments @('-AllowPending')
Invoke-CheckedScript -Path (Join-Path $repositoryRoot 'scripts\governance\validate-product-production-contract.ps1') `
    -ExpectedExitCode 0

Write-Output 'Phase 1 governance tests: PASS (10/10; product contract approved and strictly validated)'
