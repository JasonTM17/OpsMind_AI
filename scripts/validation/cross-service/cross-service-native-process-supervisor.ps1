[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DescriptorPath,
    [Parameter(Mandatory = $true)][string]$GatePath,
    [Parameter(Mandatory = $true)][string]$StartedPath,
    [Parameter(Mandatory = $true)][string]$StatusPath,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 600)]
    [int]$DrainTimeoutSeconds
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Initialize-LinuxSupervisorOwnership {
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        return
    }
    if ($null -eq ('OpsMind.Validation.LinuxSubreaper' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace OpsMind.Validation
{
    public static class LinuxSubreaper
    {
        private const int PrSetChildSubreaper = 36;
        private const int SigKill = 9;
        private const int NoHang = 1;
        private const int NoSuchProcess = 3;
        private const int NoChild = 10;
        private const long PidfdSendSignalSystemCall = 424;
        private const long PidfdOpenSystemCall = 434;

        [DllImport("libc", SetLastError = true)]
        private static extern int prctl(
            int option,
            ulong argument2,
            ulong argument3,
            ulong argument4,
            ulong argument5);

        [DllImport("libc", EntryPoint = "syscall", SetLastError = true)]
        private static extern long pidfd_open(
            long systemCall,
            int processId,
            uint flags);

        [DllImport("libc", EntryPoint = "syscall", SetLastError = true)]
        private static extern long pidfd_send_signal(
            long systemCall,
            int pidfd,
            int signal,
            IntPtr signalInformation,
            uint flags);

        [DllImport("libc", SetLastError = true)]
        private static extern int close(int fileDescriptor);

        [DllImport("libc", SetLastError = true)]
        private static extern int waitpid(
            int processId,
            IntPtr status,
            int options);

        public static void Enable()
        {
            Architecture architecture = RuntimeInformation.ProcessArchitecture;
            if (architecture != Architecture.X64 &&
                architecture != Architecture.Arm64)
            {
                throw new PlatformNotSupportedException(
                    "Linux pidfd supervision requires x64 or arm64.");
            }
            int selfPidfd = OpenProcess(Environment.ProcessId);
            if (selfPidfd < 0)
            {
                throw new PlatformNotSupportedException(
                    "Linux pidfd supervision is unavailable.");
            }
            CloseProcess(selfPidfd);
            if (prctl(PrSetChildSubreaper, 1, 0, 0, 0) != 0)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
        }

        public static int OpenProcess(int processId)
        {
            long result = pidfd_open(PidfdOpenSystemCall, processId, 0);
            if (result >= 0)
            {
                return checked((int)result);
            }
            int error = Marshal.GetLastWin32Error();
            if (error == NoSuchProcess)
            {
                return -1;
            }
            throw new Win32Exception(error);
        }

        public static void KillProcess(int pidfd)
        {
            if (pidfd_send_signal(
                PidfdSendSignalSystemCall,
                pidfd,
                SigKill,
                IntPtr.Zero,
                0) < 0)
            {
                int error = Marshal.GetLastWin32Error();
                if (error != NoSuchProcess)
                {
                    throw new Win32Exception(error);
                }
            }
        }

        public static void CloseProcess(int pidfd)
        {
            if (pidfd >= 0 && close(pidfd) != 0)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
        }

        public static void Reap()
        {
            while (true)
            {
                int result = waitpid(-1, IntPtr.Zero, NoHang);
                if (result > 0)
                {
                    continue;
                }
                if (result == 0)
                {
                    return;
                }
                int error = Marshal.GetLastWin32Error();
                if (error == NoChild)
                {
                    return;
                }
                throw new Win32Exception(error);
            }
        }
    }
}
'@
    }
    [OpsMind.Validation.LinuxSubreaper]::Enable()
}

