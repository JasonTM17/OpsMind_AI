\set ON_ERROR_STOP on
\set QUIET on

BEGIN;
INSERT INTO opsmind_evaluation.allowed_scopes (
    organization_id, project_id, incident_id, run_id, actor_id
) VALUES (
    :'scope_organization_id'::uuid,
    :'scope_project_id'::uuid,
    :'scope_incident_id'::uuid,
    :'scope_run_id'::uuid,
    :'scope_actor_id'::uuid
);
COMMIT;
