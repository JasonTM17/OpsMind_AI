#!/usr/bin/env sh

set -u

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
repository_root=$(CDPATH= cd -- "$script_dir/../.." && pwd) || exit 2
platform=$(uname -s 2>/dev/null || printf '%s' 'unknown')
windows_posix=false
case "$platform" in
    MINGW*|MSYS*|CYGWIN*) windows_posix=true ;;
esac

temporary_parent="$repository_root/.opsmind"
repository_logical=$(CDPATH= cd -- "$repository_root" && pwd -L) || exit 2
repository_physical=$(CDPATH= cd -- "$repository_root" && pwd -P) || exit 2
if [ "$repository_logical" != "$repository_physical" ]; then
    printf '%s\n' 'Refusing to create portable-test data through a repository symlink.' >&2
    exit 2
fi
if [ -e "$temporary_parent" ] && [ ! -d "$temporary_parent" ]; then
    printf '%s\n' 'Portable-test parent exists but is not a directory.' >&2
    exit 2
fi
if [ -d "$temporary_parent" ]; then
    temporary_parent_logical=$(CDPATH= cd -- "$temporary_parent" && pwd -L) || exit 2
    temporary_parent_physical=$(CDPATH= cd -- "$temporary_parent" && pwd -P) || exit 2
    if [ "$temporary_parent_logical" != "$temporary_parent_physical" ]; then
        printf '%s\n' 'Refusing to create portable-test data through a symlinked parent.' >&2
        exit 2
    fi
fi
if ! mkdir -p -- "$temporary_parent"; then
    printf '%s\n' 'Unable to create the portable-test parent directory.' >&2
    exit 2
fi
temporary_parent_physical=$(CDPATH= cd -- "$temporary_parent" && pwd -P) || exit 2
test_root=$(mktemp -d "$temporary_parent/portable-storage-tests.XXXXXX") || exit 2
resolved_test_root=$(CDPATH= cd -- "$test_root" && pwd -P) || exit 2

cleanup() {
    case "$resolved_test_root" in
        "$temporary_parent_physical/portable-storage-tests."*) rm -rf -- "$resolved_test_root" ;;
        *) printf '%s\n' "Refusing unsafe portable-test cleanup: $resolved_test_root" >&2 ;;
    esac
}
trap cleanup EXIT HUP INT TERM

expect_exit() {
    expected=$1
    shift
    "$@"
    actual=$?
    if [ "$actual" -ne "$expected" ]; then
        printf 'Unexpected exit code. Expected=%s Actual=%s Command=%s\n' "$expected" "$actual" "$1" >&2
        exit 1
    fi
}

capacity_script="$script_dir/check-capacity.sh"
roots_script="$script_dir/assert-storage-roots.sh"

capacity_pass_evidence="$test_root/capacity-pass.txt"
expect_exit 0 "$capacity_script" --path "$repository_root" --min-workspace-free-gb 0 --evidence "$capacity_pass_evidence"
grep -q 'Result=PASS' "$capacity_pass_evidence" || exit 1

capacity_block_evidence="$test_root/capacity-block.txt"
expect_exit 3 "$capacity_script" --path "$repository_root" --min-workspace-free-gb 1000000 --evidence "$capacity_block_evidence"
grep -q 'Result=BLOCK' "$capacity_block_evidence" || exit 1

capacity_roots_evidence="$test_root/capacity-roots.txt"
if [ "$windows_posix" = true ]; then
    alternate_volume_root='C:/Windows'
else
    alternate_volume_root='/dev/shm'
    if [ ! -d /dev/shm ]; then alternate_volume_root='/'; fi
fi
expect_exit 0 env OPS_MODEL_ROOT="$alternate_volume_root" "$capacity_script" \
    --path "$repository_root" --min-workspace-free-gb 0 --evidence "$capacity_roots_evidence"
grep -q 'OPS_MODEL_ROOT' "$capacity_roots_evidence" || exit 1
if [ "$windows_posix" = true ]; then
    grep -q 'PathsChecked=6' "$capacity_roots_evidence" || exit 1
    grep -q '^windows-system-volume "C:/"' "$capacity_roots_evidence" || exit 1
else
    grep -q 'PathsChecked=5' "$capacity_roots_evidence" || exit 1
fi
if [ "$windows_posix" = true ]; then grep -q 'VolumesChecked=2' "$capacity_roots_evidence" || exit 1; fi
if [ "$windows_posix" != true ] && [ "$alternate_volume_root" = /dev/shm ]; then
    grep -q 'VolumesChecked=2' "$capacity_roots_evidence" || exit 1
fi

if [ "$windows_posix" = true ]; then
    windows_c_block_evidence="$test_root/capacity-windows-c-block.txt"
    expect_exit 3 env OPS_MIN_C_FREE_GB=1000000 "$capacity_script" \
        --path "$repository_root" --min-workspace-free-gb 0 --evidence "$windows_c_block_evidence"
    grep -q '^windows-system-volume "C:/".* BLOCK below-threshold$' "$windows_c_block_evidence" || exit 1
