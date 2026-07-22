-- Phase 4B: immutable bounded evidence records linked to the investigation ledger.
-- Large/raw artifacts remain outside PostgreSQL behind the evidence artifact port.

ALTER TABLE investigation_run_events
    ADD COLUMN tool_execution_id uuid,
    ADD COLUMN tool_request_digest bytea,
    ADD CONSTRAINT investigation_run_events_tool_identity_pair
        CHECK ((tool_execution_id IS NULL) = (tool_request_digest IS NULL)),
    ADD CONSTRAINT investigation_run_events_tool_request_digest_length
        CHECK (tool_request_digest IS NULL OR octet_length(tool_request_digest) = 32);

CREATE TABLE evidence_records (
    evidence_id                 uuid NOT NULL,
    organization_id             uuid NOT NULL REFERENCES organizations(id),
    project_id                  uuid NOT NULL,
    incident_id                 uuid NOT NULL,
    run_id                      uuid NOT NULL,
    actor_id                    uuid NOT NULL,
    intent_id                   uuid NOT NULL,
    execution_id                uuid NOT NULL,
    investigation_event_id      uuid NOT NULL UNIQUE
        REFERENCES investigation_run_events(event_id),
    gateway_audit_event_id      uuid NOT NULL,
    gateway_request_digest      bytea NOT NULL,
    source_type                 varchar(32) NOT NULL
        CHECK (source_type IN ('metric', 'log_summary', 'trace', 'change', 'runbook')),
    source_identity             varchar(128) NOT NULL,
    target_identity             varchar(256) NOT NULL,
    observed_at                 timestamptz NOT NULL,
    window_start                timestamptz NOT NULL,
    window_end                  timestamptz NOT NULL,
    connector_version           varchar(128) NOT NULL,
    manifest_version            varchar(128) NOT NULL,
    policy_version              varchar(128) NOT NULL,
    source_provenance           varchar(256) NOT NULL,
    trust_class                 varchar(32) NOT NULL
        CHECK (trust_class IN ('synthetic', 'source-attested', 'derived')),
    content_digest              bytea NOT NULL,
    canonical_content           text NOT NULL,
    redacted_fields             integer NOT NULL CHECK (redacted_fields >= 0),
    truncated                   boolean NOT NULL,
    gateway_duplicate           boolean NOT NULL,
    retention_class             varchar(32) NOT NULL DEFAULT 'evidence-90d'
        CHECK (retention_class = 'evidence-90d'),
    lifecycle_state             varchar(32) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (lifecycle_state = 'AVAILABLE'),
    created_at                  timestamptz NOT NULL,
    PRIMARY KEY (organization_id, evidence_id),
    UNIQUE (organization_id, run_id, intent_id),
    UNIQUE (organization_id, run_id, execution_id),
    FOREIGN KEY (run_id, organization_id, project_id, incident_id)
        REFERENCES investigation_runs(run_id, organization_id, project_id, incident_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships(organization_id, user_id),
    CHECK (octet_length(gateway_request_digest) = 32),
    CHECK (octet_length(content_digest) = 32),
    CHECK (window_end >= window_start),
    CHECK (source_identity ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}$'),
    CHECK (target_identity ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,255}$'),
    CHECK (connector_version ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}$'),
    CHECK (manifest_version ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}$'),
    CHECK (policy_version ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}$'),
    CHECK (source_provenance ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,255}$'),
    CHECK (jsonb_typeof(canonical_content::jsonb) = 'object'),
    CHECK (octet_length(convert_to(canonical_content, 'UTF8')) BETWEEN 2 AND 65536),
    CHECK (content_digest = public.digest(convert_to(canonical_content, 'UTF8'), 'sha256'))
);

CREATE INDEX evidence_records_incident_created_idx
    ON evidence_records (
        organization_id, project_id, incident_id, created_at, evidence_id
    );
CREATE INDEX evidence_records_run_created_idx
    ON evidence_records (organization_id, run_id, created_at, evidence_id);
CREATE INDEX evidence_records_actor_membership_idx
    ON evidence_records (organization_id, actor_id, created_at DESC);

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_record_append() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    actor_id uuid := public.opsmind_current_actor_id();
    event_row record;
    details jsonb;
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
        OR NEW.retention_class IS DISTINCT FROM 'evidence-90d'
        OR NEW.lifecycle_state IS DISTINCT FROM 'AVAILABLE'
    ) THEN
        RAISE EXCEPTION 'evidence append requires the bound tenant, actor, and inline lifecycle'
            USING ERRCODE = '42501';
    END IF;

    SELECT stored_event.organization_id, stored_event.project_id, stored_event.incident_id,
           stored_event.run_id, stored_event.actor_id, stored_event.event_type,
           stored_event.occurred_at, stored_event.payload,
           stored_event.tool_execution_id, stored_event.tool_request_digest
      INTO event_row
      FROM public.investigation_run_events stored_event
     WHERE stored_event.event_id = NEW.investigation_event_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'evidence record requires an authoritative investigation event'
            USING ERRCODE = 'P7101';
    END IF;
    details := event_row.payload -> 'details';
    IF event_row.event_type IS DISTINCT FROM 'EVIDENCE_APPENDED'
       OR event_row.organization_id IS DISTINCT FROM NEW.organization_id
       OR event_row.project_id IS DISTINCT FROM NEW.project_id
       OR event_row.incident_id IS DISTINCT FROM NEW.incident_id
       OR event_row.run_id IS DISTINCT FROM NEW.run_id
       OR event_row.actor_id IS DISTINCT FROM NEW.actor_id
       OR event_row.occurred_at IS DISTINCT FROM NEW.created_at
       OR event_row.tool_execution_id IS DISTINCT FROM NEW.execution_id
       OR event_row.tool_request_digest IS DISTINCT FROM NEW.gateway_request_digest
       OR jsonb_typeof(details) IS DISTINCT FROM 'object'
       OR details ->> 'runId' IS DISTINCT FROM NEW.run_id::text
       OR details ->> 'intentId' IS DISTINCT FROM NEW.intent_id::text
       OR details ->> 'evidenceId' IS DISTINCT FROM NEW.evidence_id::text
       OR details ->> 'digest'
            IS DISTINCT FROM 'sha256:' || encode(NEW.content_digest, 'hex')
       OR details ->> 'sourceType' IS DISTINCT FROM NEW.source_type THEN
        RAISE EXCEPTION 'evidence record does not match its investigation event'
            USING ERRCODE = 'P7101';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_event_tool_identity() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF (NEW.event_type = 'EVIDENCE_APPENDED' AND (
            NEW.tool_execution_id IS NULL OR NEW.tool_request_digest IS NULL
        )) OR (NEW.event_type <> 'EVIDENCE_APPENDED' AND (
            NEW.tool_execution_id IS NOT NULL OR NEW.tool_request_digest IS NOT NULL
        )) THEN
        RAISE EXCEPTION 'tool execution identity is valid only for evidence events'
            USING ERRCODE = 'P7101';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_require_evidence_record_for_event() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NEW.event_type = 'EVIDENCE_APPENDED' AND NOT EXISTS (
        SELECT 1
          FROM public.evidence_records evidence
         WHERE evidence.investigation_event_id = NEW.event_id
           AND evidence.organization_id = NEW.organization_id
           AND evidence.run_id = NEW.run_id
    ) THEN
        RAISE EXCEPTION 'evidence investigation event requires exactly one evidence record'
            USING ERRCODE = 'P7102';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER evidence_records_validate_append
    BEFORE INSERT ON evidence_records
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_record_append();
CREATE TRIGGER investigation_evidence_event_validate_tool_identity
    BEFORE INSERT ON investigation_run_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_event_tool_identity();
