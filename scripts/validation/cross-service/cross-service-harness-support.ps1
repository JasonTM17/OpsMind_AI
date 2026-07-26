Set-StrictMode -Version Latest

function Test-CrossServiceWindows {
    return [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
}

. (Join-Path $PSScriptRoot 'cross-service-windows-job-control.ps1')

function Get-CrossServicePathComparison {
    if (Test-CrossServiceWindows) {
        return [StringComparison]::OrdinalIgnoreCase
    }
    return [StringComparison]::Ordinal
}

function Join-CrossServicePath {
    param(
        [Parameter(Mandatory = $true)][string]$BasePath,
        [Parameter(Mandatory = $true)][string[]]$ChildPath
    )

    $resolved = $BasePath
    foreach ($child in $ChildPath) {
        $resolved = Join-Path $resolved $child
    }
    return $resolved
}

function New-CrossServiceSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    }
    finally {
        $random.Dispose()
    }
    return ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Get-CrossServiceAvailablePorts {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 32)]
        [int]$Count
    )

    $listeners = New-Object 'System.Collections.Generic.List[Net.Sockets.TcpListener]'
    $ports = New-Object 'System.Collections.Generic.List[int]'
    try {
        for ($index = 0; $index -lt $Count; $index++) {
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $listeners.Add($listener)
            $ports.Add(([Net.IPEndPoint]$listener.LocalEndpoint).Port)
        }
        return $ports.ToArray()
    }
    finally {
        foreach ($listener in $listeners) {
            $listener.Stop()
        }
    }
}

function Invoke-WithProcessEnvironment {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Variables,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    $previous = @{}
    foreach ($name in $Variables.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$Variables[$name], 'Process')
    }
    try {
        return & $Action
    }
    finally {
        foreach ($name in $Variables.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
}

function Start-CrossServiceProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath,
        [Parameter(Mandatory = $true)][hashtable]$Environment
    )

    return Invoke-WithProcessEnvironment -Variables $Environment -Action {
        $startArguments = @{
            FilePath = $Executable
            ArgumentList = $Arguments
            WorkingDirectory = $WorkingDirectory
            PassThru = $true
            RedirectStandardOutput = $StdoutPath
            RedirectStandardError = $StderrPath
        }
        if (Test-CrossServiceWindows) {
            $startArguments.WindowStyle = 'Hidden'
        }
        Start-Process @startArguments
    }
}

function Get-CrossServiceRedactedLogTail {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][hashtable]$Environment
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ''
    }
    $stream = $null
    try {
        $stream = [IO.File]::Open(
            $Path,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            [IO.FileShare]::ReadWrite
        )
        $byteCount = [int][math]::Min(16384L, $stream.Length)
        if ($byteCount -eq 0) {
            return ''
        }
        [void]$stream.Seek(-1L * $byteCount, [IO.SeekOrigin]::End)
        $buffer = New-Object byte[] $byteCount
        $bytesRead = 0
        while ($bytesRead -lt $byteCount) {
            $read = $stream.Read($buffer, $bytesRead, $byteCount - $bytesRead)
            if ($read -eq 0) {
                break
            }
            $bytesRead += $read
        }
        $content = [Text.Encoding]::UTF8.GetString($buffer, 0, $bytesRead)
    }
    catch {
        return "[diagnostic unavailable: $($_.Exception.GetType().Name)]"
    }
    finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
    $sensitiveValues = New-Object 'System.Collections.Generic.HashSet[string]'
    $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
    foreach ($entry in $processEnvironment.GetEnumerator()) {
        if ([string]$entry.Key -match '(?i)(auth|credential|key|password|secret|token)') {
            $value = [string]$entry.Value
            if (-not [string]::IsNullOrEmpty($value)) {
                [void]$sensitiveValues.Add($value)
            }
        }
    }
    foreach ($name in $Environment.Keys) {
        if ($name -match '(?i)(auth|credential|key|password|secret|token)') {
            $value = [string]$Environment[$name]
            if (-not [string]::IsNullOrEmpty($value)) {
                [void]$sensitiveValues.Add($value)
            }
        }
    }
    foreach ($sensitiveValue in $sensitiveValues) {
        $content = $content.Replace($sensitiveValue, '[REDACTED]')
    }
    $content = [regex]::Replace(
        $content,
        '[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]',
        '?'
    )
    $prohibitedPattern = '(?i)(' +
        'authorization\s*["'']?\s*:\s*(?:bearer|basic)|' +
        '(?:api[_ -]?key|client[_ -]?secret|access[_ -]?token|password|' +
            'credential|secret|token|private[_ -]?key)\b|' +
        'reasoning[_-]?content|' +
        '-----BEGIN .*PRIVATE KEY-----|-----END .*PRIVATE KEY-----|' +
        '\b(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{12,}|' +
        '\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.' +
            '[A-Za-z0-9_-]{10,}\b|' +
        '\b(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\+srv)?|redis)' +
            '://[^/\s:@]+:[^@\s/]+@)'
    $allowedFailurePattern = '(?i)(' +
        'application run failed|caused by:|exception|error|failed|failure|' +
        'flyway|migration|sqlstate|permission denied|access denied|' +
        'checksum|schema history|database .* unavailable|unable to|' +
        'unsupported database|validate failed)'
    $safeLines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in ($content -split '\r?\n')) {
        $candidate = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if ($candidate -match $prohibitedPattern -or
            $candidate -match
                '(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_+/=-]{40,}' +
                    '(?![A-Za-z0-9_+/=-])') {
            $safeLines.Add('[redacted prohibited line]')
            continue
        }
        if ($candidate -match $allowedFailurePattern) {
            $safeLines.Add($candidate)
        }
    }
    if ($safeLines.Count -eq 0) {
        return '[log] diagnostic suppressed; no allowlisted failure lines'
    }
    $safeContent = $safeLines -join ' | '
    if ($safeContent.Length -gt 8186) {
        $safeContent = $safeContent.Substring($safeContent.Length - 8186)
    }
    return '[log] ' + $safeContent
}

