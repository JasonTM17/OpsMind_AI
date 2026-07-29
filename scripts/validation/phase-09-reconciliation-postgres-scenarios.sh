# Sourced by run-phase-09-reconciliation-postgres-contract.sh after its core
# scenarios. The parent owns connection helpers, assertions, and cleanup.

claim_fixture() {
  local event_id="$1"
  local lease_token="$2"
  local label="$3"
  expect_equal "$event_id" "$(reconciler_query "
SELECT event_id
FROM opsmind_claim_investigation_workflow_reconciliation(
  '$lease_token', 30000, 8, 3600000
);
")" "$label"
}

expect_failed_settlement() {
  local sql="$1"
  local label="$2"
  if reconciler_query "$sql" >/dev/null 2>&1; then
    printf '%s unexpectedly committed\n' "$label" >&2
    exit 1
  fi
  printf '%s=PASS\n' "$label"
}

expect_rollback_atomic() {
  local organization_id="$1"
  local run_id="$2"
  local event_id="$3"
  local lease_token="$4"
  local label="$5"
  expect_admin_true "
SELECT binding.status = 'PENDING'
  AND event_row.published_at IS NULL
  AND event_row.poisoned_at IS NULL
  AND event_row.lease_token = '$lease_token'
  AND reconciliation_inbox.status = 'received'
  AND reconciliation_inbox.processed_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM inbox_events starter_inbox
    WHERE starter_inbox.organization_id = '$organization_id'
      AND starter_inbox.event_id = '$event_id'
      AND starter_inbox.consumer = 'investigation-workflow-starter-v1'
  )
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events reconciliation_inbox
  ON reconciliation_inbox.organization_id = event_row.organization_id
 AND reconciliation_inbox.event_id = event_row.event_id
 AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE binding.organization_id = '$organization_id'
  AND binding.run_id = '$run_id';
" "$label"
}

expect_equal "f" "$(admin_query "
SELECT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_phase09_unsafe_probe'
);
")" "UnsafeProbeRoleAbsent"
admin_query "
CREATE ROLE opsmind_phase09_unsafe_probe
  NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
GRANT opsmind_phase09_unsafe_probe TO opsmind_workflow_reconciler;
" >/dev/null
unsafe_probe_created=true
if reconciler_query "
SELECT claim_ready_count
FROM opsmind_get_investigation_workflow_reconciliation_status();
" >/dev/null 2>&1; then
  echo "Unsafe reconciler role membership must deny every capability." >&2
  exit 1
fi
printf 'IdentityMembershipDriftDenied=PASS\n'
admin_query "
REVOKE opsmind_phase09_unsafe_probe FROM opsmind_workflow_reconciler;
DROP ROLE opsmind_phase09_unsafe_probe;
" >/dev/null
unsafe_probe_created=false

mismatch_org="95000000-0000-4000-8000-000000000001"
mismatch_run="95000000-0000-4000-8000-000000000002"
mismatch_event="95000000-0000-4000-8000-000000000003"
mismatch_lease_id="95000000-0000-4000-8000-000000000004"
insert_fixture "$mismatch_org" "$mismatch_run" "$mismatch_event" \
  "phase09-mismatch"
claim_fixture "$mismatch_event" "$mismatch_lease_id" "MismatchClaim"
expect_equal "workflow.reconciliation-contract-mismatch" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$mismatch_org', '$mismatch_event', '$mismatch_lease_id', 'MISMATCH',
  NULL, 'workflow.existing-contract-mismatch', NULL, 1000, 3600000
);
")" "MismatchSettlement"
expect_admin_true "
SELECT binding.status = 'REJECTED'
  AND binding.rejection_code = 'workflow.existing-contract-mismatch'
  AND event_row.poisoned_at IS NOT NULL
  AND event_row.published_at IS NULL
  AND event_row.attempts = 1
  AND reconciliation_inbox.status = 'processed'
  AND starter_inbox.status = 'poisoned'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events reconciliation_inbox
  ON reconciliation_inbox.organization_id = event_row.organization_id
 AND reconciliation_inbox.event_id = event_row.event_id
 AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
JOIN inbox_events starter_inbox
  ON starter_inbox.organization_id = event_row.organization_id
 AND starter_inbox.event_id = event_row.event_id
 AND starter_inbox.consumer = 'investigation-workflow-starter-v1'
WHERE binding.organization_id = '$mismatch_org'
  AND binding.run_id = '$mismatch_run';
" "MismatchAtomicState"

