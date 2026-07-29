#!/usr/bin/env sh

set -u

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
repository_root=$(CDPATH= cd -- "$script_dir/../.." && pwd) || exit 2
cd "$repository_root" || exit 2
command_name=${1:-help}
command_lock_path="$repository_root/.opsmind/command-locks/heavy"
command_lock_owner_path="$command_lock_path/owner.txt"
command_lock_owner_id="$$-$(date -u '+%Y%m%dT%H%M%SZ')"
command_lock_owned=false

enter_command_lock() {
    lock_parent=$(dirname -- "$command_lock_path")
    for lock_ancestor in "$repository_root" "$repository_root/.opsmind" "$lock_parent"; do
        if [ -e "$lock_ancestor" ] && [ ! -d "$lock_ancestor" ]; then
            printf '%s\n' 'Command-lock path contains a non-directory ancestor.' >&2
            return 2
        fi
        if [ -d "$lock_ancestor" ]; then
            ancestor_logical=$(CDPATH= cd -- "$lock_ancestor" 2>/dev/null && pwd -L) || return 2
            ancestor_physical=$(CDPATH= cd -- "$lock_ancestor" 2>/dev/null && pwd -P) || return 2
            if [ "$ancestor_logical" != "$ancestor_physical" ]; then
                printf '%s\n' 'Command-lock path cannot contain a symlinked ancestor.' >&2
                return 2
            fi
        fi
    done
    mkdir -p -- "$lock_parent" || return 2
    if ! mkdir -- "$command_lock_path" 2>/dev/null; then
        if [ -f "$command_lock_owner_path" ]; then
            owner=$(tr '\r\n' '; ' < "$command_lock_owner_path")
        else
            owner='owner metadata unavailable'
        fi
        printf '%s\n' "Another heavyweight OpsMind command owns the workspace lock: $owner" >&2
        return 5
    fi
    command_lock_owned=true
    if ! printf 'Token=%s\nProcessId=%s\nCommand=%s\nStartedUtc=%s\n' \
        "$command_lock_owner_id" "$$" "$command_name" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
        > "$command_lock_owner_path"; then
        if ! rmdir -- "$command_lock_path" 2>/dev/null; then
            printf '%s\n' 'Unable to remove a partially-created command lock.' >&2
        fi
        command_lock_owned=false
        return 2
    fi
    printf '%s\n' 'CommandLock=ACQUIRED'
}

exit_command_lock() {
    [ "$command_lock_owned" = true ] || return 0
    if [ ! -f "$command_lock_owner_path" ] || ! grep -Fqx "Token=$command_lock_owner_id" "$command_lock_owner_path"; then
        printf '%s\n' 'Command-lock ownership changed; refusing cleanup.' >&2
        command_lock_owned=false
        return 0
    fi
    cleanup_status=0
    if ! rm -f -- "$command_lock_owner_path"; then
        printf '%s\n' 'Unable to remove command-lock owner metadata.' >&2
        cleanup_status=2
    fi
    if [ -e "$command_lock_path" ] && ! rmdir -- "$command_lock_path" 2>/dev/null; then
        printf '%s\n' 'Unable to remove command-lock directory.' >&2
        cleanup_status=2
    fi
    if [ -e "$command_lock_path" ]; then
        printf '%s\n' 'Command-lock directory remains after cleanup.' >&2
        cleanup_status=2
    fi
    command_lock_owned=false
    if [ "$cleanup_status" -eq 0 ]; then
        printf '%s\n' 'CommandLock=RELEASED'
    fi
    return "$cleanup_status"
}

trap exit_command_lock 0
trap 'exit 129' 1
trap 'exit 130' 2
trap 'exit 143' 15

