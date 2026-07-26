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

function Assert-CrossServiceTestProcessesExited {
    param(
        [Parameter(Mandatory = $true)][string]$PidPath,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    if (-not (Test-Path -LiteralPath $PidPath -PathType Leaf)) {
        throw "$FailureMessage PID evidence is missing."
    }
    $processIds = @(
        [IO.File]::ReadAllText($PidPath) -split '\s+' |
            Where-Object { $_ -match '^\d+$' } |
            ForEach-Object { [int]$_ }
    )
    if ($processIds.Count -eq 0) {
        throw "$FailureMessage PID evidence is empty."
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(3)
    do {
        $alive = @()
        foreach ($processId in $processIds) {
            $candidate = $null
            try {
                $candidate = [Diagnostics.Process]::GetProcessById($processId)
                if (-not $candidate.HasExited) {
                    $alive += $processId
                }
            }
            catch [ArgumentException] {
                # The process no longer exists.
            }
            finally {
                if ($null -ne $candidate) {
                    $candidate.Dispose()
                }
            }
        }
        if ($alive.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "$FailureMessage SurvivingPids=$($alive -join ',')"
}

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
    $controlPathBlocked = $false
    try {
        [void](Get-CrossServiceControlDirectory -ConfiguredPath $linkRoot `
            -WorkingDirectory $repositoryRoot)
    }
    catch [IO.IOException] {
        $controlPathBlocked = $_.Exception.Message -eq
            'Cross-service process control path contains a reparse point.'
    }
    if (-not $controlPathBlocked) {
        throw 'Cross-service process control accepted a reparse-point ancestor.'
    }

    $forgedStatusPath = Join-Path $testRoot 'forged-process.status'
    $controlNonceProbe = [guid]::NewGuid().ToString('N')
    $gateNonceProbe = [guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText(
        $forgedStatusPath,
        ('exit:0:' + ('0' * 32)),
        [Text.UTF8Encoding]::new($false)
    )
    $forgedStatusBlocked = $false
    try {
        [void](Read-CrossServiceSupervisorStatus -Path $forgedStatusPath `
            -ControlNonce $controlNonceProbe -GateNonce $gateNonceProbe)
    }
    catch [FormatException] {
        $forgedStatusBlocked = $_.Exception.Message -eq
            'Cross-service supervisor exit status is unauthenticated.'
    }
    finally {
        Remove-Item -LiteralPath $forgedStatusPath -Force
    }
    if (-not $forgedStatusBlocked) {
        throw 'Cross-service process control accepted a forged exit status.'
    }

    # The gate nonce is written to the control directory, so anything that can
    # write a status file can also read it. Only the control nonce, which
    # travels over standard input and never reaches disk, may authenticate a
    # successful exit. A failure status may carry either, because a supervisor
    # can fail before it has read the transport.
    [IO.File]::WriteAllText(
        $forgedStatusPath,
        ('exit:0:' + $gateNonceProbe),
        [Text.UTF8Encoding]::new($false)
    )
    $gateSignedExitBlocked = $false
    try {
        [void](Read-CrossServiceSupervisorStatus -Path $forgedStatusPath `
            -ControlNonce $controlNonceProbe -GateNonce $gateNonceProbe)
    }
    catch [FormatException] {
        $gateSignedExitBlocked = $_.Exception.Message -eq
            'Cross-service supervisor exit status is unauthenticated.'
    }
    finally {
        Remove-Item -LiteralPath $forgedStatusPath -Force
    }
    if (-not $gateSignedExitBlocked) {
        throw 'Cross-service process control accepted a gate-signed exit status.'
    }

    [IO.File]::WriteAllText(
        $forgedStatusPath,
        ('failure:LaunchException:None:' + $gateNonceProbe),
        [Text.UTF8Encoding]::new($false)
    )
    $gateSignedFailure = $null
    try {
        $gateSignedFailure = Read-CrossServiceSupervisorStatus -Path $forgedStatusPath `
            -ControlNonce $controlNonceProbe -GateNonce $gateNonceProbe
    }
    finally {
        Remove-Item -LiteralPath $forgedStatusPath -Force
    }
    if ($null -eq $gateSignedFailure -or
        $gateSignedFailure.FailureType -ne 'LaunchException') {
        throw 'Cross-service process control rejected a gate-signed failure status.'
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
        $failureArguments = @(
            '/d',
            '/c',
            'echo ERROR expected-stdout & echo ERROR expected-stderr 1>&2 & exit /b 7'
        )
    }
    else {
        $probeExecutable = @(
            Get-Command sh -CommandType Application -ErrorAction Stop
        )[0].Path
        $probeArguments = @('-c', "printf '%s' `"`$$probeName`"")
        $failureArguments = @(
            '-c',
            (
                "printf '%s\n' 'ERROR expected-stdout'; " +
                    "printf '%s\n' 'ERROR expected-stderr' >&2; exit 7"
            )
        )
    }
    $previousLastExitCodeVariable = Get-Variable -Name LASTEXITCODE -Scope Global `
        -ErrorAction SilentlyContinue
    $previousLastExitCode = if ($null -ne $previousLastExitCodeVariable) {
        $previousLastExitCodeVariable.Value
    }
    else {
        $null
    }
    $global:LASTEXITCODE = 73
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable `
            -Arguments $probeArguments -WorkingDirectory $repositoryRoot `
            -StdoutPath $probeStdout -StderrPath $probeStderr `
            -Environment @{ $probeName = $probeValue }
        if ($global:LASTEXITCODE -ne 73) {
            throw 'Cross-service capture inferred the exit status from shell state.'
        }
    }
    finally {
        if ($null -ne $previousLastExitCodeVariable) {
            Set-Variable -Name LASTEXITCODE -Scope Global -Value $previousLastExitCode
        }
        else {
            Remove-Variable -Name LASTEXITCODE -Scope Global `
                -ErrorAction SilentlyContinue
        }
    }
    if ([IO.File]::ReadAllText($probeStdout).Trim() -ne $probeValue) {
        throw 'Cross-service process environment did not reach the child.'
    }

    $failureMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable `
            -Arguments $failureArguments -WorkingDirectory $repositoryRoot `
            -StdoutPath $failureStdout -StderrPath $failureStderr -Environment @{}
    }
    catch {
        $failureMessage = $_.Exception.Message
    }
    if ($failureMessage -ne
        "Cross-service command 'phase-08-process-failure.stderr' failed with exit code 7.") {
        throw "Cross-service process failure diagnostic drifted: $failureMessage"
    }
    $failureDiagnostic = (
        Get-CrossServiceRedactedLogTail -Path $failureStdout -Environment @{}
    ) + ' ' + (
        Get-CrossServiceRedactedLogTail -Path $failureStderr -Environment @{}
    )
    if ($failureDiagnostic -notmatch 'ERROR expected-stdout' -or
        $failureDiagnostic -notmatch 'ERROR expected-stderr') {
        throw 'Cross-service process failure omitted safe diagnostic output.'
    }

    $bulkExecutable = @(
        Get-Command node -CommandType Application -ErrorAction Stop
    )[0].Path
    $bulkStdout = Join-Path $testRoot 'phase-08-process-bulk.stdout.log'
    $bulkStderr = Join-Path $testRoot 'phase-08-process-bulk.stderr.log'
    $bulkByteCount = 262144
    $bulkMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $bulkExecutable -Arguments @(
            '-e',
            (
                'process.stdout.write("A".repeat(262144) + "\nERROR bulk-stdout\n");' +
                'process.stderr.write("B".repeat(262144) + "\nERROR bulk-stderr\n");' +
                'process.exitCode = 9;'
            )
        ) -WorkingDirectory $repositoryRoot -StdoutPath $bulkStdout `
            -StderrPath $bulkStderr -Environment @{}
    }
    catch {
        $bulkMessage = $_.Exception.Message
    }
    if ($bulkMessage -ne
        "Cross-service command 'phase-08-process-bulk.stderr' failed with exit code 9." -or
        (Get-Item -LiteralPath $bulkStdout).Length -lt $bulkByteCount -or
        (Get-Item -LiteralPath $bulkStderr).Length -lt $bulkByteCount -or
        [IO.File]::ReadAllText($bulkStdout) -notmatch 'ERROR bulk-stdout' -or
        [IO.File]::ReadAllText($bulkStderr) -notmatch 'ERROR bulk-stderr') {
        throw "Cross-service concurrent output capture drifted: $bulkMessage"
    }

    $stdinPayload = 'probe-' + [guid]::NewGuid().ToString('N')
    $stdinOutput = New-Object IO.MemoryStream
    $stdinError = New-Object IO.MemoryStream
    try {
        $stdinExitCode = Invoke-CrossServiceNativeCapture -Executable $bulkExecutable `
            -Arguments @(
                '-e',
                (
                    'let buffer = "";' +
                    'process.stdin.setEncoding("utf8");' +
                    'process.stdin.on("data", (chunk) => { buffer += chunk; });' +
                    'process.stdin.on("end", () => {' +
                    ' process.stdout.write(buffer);' +
                    ' process.exitCode = buffer.length > 0 ? 0 : 3;' +
                    '});'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{} `
            -StandardOutput $stdinOutput -StandardError $stdinError `
            -StandardInputText $stdinPayload -ControlDirectory $testRoot
        $stdinText = [Text.Encoding]::UTF8.GetString($stdinOutput.ToArray())
    }
    finally {
        $stdinError.Dispose()
        $stdinOutput.Dispose()
    }
    if ($stdinExitCode -ne 0 -or $stdinText -ne $stdinPayload) {
        throw 'Cross-service standard input round trip drifted.'
    }

    $largeStdinPayload = 'X' * 4194304
    $largeStdinOutput = New-Object IO.MemoryStream
    $largeStdinError = New-Object IO.MemoryStream
    try {
        $largeStdinExitCode = Invoke-CrossServiceNativeCapture `
            -Executable $bulkExecutable -Arguments @(
                '-e',
                (
                    'let length = 0;' +
                    'process.stdin.on("data", (chunk) => {' +
                    ' length += chunk.length;' +
                    '});' +
                    'process.stdin.on("end", () => {' +
                    ' process.stdout.write(String(length));' +
                    '});'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{} `
            -StandardOutput $largeStdinOutput `
            -StandardError $largeStdinError `
            -StandardInputText $largeStdinPayload -ControlDirectory $testRoot
        $largeStdinText = [Text.Encoding]::UTF8.GetString(
            $largeStdinOutput.ToArray()
        )
    }
    finally {
        $largeStdinError.Dispose()
        $largeStdinOutput.Dispose()
    }
    if ($largeStdinExitCode -ne 0 -or
        $largeStdinText -ne [string]$largeStdinPayload.Length) {
        throw 'Cross-service large supervisor transport drifted.'
    }

    $lateCleanupFailures = New-Object `
        'System.Collections.Generic.List[Exception]'
    Add-CrossServiceSupervisorCleanupFailures -Status ([pscustomobject]@{
        CleanupFailureTypes = @(
            'TargetWaitTimeoutException',
            'DescendantCleanupTimeoutException'
        )
    }) -CleanupFailures $lateCleanupFailures
    if ($lateCleanupFailures.Count -ne 2 -or
        $lateCleanupFailures[0].Message -notmatch
            'TargetWaitTimeoutException' -or
        $lateCleanupFailures[1].Message -notmatch
            'DescendantCleanupTimeoutException') {
        throw 'Cross-service late cleanup status aggregation drifted.'
    }

    $reservedFailure = $null
    try {
        [void](Invoke-CrossServiceNativeCapture -Executable $bulkExecutable `
            -Arguments @('-e', 'process.exit(0);') `
            -WorkingDirectory $repositoryRoot -Environment @{
                DOTNET_STARTUP_HOOKS = Join-Path $testRoot 'untrusted-hook.dll'
            } -StandardOutput ([IO.Stream]::Null) `
            -StandardError ([IO.Stream]::Null) `
            -ControlDirectory $testRoot)
    }
    catch {
        $reservedFailure = $_.Exception
    }
    if (-not ($reservedFailure -is [InvalidOperationException]) -or
        $reservedFailure.Message -ne
        'Cross-service target environment contains a supervisor-reserved variable.') {
        throw 'Cross-service supervisor accepted a reserved target environment variable.'
    }

    $ambientName = 'DOTNET_STARTUP_HOOKS'
    $ambientPrevious = [Environment]::GetEnvironmentVariable(
        $ambientName,
        'Process'
    )
    $ambientOutput = New-Object IO.MemoryStream
    $ambientError = New-Object IO.MemoryStream
    try {
        [Environment]::SetEnvironmentVariable(
            $ambientName,
            (Join-Path $testRoot 'ambient-untrusted-hook.dll'),
            'Process'
        )
        $ambientExitCode = Invoke-CrossServiceNativeCapture `
            -Executable $bulkExecutable -Arguments @(
                '-e',
                (
                    'process.stdout.write(' +
                        'process.env.DOTNET_STARTUP_HOOKS ?? "absent");'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{} `
            -StandardOutput $ambientOutput -StandardError $ambientError `
            -ControlDirectory $testRoot
        $ambientText = [Text.Encoding]::UTF8.GetString(
            $ambientOutput.ToArray()
        )
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            $ambientName,
            $ambientPrevious,
            'Process'
        )
        $ambientError.Dispose()
        $ambientOutput.Dispose()
    }
    if ($ambientExitCode -ne 0 -or $ambientText -ne 'absent') {
        throw 'Cross-service supervisor inherited a reserved ambient variable.'
    }

    $copyFaultPidPath = Join-Path $testRoot 'copy-fault.pids'
    $copyFaultStream = [IO.Pipes.AnonymousPipeServerStream]::new(
        [IO.Pipes.PipeDirection]::Out,
        [IO.HandleInheritability]::None
    )
    $copyFaultReader = [IO.Pipes.AnonymousPipeClientStream]::new(
        [IO.Pipes.PipeDirection]::In,
        $copyFaultStream.GetClientHandleAsString()
    )
    $copyFaultStream.DisposeLocalCopyOfClientHandle()
    $copyFaultReader.Dispose()
    $copyFaultFailure = $null
    $copyFaultWatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        [void](Invoke-CrossServiceNativeCapture `
            -Executable $bulkExecutable -Arguments @(
                '-e',
                (
                    'const fs=require("fs");' +
                        'fs.writeFileSync(' +
                        'process.env.OPSMIND_COPY_FAULT_PID_PATH,' +
                        'String(process.pid));' +
                        'const block=Buffer.alloc(65536,65);' +
                        'function write(){' +
                        'while(process.stdout.write(block)){}' +
                        'process.stdout.once("drain",write);' +
                        '}write();setInterval(()=>{},1000);'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{
                OPSMIND_COPY_FAULT_PID_PATH = $copyFaultPidPath
            } -StandardOutput $copyFaultStream `
            -StandardError ([IO.Stream]::Null) `
            -DrainTimeoutSeconds 1 -ExecutionTimeoutSeconds 10 `
            -ControlDirectory $testRoot)
    }
    catch {
        $copyFaultFailure = $_.Exception
    }
    finally {
        $copyFaultWatch.Stop()
        $copyFaultStream.Dispose()
    }
    if (-not ($copyFaultFailure -is [IO.IOException]) -or
        $copyFaultWatch.Elapsed.TotalSeconds -ge 5) {
        $copyFaultType = if ($null -eq $copyFaultFailure) {
            'NONE'
        }
        else {
            $copyFaultFailure.GetType().Name
        }
        throw (
            'Cross-service output failure was not bounded. ' +
                "Type=$copyFaultType " +
                "Seconds=$($copyFaultWatch.Elapsed.TotalSeconds)"
        )
    }
    Assert-CrossServiceTestProcessesExited -PidPath $copyFaultPidPath `
        -FailureMessage 'Cross-service output failure leaked a process.'

    $orphanPidPath = Join-Path $testRoot 'orphan-drain.pids'
    $orphanFailure = $null
    $orphanExitCode = $null
    $orphanWatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $orphanExitCode = Invoke-CrossServiceNativeCapture `
            -Executable $bulkExecutable -Arguments @(
                '-e',
                (
                    'const fs=require("fs");' +
                        'const child=require("child_process").spawn(' +
                        'process.execPath,' +
                        '["-e","setInterval(()=>{},1000);"],' +
                        '{stdio:"inherit",detached:true});' +
                        'child.unref();' +
                        'fs.writeFileSync(' +
                        'process.env.OPSMIND_ORPHAN_PID_PATH,' +
                        '`${process.pid} ${child.pid}`);' +
                        'process.exit(0);'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{
                OPSMIND_ORPHAN_PID_PATH = $orphanPidPath
            } -StandardOutput ([IO.Stream]::Null) `
            -StandardError ([IO.Stream]::Null) `
            -DrainTimeoutSeconds 1 -ExecutionTimeoutSeconds 5 `
            -ControlDirectory $testRoot
    }
    catch {
        $orphanFailure = $_.Exception
    }
    finally {
        $orphanWatch.Stop()
    }
    $boundedDrainFailure = (
        $orphanFailure -is [InvalidOperationException] -and
        $orphanFailure.Message -eq
            'Cross-service process supervisor reported a bounded StreamsTimeoutException failure.'
    )
    $cleanParentExit = (
        $null -eq $orphanFailure -and $orphanExitCode -eq 0
    )
    if ((-not $boundedDrainFailure -and -not $cleanParentExit) -or
        $orphanWatch.Elapsed.TotalSeconds -ge 5) {
        $orphanType = if ($null -eq $orphanFailure) {
            'NONE'
        }
        else {
            $orphanFailure.GetType().Name
        }
        throw (
            'Cross-service inherited-pipe drain was not bounded. ' +
                "Type=$orphanType Seconds=$($orphanWatch.Elapsed.TotalSeconds)"
        )
    }
    Assert-CrossServiceTestProcessesExited -PidPath $orphanPidPath `
        -FailureMessage 'Cross-service inherited-pipe drain leaked a process.'

    $timeoutPidPath = Join-Path $testRoot 'execution-timeout.pids'
    $timeoutFailure = $null
    $timeoutWatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        [void](Invoke-CrossServiceNativeCapture `
            -Executable $bulkExecutable -Arguments @(
                '-e',
                (
                    'require("fs").writeFileSync(' +
                        'process.env.OPSMIND_TIMEOUT_PID_PATH,' +
                        'String(process.pid));' +
                        'setInterval(()=>{},1000);'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{
                OPSMIND_TIMEOUT_PID_PATH = $timeoutPidPath
            } -StandardOutput ([IO.Stream]::Null) `
            -StandardError ([IO.Stream]::Null) `
            -DrainTimeoutSeconds 1 -ExecutionTimeoutSeconds 1 `
            -ControlDirectory $testRoot)
    }
    catch {
        $timeoutFailure = $_.Exception
    }
    finally {
        $timeoutWatch.Stop()
    }
    if (-not ($timeoutFailure -is [TimeoutException]) -or
        $timeoutWatch.Elapsed.TotalSeconds -ge 5) {
        $timeoutType = if ($null -eq $timeoutFailure) {
            'NONE'
        }
        else {
            $timeoutFailure.GetType().Name
        }
        throw (
            'Cross-service execution timeout was not enforced. ' +
                "Type=$timeoutType Seconds=$($timeoutWatch.Elapsed.TotalSeconds)"
        )
    }
    Assert-CrossServiceTestProcessesExited -PidPath $timeoutPidPath `
        -FailureMessage 'Cross-service execution timeout leaked a process.'
    $ownerDeathResult = 'OwnerDeath=SKIPPED'
    if (-not (Test-CrossServiceWindows)) {
        $ownerDeathPidPath = Join-Path $testRoot 'owner-death.pids'
        $ownerDeathNodeScript = (
            'const fs=require("fs");' +
                'const child=require("child_process").spawn(' +
                'process.execPath,' +
                '["-e","setInterval(()=>{},1000);"],' +
                '{stdio:"ignore",detached:true});' +
                'child.unref();' +
                'fs.writeFileSync(' +
                'process.env.OPSMIND_OWNER_DEATH_PID_PATH,' +
                '`${process.pid} ${child.pid}`);' +
                'setInterval(()=>{},1000);'
        )
        $ownerControllerScript = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. $env:OPSMIND_OWNER_SUPPORT_PATH
[void](Invoke-CrossServiceNativeCapture `
    -Executable $env:OPSMIND_OWNER_NODE_PATH `
    -Arguments @('-e', $env:OPSMIND_OWNER_NODE_SCRIPT) `
    -WorkingDirectory $env:OPSMIND_OWNER_WORKING_DIRECTORY `
    -Environment @{
        OPSMIND_OWNER_DEATH_PID_PATH = $env:OPSMIND_OWNER_PID_PATH
    } `
    -StandardOutput ([IO.Stream]::Null) `
    -StandardError ([IO.Stream]::Null) `
    -DrainTimeoutSeconds 1 `
    -ExecutionTimeoutSeconds 30 `
    -ControlDirectory $env:OPSMIND_OWNER_CONTROL_DIRECTORY)
'@
        $ownerControllerEncoded = [Convert]::ToBase64String(
            [Text.Encoding]::Unicode.GetBytes($ownerControllerScript)
        )
        $ownerStartInfo = [Diagnostics.ProcessStartInfo]::new()
        $ownerStartInfo.FileName = [Environment]::ProcessPath
        $ownerStartInfo.UseShellExecute = $false
        $ownerStartInfo.CreateNoWindow = $true
        $ownerStartInfo.RedirectStandardOutput = $true
        $ownerStartInfo.RedirectStandardError = $true
        foreach ($argument in @(
            '-NoLogo',
            '-NoProfile',
            '-NonInteractive',
            '-EncodedCommand',
            $ownerControllerEncoded
        )) {
            [void]$ownerStartInfo.ArgumentList.Add($argument)
        }
        $ownerStartInfo.Environment['OPSMIND_OWNER_SUPPORT_PATH'] = (
            Join-Path $PSScriptRoot 'cross-service-harness-support.ps1'
        )
        $ownerStartInfo.Environment['OPSMIND_OWNER_NODE_PATH'] = $bulkExecutable
        $ownerStartInfo.Environment['OPSMIND_OWNER_NODE_SCRIPT'] = (
            $ownerDeathNodeScript
        )
        $ownerStartInfo.Environment['OPSMIND_OWNER_WORKING_DIRECTORY'] = (
            $repositoryRoot
        )
        $ownerStartInfo.Environment['OPSMIND_OWNER_PID_PATH'] = (
            $ownerDeathPidPath
        )
        $ownerStartInfo.Environment['OPSMIND_OWNER_CONTROL_DIRECTORY'] = (
            $testRoot
        )

        $ownerController = [Diagnostics.Process]::new()
        $ownerController.StartInfo = $ownerStartInfo
        $ownerControllerStarted = $false
        $ownerDeathWatch = [Diagnostics.Stopwatch]::StartNew()
        try {
            if (-not $ownerController.Start()) {
                throw 'Cross-service owner-death controller did not start.'
            }
            $ownerControllerStarted = $true
            $ownerStdout = $ownerController.StandardOutput.ReadToEndAsync()
            $ownerStderr = $ownerController.StandardError.ReadToEndAsync()
            $ownerPidDeadline = [DateTime]::UtcNow.AddSeconds(15)
            while (-not (Test-Path -LiteralPath $ownerDeathPidPath `
                -PathType Leaf)) {
                if ($ownerController.HasExited) {
                    throw (
                        'Cross-service owner-death controller exited early: ' +
                            $ownerStderr.GetAwaiter().GetResult()
                    )
                }
                if ([DateTime]::UtcNow -ge $ownerPidDeadline) {
                    throw 'Cross-service owner-death PID evidence timed out.'
                }
                Start-Sleep -Milliseconds 20
            }
            $ownerController.Kill()
            if (-not $ownerController.WaitForExit(5000)) {
                throw 'Cross-service owner-death controller termination timed out.'
            }
            [void]$ownerStdout.GetAwaiter().GetResult()
            [void]$ownerStderr.GetAwaiter().GetResult()
            Assert-CrossServiceTestProcessesExited `
                -PidPath $ownerDeathPidPath `
                -FailureMessage (
                    'Cross-service controller death leaked an owned process.'
                )
            $ownerDeathResult = 'OwnerDeath=BLOCKED'
        }
        finally {
            $ownerDeathWatch.Stop()
            if ($ownerControllerStarted -and -not $ownerController.HasExited) {
                $ownerController.Kill($true)
                [void]$ownerController.WaitForExit(5000)
            }
            $ownerController.Dispose()
        }
        if ($ownerDeathWatch.Elapsed.TotalSeconds -ge 20) {
            throw 'Cross-service owner-death cleanup exceeded its bound.'
        }
    }
    $processSupervisionResult = (
        'CopyFailure=BLOCKED OrphanDrain=BLOCKED ExecutionTimeout=BLOCKED ' +
            "ReservedEnvironment=BLOCKED $ownerDeathResult"
    )

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

    $diagnosticSecret = 'probe-' + [guid]::NewGuid().ToString('N')
    $bearerSecret = 'bearer-' + [guid]::NewGuid().ToString('N')
    $jwtSecret = 'eyJ' + [guid]::NewGuid().ToString('N') + '.' +
        [guid]::NewGuid().ToString('N') + '.' + [guid]::NewGuid().ToString('N')
    $clientSecret = 'client-' + [guid]::NewGuid().ToString('N')
    $accessToken = 'access-' + [guid]::NewGuid().ToString('N')
    $jsonPassword = 'json-' + [guid]::NewGuid().ToString('N')
    $urlPassword = 'url-' + [guid]::NewGuid().ToString('N')
    $inheritedSecret = 'inherited-' + [guid]::NewGuid().ToString('N')
    $derivedPassword = 'derived-' + [guid]::NewGuid().ToString('N')
    $runtimeApiKey = ('A' + 'K' + 'I' + 'A') + [guid]::NewGuid().ToString('N')
    $runtimePrivateKeyBody = [guid]::NewGuid().ToString('N') +
        [guid]::NewGuid().ToString('N')
    $inheritedSecretName = 'OPSMIND_DIAGNOSTIC_INHERITED_CLIENT_SECRET'
    $previousInheritedSecret = [Environment]::GetEnvironmentVariable(
        $inheritedSecretName,
        'Process'
    )
    [Environment]::SetEnvironmentVariable(
        $inheritedSecretName,
        $inheritedSecret,
        'Process'
    )
    $privateKeyHeader = '-----BEGIN ' + 'PRIVATE KEY-----'
    $privateKeyFooter = '-----END ' + 'PRIVATE KEY-----'
    $privateKeyBody = ('A' * 64)
    $credentialDatabaseUrl = ('post' + 'gresql://') +
        "user:${urlPassword}@db.invalid/opsmind"
    $diagnosticPath = Join-Path $testRoot 'phase-08-redaction-probe.log'
    [IO.File]::WriteAllText(
        $diagnosticPath,
        ('x' * 20000) +
            "`npassword=$diagnosticSecret" +
            "`nauthorization: Bearer $bearerSecret" +
            "`nopaque=$jwtSecret" +
            "`nERROR inherited-value=$inheritedSecret" +
            "`nERROR client_secret=$clientSecret" +
            "`nERROR access_token=$accessToken" +
            "`nERROR {`"password`":`"$jsonPassword`"}" +
            "`nERROR $credentialDatabaseUrl" +
            "`n$privateKeyHeader`n$privateKeyBody`n$privateKeyFooter" +
            "`nERROR password is $derivedPassword" +
            "`nCaused by: invalid API key $runtimeApiKey" +
            "`nERROR failed to parse private key $runtimePrivateKeyBody" +
            "`n::error::forged-workflow-command" +
            "`ncontrol=$([char]27)[31m`nerror=expected-diagnostic"
    )
    try {
        $redactedDiagnostic = Get-CrossServiceRedactedLogTail `
            -Path $diagnosticPath -Environment @{
                TOOL_GATEWAY_DATABASE_PASSWORD = $diagnosticSecret
            }
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            $inheritedSecretName,
            $previousInheritedSecret,
            'Process'
        )
    }
    $sensitiveCanaries = @(
        $diagnosticSecret,
        $bearerSecret,
        $jwtSecret,
        $clientSecret,
        $accessToken,
        $jsonPassword,
        $urlPassword,
        $inheritedSecret,
        $privateKeyBody,
        $derivedPassword,
        $runtimeApiKey,
        $runtimePrivateKeyBody
    )
    foreach ($canary in $sensitiveCanaries) {
        if ($redactedDiagnostic -match [regex]::Escape($canary)) {
            throw 'Cross-service diagnostic exposed a sensitive canary.'
        }
    }
    if (-not $redactedDiagnostic.StartsWith('[log] ') -or
        $redactedDiagnostic -match '[\r\n\x1B]' -or
        $redactedDiagnostic.Length -gt 8192 -or
        $redactedDiagnostic -notmatch '\[redacted prohibited line\]' -or
        $redactedDiagnostic -notmatch 'inherited-value=\[REDACTED\]' -or
        $redactedDiagnostic -notmatch 'error=expected-diagnostic') {
        throw 'Cross-service diagnostic redaction failed closed.'
    }
    if ((Get-CrossServiceRedactedLogTail `
            -Path (Join-Path $testRoot 'missing-diagnostic.log') `
            -Environment @{}) -ne '') {
        throw 'Missing cross-service diagnostic did not remain empty.'
    }

    Write-Output (
        'CrossServicePathSafety=PASS ReparseAncestor=BLOCKED ProcessLaunch=PASS ' +
        'ControlReparse=BLOCKED StatusForgery=BLOCKED ' +
        'GateSignedExitForgery=BLOCKED GateSignedFailure=ACCEPTED ' +
        'ExitStatusFromHandle=PASS ConcurrentCapture=PASS StandardInput=PASS ' +
        'LargeTransport=PASS LateCleanupStatus=PASS ' +
        'LaunchFailure=BLOCKED RedirectionFailure=BLOCKED ' +
        "DiagnosticRedaction=PASS $processSupervisionResult"
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
