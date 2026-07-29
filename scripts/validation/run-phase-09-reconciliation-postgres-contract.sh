#!/usr/bin/env bash
set -euo pipefail

for required_name in \
  PGHOST PGPORT PGDATABASE POSTGRES_USER POSTGRES_PASSWORD \
  POSTGRES_WORKFLOW_RECONCILER_USER POSTGRES_WORKFLOW_RECONCILER_PASSWORD; do
  if [[ -z "${!required_name:-}" ]]; then
    echo "${required_name} is required." >&2
    exit 2
  fi
done

if [[ "$POSTGRES_WORKFLOW_RECONCILER_USER" != "opsmind_workflow_reconciler" ]]; then
  echo "The reconciliation contract requires the fixed reconciler login." >&2
  exit 2
fi

admin_query() {
  local sql="$1"
  PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
    --dbname "$PGDATABASE" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "$sql" | tr -d '\r'
}

reconciler_query() {
  local sql="$1"
  PGPASSWORD="$POSTGRES_WORKFLOW_RECONCILER_PASSWORD" psql \
    --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" \
    --username "$POSTGRES_WORKFLOW_RECONCILER_USER" \
    --dbname "$PGDATABASE" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "$sql" | tr -d '\r'
}

expect_equal() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf '%s expected=%s actual=%s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
  printf '%s=PASS\n' "$label"
}

expect_admin_true() {
  local sql="$1"
  local label="$2"
  expect_equal "t" "$(admin_query "$sql")" "$label"
}

expect_reconciler_true() {
  local sql="$1"
  local label="$2"
  expect_equal "t" "$(reconciler_query "$sql")" "$label"
}

insert_fixture() {
  local organization_id="$1"
  local run_id="$2"
  local event_id="$3"
  local label="$4"
  admin_query "
SET session_replication_role = replica;
INSERT INTO organizations (id, slug, name)
VALUES ('$organization_id', '$label', '$label');
INSERT INTO outbox_events (
  event_id, organization_id, aggregate_type, aggregate_id,
  aggregate_sequence, event_type, schema_version, correlation_id,
  occurred_at, payload, payload_bytes, payload_digest, attempts,
  last_error, next_attempt_at
) VALUES (
  '$event_id', '$organization_id', 'investigation-workflow', '$run_id',
  1, 'investigation.workflow-start.requested', '1',
  gen_random_uuid(), clock_timestamp() - interval '1 minute',
  '{}'::jsonb, convert_to('{}', 'UTF8'), digest('{}', 'sha256'), 1,
  'workflow.reconciliation-required', clock_timestamp()
);
INSERT INTO investigation_workflow_bindings (
  organization_id, run_id, project_id, incident_id, actor_id,
  client_request_digest, start_payload_digest, start_event_id,
  temporal_cluster_id, temporal_namespace, workflow_id, workflow_type,
  task_queue, authorization_revision, status, started_at, deadline_at,
  created_at, updated_at
) VALUES (
  '$organization_id', '$run_id', gen_random_uuid(), gen_random_uuid(),
  gen_random_uuid(), digest('request', 'sha256'), digest('{}', 'sha256'),
  '$event_id', 'phase09-test', 'phase09-test',
  'opsmind-investigation/$organization_id/$run_id',
  'OpsMindInvestigationWorkflow', 'phase09-test', 1, 'PENDING',
  clock_timestamp() - interval '2 minutes',
  clock_timestamp() + interval '10 minutes',
  clock_timestamp() - interval '2 minutes',
  clock_timestamp() - interval '2 minutes'
);
SET session_replication_role = origin;
" >/dev/null
}

match_org="91000000-0000-4000-8000-000000000001"
match_run="91000000-0000-4000-8000-000000000002"
match_event="91000000-0000-4000-8000-000000000003"
match_token="91000000-0000-4000-8000-000000000004"
wrong_token="91000000-0000-4000-8000-000000000005"

absence_org="92000000-0000-4000-8000-000000000001"
absence_run="92000000-0000-4000-8000-000000000002"
absence_event="92000000-0000-4000-8000-000000000003"
absence_token_one="92000000-0000-4000-8000-000000000004"
absence_token_two="92000000-0000-4000-8000-000000000005"

reactivation_org="93000000-0000-4000-8000-000000000001"
reactivation_run="93000000-0000-4000-8000-000000000002"
reactivation_event="93000000-0000-4000-8000-000000000003"
reactivation_token_one="93000000-0000-4000-8000-000000000004"
reactivation_token_two="93000000-0000-4000-8000-000000000005"

