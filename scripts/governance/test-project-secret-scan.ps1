[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-SecretScan {
    param(
        [Parameter(Mandatory = $true)][string]$ScannerPath,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$EvidencePath,
        [Parameter(Mandatory = $true)][int]$ExpectedExitCode,
        [string]$ExpectedRule
    )

    & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScannerPath `
        -RepositoryRoot $RepositoryRoot -EvidencePath $EvidencePath | Out-Host
    $actualExitCode = [int]$LASTEXITCODE
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "Unexpected secret-scan exit. Expected=$ExpectedExitCode Actual=$actualExitCode"
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedRule) -and
        -not (Select-String -LiteralPath $EvidencePath -SimpleMatch "Rule=$ExpectedRule" -Quiet)) {
        throw "Expected secret-scan rule was not recorded: $ExpectedRule"
    }
}

function Test-ReparsePointInRepositoryPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $currentPath = [IO.Path]::GetFullPath($Path)
    while ($currentPath.StartsWith($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $currentPath) {
            $item = Get-Item -LiteralPath $currentPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                return $true
            }
        }
        if ($currentPath.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent) { break }
        $currentPath = $parent.FullName
    }
    return $false
}

function Remove-VerifiedJunction {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$AllowedParent
    )

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $allowedPrefix = [IO.Path]::GetFullPath($AllowedParent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedPath.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove a dependency fixture outside its expected parent.'
    }
    $item = Get-Item -LiteralPath $resolvedPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
        throw 'Refusing to remove a dependency fixture that is no longer a junction.'
    }
    [IO.Directory]::Delete($resolvedPath, $false)
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\', '/')
$scannerPath = Join-Path $PSScriptRoot 'scan-project-secrets.ps1'
$suffix = [guid]::NewGuid().ToString('N')
$evidenceRoot = Join-Path $repositoryRoot ".opsmind\secret-scan-tests\$suffix"
$isolatedRepository = Join-Path $evidenceRoot 'repository'
$ignoredEnvironmentPath = Join-Path $isolatedRepository ".env.opsmind-secret-scan-test-$suffix"
$extensionlessPath = Join-Path $isolatedRepository "opsmind-secret-scan-test-$suffix"
$utf32Path = Join-Path $isolatedRepository "utf32-canary-$suffix.conf"
$genericCredentialPath = Join-Path $isolatedRepository "credential-canary-$suffix.conf"
$benignTokenSourcePath = Join-Path $isolatedRepository "benign-token-source-$suffix.txt"
$historyCanaryPath = Join-Path $isolatedRepository "history-canary-$suffix.txt"
$binaryCanaryPath = Join-Path $isolatedRepository "binary-canary-$suffix.bin"
$stagedCanaryPath = Join-Path $isolatedRepository "staged-canary-$suffix.txt"
$historicalSensitivePath = Join-Path $isolatedRepository '.env'
$nestedDependencyTarget = Join-Path $evidenceRoot 'nested-dependency-target'
$nestedDependencyParent = Join-Path $isolatedRepository 'apps\operator-web\node_modules'
$nestedDependencyLink = Join-Path $nestedDependencyParent 'fixture-package'
$externalArtifactRoot = Join-Path $evidenceRoot 'external-artifacts'
$externalArtifactCanaryPath = Join-Path $externalArtifactRoot "external-canary-$suffix.txt"
$isolatedEvidenceRoot = Join-Path $isolatedRepository 'artifacts\verification'
$encoding = New-Object Text.UTF8Encoding($false)
$previousArtifactRoot = $env:OPS_ARTIFACT_ROOT
if (Test-ReparsePointInRepositoryPath -Path $evidenceRoot -RepositoryRoot $repositoryRoot) {
    throw 'Refusing to create secret-scan test data through a reparse path.'
}

try {
    [void](New-Item -ItemType Directory -Path $isolatedRepository -Force)
    [void](New-Item -ItemType Directory -Path $isolatedEvidenceRoot -Force)
    & git -C $isolatedRepository init --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Unable to initialize isolated secret-scan test repository.' }
    & git -C $isolatedRepository config user.name 'OpsMind Test'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to configure isolated Git test identity.' }
    & git -C $isolatedRepository config user.email 'opsmind-test@example.invalid'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to configure isolated Git test identity.' }
    [IO.File]::WriteAllText((Join-Path $isolatedRepository '.gitignore'), ".env.*`nnode_modules/`n", $encoding)

    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'baseline.txt') `
        -ExpectedExitCode 0

    [void](New-Item -ItemType Directory -Path $nestedDependencyTarget -Force)
    [void](New-Item -ItemType Directory -Path $nestedDependencyParent -Force)
    [IO.File]::WriteAllText((Join-Path $nestedDependencyTarget 'package.json'), '{"name":"fixture-package"}', $encoding)
    [void](New-Item -ItemType Junction -Path $nestedDependencyLink -Target $nestedDependencyTarget)
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'nested-dependency-reparse-path.txt') `
        -ExpectedExitCode 0
    Remove-VerifiedJunction -Path $nestedDependencyLink -AllowedParent $nestedDependencyParent

    [IO.File]::WriteAllText(
        $benignTokenSourcePath,
        "continuationToken = response.nextPageToken`ncancellationToken = CancellationToken.None`n",
        $encoding
    )
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'benign-source-token-names.txt') `
        -ExpectedExitCode 0
    Remove-Item -LiteralPath $benignTokenSourcePath -Force

    $providerCanary = 'sk-proj-' + ('A' * 32)
    [IO.File]::WriteAllText($ignoredEnvironmentPath, "DEEPSEEK_API_KEY=$providerCanary", $encoding)
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'provider-key.txt') `
        -ExpectedExitCode 7 -ExpectedRule 'generic-provider-key'
    Remove-Item -LiteralPath $ignoredEnvironmentPath -Force

    $githubCanary = 'github_pat_' + ('B' * 32)
    [IO.File]::WriteAllText($extensionlessPath, "token=$githubCanary", [Text.Encoding]::Unicode)
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'github-token.txt') `
        -ExpectedExitCode 7 -ExpectedRule 'github-token'
    Remove-Item -LiteralPath $extensionlessPath -Force

    $utf32Encoding = New-Object Text.UTF32Encoding($false, $true)
    [IO.File]::WriteAllText($utf32Path, "TOKEN=$githubCanary", $utf32Encoding)
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'utf32-file.txt') `
        -ExpectedExitCode 7 -ExpectedRule 'unsupported-utf32-file'
    Remove-Item -LiteralPath $utf32Path -Force

    # Assemble canaries at runtime so the scanner test source is not itself a finding.
    $jwtCanary = ('eyJ' + ('c' * 20) + '.' + ('d' * 20) + '.' + ('e' * 20))
    $passwordVariableName = 'DB_' + 'PASSWORD'
    $databaseUrlVariableName = 'DATABASE_' + 'URL'
    $databaseScheme = 'postgres' + 'ql'
    $bearerPrefix = 'Bear' + 'er '
    $awsSecretKeyName = 'AWS_' + 'SECRET_ACCESS_KEY'
    $npmTokenName = 'NPM_' + 'TOKEN'
    $smtpPasswordName = 'SMTP_' + 'PASSWORD'
    $gitlabTokenName = 'GITLAB_' + 'TOKEN'
    $jsonApiKeyName = 'DEEPSEEK_' + 'API_KEY'
    $yamlClientSecretName = 'client-' + 'secret'
    $jsonCamelPasswordName = 'smtp' + 'Password'
    $yamlNamespacedPasswordName = 'smtp-' + 'password'
    $encryptedPrivateKeyHeader = '-----BEGIN ' + 'ENCRYPTED PRIVATE KEY-----'
    $genericCanaries = @(
        ('{0}={1}' -f $passwordVariableName, 'correct-horse-battery-staple'),
        ('{0}={1}://{2}:{3}@db.invalid/opsmind' -f $databaseUrlVariableName, $databaseScheme, 'ops', 'database-canary-password'),
        ('AUTHORIZATION={0}{1}' -f $bearerPrefix, $jwtCanary),
        ('{0}="{1}"' -f $awsSecretKeyName, ('!Ab#Long' + ' Secret Rest')),
        ('{0}={1}' -f $npmTokenName, ('token-' + ('z' * 24))),
        ('  {0} = {1}' -f $smtpPasswordName, ('mail-' + ('q' * 24))),
        ('{0}={1}' -f $gitlabTokenName, ('session-' + ('r' * 24))),
        ('"{0}": "{1}"' -f $jsonApiKeyName, ('opaque-' + ('s' * 24))),
        ('{0}: "{1}"' -f $yamlClientSecretName, ('yaml-' + ('t' * 24))),
        ('"{0}": "{1}"' -f $jsonCamelPasswordName, ('json-' + ('u' * 24))),
        ('{0}: "{1}"' -f $yamlNamespacedPasswordName, ('yaml-' + ('v' * 24))),
        $encryptedPrivateKeyHeader
    )
    [IO.File]::WriteAllText($genericCredentialPath, ($genericCanaries -join "`n"), $encoding)
    $genericEvidence = Join-Path $isolatedEvidenceRoot 'generic-credentials.txt'
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath $genericEvidence -ExpectedExitCode 7 -ExpectedRule 'credential-assignment'
    foreach ($rule in @('credential-assignment-quoted', 'credential-config-property', 'credential-yaml-property', 'credential-bearing-database-url', 'bearer-token', 'jwt-token', 'private-key-header')) {
        if (-not (Select-String -LiteralPath $genericEvidence -SimpleMatch "Rule=$rule" -Quiet)) {
            throw "Expected generic credential rule was not recorded: $rule"
        }
    }
    Remove-Item -LiteralPath $genericCredentialPath -Force

    [void](New-Item -ItemType Directory -Path $externalArtifactRoot -Force)
    [IO.File]::WriteAllText($externalArtifactCanaryPath, "EXTERNAL_TOKEN=$providerCanary", $encoding)
    $env:OPS_ARTIFACT_ROOT = $externalArtifactRoot
    $externalEvidence = Join-Path $isolatedEvidenceRoot 'external-artifact-secret.txt'
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath $externalEvidence -ExpectedExitCode 7 -ExpectedRule 'generic-provider-key'
    $externalDisplayPath = 'FindingPath=external-artifacts/{0};Rule=generic-provider-key' -f [IO.Path]::GetFileName($externalArtifactCanaryPath)
    if (-not (Select-String -LiteralPath $externalEvidence -SimpleMatch $externalDisplayPath -Quiet)) {
        throw 'Expected external artifact secret finding was not recorded.'
    }
    Remove-Item -LiteralPath $externalArtifactCanaryPath -Force
    $env:OPS_ARTIFACT_ROOT = $previousArtifactRoot

    [IO.File]::WriteAllText($stagedCanaryPath, "STAGED_TOKEN=$providerCanary", $encoding)
    & git -C $isolatedRepository add -- ([IO.Path]::GetFileName($stagedCanaryPath))
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stage isolated index canary.' }
    [IO.File]::WriteAllText($stagedCanaryPath, 'STAGED_TOKEN=example-placeholder', $encoding)
    $stagedEvidence = Join-Path $isolatedEvidenceRoot 'staged-index-secret.txt'
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath $stagedEvidence -ExpectedExitCode 7 -ExpectedRule 'generic-provider-key'
    $stagedDisplayPath = 'FindingPath=git-index/{0};Rule=generic-provider-key' -f [IO.Path]::GetFileName($stagedCanaryPath)
    if (-not (Select-String -LiteralPath $stagedEvidence -SimpleMatch $stagedDisplayPath -Quiet)) {
        throw 'Expected staged-index-only secret finding was not recorded.'
    }
    & git -C $isolatedRepository rm --cached --quiet --force -- ([IO.Path]::GetFileName($stagedCanaryPath))
    if ($LASTEXITCODE -ne 0) { throw 'Unable to clear isolated staged canary.' }
    Remove-Item -LiteralPath $stagedCanaryPath -Force

    [IO.File]::WriteAllText($historyCanaryPath, ('HISTORY_TOKEN={0}' -f $providerCanary), $encoding)
    & git -C $isolatedRepository add -- ([IO.Path]::GetFileName($historyCanaryPath))
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stage isolated history canary.' }
    & git -C $isolatedRepository commit --quiet -m 'test history scanning'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit isolated history canary.' }
    & git -C $isolatedRepository rm --quiet -- ([IO.Path]::GetFileName($historyCanaryPath))
    if ($LASTEXITCODE -ne 0) { throw 'Unable to remove isolated history canary.' }
    & git -C $isolatedRepository commit --quiet -m 'remove history canary'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit isolated history-canary removal.' }
    $historyEvidence = Join-Path $isolatedEvidenceRoot 'history-secret.txt'
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath $historyEvidence -ExpectedExitCode 7 -ExpectedRule 'generic-provider-key'
    if (-not (Select-String -LiteralPath $historyEvidence -SimpleMatch 'FindingPath=git-history;Rule=generic-provider-key' -Quiet)) {
        throw 'Expected history-only secret finding was not recorded against git-history.'
    }

    [IO.File]::WriteAllText($historicalSensitivePath, 'FEATURE_FLAG=enabled', $encoding)
    & git -C $isolatedRepository add -- '.env'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stage historical sensitive-path canary.' }
    & git -C $isolatedRepository commit --quiet -m 'test historical sensitive path scanning'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit historical sensitive-path canary.' }
    & git -C $isolatedRepository rm --quiet -- '.env'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to remove historical sensitive-path canary.' }
    & git -C $isolatedRepository commit --quiet -m 'remove historical sensitive path canary'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit historical sensitive-path removal.' }
    $historicalPathEvidence = Join-Path $isolatedEvidenceRoot 'historical-sensitive-path.txt'
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath $historicalPathEvidence -ExpectedExitCode 7 -ExpectedRule 'historical-sensitive-file'
    if (-not (Select-String -LiteralPath $historicalPathEvidence -SimpleMatch 'FindingPath=git-history/.env;Rule=historical-sensitive-file' -Quiet)) {
        throw 'Expected deleted historical sensitive-path finding was not recorded.'
    }

    [IO.File]::WriteAllBytes($binaryCanaryPath, [byte[]](0x4F, 0x50, 0x53, 0x00, 0x4D, 0x49, 0x4E, 0x44))
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'binary-file.txt') `
        -ExpectedExitCode 7 -ExpectedRule 'binary-file-unscanned'

    & git -C $isolatedRepository add -- ([IO.Path]::GetFileName($binaryCanaryPath))
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stage isolated binary-history canary.' }
    & git -C $isolatedRepository commit --quiet -m 'test binary history scanning'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit isolated binary-history canary.' }
    & git -C $isolatedRepository rm --quiet -- ([IO.Path]::GetFileName($binaryCanaryPath))
    if ($LASTEXITCODE -ne 0) { throw 'Unable to remove isolated binary-history canary.' }
    & git -C $isolatedRepository commit --quiet -m 'remove binary history canary'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit binary-history canary removal.' }
    Invoke-SecretScan -ScannerPath $scannerPath -RepositoryRoot $isolatedRepository `
        -EvidencePath (Join-Path $isolatedEvidenceRoot 'binary-history.txt') `
        -ExpectedExitCode 7 -ExpectedRule 'binary-history-unscanned'

    Write-Output 'Project secret-scan tests: PASS (13/13)'
}
finally {
    $env:OPS_ARTIFACT_ROOT = $previousArtifactRoot
    foreach ($path in @($ignoredEnvironmentPath, $extensionlessPath, $utf32Path, $genericCredentialPath, $benignTokenSourcePath, $historyCanaryPath, $binaryCanaryPath, $stagedCanaryPath, $historicalSensitivePath, $externalArtifactCanaryPath)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    Remove-VerifiedJunction -Path $nestedDependencyLink -AllowedParent $nestedDependencyParent
    if (Test-Path -LiteralPath $evidenceRoot -PathType Container) {
        $resolvedEvidenceRoot = [IO.Path]::GetFullPath($evidenceRoot)
        $allowedPrefix = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.opsmind\secret-scan-tests'))
        if ($resolvedEvidenceRoot.StartsWith($allowedPrefix + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            if (Test-ReparsePointInRepositoryPath -Path $resolvedEvidenceRoot -RepositoryRoot $repositoryRoot) {
                throw 'Refusing to clean secret-scan test data through a reparse path.'
            }
            Remove-Item -LiteralPath $resolvedEvidenceRoot -Recurse -Force
        }
    }
}
