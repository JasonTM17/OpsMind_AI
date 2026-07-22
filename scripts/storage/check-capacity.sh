#!/usr/bin/env sh

set -u

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
repository_root=$(CDPATH= cd -- "$script_dir/../.." && pwd) || exit 2
platform=$(uname -s 2>/dev/null || printf '%s' 'unknown')
windows_posix=false
case "$platform" in MINGW*|MSYS*|CYGWIN*) windows_posix=true ;; esac

target_path=$repository_root
minimum_free_gb=${OPS_MIN_WORKSPACE_FREE_GB:-20}
minimum_c_free_gb=${OPS_MIN_C_FREE_GB:-10}
cache_root=${OPS_CACHE_ROOT:-"$repository_root/.opsmind/cache"}
artifact_root=${OPS_ARTIFACT_ROOT:-"$repository_root/artifacts"}
data_root=${OPS_DATA_ROOT:-"$repository_root/.opsmind/data"}
model_root=${OPS_MODEL_ROOT:-"$repository_root/.opsmind/models"}
evidence_path=''
evidence_path_was_explicit=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --path)
            if [ "$#" -lt 2 ]; then printf '%s\n' '--path requires a value.' >&2; exit 2; fi
            target_path=$2
            shift 2
            ;;
        --min-workspace-free-gb)
            if [ "$#" -lt 2 ]; then printf '%s\n' '--min-workspace-free-gb requires a value.' >&2; exit 2; fi
            minimum_free_gb=$2
            shift 2
            ;;
        --evidence)
            if [ "$#" -lt 2 ]; then printf '%s\n' '--evidence requires a value.' >&2; exit 2; fi
            evidence_path=$2
            evidence_path_was_explicit=true
            shift 2
            ;;
        *) printf '%s\n' "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$evidence_path" ]; then
    evidence_path="$artifact_root/verification/phase-01/disk-preflight-portable.txt"
fi
if ! awk -v value="$minimum_free_gb" 'BEGIN { exit !(value ~ /^[0-9]+([.][0-9]+)?$/) }'; then
    printf '%s\n' 'Minimum free space must be a non-negative number expressed in GB.' >&2
    exit 2
fi
if ! awk -v value="$minimum_c_free_gb" 'BEGIN { exit !(value ~ /^[0-9]+([.][0-9]+)?$/) }'; then
    printf '%s\n' 'OPS_MIN_C_FREE_GB must be a non-negative number expressed in GB.' >&2
    exit 2
fi

is_absolute_path() {
    case "$1" in
        /*) return 0 ;;
        [A-Za-z]:[\\/]*) if [ "$windows_posix" = true ]; then return 0; fi ;;
    esac
    return 1
}

normalize_comparison_path() {
    comparison_input=$1
    if [ "$windows_posix" = true ]; then
        if command -v cygpath >/dev/null 2>&1; then
            converted_input=$(cygpath -am "$comparison_input" 2>/dev/null) || converted_input=''
            if [ -n "$converted_input" ]; then comparison_input=$converted_input; fi
        else
            case "$comparison_input" in
                /[A-Za-z]/*)
                    drive_path=${comparison_input#/}
                    drive_letter=${drive_path%%/*}
                    comparison_input="$drive_letter:/${drive_path#*/}"
                    ;;
            esac
        fi
    fi
    normalized=$(printf '%s' "$comparison_input" | tr '\\' '/' | awk '
        {
            path = $0
            prefix = "/"
            if (path ~ /^[A-Za-z]:\//) {
                prefix = substr(path, 1, 2) "/"
                path = substr(path, 4)
            } else {
                sub(/^\/+/, "", path)
            }
            count = split(path, parts, "/")
            depth = 0
            for (i = 1; i <= count; i++) {
                if (parts[i] == "" || parts[i] == ".") continue
                if (parts[i] == "..") {
                    if (depth > 0) depth--
                    continue
                }
                stack[++depth] = parts[i]
            }
            output = prefix
            for (i = 1; i <= depth; i++) {
                if (i > 1) output = output "/"
                output = output stack[i]
            }
            print output
        }
    ')
    if [ "$windows_posix" = true ]; then
        normalized=$(printf '%s' "$normalized" | tr '[:upper:]' '[:lower:]')
    fi
    printf '%s\n' "$normalized"
}

