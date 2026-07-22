[CmdletBinding()]
param(
    [string]$JavaPath,
    [string]$MavenPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$pomPath = Join-Path $repositoryRoot 'services\platform-api\pom.xml'
$reportRoot = Join-Path $repositoryRoot 'services\platform-api\target\surefire-reports'
$evidencePath = Join-Path $repositoryRoot 'artifacts\verification\phase-04\incident-domain.txt'
$jarPath = Join-Path $repositoryRoot 'services\platform-api\target\platform-api.jar'
$mavenRepository = Join-Path $repositoryRoot '.opsmind\cache\maven'
$startedAt = [DateTime]::UtcNow
$timer = [Diagnostics.Stopwatch]::StartNew()
$tests = @(
    'IncidentStateMachineTest',
    'IncidentControllerTest',
    'IncidentControllerHttpTest',
    'IncidentJsonCodecTest',
    'IncidentQueryServiceTest',
    'IncidentRequestIdentityTest',
    'IncidentRuntimeValuesTest'
)

function Resolve-Executable {
    param([string]$ExplicitPath, [string[]]$Names, [string]$Description)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $resolved = [IO.Path]::GetFullPath($ExplicitPath)
        if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
    }
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -ne $command) { return $command.Source }
    }
    throw "$Description was not found."
}

function Get-TextSha256 {
    param([string]$Value)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Get-SourceManifest {
    $files = [Collections.Generic.List[IO.FileInfo]]::new()
    $files.Add((Get-Item -LiteralPath $pomPath))
    foreach ($root in @(
        (Join-Path $repositoryRoot 'services\platform-api\src\main\java'),
        (Join-Path $repositoryRoot 'services\platform-api\src\test\java\ai\opsmind\platform\incident')
    )) {
        foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.java') {
            $files.Add($file)
        }
    }
    $prefixLength = $repositoryRoot.TrimEnd('\', '/').Length + 1
    $manifest = foreach ($file in $files | Sort-Object FullName -Unique) {
        $relative = $file.FullName.Substring($prefixLength).Replace('\', '/')
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative=$digest"
    }
    return @{
        Count = @($manifest).Count
        Digest = Get-TextSha256 (($manifest -join "`n") + "`n")
    }
}

function Get-ToolVersion {
    param([string]$Path, [string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $line = @(& $Path @Arguments 2>&1 | Select-Object -First 1) }
    finally { $ErrorActionPreference = $previousErrorAction }
    if ($line.Count -eq 0) { return 'UNAVAILABLE' }
    return (([string]$line[0] -replace '[\r\n]+', ' ').Trim() -replace '\s+', '_')
}

function Get-SafeDiagnostics {
    param([object[]]$Output)
    $secretValues = @(
        $env:DEEPSEEK_API_KEY,
        $env:POSTGRES_PASSWORD,
        $env:POSTGRES_APP_PASSWORD,
        $env:POSTGRES_DISPATCHER_PASSWORD,
        $env:SPRING_DATASOURCE_PASSWORD
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $safe = foreach ($item in $Output | Select-Object -Last 25) {
        $line = ([string]$item -replace '[\r\n]+', ' ').Trim()
        foreach ($secret in $secretValues) { $line = $line.Replace($secret, '[REDACTED]') }
        $line = $line -replace '(?i)(password|token|secret|api[_-]?key)=\S+', '$1=[REDACTED]'
        if ($line.Length -gt 500) { $line = $line.Substring(0, 500) }
        "Diagnostic=$line"
    }
    return @($safe)
}

function Write-EvidenceAtomically {
    param([string[]]$Lines)
    $directory = Split-Path -Parent $evidencePath
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporary = Join-Path $directory ('.phase4-domain-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    $backup = Join-Path $directory ('.phase4-domain-{0}.bak' -f [guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText(
            $temporary,
            ($Lines -join [Environment]::NewLine) + [Environment]::NewLine,
            (New-Object Text.UTF8Encoding($false))
        )
        if (Test-Path -LiteralPath $evidencePath -PathType Leaf) {
            [IO.File]::Replace($temporary, $evidencePath, $backup)
        }
        else { [IO.File]::Move($temporary, $evidencePath) }
    }
    finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
    }
}

$JavaPath = Resolve-Executable $JavaPath @('java', 'java.exe') 'Java'
$MavenPath = Resolve-Executable $MavenPath @('mvn', 'mvn.cmd') 'Maven'
[void](New-Item -ItemType Directory -Path $mavenRepository -Force)
$sourceManifest = Get-SourceManifest
$arguments = @(
    '--batch-mode',
    '--no-transfer-progress',
    "-Dmaven.repo.local=$mavenRepository",
    ('-Dtest=' + ($tests -join ',')),
    '-f',
    'services/platform-api/pom.xml',
    'test'
)
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $output = @(& $MavenPath @arguments 2>&1)
    $mavenExit = $LASTEXITCODE
}
finally { $ErrorActionPreference = $previousErrorAction }

$testsRun = 0
$failures = 0
$errors = 0
$skipped = 0
$freshReports = 0
foreach ($test in $tests) {
    $report = Get-ChildItem -LiteralPath $reportRoot -Filter "TEST-*.$test.xml" -File |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $report -or $report.LastWriteTimeUtc -lt $startedAt.AddSeconds(-2)) { continue }
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
    $suite = $document.testsuite
    $testsRun += [int]$suite.tests
    $failures += [int]$suite.failures
    $errors += [int]$suite.errors
    $skipped += [int]$suite.skipped
    $freshReports++
}