CREATE TRIGGER evidence_records_no_update
    BEFORE UPDATE OR DELETE ON evidence_records
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_audit_mutation();
CREATE TRIGGER evidence_records_no_truncate
    BEFORE TRUNCATE ON evidence_records
    FOR EACH STATEMENT EXECUTE FUNCTION opsmind_reject_audit_mutation();
CREATE CONSTRAINT TRIGGER investigation_evidence_event_requires_record
    AFTER INSERT ON investigation_run_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.event_type = 'EVIDENCE_APPENDED')
    EXECUTE FUNCTION opsmind_require_evidence_record_for_event();

ALTER TABLE evidence_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_records FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_records_tenant_isolation ON evidence_records
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

GRANT SELECT ON evidence_records TO opsmind_app;
GRANT INSERT (
    evidence_id, organization_id, project_id, incident_id, run_id, actor_id,
    intent_id, execution_id, investigation_event_id, gateway_audit_event_id,
    gateway_request_digest, source_type, source_identity, target_identity,
    observed_at, window_start, window_end, connector_version, manifest_version,
    policy_version, source_provenance, trust_class, content_digest,
    canonical_content, redacted_fields, truncated, gateway_duplicate,
    retention_class, lifecycle_state, created_at
) ON evidence_records TO opsmind_app;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence_records FROM opsmind_app;
REVOKE ALL ON evidence_records FROM opsmind_dispatcher;

REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_record_append() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_event_tool_identity() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_require_evidence_record_for_event() FROM PUBLIC;

COMMENT ON TABLE evidence_records IS
    'Immutable bounded redacted evidence records; large/raw artifacts use the separate artifact port.';
COMMENT ON COLUMN evidence_records.canonical_content IS
    'Exact canonical UTF-8 JSON bytes protected by content_digest and a 64 KiB hard limit.';