windows_capacity_entry=''
if [ "$windows_posix" = true ]; then
    windows_capacity_entry="windows-system-volume|C:/|$minimum_c_free_gb"
fi

for entry in $windows_capacity_entry \
    "workspace|$target_path|$minimum_free_gb" \
    "OPS_CACHE_ROOT|$cache_root|$minimum_free_gb" \
    "OPS_ARTIFACT_ROOT|$artifact_root|$minimum_free_gb" \
    "OPS_DATA_ROOT|$data_root|$minimum_free_gb" \
    "OPS_MODEL_ROOT|$model_root|$minimum_free_gb"
do
    label=${entry%%|*}
    entry_remainder=${entry#*|}
    candidate=${entry_remainder%%|*}
    sanitized=$(printf '%s' "$candidate" | tr -d '[:cntrl:]')
    if [ "$sanitized" != "$candidate" ] || ! is_absolute_path "$candidate"; then
        printf '%s\n' "$label must be an absolute path without control characters." >&2
        exit 2
    fi
    case "$candidate" in *'|'*|*'"'*) printf '%s\n' "$label contains an unsupported path character." >&2; exit 2 ;; esac
done

evidence_tmp=$(mktemp "${TMPDIR:-/tmp}/opsmind-disk-preflight.XXXXXX") || exit 2
publish_tmp=''
cleanup_temporary_files() {
    if [ -n "${evidence_tmp:-}" ]; then rm -f -- "$evidence_tmp"; fi
    if [ -n "${publish_tmp:-}" ]; then rm -f -- "$publish_tmp"; fi
}
trap cleanup_temporary_files 0 1 2 15
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
if ! {
    printf '%s\n' 'OpsMind portable storage capacity preflight'
    printf 'TimestampUtc=%s\n' "$timestamp"
    printf 'Source Path Device Mount FreeGb MinimumGb Status Reason\n'
} > "$evidence_tmp"; then
    rm -f -- "$evidence_tmp"
    exit 2
fi

failed=false
artifact_root_usable=false
repository_comparison=$(normalize_comparison_path "$repository_root")
seen_volumes='|'
volume_count=0
path_count=0
for entry in $windows_capacity_entry \
    "workspace|$target_path|$minimum_free_gb" \
    "OPS_CACHE_ROOT|$cache_root|$minimum_free_gb" \
    "OPS_ARTIFACT_ROOT|$artifact_root|$minimum_free_gb" \
    "OPS_DATA_ROOT|$data_root|$minimum_free_gb" \
    "OPS_MODEL_ROOT|$model_root|$minimum_free_gb"