retry_org="96000000-0000-4000-8000-000000000001"
retry_run="96000000-0000-4000-8000-000000000002"
retry_event="96000000-0000-4000-8000-000000000003"
retry_lease_id="96000000-0000-4000-8000-000000000004"
insert_fixture "$retry_org" "$retry_run" "$retry_event" "phase09-retry"
claim_fixture "$retry_event" "$retry_lease_id" "RetryClaim"
expect_equal "workflow.reconciliation-retry-scheduled" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$retry_org', '$retry_event', '$retry_lease_id', 'RETRY',
  NULL, 'workflow.temporal-unavailable', 100, 1000, 3600000
);
")" "RetrySettlement"
expect_admin_true "
SELECT binding.status = 'PENDING'
  AND event_row.published_at IS NULL
  AND event_row.poisoned_at IS NULL
  AND event_row.lease_token IS NULL
  AND event_row.attempts = 1
  AND event_row.next_attempt_at
      >= reconciliation_inbox.processed_at + interval '100 milliseconds'
  AND reconciliation_inbox.status = 'received'
  AND reconciliation_inbox.last_error = 'workflow.temporal-unavailable'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events reconciliation_inbox
  ON reconciliation_inbox.organization_id = event_row.organization_id
 AND reconciliation_inbox.event_id = event_row.event_id
 AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE binding.organization_id = '$retry_org'
  AND binding.run_id = '$retry_run';
" "RetryPreservesCanonicalPending"
admin_query "
UPDATE outbox_events
SET next_attempt_at = clock_timestamp() + interval '1 hour'
WHERE organization_id = '$retry_org'
  AND event_id = '$retry_event';
" >/dev/null

retention_org="97000000-0000-4000-8000-000000000001"
retention_run="97000000-0000-4000-8000-000000000002"
retention_event="97000000-0000-4000-8000-000000000003"
retention_lease_id="97000000-0000-4000-8000-000000000004"
insert_fixture "$retention_org" "$retention_run" "$retention_event" \
  "phase09-retention"
claim_fixture "$retention_event" "$retention_lease_id" "RetentionClaim"
expect_equal "workflow.reconciliation-blocked" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$retention_org', '$retention_event', '$retention_lease_id', 'ABSENT',
  NULL, 'workflow.temporal-start-not-found', NULL, 1000, 10000
);
")" "RetentionBoundarySettlement"
expect_admin_true "
SELECT binding.status = 'PENDING'
  AND event_row.poisoned_at IS NULL
  AND event_row.published_at IS NULL
  AND event_row.attempts = 1
  AND reconciliation_inbox.status = 'poisoned'
  AND reconciliation_inbox.last_error
      = 'workflow.reconciliation-retention-unverifiable'
FROM investigation_workflow_bindings binding
JOIN outbox_events event_row
  ON event_row.organization_id = binding.organization_id
 AND event_row.aggregate_id = binding.run_id
JOIN inbox_events reconciliation_inbox
  ON reconciliation_inbox.organization_id = event_row.organization_id
 AND reconciliation_inbox.event_id = event_row.event_id
 AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE binding.organization_id = '$retention_org'
  AND binding.run_id = '$retention_run';
" "RetentionBoundaryPreservesPending"

takeover_org="98000000-0000-4000-8000-000000000001"
takeover_run="98000000-0000-4000-8000-000000000002"
takeover_event="98000000-0000-4000-8000-000000000003"
takeover_old_lease_id="98000000-0000-4000-8000-000000000004"
takeover_new_lease_id="98000000-0000-4000-8000-000000000005"
insert_fixture "$takeover_org" "$takeover_run" "$takeover_event" \
  "phase09-takeover"
admin_query "
UPDATE outbox_events
SET lease_token = '$takeover_old_lease_id',
    lease_expires_at = clock_timestamp() - interval '1 second'
WHERE organization_id = '$takeover_org'
  AND event_id = '$takeover_event';
" >/dev/null
claim_fixture "$takeover_event" "$takeover_new_lease_id" "ExpiredLeaseTakeoverClaim"
expect_admin_true "
SELECT event_row.lease_token = '$takeover_new_lease_id'
  AND event_row.lease_expires_at > clock_timestamp()
  AND reconciliation_inbox.status = 'received'
  AND reconciliation_inbox.attempts = 1
FROM outbox_events event_row
JOIN inbox_events reconciliation_inbox
  ON reconciliation_inbox.organization_id = event_row.organization_id
 AND reconciliation_inbox.event_id = event_row.event_id
 AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
WHERE event_row.organization_id = '$takeover_org'
  AND event_row.event_id = '$takeover_event';
" "ExpiredLeaseTakeoverAtomic"
expect_equal "workflow.reconciliation-blocked" "$(reconciler_query "
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$takeover_org', '$takeover_event', '$takeover_new_lease_id', 'BLOCKED',
  NULL, 'workflow.reconciliation-observer-failed', NULL, 1000, 3600000
);
")" "ExpiredLeaseTakeoverClosed"

admin_query "
CREATE FUNCTION public.opsmind_phase09_reconciliation_failpoint()
RETURNS trigger
LANGUAGE plpgsql
AS \$failpoint\$
BEGIN
  IF current_setting('opsmind.phase09_failpoint', true) = TG_ARGV[0] THEN
    RAISE EXCEPTION 'phase09 injected settlement failure';
  END IF;
  RETURN NEW;
END
\$failpoint\$;
REVOKE ALL ON FUNCTION public.opsmind_phase09_reconciliation_failpoint()
  FROM PUBLIC;
