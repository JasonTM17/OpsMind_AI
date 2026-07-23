[CmdletBinding()]
param(
    [string]$EvidencePath,
    [string]$RepositoryRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pathStringComparison = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
    [StringComparison]::OrdinalIgnoreCase
}
else {
    [StringComparison]::Ordinal
}
$pathComparer = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
    [StringComparer]::OrdinalIgnoreCase
}
else {
    [StringComparer]::Ordinal
}

function Test-SensitivePath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $name = [IO.Path]::GetFileName($RelativePath)
    $extension = [IO.Path]::GetExtension($RelativePath).ToLowerInvariant()
    return (
        ($name -match '^\.env($|\.)' -and $name -ne '.env.example') -or
        $name -in @('.netrc', '.pypirc', 'id_rsa', 'id_dsa', 'id_ecdsa', 'id_ed25519') -or
        $extension -in @('.pem', '.key', '.p12', '.pfx', '.jks', '.keystore')
    )
}

function Test-PathContainsReparsePoint {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $currentPath = [IO.Path]::GetFullPath($Path)
    while ($currentPath.StartsWith($RepositoryRoot, $pathStringComparison)) {
        $item = Get-Item -LiteralPath $currentPath -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            return $true
        }
        if ($currentPath.Equals($RepositoryRoot, $pathStringComparison)) {
            break
        }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent) {
            break
        }
        $currentPath = $parent.FullName
    }
    return $false
}

function Test-PathContainsAnyReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Path)

    $currentPath = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($currentPath)) {
        if (Test-Path -LiteralPath $currentPath) {
            $item = Get-Item -LiteralPath $currentPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
        }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent -or $parent.FullName -eq $currentPath) { break }
        $currentPath = $parent.FullName
    }
    return $false
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($Bytes)) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-ReviewedMediaCatalog {
    param([Parameter(Mandatory = $true)][string]$Root)

    $catalog = New-Object 'Collections.Generic.Dictionary[string,object]' $pathComparer
    $catalogFindings = @()
    $manifestRelativePath = 'docs/media/media-manifest.json'
    $manifestPath = Join-Path $Root ($manifestRelativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        return [pscustomobject]@{ Catalog = $catalog; Findings = $catalogFindings }
    }
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
        (Test-PathContainsReparsePoint -Path $manifestPath -RepositoryRoot $Root)) {
        $catalogFindings += [pscustomobject]@{ Path = $manifestRelativePath; Rule = 'reviewed-media-manifest-unsafe' }
        return [pscustomobject]@{ Catalog = $catalog; Findings = $catalogFindings }
    }

    try {
        $manifestBytes = [IO.File]::ReadAllBytes($manifestPath)
        if ($manifestBytes.Length -gt 64KB -or $manifestBytes -contains 0) {
            throw 'Manifest is not a bounded UTF-8 text file.'
        }
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $manifest = $strictUtf8.GetString($manifestBytes) | ConvertFrom-Json
    }
    catch {
        $catalogFindings += [pscustomobject]@{ Path = $manifestRelativePath; Rule = 'reviewed-media-manifest-invalid' }
        return [pscustomobject]@{ Catalog = $catalog; Findings = $catalogFindings }
    }

    if ($manifest.schemaVersion -ne 1 -or $null -eq $manifest.media) {
        $catalogFindings += [pscustomobject]@{ Path = $manifestRelativePath; Rule = 'reviewed-media-manifest-schema' }
        return [pscustomobject]@{ Catalog = $catalog; Findings = $catalogFindings }
    }

    foreach ($entry in @($manifest.media)) {
        $entryProperties = @($entry.PSObject.Properties.Name)
        $missingRequiredProperties = @(@(
            'path', 'sha256', 'byteSize', 'mediaType', 'width',
            'height', 'frames', 'source', 'review'
        ) | Where-Object { $_ -notin $entryProperties })
        if ($missingRequiredProperties.Count -gt 0) {
            $catalogFindings += [pscustomobject]@{ Path = $manifestRelativePath; Rule = 'reviewed-media-manifest-entry-invalid' }
            continue
        }
        $normalizedPath = if ($null -ne $entry.path) { ([string]$entry.path -replace '\\', '/') } else { '' }
        $expectedMediaType = if ($normalizedPath.EndsWith('.png', [StringComparison]::Ordinal)) {
            'image/png'
        }
        elseif ($normalizedPath.EndsWith('.gif', [StringComparison]::Ordinal)) {
            'image/gif'
        }
        else {
            ''
        }
        $maximumBytes = if ($expectedMediaType -eq 'image/png') { 2MB } else { 10MB }
        if ($normalizedPath -cnotmatch '^docs/media/[a-z0-9]+(?:-[a-z0-9]+)*\.(?:png|gif)$' -or
            ([string]$entry.sha256) -cnotmatch '^[a-f0-9]{64}$' -or
            [long]$entry.byteSize -le 0 -or [long]$entry.byteSize -gt $maximumBytes -or
            [string]$entry.mediaType -cne $expectedMediaType -or
            [int]$entry.width -le 0 -or [int]$entry.height -le 0 -or [int]$entry.frames -le 0 -or
            [string]::IsNullOrWhiteSpace([string]$entry.source) -or
            [string]::IsNullOrWhiteSpace([string]$entry.review) -or
            $catalog.ContainsKey($normalizedPath)) {
            $catalogFindings += [pscustomobject]@{ Path = $manifestRelativePath; Rule = 'reviewed-media-manifest-entry-invalid' }
            continue
        }
        $catalog.Add($normalizedPath, $entry)
    }
    return [pscustomobject]@{ Catalog = $catalog; Findings = $catalogFindings }
}