exhausted_org="94000000-0000-4000-8000-000000000001"
exhausted_run="94000000-0000-4000-8000-000000000002"
exhausted_event="94000000-0000-4000-8000-000000000003"
exhausted_token="94000000-0000-4000-8000-000000000004"

if reconciler_query "SELECT count(*) FROM public.outbox_events;" \
  >/dev/null 2>&1; then
  echo "Direct reconciler table reads must be denied." >&2
  exit 1
fi
printf 'DirectTableReadDenied=PASS\n'

if reconciler_query "
INSERT INTO public.inbox_events (
  event_id, organization_id, consumer, attempts
) VALUES (
  gen_random_uuid(), gen_random_uuid(), 'forbidden', 1
);" >/dev/null 2>&1; then
  echo "Direct reconciler table writes must be denied." >&2
  exit 1
fi
printf 'DirectTableWriteDenied=PASS\n'

expect_equal "f" "$(reconciler_query "
SELECT has_function_privilege(
  current_user,
  'public.opsmind_validate_investigation_workflow_binding_update()',
  'EXECUTE'
);
")" "TriggerFunctionExecuteDenied"

expect_admin_true "
WITH reconciliation_api(name) AS (
  VALUES
    ('opsmind_claim_investigation_workflow_reconciliation'),
    ('opsmind_settle_investigation_workflow_reconciliation'),
    ('opsmind_get_investigation_workflow_reconciliation_status')
)
SELECT count(*) = 3
  AND bool_and(
    has_function_privilege('opsmind_workflow_reconciler', procedure.oid, 'EXECUTE')
  )
FROM pg_proc procedure
JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
JOIN reconciliation_api api ON api.name = procedure.proname
WHERE namespace.nspname = 'public';
" "ExactThreeReconciliationFunctions"

insert_fixture "$match_org" "$match_run" "$match_event" "phase09-match"
expect_equal "$match_event" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$match_token', 30000, 8, 3600000
);
")" "MatchClaim"

expect_equal "workflow.reconciliation-lease-lost" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$match_org', '$match_event', '$wrong_token', 'MATCH',
  'temporal-first-run', NULL, NULL, 1000, 3600000
);
")" "WrongLeaseRejected"

expect_admin_true "
SELECT status = 'PENDING'
  AND published_at IS NULL
  AND poisoned_at IS NULL
  AND lease_token = '$match_token'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
WHERE binding.organization_id = '$match_org'
  AND binding.run_id = '$match_run';
" "WrongLeaseAtomic"

expect_equal "0" "$(reconciler_query "
SELECT count(*)
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$wrong_token', 30000, 8, 3600000
);
")" "LiveLeaseExclusive"

expect_equal "workflow.reconciliation-started" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$match_org', '$match_event', '$match_token', 'MATCH',
  'temporal-first-run', NULL, NULL, 1000, 3600000
);
")" "MatchSettlement"

expect_admin_true "
SELECT binding.status = 'STARTED'
  AND event_row.published_at IS NOT NULL
  AND event_row.attempts = 1
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
WHERE binding.organization_id = '$match_org'
  AND binding.run_id = '$match_run';
" "MatchState"

insert_fixture "$absence_org" "$absence_run" "$absence_event" "phase09-absence"
expect_equal "$absence_event" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$absence_token_one', 30000, 8, 3600000
);
")" "AbsenceFirstClaim"
expect_equal "workflow.reconciliation-absence-candidate" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$absence_org', '$absence_event', '$absence_token_one', 'ABSENT',
  NULL, 'workflow.temporal-start-not-found', NULL, 1000, 3600000
);
")" "AbsenceFirstSample"

expect_admin_true "
SELECT binding.status = 'PENDING'
  AND event_row.poisoned_at IS NULL
  AND inbox.last_error = 'workflow.reconciliation-absence-candidate'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events inbox
  ON inbox.organization_id = event_row.organization_id
 AND inbox.event_id = event_row.event_id
 AND inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE binding.organization_id = '$absence_org'
  AND binding.run_id = '$absence_run';
" "OneAbsenceCannotReject"

sleep 1.1
expect_equal "$absence_event" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$absence_token_two', 30000, 8, 3600000
);
")" "AbsenceSecondClaim"
expect_equal "workflow.reconciliation-verified-absence" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$absence_org', '$absence_event', '$absence_token_two', 'ABSENT',
  NULL, 'workflow.temporal-start-not-found', NULL, 1000, 3600000
);
")" "AbsenceSecondSample"