function Get-LinuxProcessIdentity {
    param([Parameter(Mandatory = $true)][int]$ProcessId)

    try {
        $stat = [IO.File]::ReadAllText("/proc/$ProcessId/stat")
        $commandEnd = $stat.LastIndexOf(') ')
        if ($commandEnd -lt 0) {
            return $null
        }
        $fields = $stat.Substring($commandEnd + 2) -split '\s+'
        if ($fields.Count -lt 20 -or
            $fields[1] -notmatch '^\d+$' -or
            $fields[19] -notmatch '^\d+$') {
            return $null
        }
        return [pscustomobject]@{
            ProcessId = $ProcessId
            ParentProcessId = [int]$fields[1]
            StartTime = [uint64]$fields[19]
        }
    }
    catch [UnauthorizedAccessException] {
        throw
    }
    catch [IO.IOException] {
        if (-not (Test-Path -LiteralPath "/proc/$ProcessId/stat" `
            -PathType Leaf)) {
            return $null
        }
        throw
    }
    catch {
        throw
    }
}

function Get-LinuxPidfdProcessId {
    param([Parameter(Mandatory = $true)][int]$Pidfd)

    try {
        $fdInfo = [IO.File]::ReadAllText("/proc/self/fdinfo/$Pidfd")
        if ($fdInfo -match '(?m)^Pid:\s+(\d+)$') {
            return [int]$Matches[1]
        }
        return $null
    }
    catch [UnauthorizedAccessException] {
        throw
    }
    catch [IO.IOException] {
        if (-not (Test-Path -LiteralPath "/proc/self/fdinfo/$Pidfd" `
            -PathType Leaf)) {
            return $null
        }
        throw
    }
    catch {
        throw
    }
}

function Test-LinuxSupervisorDescendant {
    param([Parameter(Mandatory = $true)]$Identity)

    $seen = New-Object 'System.Collections.Generic.HashSet[int]'
    $current = $Identity
    for ($depth = 0; $depth -lt 512; $depth++) {
        $parentProcessId = [int]$current.ParentProcessId
        if ($parentProcessId -eq $PID) {
            return $true
        }
        if ($parentProcessId -le 1 -or
            -not $seen.Add($parentProcessId)) {
            return $false
        }
        $current = Get-LinuxProcessIdentity -ProcessId $parentProcessId
        if ($null -eq $current) {
            return $false
        }
    }
    return $false
}