function Test-ReviewedMediaBytes {
    param(
        [Parameter(Mandatory = $true)][string]$DisplayPath,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Bytes,
        [Parameter(Mandatory = $true)]$Catalog
    )

    $logicalPath = ($DisplayPath -replace '\\', '/')
    if ($logicalPath.StartsWith('git-index/', [StringComparison]::OrdinalIgnoreCase)) {
        $logicalPath = $logicalPath.Substring('git-index/'.Length)
    }
    if (-not $Catalog.ContainsKey($logicalPath)) {
        return [pscustomobject]@{ Declared = $false; Valid = $false }
    }

    $entry = $Catalog[$logicalPath]
    $valid = $Bytes.Length -eq [long]$entry.byteSize -and
        (Get-Sha256Hex -Bytes $Bytes) -ceq [string]$entry.sha256
    if ($valid -and [string]$entry.mediaType -ceq 'image/png') {
        $valid = $Bytes.Length -ge 24 -and
            $Bytes[0] -eq 0x89 -and $Bytes[1] -eq 0x50 -and $Bytes[2] -eq 0x4E -and $Bytes[3] -eq 0x47 -and
            $Bytes[4] -eq 0x0D -and $Bytes[5] -eq 0x0A -and $Bytes[6] -eq 0x1A -and $Bytes[7] -eq 0x0A
        if ($valid) {
            $width = ([int]$Bytes[16] -shl 24) -bor ([int]$Bytes[17] -shl 16) -bor
                ([int]$Bytes[18] -shl 8) -bor [int]$Bytes[19]
            $height = ([int]$Bytes[20] -shl 24) -bor ([int]$Bytes[21] -shl 16) -bor
                ([int]$Bytes[22] -shl 8) -bor [int]$Bytes[23]
            $valid = $width -eq [int]$entry.width -and $height -eq [int]$entry.height -and [int]$entry.frames -eq 1
        }
    }
    elseif ($valid -and [string]$entry.mediaType -ceq 'image/gif') {
        $signature = if ($Bytes.Length -ge 6) { [Text.Encoding]::ASCII.GetString($Bytes, 0, 6) } else { '' }
        $valid = $Bytes.Length -ge 10 -and $signature -in @('GIF87a', 'GIF89a')
        if ($valid) {
            $width = [int]$Bytes[6] + (256 * [int]$Bytes[7])
            $height = [int]$Bytes[8] + (256 * [int]$Bytes[9])
            $valid = $width -eq [int]$entry.width -and $height -eq [int]$entry.height
        }
    }
    else {
        $valid = $false
    }
    return [pscustomobject]@{ Declared = $true; Valid = $valid }
}