expect_admin_true "
SELECT binding.status = 'REJECTED'
  AND event_row.poisoned_at IS NOT NULL
  AND event_row.attempts = 1
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
WHERE binding.organization_id = '$absence_org'
  AND binding.run_id = '$absence_run';
" "VerifiedAbsenceState"

insert_fixture \
  "$reactivation_org" "$reactivation_run" "$reactivation_event" \
  "phase09-reactivation"
expect_equal "$reactivation_event" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$reactivation_token_one', 30000, 8, 3600000
);
")" "ReactivationFirstClaim"
admin_query "
INSERT INTO service_accounts (
  id, organization_id, name, credential_ref, allowed_audiences,
  allowed_scopes, status, database_principal
) VALUES (
  gen_random_uuid(), '$reactivation_org', 'phase09-dispatcher',
  'secret://phase09/dispatcher', '[\"opsmind-outbox-dispatcher\"]'::jsonb,
  '[\"outbox:dispatch\"]'::jsonb, 'active', 'opsmind_dispatcher'
);
" >/dev/null
expect_equal "workflow.reconciliation-released-to-starter" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$reactivation_org', '$reactivation_event', '$reactivation_token_one',
  'ABSENT', NULL, 'workflow.temporal-start-not-found', NULL, 1000, 3600000
);
")" "ReactivationRelease"

admin_query "
UPDATE service_accounts
SET status = 'suspended'
WHERE organization_id = '$reactivation_org'
  AND database_principal = 'opsmind_dispatcher';
UPDATE outbox_events
SET last_error = 'workflow.reconciliation-required',
    next_attempt_at = clock_timestamp()
WHERE organization_id = '$reactivation_org'
  AND event_id = '$reactivation_event';
" >/dev/null

expect_admin_true "
SELECT status = 'processed'
FROM inbox_events
WHERE organization_id = '$reactivation_org'
  AND event_id = '$reactivation_event'
  AND consumer = 'investigation-workflow-reconciler-v1';
" "ReactivationEpochProcessed"
expect_reconciler_true "
SELECT claim_ready_count = 1
FROM opsmind_get_investigation_workflow_reconciliation_status();
" "ProcessedEpochStatusReady"
expect_equal "$reactivation_event" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$reactivation_token_two', 30000, 8, 3600000
);
")" "ProcessedEpochReclaimed"
expect_equal "workflow.reconciliation-blocked" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$reactivation_org', '$reactivation_event', '$reactivation_token_two',
  'BLOCKED', NULL, 'workflow.reconciliation-permission-denied',
  NULL, 1000, 3600000
);
")" "ReactivationTerminalizedSafely"

expect_admin_true "
SELECT binding.status = 'PENDING'
  AND event_row.poisoned_at IS NULL
  AND inbox.status = 'poisoned'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events inbox
  ON inbox.organization_id = event_row.organization_id
 AND inbox.event_id = event_row.event_id
 AND inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE binding.organization_id = '$reactivation_org'
  AND binding.run_id = '$reactivation_run';
" "BlockedUncertaintyPreservesPending"

insert_fixture "$exhausted_org" "$exhausted_run" "$exhausted_event" \
  "phase09-exhausted"
expect_equal "$exhausted_event" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$exhausted_token', 30000, 1, 3600000
);
")" "ExhaustionFirstClaim"
admin_query "
UPDATE outbox_events
SET lease_expires_at = clock_timestamp() - interval '1 second'
WHERE organization_id = '$exhausted_org'
  AND event_id = '$exhausted_event';
" >/dev/null
expect_equal "0" "$(reconciler_query "
SELECT count(*)
FROM opsmind_claim_investigation_workflow_reconciliation(
  gen_random_uuid(), 30000, 1, 3600000
);
")" "ExhaustionSweep"
expect_admin_true "
SELECT binding.status = 'PENDING'
  AND event_row.poisoned_at IS NULL
  AND event_row.attempts = 1
  AND inbox.status = 'poisoned'
  AND inbox.last_error = 'workflow.reconciliation-exhausted'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events inbox
  ON inbox.organization_id = event_row.organization_id
 AND inbox.event_id = event_row.event_id
 AND inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE binding.organization_id = '$exhausted_org'
  AND binding.run_id = '$exhausted_run';
" "ExhaustionPreservesCanonicalPending"

expect_equal "workflow.reconciliation-lease-lost" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$match_org', '$exhausted_event', gen_random_uuid(), 'BLOCKED',
  NULL, 'workflow.reconciliation-observer-failed', NULL, 1000, 3600000
);
")" "CrossTenantSettlementDenied"

printf 'ReconciliationPostgresContract=PASS\n'
