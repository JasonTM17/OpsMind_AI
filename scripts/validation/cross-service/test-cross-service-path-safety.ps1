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

    $probeName = 'OPSMIND_CROSS_SERVICE_PROCESS_PROBE'
    $probeValue = [guid]::NewGuid().ToString('N')
    $probeStdout = Join-Path $testRoot 'phase-08-process-probe.stdout.log'
    $probeStderr = Join-Path $testRoot 'phase-08-process-probe.stderr.log'
    $failureStdout = Join-Path $testRoot 'phase-08-process-failure.stdout.log'
    $failureStderr = Join-Path $testRoot 'phase-08-process-failure.stderr.log'
    $missingStdout = Join-Path $testRoot 'phase-08-process-missing.stdout.log'
    $missingStderr = Join-Path $testRoot 'phase-08-process-missing.stderr.log'
    $redirectStderr = Join-Path $testRoot 'phase-08-process-redirect.stderr.log'
    $blockingParent = Join-Path $testRoot 'not-a-directory'
    [IO.File]::WriteAllText($blockingParent, 'path-boundary-probe')
    $invalidStdout = Join-Path $blockingParent 'probe.stdout.log'
    if (Test-CrossServiceWindows) {
        $probeExecutable = $env:ComSpec
        $probeArguments = @('/d', '/c', "echo %${probeName}%")
        $failureArguments = @('/d', '/c', 'exit', '7')
    }
    else {
        $probeExecutable = (Get-Command sh -CommandType Application -ErrorAction Stop).Path
        $probeArguments = @('-c', "printf '%s' `"`$$probeName`"")
        $failureArguments = @('-c', 'exit 7')
    }
    Invoke-CrossServiceProcess -Executable $probeExecutable -Arguments $probeArguments `
        -WorkingDirectory $repositoryRoot -StdoutPath $probeStdout `
        -StderrPath $probeStderr -Environment @{ $probeName = $probeValue }
    if ([IO.File]::ReadAllText($probeStdout).Trim() -ne $probeValue) {
        throw 'Cross-service process environment did not reach the child.'
    }

    $failureMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable -Arguments $failureArguments `
            -WorkingDirectory $repositoryRoot -StdoutPath $failureStdout `
            -StderrPath $failureStderr -Environment @{}
    }
    catch {
        $failureMessage = $_.Exception.Message
    }
    if ($failureMessage -ne
        "Cross-service command 'phase-08-process-failure.stderr' failed with exit code 7.") {
        throw "Cross-service process failure diagnostic drifted: $failureMessage"
    }

    $missingMessage = $null
    try {
        Invoke-CrossServiceProcess `
            -Executable (Join-Path $testRoot 'executable-does-not-exist') `
            -Arguments @('probe') -WorkingDirectory $repositoryRoot `
            -StdoutPath $missingStdout -StderrPath $missingStderr -Environment @{}
    }
    catch {
        $missingMessage = $_.Exception.Message
    }
    if ($missingMessage -notlike
        "Cross-service command 'phase-08-process-missing.stderr' failed before native exit code capture (*).") {
        throw "Cross-service missing-process guard drifted: $missingMessage"
    }

    $redirectMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable -Arguments $probeArguments `
            -WorkingDirectory $repositoryRoot -StdoutPath $invalidStdout `
            -StderrPath $redirectStderr -Environment @{ $probeName = $probeValue }
    }
    catch {
        $redirectMessage = $_.Exception.Message
    }
    if ($redirectMessage -notlike
        "Cross-service command 'phase-08-process-redirect.stderr' failed before native exit code capture (*).") {
        throw "Cross-service process-redirection guard drifted: $redirectMessage"
    }

    Write-Output (
        'CrossServicePathSafety=PASS ReparseAncestor=BLOCKED ProcessLaunch=PASS ' +
        'LaunchFailure=BLOCKED RedirectionFailure=BLOCKED'
    )
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