load_environment() {
    environment_path="$repository_root/.env"
    [ -f "$environment_path" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        line=$(printf '%s' "$line" | tr -d '\r')
        case "$line" in ''|'#'*) continue ;; esac
        if ! printf '%s' "$line" | grep -q '[^[:space:]]'; then continue; fi
        key=${line%%=*}
        value=${line#*=}
        if [ "$key" = "$line" ] || ! printf '%s' "$key" | awk 'BEGIN { ok=0 } /^[A-Za-z_][A-Za-z0-9_]*$/ { ok=1 } END { exit !ok }'; then
            printf '%s\n' 'Invalid .env entry. Expected KEY=VALUE without shell syntax.' >&2
            return 2
        fi
        case "$key" in
            POSTGRES_PASSWORD|POSTGRES_APP_PASSWORD|POSTGRES_DISPATCHER_PASSWORD|POSTGRES_WORKFLOW_RECONCILER_PASSWORD|POSTGRES_AI_RUNTIME_PASSWORD|POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD|POSTGRES_TOOL_GATEWAY_PASSWORD|TOOL_GATEWAY_DATABASE_PASSWORD|AI_RUNTIME_DATABASE_PASSWORD|SPRING_DATASOURCE_PASSWORD|MINIO_ROOT_PASSWORD|DEEPSEEK_API_KEY|OPSMIND_TOOL_WORKLOAD_CLIENT_SECRET|OPSMIND_DISPATCHER_DB_PASSWORD|OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_API_KEY|OPSMIND_WORKFLOW_RECONCILER_DB_PASSWORD)
                if [ -n "$value" ]; then
                    printf '%s\n' "$key cannot be loaded from .env. Supply runtime secrets through the process environment or an approved secret manager." >&2
                    return 2
                fi
                continue
                ;;
            OPS_CACHE_ROOT|OPS_ARTIFACT_ROOT|OPS_DATA_ROOT|OPS_MODEL_ROOT|\
            OPS_MIN_C_FREE_GB|OPS_MIN_D_FREE_GB|OPS_MIN_WORKSPACE_FREE_GB|\
            OPERATOR_WEB_PORT|PLATFORM_API_PORT|PLATFORM_MANAGEMENT_PORT|OPSMIND_MANAGEMENT_EXPOSED_ENDPOINTS|OPSMIND_MAX_JSON_BODY_BYTES|AI_RUNTIME_MAX_JSON_BODY_BYTES|\
            OPSMIND_DEPLOYMENT_ENVIRONMENT|\
            OPSMIND_OPERATOR_AUTH_MODE|OPSMIND_PLATFORM_API_URL|OPSMIND_PLATFORM_API_TIMEOUT_MS|OPSMIND_PLATFORM_API_ALLOW_LOOPBACK_CLEARTEXT|\
            OPSMIND_AI_RUNTIME_CLIENT_ENABLED|OPSMIND_AI_RUNTIME_ENDPOINT|OPSMIND_AI_RUNTIME_ALLOW_LOCAL_CLEARTEXT|\
            OPSMIND_AI_RUNTIME_CONNECT_TIMEOUT|OPSMIND_AI_RUNTIME_REQUEST_TIMEOUT|OPSMIND_AI_RUNTIME_MAX_RESPONSE_BODY_BYTES|\
            AI_RUNTIME_BODY_RECEIVE_TIMEOUT_SECONDS|AI_RUNTIME_PORT|TOOL_GATEWAY_PORT|PROMETHEUS_PORT|\
            POSTGRES_PORT|REDIS_PORT|MINIO_API_PORT|MINIO_CONSOLE_PORT|\
            POSTGRES_DB|POSTGRES_USER|POSTGRES_APP_USER|POSTGRES_DISPATCHER_USER|POSTGRES_WORKFLOW_RECONCILER_USER|POSTGRES_AI_RUNTIME_USER|POSTGRES_TOOL_GATEWAY_MIGRATOR_USER|POSTGRES_TOOL_GATEWAY_USER|SPRING_PROFILES_ACTIVE|SPRING_DATASOURCE_URL|\
            SPRING_DATASOURCE_USERNAME|OPSMIND_FLYWAY_ENABLED|OPSMIND_DB_POOL_MAX|\
            OPSMIND_DB_CONNECTION_TIMEOUT_MS|MINIO_ROOT_USER|MINIO_IMAGE|OIDC_ISSUER_URL|OIDC_AUDIENCE|\
            OIDC_REQUIRED_AMR|OIDC_MAX_TOKEN_LIFETIME|OIDC_CLOCK_SKEW|OIDC_JWKS_REFRESH_MINIMUM_INTERVAL|\
            AI_PROVIDER|AI_FIXTURE_PROVIDER_ENABLED|DEEPSEEK_MODEL|DEEPSEEK_API_BASE_URL|AI_PROVIDER_ALLOWED_HOSTS|\
            AI_ALLOWED_DATA_CLASSES|AI_PROVIDER_REGION|AI_EGRESS_POLICY_FILE|AI_EGRESS_POLICY_HOST_PATH|\
            AI_INPUT_COST_USD_PER_MILLION|AI_OUTPUT_COST_USD_PER_MILLION|AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR|AI_RUNTIME_STATE_BACKEND|AI_RUNTIME_DATABASE_HOST|\
            AI_RUNTIME_DATABASE_PORT|AI_RUNTIME_DATABASE_NAME|AI_RUNTIME_DATABASE_USER|AI_RUNTIME_DB_POOL_MIN|\
            AI_RUNTIME_DB_POOL_MAX|AI_RUNTIME_DB_POOL_TIMEOUT_SECONDS|AI_RUNTIME_RESERVATION_LEASE_SECONDS|\
            TOOL_GATEWAY_DATABASE_URL|TOOL_GATEWAY_DATABASE_USER|TOOL_GATEWAY_PERSISTENCE_ENABLED|\
            TOOL_GATEWAY_FLYWAY_ENABLED|TOOL_GATEWAY_DB_POOL_MAX|TOOL_GATEWAY_DB_CONNECTION_TIMEOUT_MS|\
            TOOL_GATEWAY_EXECUTION_LEASE_DURATION|\
            TOOL_GATEWAY_PROMETHEUS_ENABLED|TOOL_GATEWAY_PROMETHEUS_BASE_URI|\
            TOOL_GATEWAY_PROMETHEUS_ALLOW_INTERNAL_CLEARTEXT|TOOL_GATEWAY_PROMETHEUS_CONNECT_TIMEOUT|\
            TOOL_GATEWAY_PROMETHEUS_REQUEST_TIMEOUT|TOOL_GATEWAY_PROMETHEUS_MAX_RESPONSE_BYTES|\
            TOOL_GATEWAY_PROMETHEUS_MAX_SERIES|TOOL_GATEWAY_PROMETHEUS_MAX_POINTS|\
            TOOL_GATEWAY_PROMETHEUS_QUERY_WINDOW|TOOL_GATEWAY_PROMETHEUS_QUERY_STEP|\
            OPSMIND_TOOL_CAPABILITY_ISSUANCE_ENABLED|OPSMIND_TOOL_CAPABILITY_ISSUER|\
            OPSMIND_TOOL_CAPABILITY_AUDIENCE|OPSMIND_TOOL_CAPABILITY_AUTHORIZED_PARTY|\
            OPSMIND_TOOL_CAPABILITY_KEY_ID|OPSMIND_TOOL_CAPABILITY_PRIVATE_KEY_FILE|\
            OPSMIND_TOOL_CAPABILITY_MAX_LIFETIME|OPSMIND_TOOL_GATEWAY_CLIENT_ENABLED|\
            OPSMIND_TOOL_GATEWAY_ENDPOINT|OPSMIND_TOOL_GATEWAY_ALLOW_LOCAL_CLEARTEXT|\
            OPSMIND_TOOL_GATEWAY_CONNECT_TIMEOUT|OPSMIND_TOOL_GATEWAY_REQUEST_TIMEOUT|\
            OPSMIND_TOOL_GATEWAY_MAX_RESPONSE_BODY_BYTES|OPSMIND_TOOL_WORKLOAD_AUTH_ENABLED|\
            OPSMIND_TOOL_WORKLOAD_ISSUER|OPSMIND_TOOL_WORKLOAD_TOKEN_ENDPOINT|\
            OPSMIND_TOOL_WORKLOAD_ALLOW_LOCAL_CLEARTEXT|OPSMIND_TOOL_WORKLOAD_AUDIENCE|\
            OPSMIND_TOOL_WORKLOAD_CLIENT_ID|OPSMIND_TOOL_WORKLOAD_SCOPE|\
            OPSMIND_TOOL_WORKLOAD_CONNECT_TIMEOUT|OPSMIND_TOOL_WORKLOAD_REQUEST_TIMEOUT|\
            OPSMIND_TOOL_WORKLOAD_REFRESH_SKEW|OPSMIND_TOOL_WORKLOAD_MAX_TOKEN_LIFETIME|\
            OPSMIND_TOOL_WORKLOAD_MAX_RESPONSE_BODY_BYTES|\
            AI_RUNTIME_INVOCATION_RETENTION_DAYS|OPSMIND_AI_CAPABILITY_ISSUANCE_ENABLED|\
            OPSMIND_AI_CAPABILITY_ISSUER|OPSMIND_AI_CAPABILITY_AUDIENCE|OPSMIND_AI_CAPABILITY_KEY_ID|\
            OPSMIND_AI_CAPABILITY_PRIVATE_KEY_FILE|OPSMIND_AI_CAPABILITY_JWKS_FILE|\
            OPSMIND_AI_CAPABILITY_MAX_LIFETIME|OPSMIND_CAPABILITY_MAX_LIFETIME_SECONDS|\
            OPS_ENABLE_DEEPSEEK_EGRESS|OPS_ENABLE_WRITE_ACTIONS|\
            OPSMIND_SECURITY_MODE|OPSMIND_DISPATCHER_ENABLED|OPSMIND_DISPATCHER_DB_URL|\
            OPSMIND_DISPATCHER_DB_USERNAME|OPSMIND_DISPATCHER_DB_POOL_MAX|\
            OPSMIND_DISPATCHER_DB_CONNECTION_TIMEOUT_MS|OPSMIND_WORKFLOW_RECONCILER_ENABLED|\
            OPSMIND_INVESTIGATION_V1_ENABLED|OPSMIND_INVESTIGATION_FIXTURE|\
            OPSMIND_INVESTIGATION_STORE|OPSMIND_INVESTIGATION_EXECUTION_MODE|\
            OPSMIND_INVESTIGATION_TEMPORAL_CLUSTER_ID|OPSMIND_INVESTIGATION_TEMPORAL_NAMESPACE|\
            OPSMIND_INVESTIGATION_TEMPORAL_WORKFLOW_TYPE|OPSMIND_INVESTIGATION_TEMPORAL_TASK_QUEUE|\
            OPSMIND_INVESTIGATION_TEMPORAL_CLIENT_ENABLED|OPSMIND_INVESTIGATION_TEMPORAL_TARGET|\
            OPSMIND_INVESTIGATION_TEMPORAL_TLS_ENABLED|OPSMIND_INVESTIGATION_TEMPORAL_ALLOW_LOCAL_CLEARTEXT|\
            OPSMIND_INVESTIGATION_TEMPORAL_RPC_TIMEOUT|OPSMIND_INVESTIGATION_TEMPORAL_WORKER_IDENTITY|\
            OPSMIND_INVESTIGATION_TEMPORAL_WORKER_BUILD_ID|\
            OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_ENABLED|OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_TARGET|\
            OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_TLS_ENABLED|OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_ALLOW_LOCAL_CLEARTEXT|\
            OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_RPC_TIMEOUT|OPSMIND_WORKFLOW_RECONCILER_POLL_INTERVAL|\
            OPSMIND_WORKFLOW_RECONCILER_LEASE_DURATION|OPSMIND_WORKFLOW_RECONCILER_SETTLEMENT_MARGIN|\
            OPSMIND_WORKFLOW_RECONCILER_MAX_ATTEMPTS|OPSMIND_WORKFLOW_RECONCILER_MAX_AGE|\
            OPSMIND_WORKFLOW_RECONCILER_MAX_HANDOFF_AGE|OPSMIND_WORKFLOW_RECONCILER_INITIAL_BACKOFF|\
            OPSMIND_WORKFLOW_RECONCILER_MAX_BACKOFF|OPSMIND_WORKFLOW_RECONCILER_ABSENCE_CONFIRMATION_DELAY|\
            OPSMIND_WORKFLOW_RECONCILER_NAMESPACE_RETENTION|OPSMIND_WORKFLOW_RECONCILER_RETENTION_SAFETY_MARGIN|\
            OPSMIND_INVESTIGATION_WORKFLOW_STARTER_ENABLED|OPSMIND_INVESTIGATION_WORKFLOW_STARTER_POLL_INTERVAL|\
            OPSMIND_INVESTIGATION_WORKFLOW_STARTER_LEASE_DURATION|OPSMIND_INVESTIGATION_WORKFLOW_STARTER_RPC_MARGIN|\
            OPSMIND_INVESTIGATION_WORKFLOW_STARTER_MAX_AGE|OPSMIND_INVESTIGATION_WORKFLOW_STARTER_INITIAL_BACKOFF|\
            OPSMIND_INVESTIGATION_WORKFLOW_STARTER_MAX_BACKOFF|OPSMIND_INVESTIGATION_WORKFLOW_STARTER_MAX_ATTEMPTS|\
            OPSMIND_INVESTIGATION_WORKFLOW_STARTER_TENANT_LIMIT|OPSMIND_INVESTIGATION_WORKFLOW_STARTER_BATCH_SIZE|\
            OPSMIND_WORKFLOW_RECONCILER_DB_URL|OPSMIND_WORKFLOW_RECONCILER_DB_USERNAME|\
            OPSMIND_WORKFLOW_RECONCILER_DB_POOL_MAX|OPSMIND_WORKFLOW_RECONCILER_DB_CONNECTION_TIMEOUT_MS|\
            OPSMIND_WORKFLOW_RECONCILER_DB_QUERY_TIMEOUT_SECONDS|OPS_DOCKER_STORAGE_VERIFIED|\
            OPSMIND_EVALUATION_TRACE_PATH|OPSMIND_EVALUATION_OUTPUT_PATH) ;;
            *)
                printf '%s\n' "Unsupported .env key: $key. Only the documented non-secret allowlist is accepted." >&2
                return 2
                ;;
        esac
        if ! env | grep -q "^${key}="; then export "$key=$value"; fi
    done < "$environment_path"
}

