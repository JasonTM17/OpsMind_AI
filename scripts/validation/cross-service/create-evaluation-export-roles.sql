\set ON_ERROR_STOP on

-- Disposable database only. The view owner cannot log in and the evaluator
-- cannot inherit its allowlisted source-column privileges.
CREATE ROLE opsmind_evaluation_view_owner
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE opsmind_evaluator
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE opsmind_evaluator SET default_transaction_read_only = on;

GRANT CONNECT ON DATABASE opsmind TO opsmind_evaluator;
GRANT USAGE ON SCHEMA public, ai_runtime, tool_gateway
    TO opsmind_evaluation_view_owner;
GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id()
    TO opsmind_evaluation_view_owner, opsmind_evaluator;
GRANT USAGE ON SCHEMA ai_runtime TO opsmind_evaluator;
GRANT EXECUTE ON FUNCTION ai_runtime.current_tenant_id()
    TO opsmind_evaluation_view_owner, opsmind_evaluator;

GRANT SELECT (
    run_id, organization_id, project_id, incident_id, actor_id, status,
    rounds, tool_calls, total_tokens, event_count, evidence_ids_state,
    pending_intents_state, terminal_reason, started_at, deadline_at, ended_at
) ON public.investigation_runs TO opsmind_evaluation_view_owner;
GRANT REFERENCES (
    run_id, organization_id, project_id, incident_id
) ON public.investigation_runs TO opsmind_evaluation_view_owner;
GRANT SELECT (
    event_id, organization_id, project_id, incident_id, run_id, sequence_no,
    event_type, actor_id, occurred_at, payload
) ON public.investigation_run_events TO opsmind_evaluation_view_owner;
GRANT SELECT (
    evidence_id, organization_id, project_id, incident_id, run_id, actor_id,
    intent_id, execution_id, investigation_event_id, gateway_audit_event_id,
    gateway_request_digest, source_type, source_identity, target_identity,
    observed_at, window_start, window_end, connector_version, manifest_version,
    policy_version, source_provenance, trust_class, content_digest,
    redacted_fields, truncated, gateway_duplicate, created_at
) ON public.evidence_records TO opsmind_evaluation_view_owner;
GRANT SELECT (
    invocation_id, organization_id, incident_id, run_id, state,
    response_status, response_payload, provider, model_id, prompt_version,
    schema_version, actual_tokens, actual_tools, actual_cost_usd, started_at,
    finished_at
) ON ai_runtime.analysis_invocations TO opsmind_evaluation_view_owner;
GRANT SELECT (
    execution_id, tenant_id, project_id, incident_id, run_id, request_digest,
    status, completed_at
) ON tool_gateway.execution_receipts TO opsmind_evaluation_view_owner;
GRANT SELECT (
    audit_event_id, execution_id, outcome, request_digest, manifest_version,
    tool, action, risk_class, connector_id, connector_profile,
    connector_manifest_byte_digest, result_digest, policy_version, denial_code
) ON tool_gateway.tool_audit_events TO opsmind_evaluation_view_owner;

CREATE SCHEMA opsmind_evaluation AUTHORIZATION opsmind_evaluation_view_owner;
REVOKE ALL ON SCHEMA opsmind_evaluation FROM PUBLIC;

SET ROLE opsmind_evaluation_view_owner;

CREATE TABLE opsmind_evaluation.allowed_scopes (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    incident_id uuid NOT NULL,
    run_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    PRIMARY KEY (organization_id, project_id, incident_id, run_id, actor_id),
    FOREIGN KEY (run_id, organization_id, project_id, incident_id)
        REFERENCES public.investigation_runs(
            run_id, organization_id, project_id, incident_id
        )
);
REVOKE ALL ON opsmind_evaluation.allowed_scopes FROM PUBLIC;

CREATE VIEW opsmind_evaluation.scoped_runs
WITH (security_barrier = true) AS
SELECT
    run.run_id, run.organization_id, run.project_id, run.incident_id,
    run.actor_id, run.status, run.rounds, run.tool_calls, run.total_tokens,
    run.event_count, run.evidence_ids_state, run.pending_intents_state,
    run.terminal_reason, run.started_at, run.deadline_at, run.ended_at
FROM public.investigation_runs run
JOIN opsmind_evaluation.allowed_scopes scope
  ON scope.organization_id = run.organization_id
 AND scope.project_id = run.project_id
 AND scope.incident_id = run.incident_id
 AND scope.run_id = run.run_id
 AND scope.actor_id = run.actor_id
WHERE run.organization_id =
        nullif(current_setting('opsmind.tenant_id', true), '')::uuid
  AND run.project_id =
        nullif(current_setting('opsmind.evaluation_project_id', true), '')::uuid
  AND run.incident_id =
        nullif(current_setting('opsmind.evaluation_incident_id', true), '')::uuid
  AND run.run_id =
        nullif(current_setting('opsmind.evaluation_run_id', true), '')::uuid
  AND run.actor_id = nullif(current_setting('opsmind.actor_id', true), '')::uuid;