function Get-LinuxSupervisorDescendants {
    $identitiesByProcess = @{}
    foreach ($directory in [IO.Directory]::EnumerateDirectories('/proc')) {
        $name = [IO.Path]::GetFileName($directory)
        if ($name -notmatch '^\d+$' -or [int]$name -eq $PID) {
            continue
        }
        $identity = Get-LinuxProcessIdentity -ProcessId ([int]$name)
        if ($null -ne $identity) {
            $identitiesByProcess[$identity.ProcessId] = $identity
        }
    }

    $candidates = New-Object 'System.Collections.Generic.List[object]'
    foreach ($identity in $identitiesByProcess.Values) {
        if (Test-LinuxSupervisorDescendant -Identity $identity) {
            [void]$candidates.Add($identity)
        }
    }

    $descendants = New-Object 'System.Collections.Generic.List[object]'
    $completed = $false
    try {
        foreach ($candidate in $candidates) {
            $pidfd = -1
            try {
                $pidfd = [OpsMind.Validation.LinuxSubreaper]::OpenProcess(
                    [int]$candidate.ProcessId
                )
                if ($pidfd -lt 0) {
                    continue
                }
                $currentIdentity = Get-LinuxProcessIdentity -ProcessId (
                    [int]$candidate.ProcessId
                )
                $pidfdProcessId = Get-LinuxPidfdProcessId -Pidfd $pidfd
                if ($null -eq $currentIdentity -or
                    $currentIdentity.StartTime -ne $candidate.StartTime -or
                    $pidfdProcessId -ne $candidate.ProcessId -or
                    -not (Test-LinuxSupervisorDescendant `
                        -Identity $currentIdentity)) {
                    continue
                }
                [void](Add-Member -InputObject $currentIdentity `
                    -MemberType NoteProperty -Name Pidfd -Value $pidfd)
                [void]$descendants.Add($currentIdentity)
                $pidfd = -1
            }
            finally {
                if ($pidfd -ge 0) {
                    [OpsMind.Validation.LinuxSubreaper]::CloseProcess($pidfd)
                }
            }
        }
        $completed = $true
        return @($descendants.ToArray())
    }
    finally {
        if (-not $completed) {
            foreach ($identity in $descendants) {
                try {
                    [OpsMind.Validation.LinuxSubreaper]::CloseProcess(
                        [int]$identity.Pidfd
                    )
                }
                catch {
                    # Preserve the original scan failure.
                }
            }
        }
    }
}

function Stop-LinuxSupervisorDescendant {
    param([Parameter(Mandatory = $true)]$Identity)

    $pidfd = [int]$Identity.Pidfd
    $pidfdProcessId = Get-LinuxPidfdProcessId -Pidfd $pidfd
    if ($null -ne $pidfdProcessId -and
        $pidfdProcessId -ne [int]$Identity.ProcessId) {
        [OpsMind.Validation.LinuxSubreaper]::CloseProcess($pidfd)
        return
    }
    try {
        [OpsMind.Validation.LinuxSubreaper]::KillProcess($pidfd)
    }
    finally {
        [OpsMind.Validation.LinuxSubreaper]::CloseProcess($pidfd)
    }
}

function Stop-LinuxSupervisorDescendants {
    param([switch]$OwnershipInitialized)

    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        return
    }
    if (-not $OwnershipInitialized) {
        return
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(3)
    $cleanupFailures = New-Object `
        'System.Collections.Generic.List[Exception]'
    do {
        $descendants = @(Get-LinuxSupervisorDescendants)
        if ($descendants.Count -eq 0) {
            try {
                [OpsMind.Validation.LinuxSubreaper]::Reap()
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
            if ($cleanupFailures.Count -gt 0) {
                throw [AggregateException]::new(
                    'Supervisor descendant cleanup failed.',
                    $cleanupFailures.ToArray()
                )
            }
            return
        }
        foreach ($descendant in $descendants) {
            try {
                Stop-LinuxSupervisorDescendant -Identity $descendant
            }
            catch {
                $cleanupFailures.Add($_.Exception)
            }
        }
        try {
            [OpsMind.Validation.LinuxSubreaper]::Reap()
        }
        catch {
            $cleanupFailures.Add($_.Exception)
        }
        Start-Sleep -Milliseconds 20
    } while ([DateTime]::UtcNow -lt $deadline)

    $remainingDescendants = @(Get-LinuxSupervisorDescendants)
    foreach ($remainingDescendant in $remainingDescendants) {
        try {
            [OpsMind.Validation.LinuxSubreaper]::CloseProcess(
                [int]$remainingDescendant.Pidfd
            )
        }
        catch {
            $cleanupFailures.Add($_.Exception)
        }
    }
    if ($remainingDescendants.Count -gt 0) {
        $cleanupFailures.Add([TimeoutException]::new(
            'Supervisor descendant cleanup expired.'
        ))
    }
    if ($cleanupFailures.Count -gt 0) {
        throw [AggregateException]::new(
            'Supervisor descendant cleanup failed.',
            $cleanupFailures.ToArray()
        )
    }
}

function Publish-SupervisorStatus {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value -notmatch (
        '^(?:exit:-?\d{1,10}|' +
            'failure:[A-Za-z][A-Za-z0-9]{0,63}:' +
            '(?:None|[A-Za-z][A-Za-z0-9]{0,63}' +
            '(?:,[A-Za-z][A-Za-z0-9]{0,63}){0,3})):[a-f0-9]{32}$'
    )) {
        throw [FormatException]::new('Supervisor status is invalid.')
    }
    $temporaryPath = "$StatusPath.$PID.tmp"
    try {
        [IO.File]::WriteAllText(
            $temporaryPath,
            $Value,
            [Text.UTF8Encoding]::new($false)
        )
        [IO.File]::Move($temporaryPath, $StatusPath)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Publish-SupervisorStarted {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value -notmatch '^[0-9a-f]{32}$') {
        throw [FormatException]::new('Supervisor started marker is invalid.')
    }
    $temporaryPath = "$StartedPath.$PID.tmp"
    try {
        [IO.File]::WriteAllText(
            $temporaryPath,
            $Value,
            [Text.UTF8Encoding]::new($false)
        )
        [IO.File]::Move($temporaryPath, $StartedPath)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Assert-SupervisorDescriptor {
    param([Parameter(Mandatory = $true)]$Descriptor)

    $propertyNames = @($Descriptor.PSObject.Properties.Name | Sort-Object)
    if (($propertyNames -join ',') -ne
        'arguments,executable,gateNonce,workingDirectory' -or
        -not [IO.Path]::IsPathRooted([string]$Descriptor.executable) -or
        -not (Test-Path -LiteralPath ([string]$Descriptor.executable) -PathType Leaf) -or
        -not [IO.Path]::IsPathRooted([string]$Descriptor.workingDirectory) -or
        -not (Test-Path -LiteralPath ([string]$Descriptor.workingDirectory) `
            -PathType Container) -or
        [string]$Descriptor.gateNonce -notmatch '^[0-9a-f]{32}$' -or
        $Descriptor.arguments -isnot [array] -or
        @($Descriptor.arguments).Count -gt 256) {
        throw [FormatException]::new('Supervisor descriptor is invalid.')
    }
    foreach ($argument in @($Descriptor.arguments)) {
        if ($argument -isnot [string] -or $argument.Length -gt 32768) {
            throw [FormatException]::new('Supervisor argument is invalid.')
        }
    }
}

