#!/usr/bin/env bash

# Run the cutover inventory and translate its explicit marker into the stable
# operator exit contract. psql owns SQL failures; this wrapper owns only the
# successful-query result markers.
set -euo pipefail

readonly failed_marker='FAILED: unresolved legacy investigation rows block Temporal admission.'
readonly passed_marker='PASSED: zero unresolved legacy investigation rows.'
readonly script_directory="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly inventory_script="${script_directory}/investigation-workflow-cutover-inventory.sql"
output_file="$(mktemp "${TMPDIR:-/tmp}/opsmind-cutover-inventory.XXXXXX")"

cleanup() {
  rm -f -- "$output_file"
}
trap cleanup EXIT

# Connection arguments are forwarded unchanged. The inventory file is fixed so
# callers cannot accidentally run a different script while relying on this gate.
set +e
psql "$@" --file "$inventory_script" 2>&1 | tee "$output_file"
pipeline_status=("${PIPESTATUS[@]}")
set -e

psql_status="${pipeline_status[0]}"
tee_status="${pipeline_status[1]}"
if (( psql_status != 0 )); then
  # ON_ERROR_STOP reserves psql exit 3 for SQL/script failures. The wrapper
  # reserves 3 exclusively for a successful inventory that emitted its block
  # marker, so callers can never mistake a database failure for reconciliation.
  if (( psql_status == 3 )); then
    exit 5
  fi
  exit "$psql_status"
fi
if (( tee_status != 0 )); then
  printf '%s\n' 'FAILED: could not capture cutover inventory output.' >&2
  exit 4
fi

has_marker() {
  local marker="$1"
  tr -d '\r' < "$output_file" | grep -Fqx -- "$marker"
}

if has_marker "$failed_marker"; then
  exit 3
fi
if has_marker "$passed_marker"; then
  exit 0
fi

printf '%s\n' 'FAILED: cutover inventory returned neither expected result marker.' >&2
exit 4
