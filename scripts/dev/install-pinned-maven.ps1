[CmdletBinding()]
param(
    [string]$DestinationRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\', '/')
$version = (Get-Content -LiteralPath (Join-Path $repositoryRoot '.maven-version') -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid Maven version pin: $version"
}

$hostIsWindows = $env:OS -eq 'Windows_NT'
$platform = if ($hostIsWindows) { 'windows' } else { 'unix' }
$distribution = @{
    '3.9.12|windows' = @{
        Archive = 'apache-maven-3.9.12-bin.zip'
        Sha512 = '0ef0d074761a55e7b982fd7f2cd6ea2da028289c73156ebd488c0513efbff8a64ac5bc0c11c3b8bbced930ce15238ecef344b0e065ae54f7cdca8c86cf39e736'
    }
    '3.9.12|unix' = @{
        Archive = 'apache-maven-3.9.12-bin.tar.gz'
        Sha512 = '0a1be79f02466533fc1a80abbef8796e4f737c46c6574ede5658b110899942a94db634477dfd3745501c80aef9aac0d4f841d38574373f7e2d24cce89d694f70'
    }
}
$release = $distribution["$version|$platform"]
if ($null -eq $release) {
    throw "Maven $version has no verified distribution record for $platform. Add its official SHA-512 before updating the pin."
}

if ([string]::IsNullOrWhiteSpace($DestinationRoot)) {
    $DestinationRoot = if (-not [string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        Join-Path $env:RUNNER_TEMP 'opsmind-maven'
    }
    else {
        Join-Path $repositoryRoot '.opsmind\cache\tools\maven'
    }
}
$DestinationRoot = [IO.Path]::GetFullPath($DestinationRoot).TrimEnd('\', '/')
[void](New-Item -ItemType Directory -Path $DestinationRoot -Force)

$mavenHome = Join-Path $DestinationRoot "apache-maven-$version"
$mavenBin = Join-Path $mavenHome 'bin'
$mavenExecutableName = if ($hostIsWindows) { 'mvn.cmd' } else { 'mvn' }
$mavenExecutable = Join-Path $mavenBin $mavenExecutableName

function Test-MavenInstallation {
    param([Parameter(Mandatory = $true)][string]$Executable)

    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { return $false }
    if (-not $hostIsWindows) { & chmod +x $Executable }
    $versionLine = (& $Executable --version 2>&1 | Select-Object -First 1).ToString().Trim()
    $expectedPrefix = [regex]::Escape("Apache Maven $version")
    if ($versionLine -notmatch "^$expectedPrefix(\s|$)") {
        throw "Pinned Maven version mismatch: expected $version, actual $versionLine"
    }
    return $true
}

function Export-MavenPath {
    if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_PATH)) {
        [IO.File]::AppendAllText(
            $env:GITHUB_PATH,
            $mavenBin + [Environment]::NewLine,
            (New-Object Text.UTF8Encoding($false))
        )
    }
    Write-Output "Maven=READY Version=$version Source=$script:source"
}

$source = 'CACHE'
if (-not (Test-MavenInstallation -Executable $mavenExecutable)) {
    $source = 'VERIFIED_RELEASE'
    $temporaryRoot = Join-Path $DestinationRoot ".install-$PID"
    if (Test-Path -LiteralPath $temporaryRoot) {
        throw "Refusing to reuse an existing Maven installation workspace: $temporaryRoot"
    }
    [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
    try {
        $archivePath = Join-Path $temporaryRoot $release.Archive
        $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/$($release.Archive)"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archivePath
        $actualSha512 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actualSha512 -ne $release.Sha512) {
            throw "Maven archive checksum mismatch: expected $($release.Sha512), actual $actualSha512"
        }

        $extractionRoot = Join-Path $temporaryRoot 'extracted'
        [void](New-Item -ItemType Directory -Path $extractionRoot -Force)
        if ($hostIsWindows) {
            Expand-Archive -LiteralPath $archivePath -DestinationPath $extractionRoot -Force
        }
        else {
            & tar -xzf $archivePath -C $extractionRoot
            if ($LASTEXITCODE -ne 0) { throw 'Unable to extract the verified Maven archive.' }
            & chmod +x (Join-Path $extractionRoot "apache-maven-$version\bin\mvn")
        }

        $extractedHome = Join-Path $extractionRoot "apache-maven-$version"
        $extractedExecutable = Join-Path (Join-Path $extractedHome 'bin') $mavenExecutableName
        if (-not (Test-MavenInstallation -Executable $extractedExecutable)) {
            throw 'Verified Maven archive did not contain a runnable pinned distribution.'
        }
        if (Test-Path -LiteralPath $mavenHome) {
            throw "Refusing to overwrite an existing Maven installation: $mavenHome"
        }
        $copySucceeded = $false
        for ($attempt = 1; $attempt -le 5; $attempt++) {
            try {
                [void](Copy-Item -LiteralPath $extractedHome -Destination $mavenHome -Recurse -Force)
                $copySucceeded = $true
                break
            }
            catch {
                if ($attempt -eq 5) { throw }
                Start-Sleep -Milliseconds (250 * $attempt)
            }
        }
        if (-not $copySucceeded -or -not (Test-MavenInstallation -Executable $mavenExecutable)) {
            throw 'Unable to publish the verified Maven installation.'
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporaryRoot) {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
        }
    }
}

if (-not (Test-MavenInstallation -Executable $mavenExecutable)) {
    throw "Pinned Maven installation is unavailable: $mavenExecutable"
}
Export-MavenPath
