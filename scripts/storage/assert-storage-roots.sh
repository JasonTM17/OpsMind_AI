#!/usr/bin/env sh

set -u

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || {
    printf '%s\n' 'Unable to resolve the storage-guard script directory.' >&2
    exit 2
}
repository_root=$(CDPATH= cd -- "$script_dir/../.." && pwd) || {
    printf '%s\n' 'Unable to resolve the repository root.' >&2
    exit 2
}
create_missing=false
evidence_path=''
evidence_path_was_explicit=false
platform=$(uname -s 2>/dev/null || printf '%s' 'unknown')
windows_posix=false
case "$platform" in
    MINGW*|MSYS*|CYGWIN*) windows_posix=true ;;
esac

while [ "$#" -gt 0 ]; do
    case "$1" in
        --create-missing)
            create_missing=true
            shift
            ;;
        --evidence)
            if [ "$#" -lt 2 ]; then printf '%s\n' '--evidence requires a value.' >&2; exit 2; fi
            evidence_path=$2
            evidence_path_was_explicit=true
            shift 2
            ;;
        *)
            printf '%s\n' "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

cache_root=${OPS_CACHE_ROOT:-"$repository_root/.opsmind/cache"}
artifact_root=${OPS_ARTIFACT_ROOT:-"$repository_root/artifacts"}
data_root=${OPS_DATA_ROOT:-"$repository_root/.opsmind/data"}
model_root=${OPS_MODEL_ROOT:-"$repository_root/.opsmind/models"}
if [ -z "$evidence_path" ]; then
    evidence_path="$artifact_root/verification/phase-01/storage-roots-portable.txt"
fi

paths_overlap() {
    left=${1%/}
    right=${2%/}
    if [ "$left" = "$right" ]; then
        return 0
    fi
    case "$left/" in "$right/"*) return 0 ;; esac
    case "$right/" in "$left/"*) return 0 ;; esac
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

root_overlaps_other() {
    case "$1" in
        OPS_CACHE_ROOT)
            paths_overlap "$2" "$artifact_comparison" || paths_overlap "$2" "$data_comparison" || paths_overlap "$2" "$model_comparison"
            ;;
        OPS_ARTIFACT_ROOT)
            paths_overlap "$2" "$cache_comparison" || paths_overlap "$2" "$data_comparison" || paths_overlap "$2" "$model_comparison"
            ;;
        OPS_DATA_ROOT)
            paths_overlap "$2" "$cache_comparison" || paths_overlap "$2" "$artifact_comparison" || paths_overlap "$2" "$model_comparison"
            ;;
        OPS_MODEL_ROOT)
            paths_overlap "$2" "$cache_comparison" || paths_overlap "$2" "$artifact_comparison" || paths_overlap "$2" "$data_comparison"
            ;;
        *) return 1 ;;
    esac
}

cache_comparison=$(normalize_comparison_path "$cache_root")
artifact_comparison=$(normalize_comparison_path "$artifact_root")
data_comparison=$(normalize_comparison_path "$data_root")
model_comparison=$(normalize_comparison_path "$model_root")
repository_comparison=$(normalize_comparison_path "$repository_root")

timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
transcript_tmp=$(mktemp "${TMPDIR:-/tmp}/opsmind-storage-roots.XXXXXX") || {
    printf '%s\n' 'Unable to create temporary storage-root transcript.' >&2
    exit 4
}
publish_tmp=''
cleanup_temporary_files() {
    if [ -n "${transcript_tmp:-}" ]; then rm -f -- "$transcript_tmp"; fi
    if [ -n "${publish_tmp:-}" ]; then rm -f -- "$publish_tmp"; fi
}
trap cleanup_temporary_files 0 1 2 15
failed=false
artifact_root_valid=false

