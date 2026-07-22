[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$scriptPath = Join-Path $PSScriptRoot 'opsmind.ps1'
$tests = 0
$failures = @()

function Invoke-Subject {
    param([string[]]$Arguments, [string]$SubjectPath = $scriptPath)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $SubjectPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    [PSCustomObject]@{ ExitCode = $exitCode; Output = ($output -join [Environment]::NewLine) }
}

function Assert-Case {
    param([string]$Name, [bool]$Condition, [string]$Detail)
    $script:tests++
    if ($Condition) { Write-Output "PASS $Name" }
    else { $script:failures += "${Name}: $Detail"; Write-Output "FAIL $Name" }
}

$previousMinimumC = $env:OPS_MIN_C_FREE_GB
$previousMinimumD = $env:OPS_MIN_D_FREE_GB
$commandLockPath = Join-Path $repositoryRoot '.opsmind\command-locks\heavy'
$commandLockOwnerPath = Join-Path $commandLockPath 'owner.txt'
$testLockToken = 'command-surface-test-lock'
$testLockCreated = $false
$environmentTestParent = Join-Path $repositoryRoot '.opsmind\command-environment-tests'
$environmentTestRoot = Join-Path $environmentTestParent ([guid]::NewGuid().ToString('N'))
try {
    $help = Invoke-Subject @('help')
    Assert-Case 'help succeeds' ($help.ExitCode -eq 0) "exit=$($help.ExitCode)"
    foreach ($command in @('setup', 'dev', 'test', 'lint', 'build', 'up', 'down', 'migrate', 'seed', 'evaluate', 'security')) {
        Assert-Case "help lists $command" ($help.Output -match "\b$([regex]::Escape($command))\b") 'command missing'
    }

    $env:OPS_MIN_C_FREE_GB = '0'
    $env:OPS_MIN_D_FREE_GB = '0'
    $setup = Invoke-Subject @('setup', '-DryRun')
    Assert-Case 'setup dry-run succeeds' ($setup.ExitCode -eq 0) "exit=$($setup.ExitCode)"
    Assert-Case 'setup preflight runs first' ($setup.Output -match 'Preflight=PASS') 'preflight marker missing'
    Assert-Case 'setup dry-run avoids installation' ($setup.Output -match 'CommandPlan=install pinned') 'plan marker missing'

    $isolatedScriptDirectory = Join-Path $environmentTestRoot 'scripts\dev'
    [void](New-Item -ItemType Directory -Path $isolatedScriptDirectory -Force)
    Copy-Item -LiteralPath $scriptPath -Destination (Join-Path $isolatedScriptDirectory 'opsmind.ps1')
    [IO.File]::WriteAllText((Join-Path $environmentTestRoot '.python-version'), "3.13`n", (New-Object Text.UTF8Encoding($false)))
    $isolatedScript = Join-Path $isolatedScriptDirectory 'opsmind.ps1'
    $passwordName = 'POSTGRES_' + 'PASSWORD'
    [IO.File]::WriteAllText(
        (Join-Path $environmentTestRoot '.env'),
        ("{0}=runtime-{1}`n" -f $passwordName, ('x' * 16)),
        (New-Object Text.UTF8Encoding($false))
    )
    $secretFile = Invoke-Subject -Arguments @('help') -SubjectPath $isolatedScript
    Assert-Case 'repository-local secret is rejected' ($secretFile.ExitCode -ne 0) 'non-empty secret was loaded from .env'
    Assert-Case 'secret rejection explains approved channel' ($secretFile.Output -match 'cannot be loaded from .env') 'secret rejection reason missing'
    [IO.File]::WriteAllText(
        (Join-Path $environmentTestRoot '.env'),
        "OPS_CACHE_ROOT=`nDEEPSEEK_API_KEY=`n",
        (New-Object Text.UTF8Encoding($false))
    )
    $safeEnvironment = Invoke-Subject -Arguments @('help') -SubjectPath $isolatedScript
    Assert-Case 'allowlisted non-secret environment loads' ($safeEnvironment.ExitCode -eq 0) "exit=$($safeEnvironment.ExitCode)"

    if (Test-Path -LiteralPath $commandLockPath) {
        throw 'Cannot test lock contention while another heavyweight command owns the workspace lock.'
    }
    [void](New-Item -ItemType Directory -Path $commandLockPath)
    [IO.File]::WriteAllText($commandLockOwnerPath, "Token=$testLockToken`n", (New-Object Text.UTF8Encoding($false)))
    $testLockCreated = $true
    $locked = Invoke-Subject @('build', '-DryRun')
    Assert-Case 'workspace lock blocks concurrent heavy command' ($locked.ExitCode -ne 0) 'concurrent command passed'
    Assert-Case 'workspace lock blocks before command plan' ($locked.Output -notmatch 'CommandPlan=') 'command plan reached while locked'
    Remove-Item -LiteralPath $commandLockOwnerPath -Force
    [IO.Directory]::Delete($commandLockPath)
    $testLockCreated = $false

    $env:OPS_MIN_C_FREE_GB = '999999'
    $blocked = Invoke-Subject @('build', '-DryRun')
    Assert-Case 'low-space policy blocks heavy command' ($blocked.ExitCode -ne 0) 'heavy command passed'
    Assert-Case 'blocked command never reaches plan' ($blocked.Output -notmatch 'CommandPlan=') 'command plan reached after block'

    $down = Invoke-Subject @('down', '-DryRun')
    Assert-Case 'down stays available during low space' ($down.ExitCode -eq 0) "exit=$($down.ExitCode)"
    Assert-Case 'down bypass is explicit' ($down.Output -match 'without a capacity gate') 'down policy missing'

    $nodeLauncher = & node (Join-Path $PSScriptRoot 'opsmind.mjs') help 2>&1
    Assert-Case 'Node launcher succeeds' ($LASTEXITCODE -eq 0) ($nodeLauncher -join ' ')
}
finally {
    if ($testLockCreated -and (Test-Path -LiteralPath $commandLockOwnerPath -PathType Leaf)) {
        $ownerLines = [IO.File]::ReadAllLines($commandLockOwnerPath)
        if ($ownerLines -contains "Token=$testLockToken") {
            Remove-Item -LiteralPath $commandLockOwnerPath -Force
            if (Test-Path -LiteralPath $commandLockPath -PathType Container) {
                [IO.Directory]::Delete($commandLockPath)
            }
        }
    }
    if (Test-Path -LiteralPath $environmentTestRoot -PathType Container) {
        $resolvedTestRoot = [IO.Path]::GetFullPath($environmentTestRoot)
        $allowedTestPrefix = [IO.Path]::GetFullPath($environmentTestParent).TrimEnd('\') + '\'
        if (-not $resolvedTestRoot.StartsWith($allowedTestPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing unsafe command-environment test cleanup.'
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
    $env:OPS_MIN_C_FREE_GB = $previousMinimumC
    $env:OPS_MIN_D_FREE_GB = $previousMinimumD
}

Write-Output "Tests=$tests"
Write-Output "Failures=$($failures.Count)"
foreach ($failure in $failures) { Write-Output "Error=$failure" }
if ($failures.Count -gt 0) { Write-Output 'Result=FAIL'; exit 1 }
Write-Output 'Result=PASS'
