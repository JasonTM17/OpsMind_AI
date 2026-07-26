[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{32}$')]
    [string]$RunId,
    [switch]$RemoveRunDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
. (Join-Path $PSScriptRoot 'cross-service-harness-support.ps1')
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$pathComparison = Get-CrossServicePathComparison
$runRoot = [IO.Path]::GetFullPath(
    (Join-Path (Join-Path (Join-Path $repositoryRoot '.opsmind') 'cross-service') $RunId)
)
$expectedRunRoot = [IO.Path]::GetFullPath(
    (Join-Path (Join-Path (Join-Path $repositoryRoot '.opsmind') 'cross-service') $RunId)
)
if (-not $runRoot.Equals($expectedRunRoot, $pathComparison)) {
    throw 'Cross-service cleanup run root is invalid.'
}
Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
    -CandidatePath $runRoot
$cleanupErrors = New-Object 'System.Collections.Generic.List[string]'
if (Test-Path -LiteralPath $runRoot -PathType Container) {
    $reparseChildren = @(
        Get-ChildItem -LiteralPath $runRoot -Force -Recurse -ErrorAction Stop |
            Where-Object {
                ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
            }
    )
    if ($reparseChildren.Count -ne 0) {
        $cleanupErrors.Add(
            'path-safety:reparse points exist inside the run root'
        )
    }
}

$secretNames = @(
    'identity-tls-private.pem',
    'capability-private.pem',
    'operator-access-token.txt',
    'postgres.env'
)
$transientEvaluationPatterns = @(
    'evaluation-export-*.json',
    'evaluation-enriched-trace.json'
)
$removedSecretCount = 0
$removedTransientEvaluationCount = 0

# Sensitive files are removed before process or Docker operations can fail.
foreach ($secretName in $secretNames) {
    try {
        $secretPath = [IO.Path]::GetFullPath((Join-Path $runRoot $secretName))
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath $secretPath
        if (-not $secretPath.StartsWith(
            $runRoot + [IO.Path]::DirectorySeparatorChar,
            $pathComparison
        )) {
            throw 'Resolved secret is outside the managed run root.'
        }
        if (Test-Path -LiteralPath $secretPath -PathType Leaf) {
            Remove-Item -LiteralPath $secretPath -Force
            $removedSecretCount++
        }
    }
    catch {
        $cleanupErrors.Add("secret:${secretName}:$($_.Exception.Message)")
    }
}
foreach ($pattern in $transientEvaluationPatterns) {
    try {
        $transientFiles = @(
            Get-ChildItem -LiteralPath $runRoot -File -Filter $pattern `
                -ErrorAction SilentlyContinue
        )
        foreach ($transientFile in $transientFiles) {
            $transientPath = [IO.Path]::GetFullPath($transientFile.FullName)
            Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
                -CandidatePath $transientPath
            if (-not $transientPath.StartsWith(
                $runRoot + [IO.Path]::DirectorySeparatorChar,
                $pathComparison
            )) {
                throw 'Resolved transient export is outside the managed run root.'
            }
            Remove-Item -LiteralPath $transientPath -Force
            $removedTransientEvaluationCount++
        }
    }
    catch {
        $cleanupErrors.Add("transient:${pattern}:$($_.Exception.Message)")
    }
}

$managedCommandPattern = [regex]::Escape($repositoryRoot) +
    '[\\/](?:services[\\/](?:platform-api|tool-gateway)[\\/]target[\\/]|' +
    'scripts[\\/]validation[\\/]cross-service[\\/])'
$tagPattern = '--opsmind-cross-service-run-id(?:=|\s+)' +
    [regex]::Escape($RunId) +
    '(?=\s|$)'
$taggedProcesses = @()
try {
    if ($isWindowsHost) {
        $taggedProcesses = @(
            Get-CimInstance Win32_Process |
                Where-Object {
                    $_.Name -in @('java.exe', 'node.exe', 'python.exe') -and
                    $_.CommandLine -match $tagPattern -and
                    $_.CommandLine -match $managedCommandPattern
                } |
                ForEach-Object {
                    [pscustomobject]@{
                        ProcessId = $_.ProcessId
                        CommandLine = $_.CommandLine
                    }
                }
        )
    }
    else {
        $taggedProcesses = @(
            & ps -eo pid=,args= |
                ForEach-Object {
                    if ($_ -match '^\s*(\d+)\s+(.+)$') {
                        [pscustomobject]@{
                            ProcessId = [int]$Matches[1]
                            CommandLine = $Matches[2]
                        }
                    }
                } |
                Where-Object {
                    $_.CommandLine -match $tagPattern -and
                    $_.CommandLine -match $managedCommandPattern
                }
        )
    }
    if ($taggedProcesses.Count -gt 0) {
        Stop-Process -Id @($taggedProcesses.ProcessId) -Force `
            -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 300
    }
}
catch {
    $cleanupErrors.Add("process-cleanup:$($_.Exception.Message)")
}

$containerName = "opsmind-cross-service-postgres-$($RunId.Substring(0, 12))"
$docker = $null
$containers = @()
try {
    $dockerCommand = Get-Command docker -CommandType Application |
        Select-Object -First 1
    if ($null -eq $dockerCommand) {
        throw 'Docker executable is unavailable.'
    }
    $docker = $dockerCommand.Path
    $containers = @(
        & $docker ps -a --filter "name=^/${containerName}$" --format '{{.Names}}'
    )
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect the disposable cross-service container.'
    }
    if ($containers.Count -gt 1 -or
        ($containers.Count -eq 1 -and $containers[0] -ne $containerName)) {
        throw 'Cross-service cleanup resolved an unexpected container.'
    }
    if ($containers.Count -eq 1) {
        & $docker rm --force $containerName *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to remove the disposable cross-service container.'
        }
    }
}
catch {
    $cleanupErrors.Add("container-cleanup:$($_.Exception.Message)")
}