CREATE VIEW opsmind_evaluation.scoped_events
WITH (security_barrier = true) AS
SELECT
    event.event_id, event.sequence_no, event.event_type, event.occurred_at,
    CASE
        WHEN event.event_type = 'ANALYSIS_ACCEPTED'
        THEN event.payload -> 'details' -> 'response'
        ELSE NULL
    END AS accepted_analysis
FROM public.investigation_run_events event
JOIN opsmind_evaluation.allowed_scopes scope
  ON scope.organization_id = event.organization_id
 AND scope.project_id = event.project_id
 AND scope.incident_id = event.incident_id
 AND scope.run_id = event.run_id
 AND scope.actor_id = event.actor_id
WHERE event.organization_id =
        nullif(current_setting('opsmind.tenant_id', true), '')::uuid
  AND event.project_id =
        nullif(current_setting('opsmind.evaluation_project_id', true), '')::uuid
  AND event.incident_id =
        nullif(current_setting('opsmind.evaluation_incident_id', true), '')::uuid
  AND event.run_id =
        nullif(current_setting('opsmind.evaluation_run_id', true), '')::uuid
  AND event.actor_id =
        nullif(current_setting('opsmind.actor_id', true), '')::uuid;

CREATE VIEW opsmind_evaluation.scoped_evidence_records
WITH (security_barrier = true) AS
SELECT
    evidence.evidence_id, evidence.organization_id, evidence.project_id,
    evidence.incident_id, evidence.run_id, evidence.actor_id,
    evidence.intent_id, evidence.execution_id, evidence.investigation_event_id,
    evidence.gateway_audit_event_id,
    'sha256:' || encode(evidence.gateway_request_digest, 'hex')
        AS gateway_request_digest,
    evidence.source_type, evidence.source_identity, evidence.target_identity,
    evidence.observed_at, evidence.window_start, evidence.window_end,
    evidence.connector_version, evidence.manifest_version,
    evidence.policy_version, evidence.source_provenance, evidence.trust_class,
    'sha256:' || encode(evidence.content_digest, 'hex') AS content_digest,
    evidence.redacted_fields, evidence.truncated, evidence.gateway_duplicate,
    evidence.created_at
FROM public.evidence_records evidence
JOIN opsmind_evaluation.allowed_scopes scope
  ON scope.organization_id = evidence.organization_id
 AND scope.project_id = evidence.project_id
 AND scope.incident_id = evidence.incident_id
 AND scope.run_id = evidence.run_id
 AND scope.actor_id = evidence.actor_id
WHERE evidence.organization_id =
        nullif(current_setting('opsmind.tenant_id', true), '')::uuid
  AND evidence.project_id =
        nullif(current_setting('opsmind.evaluation_project_id', true), '')::uuid
  AND evidence.incident_id =
        nullif(current_setting('opsmind.evaluation_incident_id', true), '')::uuid
  AND evidence.run_id =
        nullif(current_setting('opsmind.evaluation_run_id', true), '')::uuid
  AND evidence.actor_id =
        nullif(current_setting('opsmind.actor_id', true), '')::uuid;

CREATE VIEW opsmind_evaluation.scoped_analysis_invocations
WITH (security_barrier = true) AS
SELECT
    invocation.invocation_id, invocation.organization_id,
    invocation.incident_id, invocation.run_id, invocation.state,
    invocation.response_status, invocation.response_payload,
    invocation.provider, invocation.model_id, invocation.prompt_version,
    invocation.schema_version, invocation.actual_tokens,
    invocation.actual_tools, invocation.actual_cost_usd,
    invocation.started_at, invocation.finished_at
FROM ai_runtime.analysis_invocations invocation
JOIN public.investigation_runs run
  ON run.organization_id = invocation.organization_id
 AND run.incident_id = invocation.incident_id
 AND run.run_id = invocation.run_id
JOIN opsmind_evaluation.allowed_scopes scope
  ON scope.organization_id = run.organization_id
 AND scope.project_id = run.project_id
 AND scope.incident_id = run.incident_id
 AND scope.run_id = run.run_id
 AND scope.actor_id = run.actor_id
WHERE invocation.organization_id =
        nullif(current_setting('opsmind.ai_runtime_tenant_id', true), '')::uuid
  AND run.organization_id = nullif(current_setting('opsmind.tenant_id', true), '')::uuid
  AND run.project_id = nullif(current_setting('opsmind.evaluation_project_id', true), '')::uuid
  AND run.incident_id = nullif(current_setting('opsmind.evaluation_incident_id', true), '')::uuid
  AND run.run_id = nullif(current_setting('opsmind.evaluation_run_id', true), '')::uuid
  AND run.actor_id = nullif(current_setting('opsmind.actor_id', true), '')::uuid;

CREATE VIEW opsmind_evaluation.scoped_tool_receipts
WITH (security_barrier = true) AS
SELECT
    receipt.execution_id, receipt.tenant_id, receipt.project_id,
    receipt.incident_id, receipt.run_id,
    'sha256:' || receipt.request_digest AS request_digest,
    receipt.status, receipt.completed_at,
    audit.audit_event_id, audit.outcome AS audit_outcome,
    'sha256:' || audit.request_digest AS audit_request_digest,
    CASE WHEN audit.result_digest IS NULL
        THEN NULL ELSE 'sha256:' || audit.result_digest END AS result_digest,
    audit.manifest_version, audit.policy_version, audit.denial_code,
    audit.tool AS connector, audit.action AS operation,
    audit.risk_class, audit.connector_id, audit.connector_profile,
    audit.connector_manifest_byte_digest,
    jsonb_build_array(evidence.content_digest) AS evidence_digests
