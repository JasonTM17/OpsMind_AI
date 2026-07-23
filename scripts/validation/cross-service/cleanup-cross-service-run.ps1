[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{32}$')]
    [string]$RunId,
    [switch]$RemoveRunDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$runRoot = [IO.Path]::GetFullPath(
    (Join-Path $repositoryRoot ".opsmind\cross-service\$RunId")
)
$expectedRunRoot = [IO.Path]::GetFullPath(
    (Join-Path $repositoryRoot ".opsmind\cross-service\$RunId")
)
if (-not $runRoot.Equals($expectedRunRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Cross-service cleanup run root is invalid.'
}
if (Test-Path -LiteralPath $runRoot -PathType Container) {
    $runRootItem = Get-Item -LiteralPath $runRoot -Force
    if (($runRootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Cross-service cleanup refuses a reparse-point run root.'
    }
    $reparseChildren = @(
        Get-ChildItem -LiteralPath $runRoot -Force -Recurse -ErrorAction Stop |
            Where-Object {
                ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
            }
    )
    if ($reparseChildren.Count -ne 0) {
        throw 'Cross-service cleanup refuses reparse points inside the run root.'
    }
}

$managedCommandPattern = [regex]::Escape($repositoryRoot) +
    '\\(?:services\\(?:platform-api|tool-gateway)\\target\\|' +
    'scripts\\validation\\cross-service\\)'
$tagPattern = '--opsmind-cross-service-run-id(?:=|\s+)' +
    [regex]::Escape($RunId) +
    '(?=\s|$)'
$taggedProcesses = @(
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @('java.exe', 'node.exe', 'python.exe') -and
            $_.CommandLine -match $tagPattern -and
            $_.CommandLine -match $managedCommandPattern
        }
)
if ($taggedProcesses.Count -gt 0) {
    Stop-Process -Id @($taggedProcesses.ProcessId) -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
}

$containerName = "opsmind-cross-service-postgres-$($RunId.Substring(0, 12))"
$docker = (Get-Command docker -CommandType Application | Select-Object -First 1).Path
$containers = @(
    & $docker ps -a --filter "name=^/${containerName}$" --format '{{.Names}}'
)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the disposable cross-service container.'
}
if ($containers.Count -gt 1 -or ($containers.Count -eq 1 -and $containers[0] -ne $containerName)) {
    throw 'Cross-service cleanup resolved an unexpected container.'
}
if ($containers.Count -eq 1) {
    & $docker rm --force $containerName *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to remove the disposable cross-service container.'
    }
}

$secretNames = @(
    'identity-tls-private.pem',
    'capability-private.pem',
    'operator-access-token.txt',
    'postgres.env'
)
$removedSecretCount = 0
foreach ($secretName in $secretNames) {
    $secretPath = [IO.Path]::GetFullPath((Join-Path $runRoot $secretName))
    if (-not $secretPath.StartsWith(
        $runRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw 'Cross-service cleanup resolved a secret outside the managed run root.'
    }
    if (Test-Path -LiteralPath $secretPath -PathType Leaf) {
        Remove-Item -LiteralPath $secretPath -Force
        $removedSecretCount++
    }
}

$survivingProcesses = @(
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.CommandLine -match $tagPattern -and
            $_.CommandLine -match $managedCommandPattern
        }
)
$survivingContainers = @(
    & $docker ps -a --filter "name=^/${containerName}$" --format '{{.Names}}'
)
$survivingSecrets = @(
    $secretNames |
        Where-Object {
            Test-Path -LiteralPath (Join-Path $runRoot $_) -PathType Leaf
        }
)
$cleanupIncomplete = $survivingProcesses.Count -ne 0 `
    -or $survivingContainers.Count -ne 0 `
    -or $survivingSecrets.Count -ne 0
if ($cleanupIncomplete) {
    throw 'Cross-service run cleanup did not reach a zero-resource state.'
}

$runDirectoryRemoved = $false
if ($RemoveRunDirectory) {
    if (Test-Path -LiteralPath $runRoot -PathType Container) {
        for ($attempt = 1; $attempt -le 20; $attempt++) {
            try {
                Remove-Item -LiteralPath $runRoot -Recurse -Force -ErrorAction Stop
                $runDirectoryRemoved = $true
                break
            }
            catch {
                Start-Sleep -Milliseconds 250
            }
        }
    }
    else {
        $runDirectoryRemoved = $true
    }
    if (-not $runDirectoryRemoved -or
        (Test-Path -LiteralPath $runRoot -PathType Container)) {
        throw 'Cross-service run directory remained locked after cleanup.'
    }
}

Write-Output (
    "CrossServiceRunCleanup=PASS Processes={0} Containers={1} Secrets={2} RunDirectoryRemoved={3}" -f
        $taggedProcesses.Count,
        $containers.Count,
        $removedSecretCount,
        $runDirectoryRemoved
)