$survivingProcesses = @()
try {
    if ($isWindowsHost) {
        $survivingProcesses = @(
            Get-CimInstance Win32_Process |
                Where-Object {
                    $_.CommandLine -match $tagPattern -and
                    $_.CommandLine -match $managedCommandPattern
                }
        )
    }
    else {
        $survivingProcesses = @(
            & ps -eo pid=,args= |
                Where-Object {
                    $_ -match $tagPattern -and $_ -match $managedCommandPattern
                }
        )
    }
}
catch {
    $cleanupErrors.Add("process-verification:$($_.Exception.Message)")
}

$survivingContainers = @()
if ($null -ne $docker) {
    try {
        $survivingContainers = @(
            & $docker ps -a --filter "name=^/${containerName}$" --format '{{.Names}}'
        )
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to verify disposable container cleanup.'
        }
    }
    catch {
        $cleanupErrors.Add("container-verification:$($_.Exception.Message)")
    }
}
$survivingSecrets = @(
    $secretNames |
        Where-Object {
            Test-Path -LiteralPath (Join-Path $runRoot $_) -PathType Leaf
        }
)
$survivingTransientEvaluation = @(
    foreach ($pattern in $transientEvaluationPatterns) {
        Get-ChildItem -LiteralPath $runRoot -File -Filter $pattern `
            -ErrorAction SilentlyContinue
    }
)
$cleanupIncomplete = $survivingProcesses.Count -ne 0 `
    -or $survivingContainers.Count -ne 0 `
    -or $survivingSecrets.Count -ne 0 `
    -or $survivingTransientEvaluation.Count -ne 0
if ($cleanupIncomplete) {
    $cleanupErrors.Add('zero-resource-verification:managed resources survived cleanup')
}

$runDirectoryRemoved = $false
if ($RemoveRunDirectory -and -not $cleanupIncomplete -and $cleanupErrors.Count -eq 0) {
    try {
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath $runRoot
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
    catch {
        $cleanupErrors.Add("directory-cleanup:$($_.Exception.Message)")
    }
}

if ($cleanupErrors.Count -ne 0) {
    throw ('Cross-service cleanup incomplete: ' + ($cleanupErrors -join ' | '))
}

Write-Output (
    "CrossServiceRunCleanup=PASS Processes={0} Containers={1} Secrets={2} TransientEvaluationFiles={3} RunDirectoryRemoved={4}" -f
        $taggedProcesses.Count,
        $containers.Count,
        $removedSecretCount,
        $removedTransientEvaluationCount,
        $runDirectoryRemoved
)