run_checked() {
    "$@"
    status=$?
    if [ "$status" -ne 0 ]; then
        printf '%s\n' "Command failed with exit code $status: $1" >&2
        exit "$status"
    fi
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        printf '%s\n' "Required command is unavailable: $1" >&2
        exit 2
    }
}

assert_setup_toolchain() {
    expected_node=$(tr -d '\r\n' < "$repository_root/.node-version")
    actual_node=$(node --version | sed 's/^v//')
    [ "$actual_node" = "$expected_node" ] || {
        printf 'Node version mismatch. Expected=%s Actual=%s\n' "$expected_node" "$actual_node" >&2
        return 2
    }

    expected_pnpm=$(node -p "require('./package.json').packageManager.split('@').pop()") || return 2
    actual_pnpm=$(corepack pnpm --version 2>/dev/null) || return 2
    [ "$actual_pnpm" = "$expected_pnpm" ] || {
        printf 'pnpm version mismatch. Expected=%s Actual=%s\n' "$expected_pnpm" "$actual_pnpm" >&2
        return 2
    }

    expected_java=$(tr -d '\r\n' < "$repository_root/.java-version")
    actual_java=$(java -version 2>&1 | awk -F '"' 'NR == 1 { split($2, version, "."); print version[1] }')
    [ "$actual_java" = "$expected_java" ] || {
        printf 'Java version mismatch. Expected=%s Actual=%s\n' "$expected_java" "${actual_java:-MISSING}" >&2
        return 2
    }

    expected_maven=$(tr -d '\r\n' < "$repository_root/.maven-version")
    actual_maven=$(mvn --version 2>/dev/null | sed -n '1s/^Apache Maven \([0-9.]*\).*/\1/p')
    [ "$actual_maven" = "$expected_maven" ] || {
        printf 'Maven version mismatch. Expected=%s Actual=%s\n' "$expected_maven" "${actual_maven:-MISSING}" >&2
        return 2
    }
}