fi

if [ "$windows_posix" = true ]; then
    in_repo_missing_root=$(cygpath -m "$test_root/missing-in-repo-capacity-root") || exit 1
else
    in_repo_missing_root="$test_root/alias/../missing-in-repo-capacity-root"
fi
expect_exit 0 env OPS_MODEL_ROOT="$in_repo_missing_root" "$capacity_script" \
    --path "$repository_root" --min-workspace-free-gb 0 --evidence "$test_root/capacity-missing-in-repo-root.txt"
if [ -e "$test_root/missing-in-repo-capacity-root" ]; then
    printf '%s\n' 'Portable capacity check created a missing in-repository root.' >&2
    exit 1
fi

if [ "$windows_posix" = true ]; then
    missing_capacity_root="C:/Windows/OpsMindMissingCapacityRoot.$$"
elif [ -d /dev/shm ]; then
    missing_capacity_root="/dev/shm/OpsMindMissingCapacityRoot.$$"
else
    missing_capacity_root="/OpsMindMissingCapacityRoot.$$"
fi
expect_exit 3 env OPS_MODEL_ROOT="$missing_capacity_root" "$capacity_script" \
    --path "$repository_root" --min-workspace-free-gb 0 --evidence "$test_root/capacity-missing-root.txt"
if [ -e "$missing_capacity_root" ]; then
    printf '%s\n' 'Portable capacity check created a missing configured root.' >&2
    exit 1
fi

unsafe_artifact_output="$test_root/capacity-unsafe-artifact-root.txt"
expect_exit 3 env OPS_ARTIFACT_ROOT="$repository_root" "$capacity_script" \
    --path "$repository_root" --min-workspace-free-gb 0 > "$unsafe_artifact_output"
grep -q 'artifact-root-contains-repository' "$unsafe_artifact_output" || exit 1
grep -q 'EvidencePublication=STDOUT_ONLY_ARTIFACT_ROOT_NOT_CREATED' "$unsafe_artifact_output" || exit 1

safe_base="$test_root/safe"
safe_evidence="$test_root/roots-pass.txt"
OPS_CACHE_ROOT="$safe_base/cache" \
OPS_ARTIFACT_ROOT="$safe_base/artifacts" \
OPS_DATA_ROOT="$safe_base/data" \
OPS_MODEL_ROOT="$safe_base/models" \
expect_exit 0 "$roots_script" --create-missing --evidence "$safe_evidence"
grep -q 'Result=PASS' "$safe_evidence" || exit 1

missing_base="$test_root/missing-default-evidence"
mkdir -p -- "$missing_base/cache" "$missing_base/data" "$missing_base/models" || exit 1
OPS_CACHE_ROOT="$missing_base/cache" \
OPS_ARTIFACT_ROOT="$missing_base/artifacts" \
OPS_DATA_ROOT="$missing_base/data" \
OPS_MODEL_ROOT="$missing_base/models" \
expect_exit 4 "$roots_script"
if [ -e "$missing_base/artifacts" ]; then
    printf '%s\n' 'Portable root guard created the artifact root without --create-missing.' >&2
    exit 1
fi

overlap_base="$test_root/overlap"
overlap_evidence="$test_root/roots-overlap.txt"
mkdir -p -- "$overlap_base/alias" || exit 1
OPS_CACHE_ROOT="$overlap_base/alias/../shared" \
OPS_ARTIFACT_ROOT="$overlap_base/shared" \
OPS_DATA_ROOT="$overlap_base/data" \
OPS_MODEL_ROOT="$overlap_base/models" \
expect_exit 4 "$roots_script" --create-missing --evidence "$overlap_evidence"
grep -q 'overlapping-root' "$overlap_evidence" || exit 1

volume_evidence="$test_root/roots-volume.txt"
if [ "$windows_posix" = true ]; then
    unsafe_root='C:/OpsMindUnsafe/cache'
    expected_reason='non-d-volume-disallowed'
else
    unsafe_root='D:/OpsMindUnsafe/cache'
    expected_reason='not-absolute'
fi
OPS_CACHE_ROOT="$unsafe_root" \
OPS_ARTIFACT_ROOT="$safe_base/artifacts-2" \
OPS_DATA_ROOT="$safe_base/data-2" \
OPS_MODEL_ROOT="$safe_base/models-2" \
expect_exit 4 "$roots_script" --evidence "$volume_evidence"
grep -q "$expected_reason" "$volume_evidence" || exit 1

(CDPATH= cd -- "$test_root" && expect_exit 2 "$capacity_script" --path . --min-workspace-free-gb 0 --evidence "$test_root/relative-path.txt") || exit 1

if [ "$windows_posix" = true ]; then
    printf '%s\n' 'Portable storage guard tests: PASS (12/12)'
else
    printf '%s\n' 'Portable storage guard tests: PASS (11/11)'
fi
