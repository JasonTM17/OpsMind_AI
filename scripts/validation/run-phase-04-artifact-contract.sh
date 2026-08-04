#!/usr/bin/env bash
set -euo pipefail

: "${OPSMIND_EPHEMERAL_DB:?OPSMIND_EPHEMERAL_DB=true is required}"
if [[ "$OPSMIND_EPHEMERAL_DB" != "true" ]]; then
  echo "OPSMIND_EPHEMERAL_DB=true is required for the artifact contract." >&2
  exit 2
fi

bash scripts/validation/run-phase-04c-artifact-metadata-postgres-contract.sh
bash scripts/validation/run-phase-04c-artifact-object-postgres-contract.sh
bash scripts/validation/run-phase-04c-artifact-lifecycle-postgres-contract.sh