$timer.Stop()
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $revision = @(& git -C $repositoryRoot rev-parse --verify HEAD 2>$null)
    $revisionExit = $LASTEXITCODE
}
finally { $ErrorActionPreference = $previousErrorAction }
if ($revisionExit -ne 0 -or $revision.Count -eq 0) { $revision = @('UNBORN') }
$dirty = if (@(& git -C $repositoryRoot status --porcelain --untracked-files=all).Count -gt 0) {
    'YES'
}
else { 'NO' }
$result = if (
    $mavenExit -eq 0 -and $freshReports -eq $tests.Count -and
    $testsRun -gt 0 -and $failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0
) { 'PASS' } else { 'BLOCK' }
$jarDigest = if (Test-Path -LiteralPath $jarPath -PathType Leaf) {
    (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
else { 'NOT_AVAILABLE' }

$lines = @(
    'EvidenceSchemaVersion=phase4-domain-tests-v1',
    'ReleaseEvidence=NO',
    "StartedAtUtc=$($startedAt.ToString('o'))",
    "CompletedAtUtc=$([DateTime]::UtcNow.ToString('o'))",
    "DurationMilliseconds=$($timer.ElapsedMilliseconds)",
    "CodeRevision=$($revision[0])",
    "WorkspaceDirty=$dirty",
    "SourceFilesHashed=$($sourceManifest.Count)",
    "SourceManifestSha256=$($sourceManifest.Digest)",
    "PlatformJarSha256=$jarDigest",
    "JavaVersion=$(Get-ToolVersion $JavaPath @('-version'))",
    "MavenVersion=$(Get-ToolVersion $MavenPath @('--version'))",
    'Command=mvn --batch-mode --no-transfer-progress -Dtest=<phase4-domain-matrix> test',
    "SelectedTestClasses=$($tests.Count)",
    "FreshSurefireReports=$freshReports",
    "TestsRun=$testsRun",
    "Failures=$failures",
    "Errors=$errors",
    "Skipped=$skipped",
    "MavenExit=$mavenExit"
)
if ($result -ne 'PASS') { $lines += Get-SafeDiagnostics $output }
$lines += "Result=$result"
Write-EvidenceAtomically $lines
$lines | Write-Output
if ($result -ne 'PASS') { exit 1 }
