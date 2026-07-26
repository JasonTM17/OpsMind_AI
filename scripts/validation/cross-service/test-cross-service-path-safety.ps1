[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
. (Join-Path $PSScriptRoot 'cross-service-harness-support.ps1')
$testRoot = Join-Path $repositoryRoot (
    '.opsmind/path-safety-tests/' + [guid]::NewGuid().ToString('N')
)
$targetRoot = Join-Path $testRoot 'target'
$linkRoot = Join-Path $testRoot 'managed-link'

try {
    [void](New-Item -ItemType Directory -Path $targetRoot -Force)
    $linkType = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        'Junction'
    }
    else {
        'SymbolicLink'
    }
    [void](New-Item -ItemType $linkType -Path $linkRoot -Target $targetRoot)

    $blocked = $false
    try {
        Assert-CrossServiceNoReparseAncestors -RepositoryRoot $repositoryRoot `
            -CandidatePath (Join-Path $linkRoot 'credential.txt')
    }
    catch {
        $blocked = $_.Exception.Message -match 'reparse ancestor'
    }
    if (-not $blocked) {
        throw 'Cross-service path guard accepted a reparse-point ancestor.'
    }

    $probeName = 'OPSMIND_CROSS_SERVICE_PROCESS_PROBE'
    $probeValue = [guid]::NewGuid().ToString('N')
    $probeStdout = Join-Path $testRoot 'phase-08-process-probe.stdout.log'
    $probeStderr = Join-Path $testRoot 'phase-08-process-probe.stderr.log'
    $failureStdout = Join-Path $testRoot 'phase-08-process-failure.stdout.log'
    $failureStderr = Join-Path $testRoot 'phase-08-process-failure.stderr.log'
    $missingStdout = Join-Path $testRoot 'phase-08-process-missing.stdout.log'
    $missingStderr = Join-Path $testRoot 'phase-08-process-missing.stderr.log'
    $redirectStderr = Join-Path $testRoot 'phase-08-process-redirect.stderr.log'
    $blockingParent = Join-Path $testRoot 'not-a-directory'
    [IO.File]::WriteAllText($blockingParent, 'path-boundary-probe')
    $invalidStdout = Join-Path $blockingParent 'probe.stdout.log'
    if (Test-CrossServiceWindows) {
        $probeExecutable = $env:ComSpec
        $probeArguments = @('/d', '/c', "echo %${probeName}%")
        $failureArguments = @(
            '/d',
            '/c',
            'echo ERROR expected-stdout & echo ERROR expected-stderr 1>&2 & exit /b 7'
        )
    }
    else {
        $probeExecutable = @(
            Get-Command sh -CommandType Application -ErrorAction Stop
        )[0].Path
        $probeArguments = @('-c', "printf '%s' `"`$$probeName`"")
        $failureArguments = @(
            '-c',
            "printf '%s\n' 'ERROR expected-stdout'; " +
                "printf '%s\n' 'ERROR expected-stderr' >&2; exit 7"
        )
    }
    $previousLastExitCodeVariable = Get-Variable -Name LASTEXITCODE -Scope Global `
        -ErrorAction SilentlyContinue
    $previousLastExitCode = if ($null -ne $previousLastExitCodeVariable) {
        $previousLastExitCodeVariable.Value
    }
    else {
        $null
    }
    $global:LASTEXITCODE = 73
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable `
            -Arguments $probeArguments -WorkingDirectory $repositoryRoot `
            -StdoutPath $probeStdout -StderrPath $probeStderr `
            -Environment @{ $probeName = $probeValue }
        if ($global:LASTEXITCODE -ne 73) {
            throw 'Cross-service capture inferred the exit status from shell state.'
        }
    }
    finally {
        if ($null -ne $previousLastExitCodeVariable) {
            Set-Variable -Name LASTEXITCODE -Scope Global -Value $previousLastExitCode
        }
        else {
            Remove-Variable -Name LASTEXITCODE -Scope Global `
                -ErrorAction SilentlyContinue
        }
    }
    if ([IO.File]::ReadAllText($probeStdout).Trim() -ne $probeValue) {
        throw 'Cross-service process environment did not reach the child.'
    }

    $failureMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable `
            -Arguments $failureArguments -WorkingDirectory $repositoryRoot `
            -StdoutPath $failureStdout -StderrPath $failureStderr -Environment @{}
    }
    catch {
        $failureMessage = $_.Exception.Message
    }
    if ($failureMessage -ne
        "Cross-service command 'phase-08-process-failure.stderr' failed with exit code 7.") {
        throw "Cross-service process failure diagnostic drifted: $failureMessage"
    }
    $failureDiagnostic = (
        Get-CrossServiceRedactedLogTail -Path $failureStdout -Environment @{}
    ) + ' ' + (
        Get-CrossServiceRedactedLogTail -Path $failureStderr -Environment @{}
    )
    if ($failureDiagnostic -notmatch 'ERROR expected-stdout' -or
        $failureDiagnostic -notmatch 'ERROR expected-stderr') {
        throw 'Cross-service process failure omitted safe diagnostic output.'
    }

    $bulkExecutable = @(
        Get-Command node -CommandType Application -ErrorAction Stop
    )[0].Path
    $bulkStdout = Join-Path $testRoot 'phase-08-process-bulk.stdout.log'
    $bulkStderr = Join-Path $testRoot 'phase-08-process-bulk.stderr.log'
    $bulkByteCount = 262144
    $bulkMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $bulkExecutable -Arguments @(
            '-e',
            (
                'process.stdout.write("A".repeat(262144) + "\nERROR bulk-stdout\n");' +
                'process.stderr.write("B".repeat(262144) + "\nERROR bulk-stderr\n");' +
                'process.exitCode = 9;'
            )
        ) -WorkingDirectory $repositoryRoot -StdoutPath $bulkStdout `
            -StderrPath $bulkStderr -Environment @{}
    }
    catch {
        $bulkMessage = $_.Exception.Message
    }
    if ($bulkMessage -ne
        "Cross-service command 'phase-08-process-bulk.stderr' failed with exit code 9." -or
        (Get-Item -LiteralPath $bulkStdout).Length -lt $bulkByteCount -or
        (Get-Item -LiteralPath $bulkStderr).Length -lt $bulkByteCount -or
        [IO.File]::ReadAllText($bulkStdout) -notmatch 'ERROR bulk-stdout' -or
        [IO.File]::ReadAllText($bulkStderr) -notmatch 'ERROR bulk-stderr') {
        throw "Cross-service concurrent output capture drifted: $bulkMessage"
    }

    $stdinPayload = 'probe-' + [guid]::NewGuid().ToString('N')
    $stdinOutput = New-Object IO.MemoryStream
    $stdinError = New-Object IO.MemoryStream
    try {
        $stdinExitCode = Invoke-CrossServiceNativeCapture -Executable $bulkExecutable `
            -Arguments @(
                '-e',
                (
                    'let buffer = "";' +
                    'process.stdin.setEncoding("utf8");' +
                    'process.stdin.on("data", (chunk) => { buffer += chunk; });' +
                    'process.stdin.on("end", () => {' +
                    ' process.stdout.write(buffer);' +
                    ' process.exitCode = buffer.length > 0 ? 0 : 3;' +
                    '});'
                )
            ) -WorkingDirectory $repositoryRoot -Environment @{} `
            -StandardOutput $stdinOutput -StandardError $stdinError `
            -StandardInputText $stdinPayload
        $stdinText = [Text.Encoding]::UTF8.GetString($stdinOutput.ToArray())
    }
    finally {
        $stdinError.Dispose()
        $stdinOutput.Dispose()
    }
    if ($stdinExitCode -ne 0 -or $stdinText -ne $stdinPayload) {
        throw 'Cross-service standard input round trip drifted.'
    }

    $missingMessage = $null
    try {
        Invoke-CrossServiceProcess `
            -Executable (Join-Path $testRoot 'executable-does-not-exist') `
            -Arguments @('probe') -WorkingDirectory $repositoryRoot `
            -StdoutPath $missingStdout -StderrPath $missingStderr -Environment @{}
    }
    catch {
        $missingMessage = $_.Exception.Message
    }
    if ($missingMessage -notlike
        "Cross-service command 'phase-08-process-missing.stderr' failed before native exit code capture (*).") {
        throw "Cross-service missing-process guard drifted: $missingMessage"
    }

    $redirectMessage = $null
    try {
        Invoke-CrossServiceProcess -Executable $probeExecutable -Arguments $probeArguments `
            -WorkingDirectory $repositoryRoot -StdoutPath $invalidStdout `
            -StderrPath $redirectStderr -Environment @{ $probeName = $probeValue }
    }
    catch {
        $redirectMessage = $_.Exception.Message
    }
    if ($redirectMessage -notlike
        "Cross-service command 'phase-08-process-redirect.stderr' failed before native exit code capture (*).") {
        throw "Cross-service process-redirection guard drifted: $redirectMessage"
    }

    $diagnosticSecret = 'probe-' + [guid]::NewGuid().ToString('N')
    $bearerSecret = 'bearer-' + [guid]::NewGuid().ToString('N')
    $jwtSecret = 'eyJ' + [guid]::NewGuid().ToString('N') + '.' +
        [guid]::NewGuid().ToString('N') + '.' + [guid]::NewGuid().ToString('N')
    $clientSecret = 'client-' + [guid]::NewGuid().ToString('N')
    $accessToken = 'access-' + [guid]::NewGuid().ToString('N')
    $jsonPassword = 'json-' + [guid]::NewGuid().ToString('N')
    $urlPassword = 'url-' + [guid]::NewGuid().ToString('N')
    $inheritedSecret = 'inherited-' + [guid]::NewGuid().ToString('N')
    $derivedPassword = 'derived-' + [guid]::NewGuid().ToString('N')
    $runtimeApiKey = ('A' + 'K' + 'I' + 'A') + [guid]::NewGuid().ToString('N')
    $runtimePrivateKeyBody = [guid]::NewGuid().ToString('N') +
        [guid]::NewGuid().ToString('N')
    $inheritedSecretName = 'OPSMIND_DIAGNOSTIC_INHERITED_CLIENT_SECRET'
    $previousInheritedSecret = [Environment]::GetEnvironmentVariable(
        $inheritedSecretName,
        'Process'
    )
    [Environment]::SetEnvironmentVariable(
        $inheritedSecretName,
        $inheritedSecret,
        'Process'
    )
    $privateKeyHeader = '-----BEGIN ' + 'PRIVATE KEY-----'
    $privateKeyFooter = '-----END ' + 'PRIVATE KEY-----'
    $privateKeyBody = ('A' * 64)
    $credentialDatabaseUrl = ('post' + 'gresql://') +
        "user:${urlPassword}@db.invalid/opsmind"
    $diagnosticPath = Join-Path $testRoot 'phase-08-redaction-probe.log'
    [IO.File]::WriteAllText(
        $diagnosticPath,
        ('x' * 20000) +
            "`npassword=$diagnosticSecret" +
            "`nauthorization: Bearer $bearerSecret" +
            "`nopaque=$jwtSecret" +
            "`nERROR inherited-value=$inheritedSecret" +
            "`nERROR client_secret=$clientSecret" +
            "`nERROR access_token=$accessToken" +
            "`nERROR {`"password`":`"$jsonPassword`"}" +
            "`nERROR $credentialDatabaseUrl" +
            "`n$privateKeyHeader`n$privateKeyBody`n$privateKeyFooter" +
            "`nERROR password is $derivedPassword" +
            "`nCaused by: invalid API key $runtimeApiKey" +
            "`nERROR failed to parse private key $runtimePrivateKeyBody" +
            "`n::error::forged-workflow-command" +
            "`ncontrol=$([char]27)[31m`nerror=expected-diagnostic"
    )
    try {
        $redactedDiagnostic = Get-CrossServiceRedactedLogTail `
            -Path $diagnosticPath -Environment @{
                TOOL_GATEWAY_DATABASE_PASSWORD = $diagnosticSecret
            }
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            $inheritedSecretName,
            $previousInheritedSecret,
            'Process'
        )
    }
    $sensitiveCanaries = @(
        $diagnosticSecret,
        $bearerSecret,
        $jwtSecret,
        $clientSecret,
        $accessToken,
        $jsonPassword,
        $urlPassword,
        $inheritedSecret,
        $privateKeyBody,
        $derivedPassword,
        $runtimeApiKey,
        $runtimePrivateKeyBody
    )
    foreach ($canary in $sensitiveCanaries) {
        if ($redactedDiagnostic -match [regex]::Escape($canary)) {
            throw 'Cross-service diagnostic exposed a sensitive canary.'
        }
    }
    if (-not $redactedDiagnostic.StartsWith('[log] ') -or
        $redactedDiagnostic -match '[\r\n\x1B]' -or
        $redactedDiagnostic.Length -gt 8192 -or
        $redactedDiagnostic -notmatch '\[redacted prohibited line\]' -or
        $redactedDiagnostic -notmatch 'inherited-value=\[REDACTED\]' -or
        $redactedDiagnostic -notmatch 'error=expected-diagnostic') {
        throw 'Cross-service diagnostic redaction failed closed.'
    }
    if ((Get-CrossServiceRedactedLogTail `
            -Path (Join-Path $testRoot 'missing-diagnostic.log') `
            -Environment @{}) -ne '') {
        throw 'Missing cross-service diagnostic did not remain empty.'
    }

    Write-Output (
        'CrossServicePathSafety=PASS ReparseAncestor=BLOCKED ProcessLaunch=PASS ' +
        'ExitStatusFromHandle=PASS ConcurrentCapture=PASS StandardInput=PASS ' +
        'LaunchFailure=BLOCKED RedirectionFailure=BLOCKED ' +
        'DiagnosticRedaction=PASS'
    )
}
finally {
    if (Test-Path -LiteralPath $linkRoot) {
        Remove-Item -LiteralPath $linkRoot -Force
    }
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        $unsafeChildren = @(
            Get-ChildItem -LiteralPath $testRoot -Force -Recurse |
                Where-Object {
                    ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
                }
        )
        if ($unsafeChildren.Count -eq 0) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}