function Resolve-CrossServiceApplicationPath {
    param([Parameter(Mandatory = $true)][string]$Executable)

    return @(
        Get-Command $Executable -CommandType Application -ErrorAction Stop
    )[0].Path
}

function Stop-CrossServiceOwnedProcessTree {
    param(
        [Parameter(Mandatory = $true)][Diagnostics.Process]$Process,
        $WindowsJob
    )

    $terminationFailures = New-Object `
        'System.Collections.Generic.List[Exception]'
    if ($null -ne $WindowsJob) {
        try {
            $WindowsJob.Dispose()
        }
        catch {
            $terminationFailures.Add($_.Exception)
        }
    }
    if (Test-CrossServiceWindows) {
        try {
            if (-not $Process.HasExited) {
                $Process.Kill($true)
            }
        }
        catch {
            $terminationFailures.Add($_.Exception)
        }
    }
    if (-not $Process.HasExited -and -not $Process.WaitForExit(5000)) {
        $terminationFailures.Add([TimeoutException]::new(
            'Cross-service native process termination exceeded its bound.'
        ))
        try {
            $Process.Kill($true)
            if (-not $Process.WaitForExit(5000)) {
                $terminationFailures.Add([TimeoutException]::new(
                    'Cross-service forced process termination exceeded its bound.'
                ))
            }
        }
        catch {
            $terminationFailures.Add($_.Exception)
        }
    }
    if ($terminationFailures.Count -gt 0) {
        throw [AggregateException]::new(
            'Cross-service native process termination failed.',
            $terminationFailures.ToArray()
        )
    }
}

function Test-CrossServiceSupervisorReservedEnvironment {
    param([Parameter(Mandatory = $true)][string]$Name)

    return $Name -match (
        '^(?i:LD_.*|DYLD_.*|GCONV_PATH|DOTNET_STARTUP_HOOKS|' +
            'DOTNET_ADDITIONAL_DEPS|DOTNET_SHARED_STORE|COREHOST_TRACEFILE|' +
            'COMPlus_.*)$'
    )
}

function Write-CrossServiceAtomicControlFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $temporaryPath = "$Path.$PID.tmp"
    try {
        [IO.File]::WriteAllText(
            $temporaryPath,
            $Value,
            [Text.UTF8Encoding]::new($false)
        )
        [IO.File]::Move($temporaryPath, $Path)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Get-CrossServiceControlDirectory {
    param(
        [string]$ConfiguredPath,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    $candidate = $ConfiguredPath
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = $env:OPS_CACHE_ROOT
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = [IO.Path]::GetTempPath()
    }
    if (-not [IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $WorkingDirectory $candidate
    }
    $resolved = [IO.Path]::GetFullPath($candidate)
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw [IO.DirectoryNotFoundException]::new(
            'Cross-service process control directory does not exist.'
        )
    }
    $currentPath = $resolved
    while (-not [string]::IsNullOrWhiteSpace($currentPath)) {
        if (Test-Path -LiteralPath $currentPath) {
            $item = Get-Item -LiteralPath $currentPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne
                0) {
                throw [IO.IOException]::new(
                    'Cross-service process control path contains a reparse point.'
                )
            }
        }
        $parentPath = [IO.Path]::GetDirectoryName($currentPath)
        if ([string]::IsNullOrWhiteSpace($parentPath) -or
            $parentPath -eq $currentPath) {
            break
        }
        $currentPath = $parentPath
    }
    return $resolved
}

function Read-CrossServiceSupervisorStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ControlNonce,
        [Parameter(Mandatory = $true)][string]$GateNonce
    )

    $status = [IO.File]::ReadAllText($Path).Trim()
    if ($status -match '^exit:(-?\d{1,10}):([a-f0-9]{32})$') {
        if ($Matches[2] -cne $ControlNonce) {
            throw [FormatException]::new(
                'Cross-service supervisor exit status is unauthenticated.'
            )
        }
        $exitCode = 0
        if (-not [int]::TryParse($Matches[1], [ref]$exitCode)) {
            throw [FormatException]::new(
                'Cross-service supervisor exit status is invalid.'
            )
        }
        return [pscustomobject]@{
            ExitCode = $exitCode
            FailureType = $null
            CleanupFailureTypes = @()
        }
    }
    if ($status -match
        '^failure:([A-Za-z][A-Za-z0-9]{0,63}):(None|[A-Za-z][A-Za-z0-9]{0,63}(?:,[A-Za-z][A-Za-z0-9]{0,63}){0,3}):([a-f0-9]{32})$') {
        $primaryFailureType = $Matches[1]
        $cleanupFailureText = $Matches[2]
        $statusNonce = $Matches[3]
        if ($statusNonce -cne $ControlNonce -and
            $statusNonce -cne $GateNonce) {
            throw [FormatException]::new(
                'Cross-service supervisor failure status is unauthenticated.'
            )
        }
        return [pscustomobject]@{
            ExitCode = $null
            FailureType = $primaryFailureType
            CleanupFailureTypes = if ($cleanupFailureText -ceq 'None') {
                @()
            }
            else {
                @($cleanupFailureText -split ',')
            }
        }
    }
    throw [FormatException]::new(
        'Cross-service supervisor status is invalid.'
    )
}

function Add-CrossServiceSupervisorCleanupFailures {
    param(
        [Parameter(Mandatory = $true)]$Status,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()]
        [System.Collections.Generic.List[Exception]]$CleanupFailures
    )

    foreach ($failureType in @($Status.CleanupFailureTypes)) {
        $CleanupFailures.Add([InvalidOperationException]::new(
            'Cross-service process supervisor reported a late cleanup ' +
                "failure: $failureType."
        ))
    }
}

function Invoke-CrossServiceNativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][hashtable]$Environment,
        [Parameter(Mandatory = $true)][IO.Stream]$StandardOutput,
        [Parameter(Mandatory = $true)][IO.Stream]$StandardError,
        $StandardInputText = $null,
        [ValidateRange(1, 600)][int]$DrainTimeoutSeconds = 30,
        [ValidateRange(1, 5400)][int]$ExecutionTimeoutSeconds = 900,
        [string]$ControlDirectory
    )

    $resolvedExecutable = Resolve-CrossServiceApplicationPath `
        -Executable $Executable
    $resolvedWorkingDirectory = [IO.Path]::GetFullPath($WorkingDirectory)
    if (-not (Test-Path -LiteralPath $resolvedWorkingDirectory `
        -PathType Container)) {
        throw [IO.DirectoryNotFoundException]::new(
            'Cross-service process working directory does not exist.'
        )
    }
    $controlRoot = Get-CrossServiceControlDirectory `
        -ConfiguredPath $ControlDirectory `
        -WorkingDirectory $resolvedWorkingDirectory
    $controlId = [guid]::NewGuid().ToString('N')
    $descriptorPath = Join-Path $controlRoot "process-$controlId.json"
    $gatePath = Join-Path $controlRoot "process-$controlId.gate"
    $startedPath = Join-Path $controlRoot "process-$controlId.started"
    $statusPath = Join-Path $controlRoot "process-$controlId.status"
    $supervisorPath = Join-Path $PSScriptRoot `
        'cross-service-native-process-supervisor.ps1'
    $sessionPath = Join-Path $PSScriptRoot `
        'cross-service-native-process-session.sh'
    foreach ($requiredPath in @($supervisorPath, $sessionPath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw [IO.FileNotFoundException]::new(
                'Cross-service process supervisor is unavailable.'
            )
        }
    }
    $pwshExecutable = [Environment]::ProcessPath
    if ([string]::IsNullOrWhiteSpace($pwshExecutable) -or
        -not (Test-Path -LiteralPath $pwshExecutable -PathType Leaf)) {
        throw [IO.FileNotFoundException]::new(
            'Cross-service PowerShell host is unavailable.'
        )
    }

    $gateNonce = [guid]::NewGuid().ToString('N')
    $controlNonce = [guid]::NewGuid().ToString('N')
    $descriptor = [ordered]@{
        executable = $resolvedExecutable
        workingDirectory = $resolvedWorkingDirectory
        arguments = @($Arguments | ForEach-Object { [string]$_ })
        gateNonce = $gateNonce
    } | ConvertTo-Json -Depth 4 -Compress
    $environmentEntries = @(
        $Environment.Keys |
            Sort-Object |
            ForEach-Object {
                $name = [string]$_
                if ([string]::IsNullOrWhiteSpace($name) -or
                    $name -match '[=\x00]') {
                    throw [ArgumentException]::new(
                        'Cross-service target environment name is invalid.'
                    )
                }
                $value = if ($null -eq $Environment[$_]) {
                    $null
                }
                else {
                    [string]$Environment[$_]
                }
                if ($null -ne $value -and $value.Length -gt 32768) {
                    throw [ArgumentException]::new(
                        'Cross-service target environment value is invalid.'
                    )
                }
                [ordered]@{
                    name = $name
                    value = $value
                }
            }
    )
    $transportPayload = [ordered]@{
        controlNonce = $controlNonce
        environment = $environmentEntries
        standardInput = if ($null -eq $StandardInputText) {
            $null
        }
        else {
            [string]$StandardInputText
        }
    } | ConvertTo-Json -Depth 5 -Compress
    $transportBytes = [Text.UTF8Encoding]::new(
        $false,
        $true
    ).GetBytes($transportPayload)
    if ($transportBytes.Length -gt 16777216) {
        throw [ArgumentException]::new(
            'Cross-service supervisor transport exceeds its size bound.'
        )
    }
    $transportFrame = New-Object byte[] ($transportBytes.Length + 4)
    $transportFrame[0] = [byte](
        ($transportBytes.Length -shr 24) -band 0xff
    )
    $transportFrame[1] = [byte](
        ($transportBytes.Length -shr 16) -band 0xff
    )
    $transportFrame[2] = [byte](
        ($transportBytes.Length -shr 8) -band 0xff
    )
    $transportFrame[3] = [byte]($transportBytes.Length -band 0xff)
    [Array]::Copy(
        $transportBytes,
        0,
        $transportFrame,
        4,
        $transportBytes.Length
    )
    $supervisorArguments = @(
        '-NoLogo',
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $supervisorPath,
        '-DescriptorPath',
        $descriptorPath,
        '-GatePath',
        $gatePath,
        '-StartedPath',
        $startedPath,
        '-StatusPath',
        $statusPath,
        '-DrainTimeoutSeconds',
        "$DrainTimeoutSeconds"
    )

    $setsidExecutable = $null
    $shExecutable = $null
    $killExecutable = $null
    $sleepExecutable = $null
    if (-not (Test-CrossServiceWindows)) {
        $setsidExecutable = Resolve-CrossServiceApplicationPath `
            -Executable 'setsid'
        $shExecutable = Resolve-CrossServiceApplicationPath `
            -Executable 'sh'
        $killExecutable = Resolve-CrossServiceApplicationPath `
            -Executable 'kill'
        $sleepExecutable = Resolve-CrossServiceApplicationPath `
            -Executable 'sleep'
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = if ($null -eq $setsidExecutable) {
        $pwshExecutable
    }
    else {
        $setsidExecutable
    }
    $startInfo.WorkingDirectory = $resolvedWorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    if ($null -ne $setsidExecutable) {
        [void]$startInfo.ArgumentList.Add('--wait')
        [void]$startInfo.ArgumentList.Add('--')
        [void]$startInfo.ArgumentList.Add($shExecutable)
        [void]$startInfo.ArgumentList.Add($sessionPath)
        [void]$startInfo.ArgumentList.Add($killExecutable)
        [void]$startInfo.ArgumentList.Add($sleepExecutable)
        [void]$startInfo.ArgumentList.Add($pwshExecutable)
    }
    foreach ($argument in $supervisorArguments) {
        [void]$startInfo.ArgumentList.Add([string]$argument)
    }
    foreach ($inheritedName in @($startInfo.Environment.Keys)) {
        if (Test-CrossServiceSupervisorReservedEnvironment `
            -Name ([string]$inheritedName)) {
            [void]$startInfo.Environment.Remove([string]$inheritedName)
        }
    }
    foreach ($name in $Environment.Keys) {
        $variableName = [string]$name
        if (Test-CrossServiceSupervisorReservedEnvironment `
            -Name $variableName) {
            throw [InvalidOperationException]::new(
                'Cross-service target environment contains a ' +
                    'supervisor-reserved variable.'
            )
        }
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $started = $false
    $windowsJob = $null
    $copyTasks = $null
    $inputTask = $null
    $inputDelivered = $false
    $inputClosed = $false
    $inputCleanupWaited = $false
    $inputCleanupDisposeRequired = $false
    $statusConsumed = $false
    $supervisorStopped = $false
    $operationFailure = $null
    $resultCode = $null
    $cleanupFailures = New-Object `
        'System.Collections.Generic.List[Exception]'
    try {
        Write-CrossServiceAtomicControlFile -Path $descriptorPath `
            -Value $descriptor
        $windowsJob = New-CrossServiceWindowsJob
        if (-not $process.Start()) {
            throw [InvalidOperationException]::new(
                'Cross-service process supervisor did not start.'
            )
        }
        $started = $true
        if ($null -ne $windowsJob) {
            $windowsJob.Assign($process.Handle)
        }
        $copyTaskList = New-Object `
            'System.Collections.Generic.List[Threading.Tasks.Task]'
        try {
            $copyTaskList.Add(
                $process.StandardOutput.BaseStream.CopyToAsync($StandardOutput)
            )
            $copyTaskList.Add(
                $process.StandardError.BaseStream.CopyToAsync($StandardError)
            )
        }
        catch {
            $copyTasks = $copyTaskList.ToArray()
            throw [IO.IOException]::new(
                'Cross-service native output capture failed to start.',
                $_.Exception
            )
        }
        $copyTasks = $copyTaskList.ToArray()
        Write-CrossServiceAtomicControlFile -Path $gatePath `
            -Value $gateNonce
        $inputTask = $process.StandardInput.BaseStream.WriteAsync(
            $transportFrame,
            0,
            $transportFrame.Length
        )
        $startupDeadline = [DateTime]::UtcNow.AddSeconds(30)
        $targetStarted = $false
        $executionDeadline = $null
        while ($true) {
            if ($null -ne $inputTask -and -not $inputDelivered -and
                $inputTask.IsCompleted) {
                [void]$inputTask.GetAwaiter().GetResult()
                $inputDelivered = $true
            }

            foreach ($copyTask in $copyTasks) {
                if ($copyTask.IsFaulted) {
                    throw [IO.IOException]::new(
                        'Cross-service native output capture failed.',
                        $copyTask.Exception.GetBaseException()
                    )
                }
                if ($copyTask.IsCanceled) {
                    throw [IO.IOException]::new(
                        'Cross-service native output capture was canceled.'
                    )
                }
            }

            if (-not $targetStarted -and
                (Test-Path -LiteralPath $startedPath -PathType Leaf)) {
                if ([IO.File]::ReadAllText($startedPath) -ne $controlNonce) {
                    throw [InvalidOperationException]::new(
                        'Cross-service process supervisor start marker is invalid.'
                    )
                }
                $targetStarted = $true
                $executionDeadline = [DateTime]::UtcNow.AddSeconds(
                    $ExecutionTimeoutSeconds
                )
            }
            if (Test-Path -LiteralPath $statusPath -PathType Leaf) {
                $status = Read-CrossServiceSupervisorStatus -Path $statusPath `
                    -ControlNonce $controlNonce -GateNonce $gateNonce
                $statusConsumed = $true
                if ($null -ne $status.FailureType) {
                    $cleanupFailureTypes = @($status.CleanupFailureTypes)
                    $failureMessage = if (
                        $status.FailureType -cne 'None' -and
                        $cleanupFailureTypes.Count -eq 0
                    ) {
                        'Cross-service process supervisor reported a bounded ' +
                            "$($status.FailureType) failure."
                    }
                    elseif ($status.FailureType -ceq 'None') {
                        'Cross-service process supervisor reported bounded ' +
                            'cleanup failures: ' +
                            ($cleanupFailureTypes -join ', ') + '.'
                    }
                    else {
                        'Cross-service process supervisor reported a bounded ' +
                            "$($status.FailureType) failure with cleanup " +
                            'failures: ' +
                            ($cleanupFailureTypes -join ', ') + '.'
                    }
                    throw [InvalidOperationException]::new($failureMessage)
                }
                $resultCode = [int]$status.ExitCode
                break
            }
            if ($process.HasExited) {
                throw [IO.IOException]::new(
                    'Cross-service process supervisor exited before status publication.'
                )
            }
            if (-not $targetStarted -and
                [DateTime]::UtcNow -ge $startupDeadline) {
                throw [TimeoutException]::new(
                    'Cross-service process supervisor exceeded its startup bound.'
                )
            }
            if ($targetStarted -and
                [DateTime]::UtcNow -ge $executionDeadline) {
                throw [TimeoutException]::new(
                    'Cross-service native process exceeded its execution bound.'
                )
            }

            Start-Sleep -Milliseconds 20
        }
    }
    catch {
        $operationFailure = $_.Exception
    }
    finally {
        $drainMilliseconds = [Math]::Min(
            [int]::MaxValue,
            $DrainTimeoutSeconds * 1000
        )
        $inputCleanupMilliseconds = [Math]::Min(
            $drainMilliseconds,
            5000
        )
        if ($started -and $null -ne $inputTask -and
            -not $inputTask.IsCompleted) {
            try {
                Stop-CrossServiceOwnedProcessTree -Process $process `
                    -WindowsJob $windowsJob
                $supervisorStopped = $true
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
            finally {
                $windowsJob = $null
            }
        }
        if ($started -and -not $inputClosed) {
            try {
                if ($null -ne $inputTask -and -not $inputTask.IsCompleted -and
                    -not $inputTask.Wait($inputCleanupMilliseconds)) {
                    $inputCleanupWaited = $true
                    $inputCleanupDisposeRequired = $true
                    $cleanupFailures.Add([TimeoutException]::new(
                        'Cross-service input cleanup exceeded its bound.'
                    ))
                }
                elseif ($null -ne $inputTask -and
                    -not $inputTask.IsCompleted) {
                    $inputCleanupWaited = $true
                }
                if ($null -eq $inputTask -or $inputTask.IsCompleted) {
                    $process.StandardInput.Close()
                    $inputClosed = $true
                }
            }
            catch {
                $inputCleanupWaited = $true
                $inputCleanupDisposeRequired = $true
                $cleanupFailures.Add($_.Exception)
            }
        }
        if ($started -and -not $supervisorStopped) {
            try {
                Stop-CrossServiceOwnedProcessTree -Process $process `
                    -WindowsJob $windowsJob
                $supervisorStopped = $true
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
            finally {
                $windowsJob = $null
            }
        }
        elseif ($null -ne $windowsJob) {
            try {
                $windowsJob.Dispose()
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
            finally {
                $windowsJob = $null
            }
        }
        if (-not $statusConsumed -and
            (Test-Path -LiteralPath $statusPath -PathType Leaf)) {
            try {
                $lateStatus = Read-CrossServiceSupervisorStatus `
                    -Path $statusPath -ControlNonce $controlNonce `
                    -GateNonce $gateNonce
                $statusConsumed = $true
                Add-CrossServiceSupervisorCleanupFailures `
                    -Status $lateStatus -CleanupFailures $cleanupFailures
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
        }
        if ($null -ne $inputTask) {
            try {
                if (-not $inputTask.IsCompleted -and
                    -not $inputCleanupWaited -and
                    -not $inputTask.Wait($inputCleanupMilliseconds)) {
                    $inputCleanupWaited = $true
                    $inputCleanupDisposeRequired = $true
                    $cleanupFailures.Add([TimeoutException]::new(
                        'Cross-service input cleanup exceeded its bound.'
                    ))
                }
                elseif ($inputTask.IsCompleted) {
                    [void]$inputTask.GetAwaiter().GetResult()
                }
            }
            catch {
                $inputFailure = $_.Exception.GetBaseException()
                $inputCleanupDisposeRequired = $true
                $operationBaseFailure = if ($null -eq $operationFailure) {
                    $null
                }
                else {
                    $operationFailure.GetBaseException()
                }
                if ($null -eq $operationBaseFailure -or
                    -not [object]::ReferenceEquals(
                        $inputFailure,
                        $operationBaseFailure
                    )) {
                    $cleanupFailures.Add($inputFailure)
                }
            }
        }
        if ($inputCleanupDisposeRequired -and -not $inputClosed) {
            try {
                $process.StandardInput.BaseStream.Dispose()
                $inputClosed = $true
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
        }
        if ($null -ne $copyTasks) {
            try {
                $remainingDrain = [Threading.Tasks.Task]::WhenAll($copyTasks)
                if (-not $remainingDrain.Wait($drainMilliseconds)) {
                    $cleanupFailures.Add([TimeoutException]::new(
                        'Cross-service output cleanup exceeded its bound.'
                    ))
                }
            }
            catch {
                $copyFailure = $_.Exception.GetBaseException()
                $operationBaseFailure = if ($null -eq $operationFailure) {
                    $null
                }
                else {
                    $operationFailure.GetBaseException()
                }
                if ($null -eq $operationBaseFailure -or
                    -not [object]::ReferenceEquals(
                        $copyFailure,
                        $operationBaseFailure
                    )) {
                    $cleanupFailures.Add($copyFailure)
                }
            }
        }
        try {
            $process.Dispose()
        }
        catch {
            $cleanupFailures.Add($_.Exception)
        }
        foreach ($controlPath in @(
            $descriptorPath,
            $gatePath,
            $startedPath,
            $statusPath
        )) {
            try {
                if (Test-Path -LiteralPath $controlPath -PathType Leaf) {
                    Remove-Item -LiteralPath $controlPath -Force
                }
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
        }
        if ($null -eq $operationFailure -and $cleanupFailures.Count -eq 0) {
            try {
                $StandardOutput.Flush()
                $StandardError.Flush()
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
        }
    }

    if ($null -ne $operationFailure) {
        if ($cleanupFailures.Count -gt 0) {
            $failures = New-Object 'System.Collections.Generic.List[Exception]'
            $failures.Add($operationFailure)
            foreach ($cleanupFailure in $cleanupFailures) {
                $failures.Add($cleanupFailure)
            }
            throw [AggregateException]::new(
                'Cross-service native process and cleanup both failed.',
                $failures.ToArray()
            )
        }
        throw $operationFailure
    }
    if ($cleanupFailures.Count -gt 0) {
        throw [AggregateException]::new(
            'Cross-service native process cleanup failed.',
            $cleanupFailures.ToArray()
        )
    }
    return $resultCode
}

function Invoke-CrossServiceProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath,
        [Parameter(Mandatory = $true)][hashtable]$Environment
    )

    $operation = [IO.Path]::GetFileNameWithoutExtension($StderrPath)
    try {
        foreach ($logPath in @($StdoutPath, $StderrPath)) {
            $logParent = [IO.Path]::GetDirectoryName(
                [IO.Path]::GetFullPath($logPath)
            )
            if (-not [IO.Directory]::Exists($logParent)) {
                throw [IO.DirectoryNotFoundException]::new(
                    'Cross-service process log parent does not exist.'
                )
            }
        }
        $stdoutStream = $null
        $stderrStream = $null
        try {
            $stdoutStream = [IO.File]::Open(
                $StdoutPath,
                [IO.FileMode]::Create,
                [IO.FileAccess]::Write,
                [IO.FileShare]::Read
            )
            $stderrStream = [IO.File]::Open(
                $StderrPath,
                [IO.FileMode]::Create,
                [IO.FileAccess]::Write,
                [IO.FileShare]::Read
            )
            $controlDirectory = [IO.Path]::GetDirectoryName(
                [IO.Path]::GetFullPath($StderrPath)
            )
            $exitCode = Invoke-CrossServiceNativeCapture -Executable $Executable `
                -Arguments $Arguments -WorkingDirectory $WorkingDirectory `
                -Environment $Environment -StandardOutput $stdoutStream `
                -StandardError $stderrStream `
                -ControlDirectory $controlDirectory
        }
        finally {
            if ($null -ne $stderrStream) {
                $stderrStream.Dispose()
            }
            if ($null -ne $stdoutStream) {
                $stdoutStream.Dispose()
            }
        }
    }
    catch {
        $failure = $_.Exception
        throw [InvalidOperationException]::new(
            "Cross-service command '$operation' failed before native exit code " +
                "capture ($($failure.GetType().Name)).",
            $failure
        )
    }
    if ($exitCode -ne 0) {
        try {
            foreach ($diagnosticPath in @($StdoutPath, $StderrPath)) {
                $diagnostic = Get-CrossServiceRedactedLogTail `
                    -Path $diagnosticPath -Environment $Environment
                if (-not [string]::IsNullOrWhiteSpace($diagnostic)) {
                    $diagnosticName = [IO.Path]::GetFileName($diagnosticPath)
                    Write-Warning (
                        "Redacted tail for '$diagnosticName' (max 8192 chars):`n" +
                        $diagnostic
                    ) -WarningAction Continue
                }
            }
        }
        catch {
            # Diagnostics are best-effort and must never replace the native failure.
        }
        throw "Cross-service command '$operation' failed with exit code $exitCode."
    }
}

function Invoke-CrossServiceNativeQuiet {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    try {
        $exitCode = Invoke-CrossServiceNativeCapture -Executable $Executable `
            -Arguments $Arguments -WorkingDirectory $PWD.ProviderPath `
            -Environment @{} -StandardOutput ([IO.Stream]::Null) `
            -StandardError ([IO.Stream]::Null)
    }
    catch {
        throw $FailureMessage
    }
    if ($exitCode -ne 0) {
        throw $FailureMessage
    }
}

function Wait-CrossServiceTcp {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 60
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Managed process exited before TCP port $Port became ready."
        }
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $client.ConnectAsync([Net.IPAddress]::Loopback, $Port)
            if ($pending.Wait(500) -and $client.Connected) { return }
        }
        catch {
            # Retry until the bounded deadline.
        }
        finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 250
    }
    throw "TCP port $Port did not become ready within $TimeoutSeconds seconds."
}

function Wait-CrossServiceHttp {
    param(
        [Parameter(Mandatory = $true)][uri]$Uri,
        [Parameter(Mandatory = $true)][Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 90
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Managed process exited before $Uri became ready."
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        }
        catch {
            # Retry until the bounded deadline.
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Uri did not become ready within $TimeoutSeconds seconds."
}

function Invoke-CrossServiceSql {
    param(
        [Parameter(Mandatory = $true)][string]$DockerPath,
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $capturedOutput = New-Object IO.MemoryStream
    $capturedError = New-Object IO.MemoryStream
    try {
        $exitCode = Invoke-CrossServiceNativeCapture -Executable $DockerPath -Arguments @(
            'exec', '-i', $ContainerName, 'psql', '--no-password',
            '--set=ON_ERROR_STOP=1', '--username', 'opsmind_migrator',
            '--dbname', 'opsmind'
        ) -WorkingDirectory $PWD.ProviderPath -Environment @{} `
            -StandardOutput $capturedOutput -StandardError $capturedError `
            -StandardInputText $Sql
        $output = [Text.Encoding]::UTF8.GetString($capturedOutput.ToArray()) +
            [Text.Encoding]::UTF8.GetString($capturedError.ToArray())
    }
    finally {
        $capturedError.Dispose()
        $capturedOutput.Dispose()
    }
    if ($exitCode -ne 0) {
        throw 'Cross-service SQL command failed.'
    }
    return @($output -split '\r?\n')
}

function Invoke-CrossServiceSqlFile {
    param(
        [Parameter(Mandatory = $true)][string]$DockerPath,
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [Parameter(Mandatory = $true)][string]$DatabaseUser,
        [Parameter(Mandatory = $true)][string]$SqlPath,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath,
        [hashtable]$Variables = @{}
    )

    if (-not (Test-Path -LiteralPath $SqlPath -PathType Leaf)) {
        throw "Cross-service SQL file is missing: $SqlPath"
    }
    if ($DatabaseUser -notmatch '^opsmind_[a-z_]+$') {
        throw 'Cross-service SQL database user is invalid.'
    }

    $arguments = New-Object 'System.Collections.Generic.List[string]'
    foreach ($value in @(
        'exec', '-i', $ContainerName, 'psql', '--no-password',
        '--set=ON_ERROR_STOP=1', '--quiet', '--username', $DatabaseUser,
        '--dbname', 'opsmind', '--file=-'
    )) {
        $arguments.Add($value)
    }
    foreach ($name in @($Variables.Keys | Sort-Object)) {
        $value = [string]$Variables[$name]
        if ($name -notmatch '^[a-z][a-z0-9_]{0,63}$' -or
            $value -notmatch '^[A-Za-z0-9][A-Za-z0-9_.:/@-]{0,511}$') {
            throw 'Cross-service SQL variable is invalid.'
        }
        $arguments.Add("--set=$name=$value")
    }

    $startArguments = @{
        FilePath = $DockerPath
        ArgumentList = $arguments.ToArray()
        PassThru = $true
        Wait = $true
        RedirectStandardInput = $SqlPath
        RedirectStandardOutput = $StdoutPath
        RedirectStandardError = $StderrPath
    }
    if (Test-CrossServiceWindows) {
        $startArguments.WindowStyle = 'Hidden'
    }
    $process = Start-Process @startArguments
    if ($process.ExitCode -ne 0) {
        throw "Cross-service SQL file failed as $DatabaseUser."
    }
}

function Assert-CrossServiceManagedPath {
    param(
        [Parameter(Mandatory = $true)][string]$NodePath,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$ManagedRoot,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath
    )

    Invoke-CrossServiceProcess -Executable $NodePath -Arguments @(
        (Join-CrossServicePath -BasePath $RepositoryRoot -ChildPath @(
            'scripts', 'validation', 'cross-service', 'manage-evaluation-files.mjs'
        )),
        'prepare',
        '--managed-root', $ManagedRoot,
        '--path', $Path
    ) -WorkingDirectory $RepositoryRoot -StdoutPath $StdoutPath `
        -StderrPath $StderrPath -Environment @{}
}

function Assert-CrossServiceNoReparseAncestors {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$CandidatePath
    )

    $comparison = Get-CrossServicePathComparison
    $repository = [IO.Path]::GetFullPath($RepositoryRoot)
    $candidate = [IO.Path]::GetFullPath($CandidatePath)
    if (-not $candidate.Equals($repository, $comparison) -and
        -not $candidate.StartsWith(
            $repository + [IO.Path]::DirectorySeparatorChar,
            $comparison
        )) {
        throw 'Cross-service managed path is outside the repository.'
    }

    $current = $candidate
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Cross-service managed path contains a reparse ancestor: $current"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or
            $parent.Equals($current, $comparison)) {
            break
        }
        $current = $parent
    }
}