function Get-TreeCandidateFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$DisplayPrefix,
        [Parameter(Mandatory = $true)][bool]$Tracked,
        [string[]]$ExcludedDirectoryNames = @(),
        [string[]]$ExcludedFileNames = @()
    )

    $files = @()
    $treeFindings = @()
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) {
        return [pscustomobject]@{
            Files = @()
            Findings = @([pscustomobject]@{ Path = $DisplayPrefix; Rule = 'tree-root-unavailable' })
        }
    }
    if (Test-PathContainsAnyReparsePoint -Path $resolvedRoot) {
        return [pscustomobject]@{
            Files = @()
            Findings = @([pscustomobject]@{ Path = $DisplayPrefix; Rule = 'tree-root-reparse-path' })
        }
    }

    $directories = New-Object 'Collections.Generic.Stack[string]'
    $excludedDirectorySet = New-Object 'Collections.Generic.HashSet[string]' $pathComparer
    foreach ($excludedDirectoryName in $ExcludedDirectoryNames) {
        [void]$excludedDirectorySet.Add($excludedDirectoryName)
    }
    $excludedFileSet = New-Object 'Collections.Generic.HashSet[string]' $pathComparer
    foreach ($excludedFileName in $ExcludedFileNames) {
        [void]$excludedFileSet.Add($excludedFileName)
    }
    $directories.Push($resolvedRoot)
    while ($directories.Count -gt 0) {
        $directory = $directories.Pop()
        try { $children = @(Get-ChildItem -LiteralPath $directory -Force) }
        catch {
            $treeFindings += [pscustomobject]@{ Path = $DisplayPrefix; Rule = 'tree-enumeration-failed' }
            continue
        }
        foreach ($child in $children) {
            $relativePath = $child.FullName.Substring($resolvedRoot.Length).TrimStart('\', '/') -replace '\\', '/'
            $displayPath = if ([string]::IsNullOrWhiteSpace($relativePath)) {
                $DisplayPrefix
            }
            else { "$DisplayPrefix/$relativePath" }
            if ($child.PSIsContainer -and $excludedDirectorySet.Contains($child.Name)) {
                continue
            }
            if (-not $child.PSIsContainer -and $excludedFileSet.Contains($child.Name)) {
                continue
            }
            if (-not $child.PSIsContainer -and
                $child.Name.EndsWith('.tsbuildinfo', $pathStringComparison)) {
                continue
            }
            if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                $treeFindings += [pscustomobject]@{ Path = $displayPath; Rule = 'reparse-path-unscanned' }
                continue
            }
            if ($child.PSIsContainer) {
                $directories.Push($child.FullName)
            }
            elseif ($child -is [IO.FileInfo]) {
                $files += [pscustomobject]@{
                    FullPath = $child.FullName
                    DisplayPath = $displayPath
                    Tracked = $Tracked
                    BoundaryRoot = $resolvedRoot
                }
            }
        }
    }
    return [pscustomobject]@{ Files = @($files); Findings = @($treeFindings) }
}

function Write-EvidenceAtomically {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Lines
    )

    $directory = Split-Path -Parent $Path
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporaryPath = Join-Path $directory ('.secret-scan-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    $backupPath = Join-Path $directory ('.secret-scan-{0}.bak' -f [guid]::NewGuid().ToString('N'))
    $encoding = New-Object Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllText(
            $temporaryPath,
            ($Lines -join [Environment]::NewLine) + [Environment]::NewLine,
            $encoding
        )
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            [IO.File]::Replace($temporaryPath, $Path, $backupPath)
        }
        else {
            [IO.File]::Move($temporaryPath, $Path)
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
        if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
            Remove-Item -LiteralPath $backupPath -Force
        }
    }
}

function ConvertTo-SafeEvidenceLine {
    param([AllowEmptyString()][string]$Line)

    $builder = New-Object Text.StringBuilder
    foreach ($character in $Line.ToCharArray()) {
        $codePoint = [int][char]$character
        if ($codePoint -lt 0x20 -or $codePoint -eq 0x7F -or
            $codePoint -eq 0x85 -or $codePoint -eq 0x2028 -or $codePoint -eq 0x2029) {
            [void]$builder.Append(('\u{0:X4}' -f $codePoint))
        }
        else {
            [void]$builder.Append($character)
        }
    }
    return $builder.ToString()
}

