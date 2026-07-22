[CmdletBinding()]
param(
    [string]$JavaPath,
    [string]$MavenPath,
    [string]$PythonPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$capacityScript = Join-Path $repositoryRoot 'scripts\storage\check-capacity.ps1'
$pomPath = Join-Path $repositoryRoot 'services\platform-api\pom.xml'
$reportPath = Join-Path $repositoryRoot (
    'services\platform-api\target\surefire-reports\' +
    'TEST-ai.opsmind.platform.analysis.CrossLanguageAnalysisCapabilityConformanceTest.xml'
)
$evidencePath = Join-Path $repositoryRoot 'artifacts\verification\phase-05\capability-conformance.txt'
$mavenRepository = Join-Path $repositoryRoot '.opsmind\cache\maven'
$startedAt = [DateTime]::UtcNow

function Resolve-Executable {
    param([string]$ExplicitPath, [string[]]$Candidates, [string]$Description)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $resolved = [IO.Path]::GetFullPath($ExplicitPath)
        if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
        throw "$Description path is unavailable."
    }
    foreach ($candidate in $Candidates) {
        $expanded = if ([IO.Path]::IsPathRooted($candidate)) {
            $candidate
        }
        else {
            Join-Path $repositoryRoot $candidate
        }
        if (Test-Path -LiteralPath $expanded -PathType Leaf) {
            return [IO.Path]::GetFullPath($expanded)
        }
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($null -ne $command) { return $command.Source }
    }
    throw "$Description was not found."
}

function Get-SourceManifest {
    $paths = @(
        'services\platform-api\pom.xml',
        'services\platform-api\src\main\java\ai\opsmind\platform\analysis',
        'services\platform-api\src\test\java\ai\opsmind\platform\analysis\CrossLanguageAnalysisCapabilityConformanceTest.java',
        'services\ai-runtime\pyproject.toml',
        'services\ai-runtime\uv.lock',
        'services\ai-runtime\src\opsmind_ai_runtime\application\delegated_capability.py',
        'services\ai-runtime\src\opsmind_ai_runtime\application\rsa_jwks_capability.py',
        'services\ai-runtime\src\opsmind_ai_runtime\application\rsa_jwks_parser.py',
        'services\ai-runtime\src\opsmind_ai_runtime\domain\analysis_contracts.py',
        'packages\contracts\fixtures\deepseek\analysis-request-v1.json',
        'packages\contracts\fixtures\deepseek\analysis-request-v1.digest',
        'scripts\validation\run-phase-05-capability-conformance.ps1'
    )
    $files = foreach ($relative in $paths) {
        $path = Join-Path $repositoryRoot $relative
        if (Test-Path -LiteralPath $path -PathType Container) {
            Get-ChildItem -LiteralPath $path -Recurse -File -Filter '*.java'
        }
        elseif (Test-Path -LiteralPath $path -PathType Leaf) {
            Get-Item -LiteralPath $path
        }
        else { throw "Conformance source is missing: $relative" }
    }
    $prefix = $repositoryRoot.TrimEnd('\', '/').Length + 1
    $manifest = foreach ($file in $files | Sort-Object FullName -Unique) {
        $relative = $file.FullName.Substring($prefix).Replace('\', '/')
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative=$digest"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($manifest -join "`n") + "`n")
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return @{
            Count = @($manifest).Count
            Digest = ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        }
    }
    finally { $algorithm.Dispose() }
}

function Write-EvidenceAtomically {
    param([string[]]$Lines)
    $directory = Split-Path -Parent $evidencePath
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $temporary = Join-Path $directory ('.capability-conformance-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText(
            $temporary,
            ($Lines -join [Environment]::NewLine) + [Environment]::NewLine,
            (New-Object Text.UTF8Encoding($false))
        )
        Move-Item -LiteralPath $temporary -Destination $evidencePath -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

& $capacityScript
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$JavaPath = Resolve-Executable $JavaPath @(
    '.opsmind\cache\tools\temurin-jdk-21.0.11+10\bin\java.exe',
    'java.exe',
    'java'
) 'Java 21'
$MavenPath = Resolve-Executable $MavenPath @('mvn.cmd', 'mvn') 'Maven 3.9.12'
$PythonPath = Resolve-Executable $PythonPath @(
    '.opsmind\cache\venvs\ai-runtime-py313\Scripts\python.exe',
    'python.exe',
    'python'
) 'Python 3.13 AI Runtime environment'
[void](New-Item -ItemType Directory -Path $mavenRepository -Force)
$sourceManifest = Get-SourceManifest

$saved = @{
    JAVA_HOME = $env:JAVA_HOME
    OPSMIND_PHASE5_CROSS_LANGUAGE = $env:OPSMIND_PHASE5_CROSS_LANGUAGE
    OPSMIND_PHASE5_PYTHON = $env:OPSMIND_PHASE5_PYTHON
    OPSMIND_REPOSITORY_ROOT = $env:OPSMIND_REPOSITORY_ROOT
}
$env:JAVA_HOME = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($JavaPath))
$env:OPSMIND_PHASE5_CROSS_LANGUAGE = 'true'
$env:OPSMIND_PHASE5_PYTHON = $PythonPath
$env:OPSMIND_REPOSITORY_ROOT = $repositoryRoot
$arguments = @(
    '--batch-mode',
    '--no-transfer-progress',
    "-Dmaven.repo.local=$mavenRepository",
    '-Dtest=CrossLanguageAnalysisCapabilityConformanceTest',
    '-f',
    $pomPath,
    'test'
)
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $output = @(& $MavenPath @arguments 2>&1)
    $mavenExit = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorAction
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
}

$tests = 0
$failures = 1
$errors = 0
$skipped = 0
$freshReport = Test-Path -LiteralPath $reportPath -PathType Leaf
if ($freshReport -and (Get-Item -LiteralPath $reportPath).LastWriteTimeUtc -ge $startedAt.AddSeconds(-2)) {
    [xml]$document = Get-Content -LiteralPath $reportPath -Raw
    $tests = [int]$document.testsuite.tests
    $failures = [int]$document.testsuite.failures
    $errors = [int]$document.testsuite.errors
    $skipped = [int]$document.testsuite.skipped
}
else { $freshReport = $false }

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
    $mavenExit -eq 0 -and $freshReport -and $tests -eq 1 -and
    $failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0
) { 'PASS' } else { 'BLOCK' }