function Assert-SupervisorTransport {
    param([Parameter(Mandatory = $true)]$Transport)

    $propertyNames = @($Transport.PSObject.Properties.Name | Sort-Object)
    if (($propertyNames -join ',') -ne
        'controlNonce,environment,standardInput' -or
        [string]$Transport.controlNonce -notmatch '^[a-f0-9]{32}$' -or
        $Transport.environment -isnot [array] -or
        @($Transport.environment).Count -gt 256 -or
        ($null -ne $Transport.standardInput -and
            $Transport.standardInput -isnot [string])) {
        throw [FormatException]::new('Supervisor transport is invalid.')
    }
    foreach ($entry in @($Transport.environment)) {
        $entryPropertyNames = @($entry.PSObject.Properties.Name | Sort-Object)
        if (($entryPropertyNames -join ',') -ne 'name,value' -or
            $entry.name -isnot [string] -or
            [string]::IsNullOrWhiteSpace([string]$entry.name) -or
            [string]$entry.name -match '[=\x00]' -or
            ($null -ne $entry.value -and $entry.value -isnot [string]) -or
            ($null -ne $entry.value -and $entry.value.Length -gt 32768)) {
            throw [FormatException]::new(
                'Supervisor environment entry is invalid.'
            )
        }
    }
}

function Read-SupervisorInputExact {
    param(
        [Parameter(Mandatory = $true)][IO.Stream]$Stream,
        [Parameter(Mandatory = $true)][ValidateRange(1, 16777216)]
        [int]$Length
    )

    $buffer = New-Object byte[] $Length
    $offset = 0
    while ($offset -lt $Length) {
        $read = $Stream.Read(
            $buffer,
            $offset,
            [Math]::Min(65536, $Length - $offset)
        )
        if ($read -le 0) {
            throw [EndOfStreamException]::new(
                'Supervisor transport ended before its declared length.'
            )
        }
        $offset += $read
    }
    return ,$buffer
}