assert_application_compose_configuration() {
    if [ -z "${POSTGRES_PASSWORD:-}" ] || ! printf '%s' "$POSTGRES_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    if [ -z "${POSTGRES_APP_PASSWORD:-}" ] || ! printf '%s' "$POSTGRES_APP_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_APP_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    if [ -z "${POSTGRES_DISPATCHER_PASSWORD:-}" ] || ! printf '%s' "$POSTGRES_DISPATCHER_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_DISPATCHER_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    if [ -z "${POSTGRES_WORKFLOW_RECONCILER_PASSWORD:-}" ] ||
       ! printf '%s' "$POSTGRES_WORKFLOW_RECONCILER_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_WORKFLOW_RECONCILER_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    if [ -z "${POSTGRES_AI_RUNTIME_PASSWORD:-}" ] || ! printf '%s' "$POSTGRES_AI_RUNTIME_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_AI_RUNTIME_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    if [ -z "${POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD:-}" ] ||
       ! printf '%s' "$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    if [ -z "${POSTGRES_TOOL_GATEWAY_PASSWORD:-}" ] ||
       ! printf '%s' "$POSTGRES_TOOL_GATEWAY_PASSWORD" | grep -q '[^[:space:]]'; then
        printf '%s\n' 'POSTGRES_TOOL_GATEWAY_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
        exit 2
    fi
    set -- \
        "$POSTGRES_PASSWORD" \
        "$POSTGRES_APP_PASSWORD" \
        "$POSTGRES_DISPATCHER_PASSWORD" \
        "$POSTGRES_WORKFLOW_RECONCILER_PASSWORD" \
        "$POSTGRES_AI_RUNTIME_PASSWORD" \
        "$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD" \
        "$POSTGRES_TOOL_GATEWAY_PASSWORD"
    while [ "$#" -gt 0 ]; do
        left_password=$1
        shift
        for right_password in "$@"; do
            if [ "$left_password" = "$right_password" ]; then
                printf '%s\n' 'Migration and runtime-role passwords must be pairwise different.' >&2
                exit 2
            fi
        done
    done
    unset left_password right_password
    if [ -n "${POSTGRES_APP_USER:-}" ] && [ "$POSTGRES_APP_USER" != opsmind_app ]; then
        printf '%s\n' 'POSTGRES_APP_USER must remain opsmind_app; migration grants are intentionally explicit.' >&2
        exit 2
    fi
    if [ -n "${POSTGRES_DISPATCHER_USER:-}" ] && [ "$POSTGRES_DISPATCHER_USER" != opsmind_dispatcher ]; then
        printf '%s\n' 'POSTGRES_DISPATCHER_USER must remain opsmind_dispatcher; migration grants are intentionally explicit.' >&2
        exit 2
    fi
    if [ -n "${POSTGRES_WORKFLOW_RECONCILER_USER:-}" ] &&
       [ "$POSTGRES_WORKFLOW_RECONCILER_USER" != opsmind_workflow_reconciler ]; then
        printf '%s\n' 'POSTGRES_WORKFLOW_RECONCILER_USER must remain opsmind_workflow_reconciler; migration grants are intentionally explicit.' >&2
        exit 2
    fi
    if [ -n "${POSTGRES_AI_RUNTIME_USER:-}" ] && [ "$POSTGRES_AI_RUNTIME_USER" != opsmind_ai_runtime ]; then
        printf '%s\n' 'POSTGRES_AI_RUNTIME_USER must remain opsmind_ai_runtime; migration grants are intentionally explicit.' >&2
        exit 2
    fi
    if [ -n "${POSTGRES_TOOL_GATEWAY_MIGRATOR_USER:-}" ] &&
       [ "$POSTGRES_TOOL_GATEWAY_MIGRATOR_USER" != opsmind_tool_gateway_migrator ]; then
        printf '%s\n' 'POSTGRES_TOOL_GATEWAY_MIGRATOR_USER must remain opsmind_tool_gateway_migrator; schema ownership is intentionally explicit.' >&2
        exit 2
    fi
    if [ -n "${POSTGRES_TOOL_GATEWAY_USER:-}" ] &&
       [ "$POSTGRES_TOOL_GATEWAY_USER" != opsmind_tool_gateway ]; then
        printf '%s\n' 'POSTGRES_TOOL_GATEWAY_USER must remain opsmind_tool_gateway; migration grants are intentionally explicit.' >&2
        exit 2
    fi
    if [ "${OPS_DOCKER_STORAGE_VERIFIED:-false}" != true ]; then
        printf '%s\n' 'OPS_DOCKER_STORAGE_VERIFIED=true is required after verifying Docker daemon/build storage is on an approved monitored volume.' >&2
        exit 2
    fi
}

