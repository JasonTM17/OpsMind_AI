Set-StrictMode -Version Latest

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
        Start-Process -FilePath $Executable -ArgumentList $Arguments `
            -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
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

    $process = Invoke-WithProcessEnvironment -Variables $Environment -Action {
        Start-Process -FilePath $Executable -ArgumentList $Arguments `
            -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru -Wait `
            -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    }
    if ($process.ExitCode -ne 0) {
        throw "Cross-service command failed with exit code $($process.ExitCode)."
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

function Wait-CrossServiceHttps {
    param(
        [Parameter(Mandatory = $true)][uri]$Uri,
        [Parameter(Mandatory = $true)][Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 90
    )

    Add-Type -AssemblyName System.Net.Http
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.ServerCertificateCustomValidationCallback = {
        param($request, $certificate, $chain, $policyErrors)
        return $true
    }
    $client = [System.Net.Http.HttpClient]::new($handler)
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($Process.HasExited) {
                throw "Managed process exited before $Uri became ready."
            }
            try {
                $responseTask = $client.GetAsync($Uri)
                if ($responseTask.Wait(2000) -and
                    [int]$responseTask.Result.StatusCode -eq 200) {
                    return
                }
            }
            catch {
                # Retry until the bounded deadline.
            }
            Start-Sleep -Milliseconds 500
        }
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
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