$target = $null
$targetStarted = $false
$linuxOwnershipInitialized = $false
$statusValue = $null
$statusNonce = '00000000000000000000000000000000'
$failureStage = 'Bootstrap'
$controllerDisconnect = $null
$controllerInput = $null
$disconnectBuffer = $null
$controllerDisconnected = $false
$primaryFailureType = 'None'
$cleanupFailureTypes = New-Object 'System.Collections.Generic.List[string]'
try {
    Initialize-LinuxSupervisorOwnership
    $linuxOwnershipInitialized = $true
    foreach ($path in @(
        $DescriptorPath,
        $GatePath,
        $StartedPath,
        $StatusPath
    )) {
        if (-not [IO.Path]::IsPathRooted($path)) {
            throw [FormatException]::new('Supervisor control path is invalid.')
        }
    }
    if (-not (Test-Path -LiteralPath $DescriptorPath -PathType Leaf) -or
        (Test-Path -LiteralPath $StartedPath) -or
        (Test-Path -LiteralPath $StatusPath)) {
        throw [IO.IOException]::new('Supervisor control state is invalid.')
    }
    $failureStage = 'Descriptor'
    $descriptor = [IO.File]::ReadAllText($DescriptorPath) |
        ConvertFrom-Json -Depth 8
    Assert-SupervisorDescriptor -Descriptor $descriptor
    $statusNonce = [string]$descriptor.gateNonce

    $failureStage = 'Gate'
    $gateDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not (Test-Path -LiteralPath $GatePath -PathType Leaf)) {
        if ([DateTime]::UtcNow -ge $gateDeadline) {
            throw [TimeoutException]::new('Supervisor start gate expired.')
        }
        Start-Sleep -Milliseconds 10
    }
    if ([IO.File]::ReadAllText($GatePath) -ne [string]$descriptor.gateNonce) {
        throw [InvalidOperationException]::new('Supervisor start gate is invalid.')
    }

    $failureStage = 'Transport'
    $controllerInput = [Console]::OpenStandardInput()
    $lengthBytes = Read-SupervisorInputExact -Stream $controllerInput -Length 4
    $transportLength = (
        ([int]$lengthBytes[0] -shl 24) -bor
        ([int]$lengthBytes[1] -shl 16) -bor
        ([int]$lengthBytes[2] -shl 8) -bor
        [int]$lengthBytes[3]
    )
    if ($transportLength -le 0 -or $transportLength -gt 16777216) {
        throw [FormatException]::new('Supervisor transport is invalid.')
    }
    $transportBytes = Read-SupervisorInputExact -Stream $controllerInput `
        -Length $transportLength
    $strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
    $transportLine = $strictUtf8.GetString($transportBytes)
    if ([string]::IsNullOrWhiteSpace($transportLine)) {
        throw [FormatException]::new('Supervisor transport is invalid.')
    }
    $failureStage = 'TransportJson'
    $transport = $transportLine | ConvertFrom-Json -Depth 8
    $failureStage = 'TransportContract'
    Assert-SupervisorTransport -Transport $transport
    $statusNonce = [string]$transport.controlNonce
    $disconnectBuffer = New-Object byte[] 1
    $controllerDisconnect = $controllerInput.BeginRead(
        $disconnectBuffer,
        0,
        1,
        $null,
        $null
    )

    $failureStage = 'TargetStart'
    $targetStartInfo = [Diagnostics.ProcessStartInfo]::new()
    $targetStartInfo.FileName = [string]$descriptor.executable
    $targetStartInfo.WorkingDirectory = [string]$descriptor.workingDirectory
    $targetStartInfo.UseShellExecute = $false
    $targetStartInfo.CreateNoWindow = $true
    $targetStartInfo.RedirectStandardInput = $true
    $targetStartInfo.RedirectStandardOutput = $true
    $targetStartInfo.RedirectStandardError = $true
    foreach ($argument in @($descriptor.arguments)) {
        [void]$targetStartInfo.ArgumentList.Add([string]$argument)
    }
    foreach ($entry in @($transport.environment)) {
        $name = [string]$entry.name
        if ($null -eq $entry.value) {
            [void]$targetStartInfo.Environment.Remove($name)
        }
        else {
            $targetStartInfo.Environment[$name] = [string]$entry.value
        }
    }

    $target = [Diagnostics.Process]::new()
    $target.StartInfo = $targetStartInfo
    if (-not $target.Start()) {
        throw [InvalidOperationException]::new('Supervisor target did not start.')
    }
    $targetStarted = $true
    Publish-SupervisorStarted -Value $statusNonce

    $failureStage = 'Streams'
    $standardOutput = [Console]::OpenStandardOutput()
    $standardError = [Console]::OpenStandardError()
    $inputClosed = $null -eq $transport.standardInput
    $inputCopy = if ($inputClosed) {
        $target.StandardInput.Close()
        [Threading.Tasks.Task]::CompletedTask
    }
    else {
        $target.StandardInput.WriteAsync([string]$transport.standardInput)
    }
    $outputCopies = [Threading.Tasks.Task[]]@(
        $target.StandardOutput.BaseStream.CopyToAsync($standardOutput),
        $target.StandardError.BaseStream.CopyToAsync($standardError)
    )
    $exitTask = $target.WaitForExitAsync()
    $drainDeadline = $null
    while ($true) {
        if ($controllerDisconnect.IsCompleted) {
            $disconnectRead = $controllerInput.EndRead($controllerDisconnect)
            $controllerDisconnected = $true
            if ($disconnectRead -eq 0) {
                throw [EndOfStreamException]::new(
                    'Supervisor controller disconnected.'
                )
            }
            throw [FormatException]::new(
                'Supervisor controller sent trailing transport bytes.'
            )
        }
        if (-not $inputClosed -and $inputCopy.IsCompleted) {
            [void]$inputCopy.GetAwaiter().GetResult()
            $target.StandardInput.Close()
            $inputClosed = $true
        }
        foreach ($copyTask in $outputCopies) {
            if ($copyTask.IsFaulted -or $copyTask.IsCanceled) {
                [void]$copyTask.GetAwaiter().GetResult()
            }
        }
        if ($exitTask.IsCompleted) {
            [void]$exitTask.GetAwaiter().GetResult()
            if ($null -eq $drainDeadline) {
                $drainDeadline = [DateTime]::UtcNow.AddSeconds(
                    $DrainTimeoutSeconds
                )
            }
            $outputCompleted = @(
                $outputCopies | Where-Object { -not $_.IsCompleted }
            ).Count -eq 0
            if ($outputCompleted -and $inputClosed) {
                foreach ($copyTask in $outputCopies) {
                    [void]$copyTask.GetAwaiter().GetResult()
                }
                break
            }
            if ([DateTime]::UtcNow -ge $drainDeadline) {
                throw [TimeoutException]::new('Supervisor stream drain expired.')
            }
        }
        Start-Sleep -Milliseconds 20
    }
    $standardOutput.Flush()
    $standardError.Flush()
    $targetExitCode = [int]$target.ExitCode
}
catch {
    $failureType = $_.Exception.GetType().Name
    $primaryFailureType = "$failureStage$failureType"
}
finally {
    if ($null -ne $target) {
        if ($targetStarted) {
            try {
                if (-not $target.HasExited) {
                    $target.Kill($true)
                    if (-not $target.WaitForExit(5000)) {
                        $cleanupFailureTypes.Add(
                            'TargetWaitTimeoutException'
                        )
                    }
                }
            }
            catch {
                $cleanupFailureTypes.Add(
                    "TargetKill$($_.Exception.GetType().Name)"
                )
            }
        }
        try {
            $target.Dispose()
        }
        catch {
            $cleanupFailureTypes.Add(
                "TargetDispose$($_.Exception.GetType().Name)"
            )
        }
    }
    try {
    Stop-LinuxSupervisorDescendants `
        -OwnershipInitialized:$linuxOwnershipInitialized
    }
    catch {
        $cleanupFailureTypes.Add(
            "DescendantCleanup$($_.Exception.GetType().Name)"
        )
    }
}

