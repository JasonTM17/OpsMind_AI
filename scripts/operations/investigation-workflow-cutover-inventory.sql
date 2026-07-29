\set ON_ERROR_STOP on

-- Run through scripts/operations/run-investigation-workflow-cutover-inventory.sh
-- with the migration/admin role while new investigation starts are frozen. The
-- wrapper owns the blocked-inventory exit contract across psql versions.
--
-- Legacy Phase 7 runs did not persist the canonical HTTP request digest or the
-- authorization snapshot revision. Consequently no unbound row is safe for
-- automatic backfill: similar-looking reducer state is insufficient proof.

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
LOCK TABLE investigation_runs IN SHARE MODE;

WITH inventory AS (
SELECT
    run.organization_id,
    run.project_id,
    run.incident_id,
    run.run_id,
    run.actor_id,
    run.status,
    run.revision,
    run.event_count,
    run.started_at,
    run.deadline_at,
    CASE
        WHEN run.revision <> 0 OR run.event_count <> 1
            THEN 'non_initial_reducer_state'
        WHEN (
            SELECT count(*)
            FROM investigation_run_events event
            WHERE event.organization_id = run.organization_id
              AND event.run_id = run.run_id
              AND event.sequence_no = 1
              AND event.event_type = 'RUN_STARTED'
        ) <> 1
            THEN 'initial_ledger_incomplete'
        WHEN (
            SELECT count(*)
            FROM audit_events audit
            WHERE audit.organization_id = run.organization_id
              AND audit.resource_type = 'investigation_run'
              AND audit.resource_id = run.run_id::text
        ) <> 1
            THEN 'initial_audit_incomplete'
        ELSE 'legacy_request_digest_and_authorization_revision_not_persisted'
    END AS reconciliation_reason,
    false AS eligible_for_automatic_backfill
FROM investigation_runs run
LEFT JOIN investigation_workflow_bindings binding
  ON binding.organization_id = run.organization_id
 AND binding.run_id = run.run_id
WHERE run.status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE')
  AND binding.run_id IS NULL
)
SELECT jsonb_pretty(jsonb_build_object(
    'schema_version', 'investigation-workflow-cutover-v1',
    'generated_at', statement_timestamp(),
    'starts_must_be_frozen', true,
    'automatic_backfill_count', 0,
    'unresolved_count', count(*),
    'unresolved', COALESCE(
        jsonb_agg(to_jsonb(inventory) ORDER BY organization_id, started_at, run_id),
        '[]'::jsonb
    )
)) AS cutover_report
FROM inventory;

WITH inventory AS (
    SELECT run.run_id
    FROM investigation_runs run
    LEFT JOIN investigation_workflow_bindings binding
      ON binding.organization_id = run.organization_id
     AND binding.run_id = run.run_id
    WHERE run.status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE')
      AND binding.run_id IS NULL
)
SELECT (count(*) > 0)::text AS has_unresolved
FROM inventory
\gset

\if :has_unresolved
    \echo 'FAILED: unresolved legacy investigation rows block Temporal admission.'
    ROLLBACK;
    \quit
\else
    \echo 'PASSED: zero unresolved legacy investigation rows.'
    COMMIT;
\endif
