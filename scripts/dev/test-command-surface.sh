#!/usr/bin/env sh

set -u

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
repository_root=$(CDPATH= cd -- "$script_dir/../.." && pwd) || exit 2
subject="$script_dir/opsmind.sh"
tests=0
failures=0

assert_case() {
    tests=$((tests + 1))
    if [ "$2" -eq 0 ]; then printf '%s\n' "PASS $1"
    else failures=$((failures + 1)); printf '%s\n' "FAIL $1"; fi
}

capture() {
    output_file=$1
    shift
    "$@" >"$output_file" 2>&1
    return $?
}

temporary_parent="$repository_root/.opsmind/command-surface-tests"
if [ -d "$temporary_parent" ]; then
    temporary_parent_logical=$(CDPATH= cd -- "$temporary_parent" && pwd -L) || exit 2
    temporary_parent_physical=$(CDPATH= cd -- "$temporary_parent" && pwd -P) || exit 2
    [ "$temporary_parent_logical" = "$temporary_parent_physical" ] || exit 2
fi
mkdir -p -- "$temporary_parent" || exit 2
temporary_parent_physical=$(CDPATH= cd -- "$temporary_parent" && pwd -P) || exit 2
temporary_root=$(mktemp -d "$temporary_parent/run.XXXXXX") || exit 2
resolved_temporary_root=$(CDPATH= cd -- "$temporary_root" && pwd -P) || exit 2
help_output="$temporary_root/help.txt"
setup_output="$temporary_root/setup.txt"
blocked_output="$temporary_root/blocked.txt"
down_output="$temporary_root/down.txt"
secret_environment_output="$temporary_root/secret-environment.txt"
safe_environment_output="$temporary_root/safe-environment.txt"
command_lock_path="$script_dir/../../.opsmind/command-locks/heavy"
command_lock_owner_path="$command_lock_path/owner.txt"
test_lock_owner_id='command-surface-test-lock'
test_lock_created=false

cleanup() {
    if [ "$test_lock_created" = true ] && [ -f "$command_lock_owner_path" ] && \
        grep -Fqx "Token=$test_lock_owner_id" "$command_lock_owner_path"; then
        rm -f -- "$command_lock_owner_path"
        rmdir -- "$command_lock_path" 2>/dev/null || true
    fi
    case "$resolved_temporary_root" in
        "$temporary_parent_physical/run."*) rm -rf -- "$resolved_temporary_root" ;;
        *) printf '%s\n' "Refusing unsafe command-surface test cleanup: $resolved_temporary_root" >&2 ;;
    esac
}
trap cleanup 0 1 2 15

capture "$help_output" "$subject" help
assert_case 'help succeeds' "$?"
for command in setup dev test lint build up down migrate seed evaluate security; do
    grep -Eq "(^|[[:space:]])$command([[:space:]]|$)" "$help_output"
    assert_case "help lists $command" "$?"
done

OPS_MIN_WORKSPACE_FREE_GB=0 capture "$setup_output" "$subject" setup --dry-run
assert_case 'setup dry-run succeeds' "$?"
grep -q 'Preflight=PASS' "$setup_output"
assert_case 'setup preflight runs first' "$?"
grep -q 'CommandPlan=install pinned' "$setup_output"
assert_case 'setup dry-run avoids installation' "$?"

isolated_root="$temporary_root/isolated"
isolated_script_directory="$isolated_root/scripts/dev"
mkdir -p -- "$isolated_script_directory" || exit 1
cp -- "$subject" "$isolated_script_directory/opsmind.sh" || exit 1
printf '%s\n' '3.13' > "$isolated_root/.python-version" || exit 1
password_name='POSTGRES_''PASSWORD'
printf '%s=runtime-%s\n' "$password_name" 'xxxxxxxxxxxxxxxx' > "$isolated_root/.env" || exit 1
capture "$secret_environment_output" sh "$isolated_script_directory/opsmind.sh" help
secret_environment_status=$?
[ "$secret_environment_status" -ne 0 ]
assert_case 'repository-local secret is rejected' "$?"
grep -q 'cannot be loaded from .env' "$secret_environment_output"
assert_case 'secret rejection explains approved channel' "$?"
printf 'OPS_CACHE_ROOT=\nDEEPSEEK_API_KEY=\n' > "$isolated_root/.env" || exit 1
capture "$safe_environment_output" sh "$isolated_script_directory/opsmind.sh" help
assert_case 'allowlisted non-secret environment loads' "$?"

if [ -e "$command_lock_path" ]; then
    printf '%s\n' 'Cannot test lock contention while another heavyweight command owns the workspace lock.' >&2
    exit 1
fi
mkdir -p -- "$(dirname -- "$command_lock_path")" || exit 1
mkdir -- "$command_lock_path" || exit 1
printf 'Token=%s\n' "$test_lock_owner_id" > "$command_lock_owner_path" || exit 1
test_lock_created=true
OPS_MIN_WORKSPACE_FREE_GB=0 capture "$blocked_output" "$subject" build --dry-run
locked_status=$?
[ "$locked_status" -ne 0 ]
assert_case 'workspace lock blocks concurrent heavy command' "$?"
if grep -q 'CommandPlan=' "$blocked_output"; then false; else true; fi
assert_case 'workspace lock blocks before command plan' "$?"
rm -f -- "$command_lock_owner_path"
rmdir -- "$command_lock_path" || exit 1
test_lock_created=false

OPS_MIN_WORKSPACE_FREE_GB=999999 capture "$blocked_output" "$subject" build --dry-run
blocked_status=$?
[ "$blocked_status" -ne 0 ]
assert_case 'low-space policy blocks heavy command' "$?"
if grep -q 'CommandPlan=' "$blocked_output"; then false; else true; fi
assert_case 'blocked command never reaches plan' "$?"

OPS_MIN_WORKSPACE_FREE_GB=999999 capture "$down_output" "$subject" down --dry-run
assert_case 'down stays available during low space' "$?"
grep -q 'without a capacity gate' "$down_output"
assert_case 'down bypass is explicit' "$?"

printf '%s\n' "Tests=$tests" "Failures=$failures"
if [ "$failures" -gt 0 ]; then printf '%s\n' 'Result=FAIL'; exit 1; fi
printf '%s\n' 'Result=PASS'