load_environment || exit $?
case "$command_name" in
    dev|up) ;;
    migrate) unset POSTGRES_PASSWORD POSTGRES_APP_PASSWORD POSTGRES_DISPATCHER_PASSWORD POSTGRES_WORKFLOW_RECONCILER_PASSWORD POSTGRES_AI_RUNTIME_PASSWORD POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD POSTGRES_TOOL_GATEWAY_PASSWORD TOOL_GATEWAY_DATABASE_PASSWORD AI_RUNTIME_DATABASE_PASSWORD MINIO_ROOT_PASSWORD DEEPSEEK_API_KEY OPSMIND_TOOL_WORKLOAD_CLIENT_SECRET OPSMIND_DISPATCHER_DB_PASSWORD OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_API_KEY OPSMIND_WORKFLOW_RECONCILER_DB_PASSWORD ;;
    *) unset POSTGRES_PASSWORD POSTGRES_APP_PASSWORD POSTGRES_DISPATCHER_PASSWORD POSTGRES_WORKFLOW_RECONCILER_PASSWORD POSTGRES_AI_RUNTIME_PASSWORD POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD POSTGRES_TOOL_GATEWAY_PASSWORD TOOL_GATEWAY_DATABASE_PASSWORD AI_RUNTIME_DATABASE_PASSWORD SPRING_DATASOURCE_PASSWORD MINIO_ROOT_PASSWORD DEEPSEEK_API_KEY OPSMIND_TOOL_WORKLOAD_CLIENT_SECRET OPSMIND_DISPATCHER_DB_PASSWORD OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_API_KEY OPSMIND_WORKFLOW_RECONCILER_DB_PASSWORD ;;