if ($cleanupFailureTypes.Count -gt 4) {
    $cleanupFailureTypes.Clear()
    $cleanupFailureTypes.Add('MultipleCleanupFailures')
}
$statusValue = if ($primaryFailureType -ceq 'None' -and
    $cleanupFailureTypes.Count -eq 0) {
    "exit:$targetExitCode`:$statusNonce"
}
else {
    $cleanupValue = if ($cleanupFailureTypes.Count -eq 0) {
        'None'
    }
    else {
        $cleanupFailureTypes -join ','
    }
    "failure:$primaryFailureType`:$cleanupValue`:$statusNonce"
}

try {
    Publish-SupervisorStatus -Value $statusValue
}
catch {
    [Console]::Error.WriteLine(
        "ERROR cross-service supervisor status publication failed ($($_.Exception.GetType().Name))."
    )
    exit 70
}

if (-not $controllerDisconnected -and $null -ne $controllerDisconnect) {
    [void]$controllerDisconnect.AsyncWaitHandle.WaitOne()
    [void]$controllerInput.EndRead($controllerDisconnect)
}
if ($null -ne $controllerDisconnect) {
    $controllerDisconnect.AsyncWaitHandle.Dispose()
}
if ($null -ne $controllerInput) {
    $controllerInput.Dispose()
}
