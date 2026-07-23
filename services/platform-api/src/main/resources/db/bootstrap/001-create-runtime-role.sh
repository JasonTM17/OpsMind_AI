#!/usr/bin/env bash

set -Eeuo pipefail

: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be supplied for the non-owner runtime role}"
: "${POSTGRES_DISPATCHER_PASSWORD:?POSTGRES_DISPATCHER_PASSWORD must be supplied for the outbox dispatcher role}"
: "${POSTGRES_AI_RUNTIME_PASSWORD:?POSTGRES_AI_RUNTIME_PASSWORD must be supplied for the AI runtime role}"
: "${POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD:?POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD must be supplied for the Tool Gateway migration role}"
: "${POSTGRES_TOOL_GATEWAY_PASSWORD:?POSTGRES_TOOL_GATEWAY_PASSWORD must be supplied for the Tool Gateway runtime role}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be supplied for the migration owner}"

if [[ "${POSTGRES_APP_USER:-opsmind_app}" != "opsmind_app" ]]; then
    printf '%s\n' 'POSTGRES_APP_USER must remain opsmind_app; migration grants are intentionally explicit.' >&2
    exit 2
fi
if [[ "${POSTGRES_DISPATCHER_USER:-opsmind_dispatcher}" != "opsmind_dispatcher" ]]; then
    printf '%s\n' 'POSTGRES_DISPATCHER_USER must remain opsmind_dispatcher; migration grants are intentionally explicit.' >&2
    exit 2
fi
if [[ "${POSTGRES_AI_RUNTIME_USER:-opsmind_ai_runtime}" != "opsmind_ai_runtime" ]]; then
    printf '%s\n' 'POSTGRES_AI_RUNTIME_USER must remain opsmind_ai_runtime; migration grants are intentionally explicit.' >&2
    exit 2
fi
if [[ "${POSTGRES_TOOL_GATEWAY_MIGRATOR_USER:-opsmind_tool_gateway_migrator}" != "opsmind_tool_gateway_migrator" ]]; then
    printf '%s\n' 'POSTGRES_TOOL_GATEWAY_MIGRATOR_USER must remain opsmind_tool_gateway_migrator; schema ownership is intentionally explicit.' >&2
    exit 2
fi
if [[ "${POSTGRES_TOOL_GATEWAY_USER:-opsmind_tool_gateway}" != "opsmind_tool_gateway" ]]; then
    printf '%s\n' 'POSTGRES_TOOL_GATEWAY_USER must remain opsmind_tool_gateway; migration grants are intentionally explicit.' >&2
    exit 2
fi

database_passwords=(
    "$POSTGRES_PASSWORD"
    "$POSTGRES_APP_PASSWORD"
    "$POSTGRES_DISPATCHER_PASSWORD"
    "$POSTGRES_AI_RUNTIME_PASSWORD"
    "$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD"
    "$POSTGRES_TOOL_GATEWAY_PASSWORD"
)
for ((left = 0; left < ${#database_passwords[@]}; left++)); do
    for ((right = left + 1; right < ${#database_passwords[@]}; right++)); do
        if [[ "${database_passwords[$left]}" == "${database_passwords[$right]}" ]]; then
            printf '%s\n' 'Migration and runtime-role passwords must be pairwise different.' >&2
            exit 2
        fi
    done
done
unset database_passwords left right

export PGPASSWORD="$POSTGRES_PASSWORD"

psql --no-password --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --command "DO \$\$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_app') THEN
            CREATE ROLE opsmind_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_app'
               AND (rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_app has unsafe role attributes';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_context_resolver') THEN
            CREATE ROLE opsmind_context_resolver NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_context_resolver'
               AND (rolsuper OR rolbypassrls OR rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_context_resolver has unsafe role attributes';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_dispatcher') THEN
            CREATE ROLE opsmind_dispatcher LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_dispatcher'
               AND (rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_dispatcher has unsafe role attributes';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_dispatch_resolver') THEN
            CREATE ROLE opsmind_dispatch_resolver NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_dispatch_resolver'
               AND (rolsuper OR rolbypassrls OR rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_dispatch_resolver has unsafe role attributes';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_ai_runtime') THEN
            CREATE ROLE opsmind_ai_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_ai_runtime'
               AND (rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_ai_runtime has unsafe role attributes';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_tool_gateway_migrator') THEN
            CREATE ROLE opsmind_tool_gateway_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_tool_gateway_migrator'
               AND (rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_tool_gateway_migrator has unsafe role attributes';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_tool_gateway') THEN
            CREATE ROLE opsmind_tool_gateway LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_roles
             WHERE rolname = 'opsmind_tool_gateway'
               AND (rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit)
        ) THEN
            RAISE EXCEPTION 'opsmind_tool_gateway has unsafe role attributes';
        END IF;
    END \$\$;"

psql --no-password --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --command "CREATE SCHEMA IF NOT EXISTS tool_gateway AUTHORIZATION opsmind_tool_gateway_migrator;" \
    --command "REVOKE ALL ON SCHEMA tool_gateway FROM PUBLIC;" \
    --command "DO \$\$ BEGIN
        IF pg_get_userbyid((SELECT nspowner FROM pg_namespace WHERE nspname = 'tool_gateway'))
            IS DISTINCT FROM 'opsmind_tool_gateway_migrator' THEN
            RAISE EXCEPTION 'tool_gateway schema has an unexpected owner';
        END IF;
    END \$\$;"

# \password reads the secret from stdin and avoids placing it in a command
# argument or in the repository. The one-shot provisioner runs this idempotently
# for both fresh and existing PostgreSQL data volumes.
psql --no-password --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOF
\password opsmind_app
$POSTGRES_APP_PASSWORD
$POSTGRES_APP_PASSWORD
\password opsmind_dispatcher
$POSTGRES_DISPATCHER_PASSWORD
$POSTGRES_DISPATCHER_PASSWORD
\password opsmind_ai_runtime
$POSTGRES_AI_RUNTIME_PASSWORD
$POSTGRES_AI_RUNTIME_PASSWORD
\password opsmind_tool_gateway_migrator
$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD
$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD
\password opsmind_tool_gateway
$POSTGRES_TOOL_GATEWAY_PASSWORD
$POSTGRES_TOOL_GATEWAY_PASSWORD
EOF

unset PGPASSWORD POSTGRES_APP_PASSWORD POSTGRES_DISPATCHER_PASSWORD \
    POSTGRES_AI_RUNTIME_PASSWORD POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD \
    POSTGRES_TOOL_GATEWAY_PASSWORD POSTGRES_PASSWORD
