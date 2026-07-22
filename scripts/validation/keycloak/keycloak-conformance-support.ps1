Set-StrictMode -Version Latest

function New-OpsMindConformanceSecret {
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

function Get-OpsMindAvailableTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Resolve-OpsMindExecutable {
    param(
        [string]$ExplicitPath,
        [Parameter(Mandatory = $true)][string[]]$Names,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $resolved = [IO.Path]::GetFullPath($ExplicitPath)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "$Description was not found at the explicit path."
        }
        return $resolved
    }
    foreach ($name in $Names) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $command) {
            return $command.Path
        }
    }
    throw "$Description is required for the Keycloak conformance harness."
}

function Get-OpsMindNativeVersion {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Executable @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Unable to read runtime version from $Executable."
    }
    $line = $output | ForEach-Object { ([string]$_).Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($line)) {
        throw "Runtime version output was empty for $Executable."
    }
    return ($line -replace '[\r\n]+', ' ')
}

function Get-OpsMindFileSetSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string[]]$Paths
    )

    $root = [IO.Path]::GetFullPath($RepositoryRoot)
    $pathSeparators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $rootPrefix = $root.TrimEnd($pathSeparators) + [IO.Path]::DirectorySeparatorChar
    $pathComparison = if ($env:OS -eq 'Windows_NT') {
        [StringComparison]::OrdinalIgnoreCase
    }
    else {
        [StringComparison]::Ordinal
    }
    $manifest = [Text.StringBuilder]::new()
    foreach ($path in $Paths | Sort-Object) {
        $resolved = [IO.Path]::GetFullPath($path)
        if (-not $resolved.StartsWith($rootPrefix, $pathComparison) `
            -or -not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw 'Conformance profile digest input must be a repository file.'
        }
        $relative = $resolved.Substring($rootPrefix.Length).Replace('\', '/')
        $fileHash = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
        [void]$manifest.AppendLine("$relative=$fileHash")
    }
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($manifest.ToString())
        return ($sha256.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-OpsMindConformanceProfilePaths {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$ManifestPath
    )

    $root = [IO.Path]::GetFullPath($RepositoryRoot)
    $manifest = [IO.Path]::GetFullPath($ManifestPath)
    $pathSeparators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $rootPrefix = $root.TrimEnd($pathSeparators) + [IO.Path]::DirectorySeparatorChar
    $pathComparison = if ($env:OS -eq 'Windows_NT') {
        [StringComparison]::OrdinalIgnoreCase
    }
    else {
        [StringComparison]::Ordinal
    }
    if (-not $manifest.StartsWith($rootPrefix, $pathComparison) `
        -or -not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw 'Conformance profile manifest must be a repository file.'
    }

    $paths = New-Object 'System.Collections.Generic.List[string]'
    $pathComparer = if ($env:OS -eq 'Windows_NT') {
        [StringComparer]::OrdinalIgnoreCase
    }
    else {
        [StringComparer]::Ordinal
    }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' $pathComparer
    $paths.Add($manifest)
    foreach ($line in Get-Content -LiteralPath $manifest) {
        $relative = ([string]$line).Trim()
        if ([string]::IsNullOrWhiteSpace($relative) -or $relative.StartsWith('#')) {
            continue
        }
        $isDirectory = $relative.EndsWith('/')
        $entry = if ($isDirectory) { $relative.TrimEnd('/') } else { $relative }
        if ([string]::IsNullOrWhiteSpace($entry) -or $entry.StartsWith('/') `
            -or $entry.Contains('\') -or $entry.Contains(':') `
            -or $entry.Split('/') -contains '..' -or $entry.Split('/') -contains '.') {
            throw 'Conformance profile manifest contains an unsafe relative path.'
        }
        $resolved = [IO.Path]::GetFullPath([IO.Path]::Combine(
                $root,
                $entry.Replace('/', [IO.Path]::DirectorySeparatorChar)
            ))
        if (-not $resolved.StartsWith($rootPrefix, $pathComparison)) {
            throw 'Conformance profile manifest references a missing repository file.'
        }
        if ($isDirectory) {
            if (-not (Test-Path -LiteralPath $resolved -PathType Container) `
                -or ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'Conformance profile manifest references an unsafe or missing directory.'
            }
            $directoryFiles = @(Get-ChildItem -LiteralPath $resolved -File -Recurse -Force)
            if ($directoryFiles.Count -eq 0) {
                throw 'Conformance profile manifest references an empty directory.'
            }
            foreach ($file in $directoryFiles) {
                if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw 'Conformance profile directory contains a reparse-point file.'
                }
                $canonicalRelative = $file.FullName.Substring($rootPrefix.Length).Replace('\', '/')
                if (-not $seen.Add($canonicalRelative)) {
                    throw 'Conformance profile manifest contains an overlapping path.'
                }
                $paths.Add($file.FullName)
            }
        }
        else {
            if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) `
                -or ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'Conformance profile manifest references an unsafe or missing repository file.'
            }
            $canonicalRelative = $resolved.Substring($rootPrefix.Length).Replace('\', '/')
            if (-not $seen.Add($canonicalRelative)) {
                throw 'Conformance profile manifest contains a duplicate path.'
            }
            $paths.Add($resolved)
        }
    }
    if ($paths.Count -lt 2) {
        throw 'Conformance profile manifest does not contain any profile inputs.'
    }
    return $paths.ToArray()
}

function Get-OpsMindGitMetadata {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $git) {
        return [pscustomobject]@{ Revision = 'UNAVAILABLE'; Dirty = 'UNKNOWN' }
    }
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $revision = & $git.Path -C $RepositoryRoot rev-parse --verify HEAD 2>$null
        $revisionExitCode = $LASTEXITCODE
        $status = @(& $git.Path -C $RepositoryRoot status --porcelain=v1 2>$null)
        $statusExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($revisionExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        $revision = 'UNBORN'
    }
    $dirty = if ($statusExitCode -eq 0 -and $status.Count -eq 0) { 'NO' } else { 'YES' }
    return [pscustomobject]@{ Revision = ([string]$revision).Trim(); Dirty = $dirty }
}

