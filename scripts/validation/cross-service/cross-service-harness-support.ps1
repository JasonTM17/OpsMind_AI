Set-StrictMode -Version Latest

function Test-CrossServiceWindows {
    return [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
}

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
            $logProbe = [IO.File]::Open(
                $logPath,
                [IO.FileMode]::Create,
                [IO.FileAccess]::Write,
                [IO.FileShare]::Read
            )
            $logProbe.Dispose()
        }
        if (Test-CrossServiceWindows) {
            $process = Invoke-WithProcessEnvironment -Variables $Environment -Action {
                Start-Process -FilePath $Executable -ArgumentList $Arguments `
                    -WorkingDirectory $WorkingDirectory -PassThru -Wait `
                    -RedirectStandardOutput $StdoutPath `
                    -RedirectStandardError $StderrPath -WindowStyle Hidden
            }
            $exitCode = [int]$process.ExitCode
        }
        else {
            $exitCode = Invoke-WithProcessEnvironment -Variables $Environment -Action {
                Push-Location -LiteralPath $WorkingDirectory
                $previousErrorActionPreference = $ErrorActionPreference
                $nativeErrorPreference = Get-Variable `
                    -Name PSNativeCommandUseErrorActionPreference `
                    -ErrorAction SilentlyContinue
                $previousNativeErrorPreference = if ($null -ne $nativeErrorPreference) {
                    $nativeErrorPreference.Value
                }
                else {
                    $null
                }
                $ErrorActionPreference = 'Stop'
                $PSNativeCommandUseErrorActionPreference = $false
                try {
                    $global:LASTEXITCODE = $null
                    & $Executable @Arguments 1> $StdoutPath 2> $StderrPath
                    if ($null -eq $global:LASTEXITCODE) {
                        throw 'Native process completed without reporting an exit code.'
                    }
                    return [int]$global:LASTEXITCODE
                }
                finally {
                    $ErrorActionPreference = $previousErrorActionPreference
                    if ($null -ne $nativeErrorPreference) {
                        $PSNativeCommandUseErrorActionPreference = `
                            $previousNativeErrorPreference
                    }
                    else {
                        Remove-Variable `
                            -Name PSNativeCommandUseErrorActionPreference `
                            -ErrorAction SilentlyContinue
                    }
                    Pop-Location
                }
            }
        }
    }
    catch {
        $failureType = $_.Exception.GetType().Name
        throw (
            "Cross-service command '$operation' failed before native exit code " +
            "capture ($failureType)."
        )
    }
    if ($exitCode -ne 0) {
        throw "Cross-service command '$operation' failed with exit code $exitCode."
    }
}

function Invoke-CrossServiceNativeQuiet {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Executable @Arguments 2>$null | Out-Null
        $exitCode = [int]$LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
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

    $output = $Sql | & $DockerPath exec -i $ContainerName `
        psql --no-password --set=ON_ERROR_STOP=1 --username opsmind_migrator --dbname opsmind 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'Cross-service SQL command failed.'
    }
    return @($output)
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