esac
cache_root=${OPS_CACHE_ROOT:-"$repository_root/.opsmind/cache"}
artifact_root=${OPS_ARTIFACT_ROOT:-"$repository_root/artifacts"}
required_python_version=$(tr -d '\r\n' < "$repository_root/.python-version")
python_version_tag=$(printf '%s' "$required_python_version" | tr -d '.')
python_environment="$cache_root/venvs/ai-runtime-py$python_version_tag"
actionlint_version=1.7.12
uv_version=0.11.29
uv_tool_environment="$cache_root/tools/uv-$uv_version"
case "${OS:-}" in
    Windows_NT)
        actionlint_executable="$cache_root/tools/actionlint/$actionlint_version/actionlint.exe"
        venv_python="$python_environment/Scripts/python.exe"
        uv_executable="$uv_tool_environment/Scripts/uv.exe"
        uv_tool_python="$uv_tool_environment/Scripts/python.exe"
        ;;
    *)
        actionlint_executable="$cache_root/tools/actionlint/$actionlint_version/actionlint"
        venv_python="$python_environment/bin/python"
        uv_executable="$uv_tool_environment/bin/uv"
        uv_tool_python="$uv_tool_environment/bin/python"
        ;;
esac
uv_cache="$cache_root/uv"
ai_runtime_root="$repository_root/services/ai-runtime"
maven_repository="$cache_root/maven"
dependency_check_data="$cache_root/owasp-dependency-check"
pnpm_store="$cache_root/pnpm-store"
process_temp_root="$cache_root/temp"
phase_evidence_root="$artifact_root/verification/phase-02"
export COREPACK_HOME="$cache_root/corepack"

initialize_bounded_process_environment() {
    mkdir -p "$process_temp_root" || exit 2
    TMP="$process_temp_root"
    TEMP="$process_temp_root"
    TMPDIR="$process_temp_root"
    NODE_OPTIONS=${NODE_OPTIONS:---max-old-space-size=1536}
    MAVEN_OPTS=${MAVEN_OPTS:-"-Xmx768m -XX:MaxMetaspaceSize=384m -Djava.io.tmpdir=\"$process_temp_root\""}
    UV_CACHE_DIR="$uv_cache"
    UV_PROJECT_ENVIRONMENT="$python_environment"
    UV_PYTHON="$required_python_version"
    UV_PYTHON_DOWNLOADS=never
    PLAYWRIGHT_BROWSERS_PATH="$cache_root/playwright"
    export TMP TEMP TMPDIR NODE_OPTIONS MAVEN_OPTS UV_CACHE_DIR UV_PROJECT_ENVIRONMENT UV_PYTHON UV_PYTHON_DOWNLOADS PLAYWRIGHT_BROWSERS_PATH
}

resolve_python_bootstrap() {
    python_command=''
    python_selector=''
    for candidate in "python$required_python_version" python3 python; do
        command -v "$candidate" >/dev/null 2>&1 || continue
        actual_version=$($candidate -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null) || continue
        if [ "$actual_version" = "$required_python_version" ]; then
            python_command=$candidate
            return 0
        fi
    done
    if command -v py >/dev/null 2>&1; then
        actual_version=$(py "-$required_python_version" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null) || actual_version=''
        if [ "$actual_version" = "$required_python_version" ]; then
            python_command=py
            python_selector="-$required_python_version"
            return 0
        fi
    fi
    printf '%s\n' "Python $required_python_version is required but no matching interpreter was found." >&2
    return 2
}

storage_preflight() {
    create_missing=${1:-false}
    run_checked "$repository_root/scripts/storage/check-capacity.sh"
    if [ "$create_missing" = true ]; then
        run_checked "$repository_root/scripts/storage/assert-storage-roots.sh" --create-missing
    else
        run_checked "$repository_root/scripts/storage/assert-storage-roots.sh"
    fi
    mkdir -p "$phase_evidence_root" || exit 2
    run_checked "$repository_root/scripts/storage/check-capacity.sh" --evidence "$phase_evidence_root/capacity-portable.txt"
    run_checked "$repository_root/scripts/storage/assert-storage-roots.sh" --evidence "$phase_evidence_root/storage-roots-portable.txt"
    printf '%s\n' 'Preflight=PASS'
}

capacity_guard() {
    run_checked "$repository_root/scripts/storage/check-capacity.sh"
    printf '%s\n' 'CapacityGuard=PASS'
}

write_doctor_result() {
    doctor_name=$1
    doctor_expected=$2
    doctor_actual=$3
    if { [ "$doctor_expected" = available ] && [ "$doctor_actual" != MISSING ]; } || \
        [ "$doctor_actual" = "$doctor_expected" ]; then
        doctor_status=PASS
    else
        doctor_status=BLOCK
        doctor_failures=$((doctor_failures + 1))
        if [ -n "$doctor_failure_names" ]; then
            doctor_failure_names="$doctor_failure_names,$doctor_name"
        else
            doctor_failure_names=$doctor_name
        fi
    fi
    printf 'Tool.%s=%s Expected=%s Actual=%s\n' \
        "$doctor_name" "$doctor_status" "$doctor_expected" "$doctor_actual"
}

