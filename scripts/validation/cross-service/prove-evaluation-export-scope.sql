\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset pager off
\set QUIET on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT set_config('opsmind.tenant_id', :'foreign_organization_id', true) AS tenant_context
\gset
SELECT set_config(
    'opsmind.ai_runtime_tenant_id', :'foreign_organization_id', true
) AS runtime_tenant_context
\gset
SELECT set_config('opsmind.actor_id', :'foreign_actor_id', true) AS actor_context
\gset
SELECT set_config(
    'opsmind.evaluation_project_id', :'scope_project_id', true
) AS project_context
\gset
SELECT set_config(
    'opsmind.evaluation_incident_id', :'scope_incident_id', true
) AS incident_context
\gset
SELECT set_config('opsmind.evaluation_run_id', :'scope_run_id', true) AS run_context
\gset

SELECT 'CROSS_TENANT_PROOF_PASS'
WHERE current_user = 'opsmind_evaluator'
  AND session_user = 'opsmind_evaluator'
  AND (SELECT count(*) FROM opsmind_evaluation.scoped_runs) = 0
  AND (SELECT count(*) FROM opsmind_evaluation.scoped_events) = 0
  AND (SELECT count(*) FROM opsmind_evaluation.scoped_evidence_records) = 0
  AND (SELECT count(*) FROM opsmind_evaluation.scoped_analysis_invocations) = 0
  AND (SELECT count(*) FROM opsmind_evaluation.scoped_tool_receipts) = 0;
COMMIT;
