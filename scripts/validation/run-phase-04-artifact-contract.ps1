$ErrorActionPreference = 'Stop'

if ($env:OPSMIND_EPHEMERAL_DB -ne 'true') {
    throw 'OPSMIND_EPHEMERAL_DB=true is required for the artifact contract.'
}

$bash = Get-Command bash -ErrorAction Stop
& $bash.Source 'scripts/validation/run-phase-04-artifact-contract.sh'
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