show_help() {
    printf '%s\n' \
        'OpsMind command surface' \
        'Usage: ./scripts/dev/opsmind.sh <command> [--dry-run]' \
        '' \
        'Commands: setup dev test lint build up down migrate seed evaluate security' \
        '          security-scan doctor help' \
        '' \
        'Heavy commands run storage preflight before side effects. down remains' \
        'available during low-space events. migrate applies the Phase 3 Flyway' \
        'schema; seed remains unavailable and evaluate starts in Phase 8.'
}

dry_run=false
[ "$#" -gt 0 ] && shift
while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run) dry_run=true ;;
        *) printf '%s\n' "Unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

case "$command_name" in
    help|setup|dev|test|lint|build|up|down|migrate|seed|evaluate|security|security-scan|doctor) ;;
    *) printf '%s\n' "Unknown command: $command_name" >&2; exit 2 ;;
esac

case "$command_name" in
    setup|dev|test|lint|build|up|migrate|seed|evaluate|security|security-scan|doctor)
        enter_command_lock || exit $?
        ;;
esac

case "$command_name" in
    setup) storage_preflight true; initialize_bounded_process_environment ;;
    dev|test|lint|build|up|migrate|seed|evaluate|security|security-scan)
        storage_preflight false
        initialize_bounded_process_environment
        ;;
esac

case "$command_name" in
    setup) command_plan='install pinned workspace dependencies into configured caches' ;;
    dev) command_plan='build and start the application Compose profile in the foreground' ;;
    test) command_plan='run governance, layout, frontend, Java, and Python tests' ;;
    lint) command_plan='run repository, frontend, Java compile, Python lint and type checks' ;;
    build) command_plan='build frontend and Java artifacts; compile Python sources' ;;
    up) command_plan='build and start the application Compose profile with health waits' ;;
    down) command_plan='stop the application Compose profile without a capacity gate' ;;
    migrate) command_plan='package the Platform API and apply its Flyway migrations with the migration role' ;;
    seed) command_plan='unavailable until Phase 3 owns deterministic seed data' ;;
    evaluate) command_plan='score the Phase 8 deterministic smoke scenario against a fresh Phase 7 trace' ;;
    security|security-scan) command_plan='run repository secret and ecosystem dependency scans' ;;
    doctor) command_plan='run preflight and report required tool availability' ;;
    *) command_plan='show command help' ;;
esac

if [ "$dry_run" = true ]; then
    printf '%s\n' "Command=$command_name" "CommandPlan=$command_plan"
    exit 0
fi
if [ "$command_name" = help ]; then show_help; exit 0; fi
if [ "$command_name" = doctor ]; then
    storage_preflight false
    doctor_failures=0
    doctor_failure_names=''

    expected_node=$(tr -d '\r\n' < "$repository_root/.node-version")
    if command -v node >/dev/null 2>&1; then actual_node=$(node --version | sed 's/^v//'); else actual_node=MISSING; fi
    write_doctor_result Node "$expected_node" "$actual_node"

    expected_pnpm=$(node -p "require('./package.json').packageManager.split('@').pop()" 2>/dev/null || printf '%s' MISSING)
    if command -v corepack >/dev/null 2>&1; then actual_pnpm=$(corepack pnpm --version 2>/dev/null || printf '%s' MISSING); else actual_pnpm=MISSING; fi
    write_doctor_result Pnpm "$expected_pnpm" "$actual_pnpm"

    expected_java=$(tr -d '\r\n' < "$repository_root/.java-version")
    if command -v java >/dev/null 2>&1; then
        actual_java=$(java -version 2>&1 | awk -F '"' 'NR == 1 { split($2, version, "."); print version[1] }')
        [ -n "$actual_java" ] || actual_java=MISSING
    else actual_java=MISSING; fi
    write_doctor_result Java "$expected_java" "$actual_java"

    expected_maven=$(tr -d '\r\n' < "$repository_root/.maven-version")
    if command -v mvn >/dev/null 2>&1; then
        actual_maven=$(mvn --version 2>/dev/null | sed -n '1s/^Apache Maven \([0-9.]*\).*/\1/p')
        [ -n "$actual_maven" ] || actual_maven=MISSING
    else actual_maven=MISSING; fi
    write_doctor_result Maven "$expected_maven" "$actual_maven"

    if resolve_python_bootstrap; then
        actual_python=$($python_command $python_selector -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null || printf '%s' MISSING)
    else actual_python=MISSING; fi
    write_doctor_result Python "$required_python_version" "$actual_python"

    if [ -x "$uv_executable" ]; then
        actual_uv=$($uv_executable --version 2>/dev/null | sed 's/^uv //; s/ .*//')
        [ -n "$actual_uv" ] || actual_uv=MISSING
    else actual_uv=MISSING; fi
    write_doctor_result Uv "$uv_version" "$actual_uv"

    if [ -x "$actionlint_executable" ]; then
        actual_actionlint=$($actionlint_executable -version 2>/dev/null | sed -n '1p')
        [ -n "$actual_actionlint" ] || actual_actionlint=MISSING
    else actual_actionlint=MISSING; fi
    write_doctor_result Actionlint "$actionlint_version" "$actual_actionlint"

    if command -v docker >/dev/null 2>&1; then actual_docker=$(docker --version 2>/dev/null | sed -n '1p'); else actual_docker=MISSING; fi
    write_doctor_result Docker available "$actual_docker"

    if [ "$doctor_failures" -gt 0 ]; then
        printf 'Doctor=BLOCK Failures=%s\n' "$doctor_failure_names"
        exit 6
    fi
    printf '%s\n' 'Doctor=PASS'
    exit 0