$lines = @(
    'EvidenceSchemaVersion=phase5-capability-conformance-v1',
    'ReleaseEvidence=NO',
    "StartedAtUtc=$($startedAt.ToString('o'))",
    "CompletedAtUtc=$([DateTime]::UtcNow.ToString('o'))",
    "CodeRevision=$($revision[0])",
    "WorkspaceDirty=$dirty",
    "SourceFilesHashed=$($sourceManifest.Count)",
    "SourceManifestSha256=$($sourceManifest.Digest)",
    'PrivateKeyPersistence=NO',
    'CapabilityAlgorithm=RS256',
    'VerifierKeySource=EPHEMERAL_PUBLIC_JWKS',
    'Command=mvn -Dtest=CrossLanguageAnalysisCapabilityConformanceTest test',
    "FreshSurefireReport=$($freshReport.ToString().ToUpperInvariant())",
    "TestsRun=$tests",
    "Failures=$failures",
    "Errors=$errors",
    "Skipped=$skipped",
    "MavenExit=$mavenExit"
)
if ($result -ne 'PASS') {
    foreach ($item in $output | Select-Object -Last 20) {
        $line = ([string]$item -replace '[\r\n]+', ' ').Trim()
        $line = $line -replace '(?i)(password|token|secret|api[_-]?key)=\S+', '$1=[REDACTED]'
        if ($line.Length -gt 500) { $line = $line.Substring(0, 500) }
        $lines += "Diagnostic=$line"
    }
}
$lines += "Result=$result"
Write-EvidenceAtomically $lines
$lines | Write-Output
if ($result -ne 'PASS') { exit 1 }