function Get-StaleIndexSnapshotFindings {
    param([Parameter(Mandatory = $true)][string]$ParentPath)

    $findings = @()
    if (-not (Test-Path -LiteralPath $ParentPath -PathType Container)) {
        return $findings
    }
    $parentFullPath = [IO.Path]::GetFullPath($ParentPath).TrimEnd('\', '/')
    if (Test-PathContainsAnyReparsePoint -Path $parentFullPath) {
        return @([pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-snapshot-parent-reparse-path' })
    }
    $parentPrefix = $parentFullPath + [IO.Path]::DirectorySeparatorChar
    foreach ($snapshot in @(Get-ChildItem -LiteralPath $parentFullPath -Directory -Force)) {
        $snapshotPath = [IO.Path]::GetFullPath($snapshot.FullName)
        if (-not $snapshotPath.StartsWith($parentPrefix, $pathStringComparison) -or
            ($snapshot.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            $findings += [pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-stale-snapshot-unsafe' }
            continue
        }

        $ownerPath = Join-Path $snapshotPath 'owner.txt'
        $ownerProcessId = $null
        $ownerStartedUtc = $null
        if (Test-Path -LiteralPath $ownerPath -PathType Leaf) {
            try {
                $ownerLines = [IO.File]::ReadAllLines($ownerPath)
                $ownerProcessId = ($ownerLines | Where-Object { $_ -match '^ProcessId=(\d+)$' } | Select-Object -First 1) -replace '^ProcessId=', ''
                $ownerStartedUtc = ($ownerLines | Where-Object { $_ -match '^StartedUtc=(.+)$' } | Select-Object -First 1) -replace '^StartedUtc=', ''
            }
            catch {
                $ownerProcessId = $null
                $ownerStartedUtc = $null
            }
        }

        $active = $false
        $ownerIsStale = $false
        $ownerMetadataValid = $ownerProcessId -match '^\d+$' -and -not [string]::IsNullOrWhiteSpace($ownerStartedUtc)
        if ($ownerMetadataValid) {
            try {
                $process = Get-Process -Id ([int]$ownerProcessId) -ErrorAction Stop
                try {
                    $recordedStart = [DateTime]::Parse($ownerStartedUtc).ToUniversalTime()
                    $actualStart = $process.StartTime.ToUniversalTime()
                    $active = ([Math]::Abs(($actualStart - $recordedStart).TotalSeconds) -lt 2)
                    $ownerIsStale = -not $active
                }
                catch {
                    # A live process with unavailable start metadata is never safe to remove.
                    $active = $true
                }
            }
            catch {
                $ownerIsStale = $true
            }
        }

        $oldEnough = ([DateTime]::UtcNow - $snapshot.LastWriteTimeUtc).TotalHours -ge 24
        if (-not $ownerMetadataValid -and $oldEnough) {
            $findings += [pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-stale-snapshot-unverified' }
        }
        elseif ($ownerMetadataValid -and -not $active -and $ownerIsStale) {
            try {
                Remove-Item -LiteralPath $snapshotPath -Recurse -Force
            }
            catch {
                $findings += [pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-stale-snapshot-cleanup-failed' }
            }
        }
    }
    return $findings
}

function Read-GitOutputBounded {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][int64]$MaximumBytes
    )

    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = 'git'
    $startInfo.Arguments = $Arguments
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    try { $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8 } catch { }

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw 'Unable to start bounded git history reader.'
    }

    $builder = New-Object Text.StringBuilder
    $buffer = New-Object char[] 8192
    $bytesRead = [int64]0
    $truncated = $false
    try {
        while (($read = $process.StandardOutput.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $chunkBytes = [Text.Encoding]::UTF8.GetByteCount($buffer, 0, $read)
            if ($bytesRead + $chunkBytes -gt $MaximumBytes) {
                $truncated = $true
                try { $process.Kill() } catch { }
                break
            }
            [void]$builder.Append($buffer, 0, $read)
            $bytesRead += $chunkBytes
        }
        if (-not $process.HasExited) {
            $process.WaitForExit()
        }
        return [pscustomobject]@{
            Text = $builder.ToString()
            Bytes = if ($truncated) { $MaximumBytes + 1 } else { $bytesRead }
            ExitCode = if ($truncated) { 0 } else { $process.ExitCode }
            Truncated = $truncated
        }
    }
    finally {
        $process.Dispose()
    }
}

$repositoryRoot = if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\', '/')
}
else {
    if (-not [IO.Path]::IsPathRooted($RepositoryRoot)) {
        throw 'RepositoryRoot must be an absolute path.'
    }
    [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
}
if (-not (Test-Path -LiteralPath $repositoryRoot -PathType Container)) {
    throw "RepositoryRoot is unavailable: $repositoryRoot"
}
$evidencePathWasExplicit = -not [string]::IsNullOrWhiteSpace($EvidencePath)
$artifactRoot = if ([string]::IsNullOrWhiteSpace($env:OPS_ARTIFACT_ROOT)) {
    Join-Path $repositoryRoot 'artifacts'
}
else {
    if (-not [IO.Path]::IsPathRooted($env:OPS_ARTIFACT_ROOT)) {
        throw 'OPS_ARTIFACT_ROOT must be an absolute path.'
    }
    [IO.Path]::GetFullPath($env:OPS_ARTIFACT_ROOT)
}
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $artifactRoot 'verification\phase-01\doc-secret-scan.txt'
}
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)

$patterns = [ordered]@{
    'generic-provider-key' = '(?i)(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])'
    'aws-access-key' = '(?<![A-Z0-9])(AKIA|ASIA)[0-9A-Z]{16}(?![A-Z0-9])'
    'github-token' = '(?i)(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,})'
    'google-api-key' = 'AIza[0-9A-Za-z_-]{35}'
    'slack-token' = 'xox[baprs]-[0-9A-Za-z-]{20,}'
    'stripe-live-key' = '(?i)(sk|rk)_live_[0-9A-Za-z]{16,}'
    'credential-assignment-quoted' = '(?m)^[ \t]*(?:(?:[A-Z][A-Z0-9_]*_)?(?:PASSWORD|PASSWD|SECRET|API_KEY|TOKEN|SECRET_ACCESS_KEY)|(?:[a-z][a-z0-9_]*_)?(?:password|passwd|secret|api_key|token|secret_access_key))[ \t]*(?:=|:)[ \t]*(["''])(?!\s*(?:\$\{|\$[A-Za-z_][A-Za-z0-9_]*|<|example\b|placeholder\b|changeme\b|replace\b|none\b|null\b))(?=[^\r\n]{8,}\1)[^\r\n]*?\1'
    'credential-assignment' = '(?m)^[ \t]*(?:(?:[A-Z][A-Z0-9_]*_)?(?:PASSWORD|PASSWD|SECRET|API_KEY|TOKEN|SECRET_ACCESS_KEY)|(?:[a-z][a-z0-9_]*_)?(?:password|passwd|secret|api_key|token|secret_access_key))[ \t]*(?:=|:)[ \t]*(?!["''])(?!\s*(?:\$\{|\$[A-Za-z_][A-Za-z0-9_]*|<|example\b|placeholder\b|changeme\b|replace\b|none\b|null\b))[^\s#;]{8,}'
    'credential-config-property' = '(?m)(["''])(?:(?:[A-Z][A-Z0-9_]*_)?(?:PASSWORD|PASSWD|SECRET|API_KEY|TOKEN|SECRET_ACCESS_KEY)|(?:[a-z][a-z0-9_]*_)?(?:password|passwd|secret|api_key|token|secret_access_key)|(?:[A-Za-z][A-Za-z0-9]*(?:Password|Passwd|Secret|ApiKey))|(?i:clientSecret|apiKey|secretAccessKey|accessToken|refreshToken|authToken))\1[ \t]*:[ \t]*(["''])(?!\s*(?:\$\{|\$[A-Za-z_][A-Za-z0-9_]*|<|example\b|placeholder\b|changeme\b|replace\b|none\b|null\b))(?=[^\r\n]{8,}\2)[^\r\n]*?\2'
    'credential-yaml-property' = '(?im)^[ \t]*(?:(?:[a-z][a-z0-9.-]*[-_.])?(?:password|passwd|secret|api[_-]?key)|secret[_-]?access[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|token)[ \t]*:[ \t]*(?:(["''])(?!\s*(?:\$\{|\$[A-Za-z_][A-Za-z0-9_]*|<|example\b|placeholder\b|changeme\b|replace\b|none\b|null\b))(?=[^\r\n]{8,}\1)[^\r\n]*?\1|(?!["''])(?!\s*(?:\$\{|\$[A-Za-z_][A-Za-z0-9_]*|<|example\b|placeholder\b|changeme\b|replace\b|none\b|null\b))[^\s#;]{8,})'
    'credential-bearing-database-url' = '(?i)\b(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\+srv)?|redis)://[^/\s:@]+:[^@\s/]+@'
    'bearer-token' = '(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{20,}'
    'jwt-token' = '(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])'
    'private-key-header' = '-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'
}
$pathspecs = @(
    '.',
    ':(exclude).agents/**',
    ':(exclude).claude/**',
    ':(exclude).codex/**',
    ':(exclude).git/**',
    ':(exclude).opsmind/**',
    ':(glob,exclude)**/node_modules/**',
    ':(glob,exclude)**/.venv/**',
    ':(glob,exclude)**/venv/**',
    ':(glob,exclude)**/__pycache__/**',
    ':(glob,exclude)**/.pytest_cache/**',
    ':(glob,exclude)**/.mypy_cache/**',
    ':(glob,exclude)**/.ruff_cache/**',
    ':(glob,exclude)**/.next/**',
    ':(glob,exclude)**/target/**',
    ':(exclude)repomix-output.xml'
)
$historyPathspecArguments = ' -- ' + ($pathspecs -join ' ')
$findings = @()
$reviewedMediaResult = Get-ReviewedMediaCatalog -Root $repositoryRoot
$reviewedMediaCatalog = $reviewedMediaResult.Catalog
$findings += @($reviewedMediaResult.Findings)

$previousErrorActionPreference = $ErrorActionPreference
$trackedPaths = @()
$trackedPathsExitCode = -1
try {
    $ErrorActionPreference = 'Continue'
    $trackedPaths = @(& git -C $repositoryRoot ls-files --cached -- @pathspecs 2>$null)
    $trackedPathsExitCode = [int]$LASTEXITCODE
}
catch {
    $trackedPathsExitCode = -1
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($trackedPathsExitCode -ne 0) {
    $findings += [pscustomobject]@{ Path = 'repository'; Rule = 'git-ls-files-failed' }
    $trackedPaths = @()
}

$trackedSet = New-Object 'Collections.Generic.HashSet[string]' $pathComparer
foreach ($trackedPath in $trackedPaths) {
    [void]$trackedSet.Add(($trackedPath -replace '\\', '/'))
}
$candidateFiles = New-Object Collections.ArrayList
$candidateFullPaths = New-Object 'Collections.Generic.HashSet[string]' $pathComparer
$excludedWorkingTreeDirectories = @(
    '.agents', '.claude', '.codex', '.git', '.opsmind',
    'node_modules', '.venv', 'venv', '__pycache__', '.pytest_cache',
    '.mypy_cache', '.ruff_cache', '.next', 'target'
)
$excludedWorkingTreeFiles = @('repomix-output.xml')
$workingTree = Get-TreeCandidateFiles -Root $repositoryRoot -DisplayPrefix 'working-tree' -Tracked $false -ExcludedDirectoryNames $excludedWorkingTreeDirectories -ExcludedFileNames $excludedWorkingTreeFiles
$findings += @($workingTree.Findings)
foreach ($file in @($workingTree.Files)) {
    $relativePath = $file.FullPath.Substring($repositoryRoot.Length).TrimStart('\', '/') -replace '\\', '/'
    if ($candidateFullPaths.Add($file.FullPath)) {
        [void]$candidateFiles.Add([pscustomobject]@{
            FullPath = $file.FullPath
            DisplayPath = $relativePath
            Tracked = $trackedSet.Contains($relativePath)
            BoundaryRoot = $repositoryRoot
        })
    }
}

$repositoryPrefix = $repositoryRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$artifactRootNormalized = [IO.Path]::GetFullPath($artifactRoot).TrimEnd('\', '/')
$artifactInsideRepository = $artifactRootNormalized.Equals($repositoryRoot, $pathStringComparison) -or
    $artifactRootNormalized.StartsWith($repositoryPrefix, $pathStringComparison)
$repositoryInsideArtifact = $repositoryRoot.StartsWith(
    $artifactRootNormalized + [IO.Path]::DirectorySeparatorChar,
    $pathStringComparison
)
$artifactRootUsable = $false
if (-not (Test-Path -LiteralPath $artifactRootNormalized -PathType Container)) {
    $findings += [pscustomobject]@{ Path = 'artifact-root'; Rule = 'artifact-root-unavailable' }
}
elseif (Test-PathContainsAnyReparsePoint -Path $artifactRootNormalized) {
    $findings += [pscustomobject]@{ Path = 'artifact-root'; Rule = 'artifact-root-reparse-path' }
}
elseif ($artifactRootNormalized.Equals($repositoryRoot, $pathStringComparison) -or
    $repositoryInsideArtifact -or
    $artifactRootNormalized -eq [IO.Path]::GetPathRoot($artifactRootNormalized).TrimEnd('\', '/')) {
    $findings += [pscustomobject]@{ Path = 'external-artifacts'; Rule = 'unsafe-artifact-scan-root' }
}
else {
    $artifactRootUsable = $true
    $artifactDisplayPrefix = if ($artifactInsideRepository) { 'configured-artifacts' } else { 'external-artifacts' }
    $artifactTree = Get-TreeCandidateFiles -Root $artifactRootNormalized -DisplayPrefix $artifactDisplayPrefix -Tracked $false
    $findings += @($artifactTree.Findings)
    foreach ($file in @($artifactTree.Files)) {
        if ($candidateFullPaths.Add($file.FullPath)) { [void]$candidateFiles.Add($file) }
    }
}

$indexSnapshotParent = Join-Path $repositoryRoot '.opsmind\secret-index-scan'
$findings += @(Get-StaleIndexSnapshotFindings -ParentPath $indexSnapshotParent)
$indexSnapshotRoot = Join-Path $indexSnapshotParent ([guid]::NewGuid().ToString('N'))
$indexSnapshotCreated = $false
try {
    if (Test-PathContainsAnyReparsePoint -Path (Split-Path -Parent $indexSnapshotRoot)) {
        throw 'Index snapshot parent contains a reparse point.'
    }
    [void](New-Item -ItemType Directory -Path $indexSnapshotRoot -Force)
    $indexSnapshotCreated = $true
    [IO.File]::WriteAllLines(
        (Join-Path $indexSnapshotRoot 'owner.txt'),
        @("ProcessId=$PID", "StartedUtc=$([DateTime]::UtcNow.ToString('o'))"),
        (New-Object Text.UTF8Encoding($false))
    )
    $gitPrefix = ($indexSnapshotRoot -replace '\\', '/') + '/'
    & git -C $repositoryRoot checkout-index --all --force "--prefix=$gitPrefix" 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'git checkout-index failed' }
    $indexTree = Get-TreeCandidateFiles -Root $indexSnapshotRoot -DisplayPrefix 'git-index' -Tracked $true `
        -ExcludedDirectoryNames $excludedWorkingTreeDirectories -ExcludedFileNames $excludedWorkingTreeFiles
    $findings += @($indexTree.Findings)
    foreach ($file in @($indexTree.Files)) {
        if ($candidateFullPaths.Add($file.FullPath)) { [void]$candidateFiles.Add($file) }
    }
}
catch {
    $findings += [pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-snapshot-failed' }
}

$textFilesScanned = 0
$binaryFilesSkipped = 0
$reviewedBinaryFiles = 0
$maximumFileBytes = 10MB

try {
foreach ($candidateFile in @($candidateFiles)) {
    $relativePath = $candidateFile.DisplayPath
    $fullPath = $candidateFile.FullPath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'candidate-file-unavailable' }
        continue
    }
    if ($candidateFile.Tracked -and (Test-SensitivePath -RelativePath $relativePath)) {
        $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'tracked-sensitive-file' }
    }

    $file = Get-Item -LiteralPath $fullPath -Force
    if (Test-PathContainsReparsePoint -Path $fullPath -RepositoryRoot $candidateFile.BoundaryRoot) {
        $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'reparse-path-unscanned' }
        continue
    }
    if ($file.Length -gt $maximumFileBytes) {
        $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'unscanned-large-file' }
        continue
    }

    try {
        $bytes = [IO.File]::ReadAllBytes($fullPath)
    }
    catch {
        $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'file-read-failed' }
        continue
    }
    $reviewedMedia = Test-ReviewedMediaBytes -DisplayPath $relativePath -Bytes $bytes -Catalog $reviewedMediaCatalog
    if ($reviewedMedia.Declared) {
        if ($reviewedMedia.Valid) {
            $reviewedBinaryFiles++
        }
        else {
            $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'reviewed-media-integrity-mismatch' }
        }
        continue
    }
    if ($bytes.Length -ge 4 -and
        (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE -and $bytes[2] -eq 0x00 -and $bytes[3] -eq 0x00) -or
         ($bytes[0] -eq 0x00 -and $bytes[1] -eq 0x00 -and $bytes[2] -eq 0xFE -and $bytes[3] -eq 0xFF))) {
        $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'unsupported-utf32-file' }
        continue
    }
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
        $content = [Text.Encoding]::Unicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF) {
        $content = [Text.Encoding]::BigEndianUnicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    elseif ($bytes -contains 0) {
        $binaryFilesSkipped++
        $findings += [pscustomobject]@{
            Path = $relativePath
            Rule = if (Test-SensitivePath -RelativePath $relativePath) { 'sensitive-binary-file' } else { 'binary-file-unscanned' }
        }
        continue
    }
    else {
        try {
            $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
            $content = $strictUtf8.GetString($bytes)
        }
        catch {
            $findings += [pscustomobject]@{ Path = $relativePath; Rule = 'invalid-utf8-file' }
            continue
        }
    }
    $textFilesScanned++
    foreach ($entry in $patterns.GetEnumerator()) {
        if ([regex]::IsMatch($content, $entry.Value)) {
            $findings += [pscustomobject]@{ Path = $relativePath; Rule = $entry.Key }
        }
    }
}
}
finally {
if ($indexSnapshotCreated -and (Test-Path -LiteralPath $indexSnapshotRoot -PathType Container)) {
    $safeIndexPrefix = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.opsmind\secret-index-scan')).TrimEnd('\', '/') +
        [IO.Path]::DirectorySeparatorChar
    if (-not $indexSnapshotRoot.StartsWith($safeIndexPrefix, $pathStringComparison) -or
        (Test-PathContainsReparsePoint -Path $indexSnapshotRoot -RepositoryRoot $repositoryRoot)) {
        $findings += [pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-cleanup-unsafe' }
    }
    else {
        try { Remove-Item -LiteralPath $indexSnapshotRoot -Recurse -Force }
        catch { $findings += [pscustomobject]@{ Path = 'git-index'; Rule = 'git-index-cleanup-failed' }
        }
    }
}
}

$historyCommitCount = 0
$historyBytesScanned = 0
$commitCountOutput = @(& git -C $repositoryRoot rev-list --all --count 2>$null)
if ($LASTEXITCODE -ne 0 -or $commitCountOutput.Count -ne 1 -or $commitCountOutput[0] -notmatch '^\d+$') {
    $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-history-enumeration-failed' }
}
else {
    $historyCommitCount = [int]$commitCountOutput[0]
    if ($historyCommitCount -gt 0) {
        try {
            $historyRead = Read-GitOutputBounded -WorkingDirectory $repositoryRoot `
                -Arguments ('log --all --format=fuller -p --no-ext-diff --no-renames' + $historyPathspecArguments) `
                -MaximumBytes 20MB
        }
        catch {
            $historyRead = $null
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-history-read-failed' }
        }
        if ($null -eq $historyRead) {
            # The failure finding was recorded above.
        }
        elseif ($historyRead.Truncated) {
            $historyBytesScanned = $historyRead.Bytes
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'history-too-large-for-builtin-scan' }
        }
        elseif ($historyRead.ExitCode -ne 0) {
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-history-read-failed' }
        }
        else {
            $historyText = $historyRead.Text
            $historyBytesScanned = $historyRead.Bytes
            foreach ($entry in $patterns.GetEnumerator()) {
                if ([regex]::IsMatch($historyText, $entry.Value)) {
                    $findings += [pscustomobject]@{ Path = 'git-history'; Rule = $entry.Key }
                }
            }
        }
        try {
            $binaryHistoryRead = Read-GitOutputBounded -WorkingDirectory $repositoryRoot `
                -Arguments ('log --all --numstat --format= --no-renames' + $historyPathspecArguments) `
                -MaximumBytes 20MB
        }
        catch {
            $binaryHistoryRead = $null
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-binary-history-enumeration-failed' }
        }
        if ($null -eq $binaryHistoryRead) {
            # The failure finding was recorded above.
        }
        elseif ($binaryHistoryRead.Truncated) {
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-binary-history-too-large' }
        }
        elseif ($binaryHistoryRead.ExitCode -ne 0) {
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-binary-history-enumeration-failed' }
        }
        else {
            foreach ($binaryHistoryLine in @($binaryHistoryRead.Text -split '\r?\n' | Where-Object { $_ -match '^\s*-\s+-\s+' })) {
                $binaryHistoryPath = ($binaryHistoryLine -replace '^\s*-\s+-\s+', '').Trim() -replace '\\', '/'
                if ($binaryHistoryPath.StartsWith('"') -or -not $reviewedMediaCatalog.ContainsKey($binaryHistoryPath)) {
                    $findings += [pscustomobject]@{
                        Path = if ($binaryHistoryPath.StartsWith('"')) { 'git-history' } else { "git-history/$binaryHistoryPath" }
                        Rule = 'binary-history-unscanned'
                    }
                }
            }
        }

        try {
            $historicalPathsRead = Read-GitOutputBounded -WorkingDirectory $repositoryRoot `
                -Arguments ('-c core.quotepath=false log --all --name-only --format= --no-renames' + $historyPathspecArguments) `
                -MaximumBytes 20MB
        }
        catch {
            $historicalPathsRead = $null
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-history-path-enumeration-failed' }
        }
        if ($null -eq $historicalPathsRead) {
            # The failure finding was recorded above.
        }
        elseif ($historicalPathsRead.Truncated) {
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-history-paths-too-large' }
        }
        elseif ($historicalPathsRead.ExitCode -ne 0) {
            $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'git-history-path-enumeration-failed' }
        }
        else {
            foreach ($historicalPath in @($historicalPathsRead.Text -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
                $normalizedHistoricalPath = $historicalPath.Trim() -replace '\\', '/'
                if ($normalizedHistoricalPath.StartsWith('"')) {
                    $findings += [pscustomobject]@{ Path = 'git-history'; Rule = 'historical-path-encoding-unsupported' }
                }
                elseif (Test-SensitivePath -RelativePath $normalizedHistoricalPath) {
                    $findings += [pscustomobject]@{
                        Path = "git-history/$normalizedHistoricalPath"
                        Rule = 'historical-sensitive-file'
                    }
                }
            }
        }
    }
}

$findings = @($findings | Sort-Object Path, Rule -Unique)
$result = if ($findings.Count -eq 0) { 'PASS' } else { 'BLOCK' }
$lines = @(
    'OpsMind project secret-pattern scan',
    ('TimestampUtc={0}' -f [DateTime]::UtcNow.ToString('o')),
    'Scope=working-tree,ignored-config,git-index,configured-artifacts,git-history-content,git-history-paths;Excluded=.agents,.claude,.codex,.git,.opsmind,dependency-and-generated-build-caches,repomix-output.xml',
    ('CandidateFiles={0}' -f $candidateFiles.Count),
    ('TextFilesScanned={0}' -f $textFilesScanned),
    ('BinaryFilesSkipped={0}' -f $binaryFilesSkipped),
    ('ReviewedBinaryFiles={0}' -f $reviewedBinaryFiles),
    ('HistoryCommits={0}' -f $historyCommitCount),
    ('HistoryBytesScanned={0}' -f $historyBytesScanned),
    ('Findings={0}' -f $findings.Count)
)
foreach ($finding in $findings) {
    $lines += ('FindingPath={0};Rule={1}' -f $finding.Path, $finding.Rule)
}
$lines += ('Result={0}' -f $result)
$lines = @($lines | ForEach-Object { ConvertTo-SafeEvidenceLine -Line ([string]$_) })

if ($evidencePathWasExplicit -or $artifactRootUsable) {
    Write-EvidenceAtomically -Path $EvidencePath -Lines $lines
}
else {
    $lines = @($lines[0..($lines.Count - 2)]) + 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT' + $lines[-1]
}
$lines | Write-Output

if ($findings.Count -gt 0) {
    exit 7
}
exit 0