function Get-OpsMindSanitizedDiagnosticLines {
    param(
        [object[]]$Lines,
        [string[]]$SensitiveValues = @(),
        [int]$MaximumLines = 100
    )

    if ($MaximumLines -lt 1 -or $MaximumLines -gt 500) {
        throw 'Diagnostic line limit must be between one and 500.'
    }
    $selected = @($Lines | Select-Object -Last $MaximumLines)
    $sanitized = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in $selected) {
        $value = (([string]$line -replace '[\r\n]+', ' ') `
                -replace '[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '').Trim()
        foreach ($sensitiveValue in $SensitiveValues) {
            if (-not [string]::IsNullOrWhiteSpace($sensitiveValue)) {
                $value = $value.Replace($sensitiveValue, '<redacted-runtime-value>')
            }
        }
        $value = $value -replace '(?i)(authorization\s*[:=]\s*bearer\s+)\S+', '$1<redacted>'
        $value = $value -replace 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '<redacted-jwt>'
        $value = $value -replace '(?i)([?&](?:code|session_code|client_data|tab_id|execution)=)[^&\s]+', '$1<redacted>'
        $value = $value -replace '(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])', '<redacted-long-value>'
        if ($value.Length -gt 1000) {
            $value = $value.Substring(0, 1000) + '<truncated>'
        }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $sanitized.Add($value)
        }
    }
    return $sanitized.ToArray()
}

function Invoke-OpsMindKeycloakAdmin {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [Parameter(Mandatory = $true)][string]$DockerPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = & $DockerPath exec $ContainerName /opt/keycloak/bin/kcadm.sh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'A Keycloak Admin API operation failed.'
    }
    return $output
}

function Invoke-OpsMindPlatformRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$AccessToken
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers $headers -TimeoutSec 10
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            ContentType = [string]$response.Headers['Content-Type']
            Body = [string]$response.Content
        }
    }
    catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw
        }
        $status = [int]$response.StatusCode
        $body = ''
        if ($null -ne $_.ErrorDetails `
            -and $_.ErrorDetails.PSObject.Properties.Name -contains 'Message') {
            $body = [string]$_.ErrorDetails.Message
        }
        if ([string]::IsNullOrWhiteSpace($body) `
            -and $response.PSObject.Properties.Name -contains 'Content' `
            -and $null -ne $response.Content `
            -and $response.Content.PSObject.Methods.Name -contains 'ReadAsStringAsync') {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        if ([string]::IsNullOrWhiteSpace($body) -and $response.PSObject.Methods.Name -contains 'GetResponseStream') {
            $stream = $response.GetResponseStream()
            if ($null -ne $stream) {
                $reader = [IO.StreamReader]::new($stream)
                try {
                    $body = $reader.ReadToEnd()
                }
                finally {
                    $reader.Dispose()
                }
            }
        }
        $contentType = ''
        if ($response.PSObject.Properties.Name -contains 'ContentType') {
            $contentType = [string]$response.ContentType
        }
        elseif ($response.PSObject.Properties.Name -contains 'Content' `
            -and $null -ne $response.Content `
            -and $response.Content.PSObject.Properties.Name -contains 'Headers') {
            $contentType = [string]$response.Content.Headers.ContentType
        }
        return [pscustomobject]@{
            Status = $status
            ContentType = $contentType
            Body = $body
        }
    }
}

function Wait-OpsMindHttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][Diagnostics.Process]$Process,
        [int]$Attempts = 120
    )

    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        if ($Process.HasExited) {
            throw 'The Platform API exited before becoming ready.'
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 2
            if ([int]$response.StatusCode -eq 200) {
                return
            }
        }
        catch {
            # Startup races are expected until the bounded deadline.
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'The Platform API did not become ready before the deadline.'
}

function Remove-OpsMindValidatedTempDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    $resolved = [IO.Path]::GetFullPath($Path)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $name = [IO.Path]::GetFileName($resolved)
    $pathSeparators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedTemporaryRoot = $temporaryRoot.TrimEnd($pathSeparators)
    $resolvedParent = [IO.Path]::GetFullPath((Split-Path -Parent $resolved)).TrimEnd($pathSeparators)
    $pathComparison = if ($env:OS -eq 'Windows_NT') {
        [StringComparison]::OrdinalIgnoreCase
    }
    else {
        [StringComparison]::Ordinal
    }
    if (-not $resolvedParent.Equals($normalizedTemporaryRoot, $pathComparison) `
        -or $name -notmatch '^opsmind-keycloak-conformance-[0-9a-f]{32}$') {
        throw 'Refusing to remove an unvalidated conformance temporary directory.'
    }
    if (Test-Path -LiteralPath $resolved) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