FROM tool_gateway.execution_receipts receipt
JOIN public.investigation_runs run
  ON run.organization_id = receipt.tenant_id
 AND run.project_id = receipt.project_id
 AND run.incident_id = receipt.incident_id
 AND run.run_id = receipt.run_id
JOIN opsmind_evaluation.allowed_scopes scope
  ON scope.organization_id = run.organization_id
 AND scope.project_id = run.project_id
 AND scope.incident_id = run.incident_id
 AND scope.run_id = run.run_id
 AND scope.actor_id = run.actor_id
JOIN opsmind_evaluation.scoped_evidence_records evidence
  ON evidence.organization_id = receipt.tenant_id
 AND evidence.run_id = receipt.run_id
 AND evidence.execution_id = receipt.execution_id
JOIN tool_gateway.tool_audit_events audit
  ON audit.audit_event_id = evidence.gateway_audit_event_id
 AND audit.execution_id = receipt.execution_id
WHERE receipt.tenant_id = nullif(current_setting('opsmind.tenant_id', true), '')::uuid
  AND receipt.project_id = nullif(current_setting('opsmind.evaluation_project_id', true), '')::uuid
  AND receipt.incident_id = nullif(current_setting('opsmind.evaluation_incident_id', true), '')::uuid
  AND receipt.run_id = nullif(current_setting('opsmind.evaluation_run_id', true), '')::uuid
  AND run.actor_id = nullif(current_setting('opsmind.actor_id', true), '')::uuid;

RESET ROLE;

GRANT USAGE ON SCHEMA opsmind_evaluation TO opsmind_evaluator;
GRANT SELECT ON
    opsmind_evaluation.scoped_runs,
    opsmind_evaluation.scoped_events,
    opsmind_evaluation.scoped_evidence_records,
    opsmind_evaluation.scoped_analysis_invocations,
    opsmind_evaluation.scoped_tool_receipts
TO opsmind_evaluator;

DO $role_assertions$
DECLARE
    evaluator record;
    view_owner record;
    unsafe_view_count integer;
BEGIN
    SELECT rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin, rolreplication,
           rolbypassrls
      INTO evaluator
      FROM pg_roles
     WHERE rolname = 'opsmind_evaluator';
    SELECT rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin, rolreplication,
           rolbypassrls
      INTO view_owner
      FROM pg_roles
     WHERE rolname = 'opsmind_evaluation_view_owner';
    IF evaluator.rolsuper OR evaluator.rolinherit OR evaluator.rolcreaterole
       OR evaluator.rolcreatedb OR NOT evaluator.rolcanlogin
       OR evaluator.rolreplication OR evaluator.rolbypassrls
       OR view_owner.rolsuper OR view_owner.rolinherit OR view_owner.rolcreaterole
       OR view_owner.rolcreatedb OR view_owner.rolcanlogin
       OR view_owner.rolreplication OR view_owner.rolbypassrls
       OR pg_has_role('opsmind_evaluator', 'opsmind_evaluation_view_owner', 'MEMBER')
       OR NOT has_function_privilege(
            'opsmind_evaluator', 'public.opsmind_current_tenant_id()', 'EXECUTE'
       )
       OR NOT has_function_privilege(
            'opsmind_evaluator', 'ai_runtime.current_tenant_id()', 'EXECUTE'
       )
       OR has_table_privilege('opsmind_evaluator', 'public.investigation_runs', 'SELECT')
       OR has_table_privilege('opsmind_evaluator', 'public.investigation_run_events', 'SELECT')
       OR has_table_privilege('opsmind_evaluator', 'public.evidence_records', 'SELECT')
       OR has_table_privilege(
            'opsmind_evaluator', 'ai_runtime.analysis_invocations', 'SELECT'
       )
       OR has_table_privilege(
            'opsmind_evaluator', 'tool_gateway.execution_receipts', 'SELECT'
       )
       OR has_table_privilege(
            'opsmind_evaluator', 'tool_gateway.tool_audit_events', 'SELECT'
       )
       OR has_table_privilege(
            'opsmind_evaluator', 'opsmind_evaluation.allowed_scopes', 'SELECT'
       ) THEN
        RAISE EXCEPTION 'disposable evaluator roles or raw grants are unsafe';
    END IF;

    SELECT count(*)
      INTO unsafe_view_count
      FROM pg_class relation
      JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
     WHERE namespace.nspname = 'opsmind_evaluation'
       AND relation.relkind = 'v'
       AND NOT coalesce(relation.reloptions, ARRAY[]::text[])
            @> ARRAY['security_barrier=true'];
    IF unsafe_view_count <> 0 THEN
        RAISE EXCEPTION 'every evaluator view must be a security barrier';
    END IF;
END
$role_assertions$;
