#!/usr/bin/env bash

# Exercise the wrapper's public exit contract without requiring PostgreSQL.
set -euo pipefail

readonly script_directory="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly wrapper="${script_directory}/../operations/run-investigation-workflow-cutover-inventory.sh"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/opsmind-cutover-wrapper.XXXXXX")"
readonly temporary_directory

cleanup() {
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

mkdir -p "$temporary_directory/bin"
fake_psql="$temporary_directory/bin/psql"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'case "${OPSMIND_FAKE_PSQL_OUTCOME:?}" in' \
  "  blocked) printf '%s\\n' 'FAILED: unresolved legacy investigation rows block Temporal admission.' ;;" \
  "  blocked_crlf) printf '%s\\r\\n' 'FAILED: unresolved legacy investigation rows block Temporal admission.' ;;" \
  "  passed) printf '%s\\n' 'PASSED: zero unresolved legacy investigation rows.' ;;" \
  "  passed_crlf) printf '%s\\r\\n' 'PASSED: zero unresolved legacy investigation rows.' ;;" \
  "  error) printf '%s\\n' 'synthetic psql failure' >&2; exit 2 ;;" \
  "  script_error) printf '%s\\n' 'synthetic psql script failure' >&2; exit 3 ;;" \
  "  unknown) printf '%s\\n' 'synthetic unknown result' ;;" \
  '  *) exit 64 ;;' \
  'esac' > "$fake_psql"
chmod +x "$fake_psql"

assert_exit() {
  local expected_status="$1"
  local outcome="$2"
  local actual_status

  set +e
  PATH="$temporary_directory/bin:$PATH" OPSMIND_FAKE_PSQL_OUTCOME="$outcome" \
    "$wrapper" --dbname fixture >/dev/null 2>&1
  actual_status=$?
  set -e

  [[ "$actual_status" == "$expected_status" ]]
}

assert_exit 3 blocked
assert_exit 3 blocked_crlf
assert_exit 0 passed
assert_exit 0 passed_crlf
assert_exit 2 error
assert_exit 5 script_error
assert_exit 4 unknown
printf '%s\n' 'CutoverInventoryWrapperResult=PASS'