if ! {
    printf '%s\n' 'OpsMind portable storage root preflight'
    printf 'TimestampUtc=%s\n' "$timestamp"
    printf 'Name Path Status Reason\n'
} > "$transcript_tmp"; then
    rm -f -- "$transcript_tmp"
    printf '%s\n' 'Unable to initialize storage-root evidence.' >&2
    exit 4
fi

seen_paths='|'
seen_identities='|'
for entry in \
    "OPS_CACHE_ROOT|$cache_root" \
    "OPS_ARTIFACT_ROOT|$artifact_root" \
    "OPS_DATA_ROOT|$data_root" \
    "OPS_MODEL_ROOT|$model_root"
do
    name=${entry%%|*}
    root_path=${entry#*|}
    status=PASS
    reason=writable

    single_line_path=$(printf '%s' "$root_path" | tr -d '[:cntrl:]')
    if [ "$single_line_path" != "$root_path" ]; then
        status=BLOCK
        reason=unsupported-path-character
    fi

    case "$root_path" in
        /*) : ;;
        [A-Za-z]:[\\/]*)
            if [ "$windows_posix" != true ]; then
                status=BLOCK
                reason=not-absolute
            fi
            ;;
        *) status=BLOCK; reason=not-absolute ;;
    esac

    if [ "$status" = PASS ]; then
        case "$root_path" in
            /|/mnt/[A-Za-z]|/mnt/[A-Za-z]/)
                status=BLOCK; reason=volume-root-disallowed ;;
            //\?/*|//./*|\\\\\?\\*|\\\\.\\*)
                status=BLOCK; reason=device-namespace-disallowed ;;
            *'|'*|*'"'*)
                status=BLOCK; reason=unsupported-path-character ;;
        esac
        if [ "$status" = PASS ] && [ "$windows_posix" = true ]; then
            case "$root_path" in
                /[A-Za-z]|/[A-Za-z]/|[A-Za-z]:|[A-Za-z]:[\\/])
                    status=BLOCK; reason=volume-root-disallowed ;;
            esac
        fi
    fi

    if [ "$status" = PASS ] && [ "$windows_posix" = true ]; then
        case "$root_path" in
            /d/*|/D/*|[Dd]:[\\/]*) : ;;
            *) status=BLOCK; reason=non-d-volume-disallowed ;;
        esac
    fi

    if [ "$status" = PASS ]; then
        case "$root_path" in
            /mnt/c/*|/mnt/C/*) status=BLOCK; reason=system-volume-disallowed ;;
        esac
        if [ "$status" = PASS ] && [ "$windows_posix" = true ]; then
            case "$root_path" in
                /c/*|/C/*|[Cc]:[\\/]*) status=BLOCK; reason=system-volume-disallowed ;;
            esac
        fi
    fi

    case "$name" in
        OPS_CACHE_ROOT) comparison_path=$cache_comparison ;;
        OPS_ARTIFACT_ROOT) comparison_path=$artifact_comparison ;;
        OPS_DATA_ROOT) comparison_path=$data_comparison ;;
        OPS_MODEL_ROOT) comparison_path=$model_comparison ;;
    esac
    if [ "$status" = PASS ] && root_overlaps_other "$name" "$comparison_path"; then
        status=BLOCK
        reason=overlapping-root
    fi

    case "$seen_paths" in
        *"|$comparison_path|"*) status=BLOCK; reason=duplicate-root ;;
        *) seen_paths="$seen_paths$comparison_path|" ;;
    esac

    if [ "$status" = PASS ] && [ ! -d "$root_path" ]; then
        if [ "$create_missing" = true ]; then
            case "$comparison_path/" in
                "$repository_comparison/"*)
                    if ! mkdir -p -- "$root_path"; then status=BLOCK; reason=create-failed; fi
                    ;;
                *) status=BLOCK; reason=external-root-must-exist ;;
            esac
        else
            status=BLOCK
            reason=missing
        fi
    fi

    if [ "$status" = PASS ]; then
        physical_path=$(CDPATH= cd -- "$root_path" 2>/dev/null && pwd -P) || physical_path=''
        logical_path=$(CDPATH= cd -- "$root_path" 2>/dev/null && pwd -L) || logical_path=''
        if [ -z "$physical_path" ] || [ -z "$logical_path" ]; then
            status=BLOCK
            reason=resolve-failed
        elif [ "$physical_path" != "$logical_path" ]; then
            status=BLOCK
            reason=symlink-path-disallowed
        fi
    fi

    if [ "$status" = PASS ]; then
        identity_device=$(df -Pk "$root_path" 2>/dev/null | awk 'NR == 2 { print $1 }')
        identity_inode=$(ls -di -- "$root_path" 2>/dev/null | awk 'NR == 1 { print $1 }')
        physical_identity="$identity_device:$identity_inode"
        if [ -z "$identity_device" ] || [ -z "$identity_inode" ]; then
            status=BLOCK
            reason=identity-query-failed
        else
            case "$seen_identities" in
                *"|$physical_identity|"*) status=BLOCK; reason=duplicate-physical-root ;;
                *) seen_identities="$seen_identities$physical_identity|" ;;
            esac
        fi
    fi

    if [ "$status" = PASS ]; then
        probe_path=$(mktemp "$root_path/.opsmind-write-probe.XXXXXX") || probe_path=''
        if [ -n "$probe_path" ] && printf '%s' 'write-probe' > "$probe_path" 2>/dev/null; then
            if ! rm -f -- "$probe_path"; then
                status=BLOCK
                reason=probe-cleanup-failed
            fi
        else
            if [ -n "$probe_path" ]; then rm -f -- "$probe_path"; fi
            status=BLOCK
            reason=not-writable
        fi
    fi

    if [ "$status" = BLOCK ]; then
        failed=true
    fi
    if [ "$name" = OPS_ARTIFACT_ROOT ] && [ "$status" = PASS ]; then
        artifact_root_valid=true
    fi
    if ! printf '%s "%s" %s %s\n' "$name" "$root_path" "$status" "$reason" >> "$transcript_tmp"; then
        rm -f -- "$transcript_tmp"
        printf '%s\n' 'Unable to append storage-root evidence.' >&2
        exit 4
    fi
done

if [ "$failed" = true ]; then
    result=BLOCK
else
    result=PASS
fi
if [ "$evidence_path_was_explicit" != true ] && [ "$artifact_root_valid" != true ]; then
    if ! printf '%s\n' 'EvidencePublication=SKIPPED_INVALID_ARTIFACT_ROOT' >> "$transcript_tmp"; then
        printf '%s\n' 'Unable to record skipped evidence publication.' >&2
        exit 4
    fi
fi
if ! printf 'Result=%s\n' "$result" >> "$transcript_tmp"; then
    rm -f -- "$transcript_tmp"
    printf '%s\n' 'Unable to finalize storage-root evidence.' >&2
    exit 4
fi

if ! cat "$transcript_tmp"; then
    rm -f -- "$transcript_tmp"
    printf '%s\n' 'Unable to read generated storage-root evidence.' >&2
    exit 4
fi
if [ "$evidence_path_was_explicit" = true ] || [ "$artifact_root_valid" = true ]; then
    evidence_dir=$(dirname -- "$evidence_path")
    if ! mkdir -p -- "$evidence_dir"; then
        printf '%s\n' "Unable to create evidence directory: $evidence_dir" >&2
        exit 4
    fi
    publish_tmp=$(mktemp "$evidence_dir/.storage-roots.XXXXXX") || {
        printf '%s\n' "Unable to create evidence file in: $evidence_dir" >&2
        exit 4
    }
    if ! cat "$transcript_tmp" > "$publish_tmp" || ! mv -- "$publish_tmp" "$evidence_path"; then
        printf '%s\n' "Unable to publish storage-root evidence: $evidence_path" >&2
        exit 4
    fi
    publish_tmp=''
fi

if [ "$failed" = true ]; then
    exit 4
fi

exit 0