do
    label=${entry%%|*}
    entry_remainder=${entry#*|}
    candidate=${entry_remainder%%|*}
    row_minimum_free_gb=${entry_remainder#*|}
    candidate_comparison=$(normalize_comparison_path "$candidate")
    path_count=$((path_count + 1))
    if [ ! -d "$candidate" ]; then
        existing_path=''
        if [ "$label" != workspace ]; then
            case "$candidate_comparison/" in "$repository_comparison/"*) existing_path=$repository_root ;; esac
        fi
        if [ -z "$existing_path" ]; then
            failed=true
            printf '%s "%s" unavailable unavailable unavailable %s BLOCK external-configured-path-missing\n' \
                "$label" "$candidate" "$row_minimum_free_gb" >> "$evidence_tmp" || exit 2
            continue
        fi
    else
        existing_path=$(CDPATH= cd -- "$candidate" 2>/dev/null && pwd -P) || existing_path=''
    fi
    if [ -z "$existing_path" ] || ! df_output=$(df -Pk "$existing_path" 2>/dev/null); then
        failed=true
        printf '%s "%s" unavailable unavailable unavailable %s BLOCK capacity-query-failed\n' \
            "$label" "$candidate" "$row_minimum_free_gb" >> "$evidence_tmp" || exit 2
        continue
    fi

    device=$(printf '%s\n' "$df_output" | awk 'NR == 2 { print $1 }')
    available_kb=$(printf '%s\n' "$df_output" | awk 'NR == 2 { print $4 }')
    mount_path=$(printf '%s\n' "$df_output" | awk 'NR == 2 { print $6 }')
    case "$available_kb" in
        ''|*[!0-9]*)
            failed=true
            printf '%s "%s" "%s" "%s" unavailable %s BLOCK invalid-capacity-result\n' \
                "$label" "$candidate" "$device" "$mount_path" "$row_minimum_free_gb" >> "$evidence_tmp" || exit 2
            continue
            ;;
    esac

    volume_identity="$device@$mount_path"
    case "$seen_volumes" in
        *"|$volume_identity|"*) : ;;
        *) seen_volumes="$seen_volumes$volume_identity|"; volume_count=$((volume_count + 1)) ;;
    esac
    free_gb=$(awk -v kb="$available_kb" 'BEGIN { printf "%.2f", kb / 1048576 }')
    unsafe_artifact_reason=''
    if [ "$label" = OPS_ARTIFACT_ROOT ] && [ -d "$candidate" ]; then
        logical_path=$(CDPATH= cd -- "$candidate" 2>/dev/null && pwd -L) || logical_path=''
        physical_comparison=$(normalize_comparison_path "$existing_path")
        logical_comparison=$(normalize_comparison_path "$logical_path")
        mount_comparison=$(normalize_comparison_path "$mount_path")
        if [ -z "$logical_path" ] || [ "$logical_comparison" != "$physical_comparison" ]; then
            unsafe_artifact_reason=artifact-root-symlink-disallowed
        elif [ "$physical_comparison" = "$mount_comparison" ]; then
            unsafe_artifact_reason=artifact-volume-root-disallowed
        else
            case "$repository_comparison/" in
                "$physical_comparison/"*) unsafe_artifact_reason=artifact-root-contains-repository ;;
            esac
        fi
    fi
    if [ -n "$unsafe_artifact_reason" ]; then
        failed=true
        row_status=BLOCK
        reason=$unsafe_artifact_reason
    else
        row_status=$(awk -v kb="$available_kb" -v minimum="$row_minimum_free_gb" \
            'BEGIN { if (kb >= minimum * 1048576) print "PASS"; else print "BLOCK" }')
        if [ "$row_status" = BLOCK ]; then failed=true; reason=below-threshold; else reason=available; fi
        if [ "$label" = OPS_ARTIFACT_ROOT ] && [ -d "$candidate" ]; then artifact_root_usable=true; fi
    fi
    printf '%s "%s" "%s" "%s" %s %s %s %s\n' \
        "$label" "$candidate" "$device" "$mount_path" "$free_gb" "$row_minimum_free_gb" "$row_status" "$reason" \
        >> "$evidence_tmp" || exit 2
done

if [ "$failed" = true ]; then result=BLOCK; else result=PASS; fi
{
    printf 'PathsChecked=%s\n' "$path_count"
    printf 'VolumesChecked=%s\n' "$volume_count"
    if [ "$evidence_path_was_explicit" != true ] && [ "$artifact_root_usable" != true ]; then
        printf '%s\n' 'EvidencePublication=STDOUT_ONLY_ARTIFACT_ROOT_NOT_CREATED'
    fi
    printf 'Result=%s\n' "$result"
} >> "$evidence_tmp" || exit 2
cat "$evidence_tmp" || { rm -f -- "$evidence_tmp"; exit 2; }
if [ "$evidence_path_was_explicit" = true ] || [ "$artifact_root_usable" = true ]; then
    evidence_dir=$(dirname -- "$evidence_path")
    mkdir -p -- "$evidence_dir" || exit 2
    publish_tmp=$(mktemp "$evidence_dir/.disk-preflight.XXXXXX") || exit 2
    if ! cat "$evidence_tmp" > "$publish_tmp" || ! mv -- "$publish_tmp" "$evidence_path"; then exit 2; fi
    publish_tmp=''
fi

if [ "$failed" = true ]; then exit 3; fi
exit 0