CREATE TRIGGER phase09_fail_after_starter
AFTER INSERT ON public.inbox_events
FOR EACH ROW
WHEN (NEW.consumer = 'investigation-workflow-starter-v1')
EXECUTE FUNCTION public.opsmind_phase09_reconciliation_failpoint('after_starter');
CREATE TRIGGER phase09_fail_after_binding
AFTER UPDATE ON public.investigation_workflow_bindings
FOR EACH ROW
EXECUTE FUNCTION public.opsmind_phase09_reconciliation_failpoint('after_binding');
CREATE TRIGGER phase09_fail_after_inbox
AFTER UPDATE ON public.inbox_events
FOR EACH ROW
EXECUTE FUNCTION public.opsmind_phase09_reconciliation_failpoint('after_inbox');
CREATE TRIGGER phase09_fail_after_outbox
AFTER UPDATE ON public.outbox_events
FOR EACH ROW
EXECUTE FUNCTION public.opsmind_phase09_reconciliation_failpoint('after_outbox');
" >/dev/null

rollback_one_org="99010000-0000-4000-8000-000000000001"
rollback_one_run="99010000-0000-4000-8000-000000000002"
rollback_one_event="99010000-0000-4000-8000-000000000003"
rollback_one_lease_id="99010000-0000-4000-8000-000000000004"
insert_fixture "$rollback_one_org" "$rollback_one_run" "$rollback_one_event" \
  "phase09-rollback-starter"
claim_fixture "$rollback_one_event" "$rollback_one_lease_id" \
  "RollbackAfterStarterClaim"
expect_failed_settlement "
SET opsmind.phase09_failpoint = 'after_starter';
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$rollback_one_org', '$rollback_one_event', '$rollback_one_lease_id', 'MISMATCH',
  NULL, 'workflow.existing-contract-mismatch', NULL, 1000, 3600000
);
" "RollbackAfterStarterInjected"
expect_rollback_atomic \
  "$rollback_one_org" "$rollback_one_run" "$rollback_one_event" \
  "$rollback_one_lease_id" "RollbackAfterStarterAtomic"

rollback_two_org="99020000-0000-4000-8000-000000000001"
rollback_two_run="99020000-0000-4000-8000-000000000002"
rollback_two_event="99020000-0000-4000-8000-000000000003"
rollback_two_lease_id="99020000-0000-4000-8000-000000000004"
insert_fixture "$rollback_two_org" "$rollback_two_run" "$rollback_two_event" \
  "phase09-rollback-binding"
claim_fixture "$rollback_two_event" "$rollback_two_lease_id" \
  "RollbackAfterBindingClaim"
expect_failed_settlement "
SET opsmind.phase09_failpoint = 'after_binding';
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$rollback_two_org', '$rollback_two_event', '$rollback_two_lease_id', 'MATCH',
  'temporal-first-run', NULL, NULL, 1000, 3600000
);
" "RollbackAfterBindingInjected"
expect_rollback_atomic \
  "$rollback_two_org" "$rollback_two_run" "$rollback_two_event" \
  "$rollback_two_lease_id" "RollbackAfterBindingAtomic"

rollback_three_org="99030000-0000-4000-8000-000000000001"
rollback_three_run="99030000-0000-4000-8000-000000000002"
rollback_three_event="99030000-0000-4000-8000-000000000003"
rollback_three_lease_id="99030000-0000-4000-8000-000000000004"
insert_fixture "$rollback_three_org" "$rollback_three_run" \
  "$rollback_three_event" "phase09-rollback-inbox"
claim_fixture "$rollback_three_event" "$rollback_three_lease_id" \
  "RollbackAfterInboxClaim"
expect_failed_settlement "
SET opsmind.phase09_failpoint = 'after_inbox';
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$rollback_three_org', '$rollback_three_event', '$rollback_three_lease_id', 'MATCH',
  'temporal-first-run', NULL, NULL, 1000, 3600000
);
" "RollbackAfterInboxInjected"
expect_rollback_atomic \
  "$rollback_three_org" "$rollback_three_run" "$rollback_three_event" \
  "$rollback_three_lease_id" "RollbackAfterInboxAtomic"

rollback_four_org="99040000-0000-4000-8000-000000000001"
rollback_four_run="99040000-0000-4000-8000-000000000002"
rollback_four_event="99040000-0000-4000-8000-000000000003"
rollback_four_lease_id="99040000-0000-4000-8000-000000000004"
insert_fixture "$rollback_four_org" "$rollback_four_run" "$rollback_four_event" \
  "phase09-rollback-outbox"
claim_fixture "$rollback_four_event" "$rollback_four_lease_id" \
  "RollbackAfterOutboxClaim"
expect_failed_settlement "
SET opsmind.phase09_failpoint = 'after_outbox';
SELECT opsmind_settle_investigation_workflow_reconciliation(
  '$rollback_four_org', '$rollback_four_event', '$rollback_four_lease_id', 'MATCH',
  'temporal-first-run', NULL, NULL, 1000, 3600000
);
" "RollbackAfterOutboxInjected"
expect_rollback_atomic \
  "$rollback_four_org" "$rollback_four_run" "$rollback_four_event" \
  "$rollback_four_lease_id" "RollbackAfterOutboxAtomic"
