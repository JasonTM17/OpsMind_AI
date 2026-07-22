[CmdletBinding()]
param(
    [string]$ActionlintPath,
    [string]$EvidencePath,
    [string]$BashPath,
    [string]$UvPath,
    [string]$PythonPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Set-Location -LiteralPath $repositoryRoot
$artifactRoot = if ($env:OPS_ARTIFACT_ROOT) { [IO.Path]::GetFullPath($env:OPS_ARTIFACT_ROOT) } else { Join-Path $repositoryRoot 'artifacts' }
$cacheRoot = if ($env:OPS_CACHE_ROOT) { [IO.Path]::GetFullPath($env:OPS_CACHE_ROOT) } else { Join-Path $repositoryRoot '.opsmind\cache' }
$requiredPythonVersion = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.python-version')).Trim()
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $artifactRoot 'verification\phase-02\foundation-validation.txt'
}
$artifactRoot = [IO.Path]::GetFullPath($artifactRoot).TrimEnd('\', '/')
$EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
if (-not $EvidencePath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'EvidencePath must remain under OPS_ARTIFACT_ROOT.'
}
$normalizedRepositoryRoot = $repositoryRoot.TrimEnd('\', '/')
if ($artifactRoot.Equals($normalizedRepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $normalizedRepositoryRoot.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OPS_ARTIFACT_ROOT cannot be the repository or one of its ancestors.'
}
if ([string]::IsNullOrWhiteSpace($ActionlintPath)) {
    $pinnedActionlintPath = Join-Path $cacheRoot 'tools\actionlint\1.7.12\actionlint.exe'
    $actionlintCommand = Get-Command actionlint -ErrorAction SilentlyContinue
    $ActionlintPath = if (Test-Path -LiteralPath $pinnedActionlintPath -PathType Leaf) {
        $pinnedActionlintPath
    }
    elseif ($null -ne $actionlintCommand) { $actionlintCommand.Source }
    else { $pinnedActionlintPath }
}
if ([string]::IsNullOrWhiteSpace($BashPath)) {
    $gitBash = 'C:\Program Files\Git\bin\bash.exe'
    $BashPath = if (Test-Path -LiteralPath $gitBash) { $gitBash } else { 'bash' }
}
if ([string]::IsNullOrWhiteSpace($UvPath)) {
    $UvPath = Join-Path $cacheRoot 'tools\uv-0.11.29\Scripts\uv.exe'
}
if ([string]::IsNullOrWhiteSpace($PythonPath)) {
    $pythonVersionTag = $requiredPythonVersion.Replace('.', '')
    $PythonPath = Join-Path $cacheRoot "venvs\ai-runtime-py$pythonVersionTag\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf) -and
        $null -ne (Get-Command py -ErrorAction SilentlyContinue)) {
        $PythonPath = (& py "-$requiredPythonVersion" -c 'import sys; print(sys.executable)' 2>$null | Select-Object -Last 1)
    }
}

$checks = 0
$errors = @()
$results = @()

function Add-Result {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $script:checks++
    if ($Passed) {
        $script:results += "${Name}=PASS"
        Write-Output "PASS $Name"
    }
    else {
        $safeDetail = $Detail -replace '[\r\n]+', ' '
        $script:results += "${Name}=FAIL"
        $script:errors += "${Name}: $safeDetail"
        Write-Output "FAIL $Name"
    }
}

function Invoke-Gate {
    param([string]$Name, [string]$Executable, [string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments *> $null
        $exitCode = [int]$LASTEXITCODE
        Add-Result $Name ($exitCode -eq 0) "exit=$exitCode"
    }
    catch {
        Add-Result $Name $false $_.Exception.Message
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

try {
    $actualActionlintVersion = (& $ActionlintPath -version 2>$null | Select-Object -First 1).Trim()
    Add-Result 'ActionlintPin' ($actualActionlintVersion -eq '1.7.12') "actual=$actualActionlintVersion"
}
catch { Add-Result 'ActionlintPin' $false $_.Exception.Message }

try {
    $actualUvVersion = ((& $UvPath --version 2>$null | Select-Object -First 1) -replace '^uv\s+', '' -replace '\s+.*$', '').Trim()
    Add-Result 'UvPin' ($actualUvVersion -eq '0.11.29') "actual=$actualUvVersion"
}
catch { Add-Result 'UvPin' $false $_.Exception.Message }

try {
    $actualPythonVersion = (& $PythonPath -c 'import sys; print(sys.version_info.major, sys.version_info.minor, sep=chr(46))' 2>$null | Select-Object -Last 1).Trim()
    Add-Result 'PythonPin' ($actualPythonVersion -eq $requiredPythonVersion) "actual=$actualPythonVersion"
}
catch { Add-Result 'PythonPin' $false $_.Exception.Message }

Invoke-Gate 'Capacity' 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\storage\check-capacity.ps1')
Invoke-Gate 'StorageRoots' 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\storage\assert-storage-roots.ps1')
Invoke-Gate 'Contract' 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\governance\validate-product-production-contract.ps1')
Invoke-Gate 'Documentation' 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\governance\validate-documentation.ps1')
Invoke-Gate 'SecretScan' 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\governance\scan-project-secrets.ps1')
$previousLayoutEvidencePath = $env:OPS_LAYOUT_EVIDENCE_PATH
try {
    $env:OPS_LAYOUT_EVIDENCE_PATH = Join-Path $artifactRoot 'verification\phase-02\repository-layout.txt'
    Invoke-Gate 'RepositoryLayout' 'node' @('.\scripts\validation\validate-repository-layout.mjs')
}
finally {
    $env:OPS_LAYOUT_EVIDENCE_PATH = $previousLayoutEvidencePath
}
Invoke-Gate 'WindowsCommandSurface' 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '.\scripts\dev\test-command-surface.ps1')
Invoke-Gate 'PortableCommandSurface' $BashPath @('.\scripts\dev\test-command-surface.sh')
Invoke-Gate 'ComposeConfig' 'docker' @('compose', '--profile', 'application', 'config', '--quiet')
Invoke-Gate 'GitHubWorkflow' $ActionlintPath @('.\.github\workflows\pr-quality.yml')
Invoke-Gate 'PythonLock' $UvPath @('lock', '--project', '.\services\ai-runtime', '--check')

try {
    foreach ($manifest in @('package.json', 'apps\operator-web\package.json')) {
        [void]((Get-Content -Raw -LiteralPath $manifest) | ConvertFrom-Json)
    }
    Add-Result 'JsonManifests' $true 'valid JSON'
}
catch { Add-Result 'JsonManifests' $false $_.Exception.Message }

try {
    foreach ($pom in @('services\platform-api\pom.xml', 'services\tool-gateway\pom.xml')) {
        [void][xml](Get-Content -Raw -LiteralPath $pom)
    }
    Add-Result 'MavenXml' $true 'valid XML'
}
catch { Add-Result 'MavenXml' $false $_.Exception.Message }

$tomlCheck = @'
import pathlib, tomllib
for path in ("services/ai-runtime/pyproject.toml", "services/ai-runtime/uv.lock"):
    with pathlib.Path(path).open("rb") as handle:
        tomllib.load(handle)
'@
try {
    $tomlCheck | & $PythonPath -
    Add-Result 'PythonManifest' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
}
catch { Add-Result 'PythonManifest' $false $_.Exception.Message }

if (Get-Command ck -ErrorAction SilentlyContinue) {
    Invoke-Gate 'PlanStructure' 'ck' @('plan', 'validate', '.\plans\260719-1747-opsmind-ai-production-platform\plan.md', '--strict')
}
else {
    $results += 'PlanStructure=SKIPPED_CK_UNAVAILABLE'
}

$lines = @(
    'OpsMind Phase 2 foundation validation',
    ('TimestampUtc={0}' -f [DateTime]::UtcNow.ToString('o')),
    ('Checks={0}' -f $checks)
) + $results + @(
    ('Errors={0}' -f $errors.Count)
)
$lines += $errors | ForEach-Object { 'Error={0}' -f $_ }
$lines += if ($errors.Count -eq 0) { 'Result=PASS' } else { 'Result=BLOCK' }

$evidenceDirectory = Split-Path -Parent $EvidencePath
[void](New-Item -ItemType Directory -Path $evidenceDirectory -Force)
$temporaryPath = Join-Path $evidenceDirectory ('.phase-02-foundation-{0}.tmp' -f [guid]::NewGuid().ToString('N'))
[IO.File]::WriteAllText($temporaryPath, ($lines -join [Environment]::NewLine) + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
Move-Item -LiteralPath $temporaryPath -Destination $EvidencePath -Force
$lines | Write-Output
if ($errors.Count -gt 0) { exit 1 }