fi
case "$command_name" in
    seed) printf '%s\n' "CommandUnavailable=$command_plan" >&2; exit 3 ;;
    security-scan) command_name=security ;;
esac

platform_pom="$repository_root/services/platform-api/pom.xml"
gateway_pom="$repository_root/services/tool-gateway/pom.xml"

case "$command_name" in
    setup)
        for tool in node corepack java mvn; do require_command "$tool"; done
        assert_setup_toolchain || exit $?
        resolve_python_bootstrap || exit $?
        mkdir -p "$cache_root" || exit 2
        run_checked node scripts/dev/install-pinned-actionlint.mjs --cache-root "$cache_root"
        run_checked corepack pnpm --config.ci=true "--config.store-dir=$pnpm_store" install --frozen-lockfile
        capacity_guard
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web \
            exec playwright install --with-deps chromium
        capacity_guard
        [ -x "$uv_tool_python" ] || run_checked "$python_command" $python_selector -m venv "$uv_tool_environment"
        [ -x "$uv_executable" ] || run_checked "$uv_tool_python" -m pip install --disable-pip-version-check --cache-dir "$cache_root/pip" "uv==$uv_version"
        run_checked "$uv_executable" sync --project "$ai_runtime_root" --locked
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$platform_pom" dependency:go-offline
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$gateway_pom" dependency:go-offline
        capacity_guard
        ;;
    dev) assert_application_compose_configuration; run_checked docker compose --profile application up --build ;;
    up) assert_application_compose_configuration; run_checked docker compose --profile application up --build --detach --wait ;;
    down) run_checked docker compose --profile application down ;;
    migrate)
        if [ -z "${SPRING_DATASOURCE_PASSWORD:-}" ] || ! printf '%s' "$SPRING_DATASOURCE_PASSWORD" | grep -q '[^[:space:]]'; then
            printf '%s\n' 'SPRING_DATASOURCE_PASSWORD must be supplied through the process environment or an approved secret manager.' >&2
            exit 2
        fi
        require_command java
        require_command mvn
        SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:postgresql://127.0.0.1:5432/opsmind}
        SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-opsmind_migrator}
        export SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$platform_pom" -DskipTests package
        capacity_guard
        run_checked java -jar "$repository_root/services/platform-api/target/platform-api.jar" \
            --spring.main.web-application-type=none --spring.profiles.active=persistence
        capacity_guard
        ;;
    test)
        require_command pwsh
        run_checked pwsh -NoProfile -File scripts/governance/test-phase-01-governance.ps1
        capacity_guard
        run_checked env OPS_LAYOUT_EVIDENCE_PATH="$phase_evidence_root/repository-layout.txt" \
            node scripts/validation/validate-repository-layout.mjs
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web test
        capacity_guard
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web build
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web test:e2e:production
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web test:e2e
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$platform_pom" test
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$gateway_pom" test
        run_checked "$venv_python" -m pytest services/ai-runtime/tests
        run_checked node --test evaluation/runner/*.test.mjs
        run_checked node scripts/validation/validate-phase-08-evaluation-foundation.mjs
        capacity_guard
        ;;
    lint)
        run_checked env OPS_LAYOUT_EVIDENCE_PATH="$phase_evidence_root/repository-layout.txt" \
            node scripts/validation/validate-repository-layout.mjs
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web lint
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web typecheck
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$platform_pom" -DskipTests compile
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$gateway_pom" -DskipTests compile
        capacity_guard
        run_checked "$uv_executable" lock --project "$ai_runtime_root" --check
        run_checked "$venv_python" -m ruff check services/ai-runtime
        run_checked "$venv_python" -m mypy services/ai-runtime/src
        capacity_guard
        ;;
    build)
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" --filter @opsmind/operator-web build
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$platform_pom" -DskipTests package
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" -f "$gateway_pom" -DskipTests package
        run_checked "$venv_python" -m compileall -q services/ai-runtime/src
        capacity_guard
        ;;
    evaluate)
        run_checked node --test evaluation/runner/*.test.mjs
        run_checked node scripts/validation/validate-phase-08-evaluation-foundation.mjs
        run_checked node evaluation/runner/score-phase-07-trace.mjs
        ;;
    security)
        require_command pwsh
        run_checked pwsh -NoProfile -File scripts/governance/scan-project-secrets.ps1
        run_checked node scripts/security/verify-brace-expansion-patch.mjs
        run_checked corepack pnpm "--config.store-dir=$pnpm_store" audit --audit-level moderate
        capacity_guard
        run_checked "$venv_python" -m pip_audit
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" \
            "-DdataDirectory=$dependency_check_data" \
            "-DfailBuildOnCVSS=7" \
            "-DfailOnError=true" \
            "-Dformat=JSON" \
            -f "$platform_pom" org.owasp:dependency-check-maven:12.2.2:check
        capacity_guard
        run_checked mvn -q "-Dmaven.repo.local=$maven_repository" \
            "-DdataDirectory=$dependency_check_data" \
            "-DfailBuildOnCVSS=7" \
            "-DfailOnError=true" \
            "-Dformat=JSON" \
            -f "$gateway_pom" org.owasp:dependency-check-maven:12.2.2:check
        capacity_guard
        ;;
esac
