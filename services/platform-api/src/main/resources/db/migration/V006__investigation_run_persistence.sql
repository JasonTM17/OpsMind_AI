-- Durable tenant-scoped investigation snapshots, append-only reducer events,
-- and audit-chain binding. Raw prompts, provider reasoning, credentials, and
-- tool response bodies are deliberately absent from this schema.

DO $$
DECLARE
    app_unsafe boolean;
    dispatcher_unsafe boolean;
BEGIN
    SELECT rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit
      INTO app_unsafe
      FROM pg_roles
     WHERE rolname = 'opsmind_app';
    IF app_unsafe IS NULL THEN
        RAISE EXCEPTION 'required non-owner runtime role opsmind_app is missing';
    END IF;
    IF app_unsafe THEN
        RAISE EXCEPTION 'runtime role opsmind_app has unsafe attributes';
    END IF;

    SELECT rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit
      INTO dispatcher_unsafe
      FROM pg_roles
     WHERE rolname = 'opsmind_dispatcher';
    IF dispatcher_unsafe IS NULL THEN
        RAISE EXCEPTION 'required outbox role opsmind_dispatcher is missing';
    END IF;
    IF dispatcher_unsafe THEN
        RAISE EXCEPTION 'outbox role opsmind_dispatcher has unsafe attributes';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_json_object_has_exact_keys(
    document jsonb,
    required_keys text[]
) RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT jsonb_typeof(document) = 'object'
       AND document ?& required_keys
       AND document - required_keys = '{}'::jsonb
$$;

CREATE OR REPLACE FUNCTION opsmind_valid_analysis_response(
    document jsonb,
    expected_run_id uuid,
    expected_status text
) RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT public.opsmind_json_object_has_exact_keys(document, ARRAY[
               'status', 'run_id', 'model_id', 'prompt_version', 'schema_version',
               'hypotheses', 'counter_evidence', 'missing_evidence', 'citations',
               'confidence', 'usage', 'cost_estimate', 'requested_tool_calls'
           ])
       AND document ->> 'status' = expected_status
       AND document ->> 'run_id' = expected_run_id::text
       AND document ->> 'schema_version' = 'analysis-v1'
       AND length(document ->> 'model_id') BETWEEN 1 AND 256
       AND length(document ->> 'prompt_version') BETWEEN 1 AND 256
       AND jsonb_typeof(document -> 'hypotheses') = 'array'
       AND jsonb_array_length(document -> 'hypotheses') BETWEEN 1 AND 20
       AND jsonb_typeof(document -> 'counter_evidence') = 'array'
       AND jsonb_array_length(document -> 'counter_evidence') <= 100
       AND jsonb_typeof(document -> 'missing_evidence') = 'array'
       AND jsonb_array_length(document -> 'missing_evidence') <= 100
       AND jsonb_typeof(document -> 'citations') = 'array'
       AND jsonb_array_length(document -> 'citations') BETWEEN 1 AND 100
       AND jsonb_typeof(document -> 'confidence') = 'number'
       AND jsonb_typeof(document -> 'usage') = 'object'
       AND public.opsmind_json_object_has_exact_keys(
               document -> 'usage',
               ARRAY['prompt_tokens', 'completion_tokens', 'total_tokens']
           )
       AND jsonb_typeof(document -> 'usage' -> 'prompt_tokens') = 'number'
       AND jsonb_typeof(document -> 'usage' -> 'completion_tokens') = 'number'
       AND jsonb_typeof(document -> 'usage' -> 'total_tokens') = 'number'
       AND jsonb_typeof(document -> 'cost_estimate') = 'object'
       AND public.opsmind_json_object_has_exact_keys(
               document -> 'cost_estimate', ARRAY['currency', 'amount']
           )
       AND document -> 'cost_estimate' ->> 'currency' = 'USD'
       AND jsonb_typeof(document -> 'cost_estimate' -> 'amount') = 'number'
       AND jsonb_typeof(document -> 'requested_tool_calls') = 'array'
       AND jsonb_array_length(document -> 'requested_tool_calls') = 0
$$;

CREATE TABLE investigation_runs (
    run_id                          uuid NOT NULL,
    organization_id                uuid NOT NULL REFERENCES organizations(id),
    project_id                     uuid NOT NULL,
    incident_id                    uuid NOT NULL,
    actor_id                       uuid NOT NULL,
    status                         varchar(32) NOT NULL
        CHECK (status IN (
            'CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE', 'COMPLETED',
            'ABSTAINED', 'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED'
        )),
    max_rounds                     integer NOT NULL CHECK (max_rounds BETWEEN 1 AND 20),
    max_tool_calls                 integer NOT NULL CHECK (max_tool_calls BETWEEN 0 AND 20),
    max_evidence_items             integer NOT NULL CHECK (max_evidence_items BETWEEN 1 AND 200),
    max_tokens                     integer NOT NULL CHECK (max_tokens BETWEEN 1 AND 100000),
    rounds                         integer NOT NULL DEFAULT 0 CHECK (rounds >= 0),
    tool_calls                     integer NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    total_tokens                   integer NOT NULL DEFAULT 0 CHECK (total_tokens >= 0),
    revision                       bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    event_count                    bigint NOT NULL CHECK (event_count >= 1),
    requested_fingerprints_state   jsonb NOT NULL DEFAULT '[]'::jsonb,
    evidence_ids_state             jsonb NOT NULL DEFAULT '[]'::jsonb,
    pending_intents_state          jsonb NOT NULL DEFAULT '[]'::jsonb,
    final_response                 jsonb,
    terminal_reason                varchar(2000),
    started_at                     timestamptz NOT NULL,
    deadline_at                    timestamptz NOT NULL,
    ended_at                       timestamptz,
    PRIMARY KEY (organization_id, run_id),
    UNIQUE (run_id, organization_id, project_id, incident_id),
    FOREIGN KEY (incident_id, organization_id, project_id)
        REFERENCES incidents(id, organization_id, project_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships(organization_id, user_id),
    CHECK (deadline_at > started_at),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    CHECK (rounds <= max_rounds + 1),
    CHECK (tool_calls <= max_tool_calls),
    CHECK (jsonb_typeof(requested_fingerprints_state) = 'array'),
    CHECK (jsonb_array_length(requested_fingerprints_state) <= max_tool_calls),
    CHECK (octet_length(convert_to(requested_fingerprints_state::text, 'UTF8')) <= 1048576),
    CHECK (jsonb_typeof(evidence_ids_state) = 'array'),
    CHECK (jsonb_array_length(evidence_ids_state) <= max_evidence_items),
    CHECK (octet_length(convert_to(evidence_ids_state::text, 'UTF8')) <= 1048576),
    CHECK (jsonb_typeof(pending_intents_state) = 'array'),
    CHECK (jsonb_array_length(pending_intents_state) <= max_tool_calls),
    CHECK (octet_length(convert_to(pending_intents_state::text, 'UTF8')) <= 1048576),
    CHECK (final_response IS NULL OR (
        octet_length(convert_to(final_response::text, 'UTF8')) <= 1048576
        AND public.opsmind_valid_analysis_response(final_response, run_id, 'complete')
    ) IS TRUE),
    CHECK (terminal_reason IS NULL OR length(trim(terminal_reason)) > 0),
    CHECK (
        (status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE')
            AND ended_at IS NULL
            AND terminal_reason IS NULL
            AND final_response IS NULL)
        OR
        (status = 'COMPLETED'
            AND ended_at IS NOT NULL
            AND terminal_reason IS NULL
            AND final_response IS NOT NULL)
        OR
        (status IN ('ABSTAINED', 'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED')
            AND ended_at IS NOT NULL
            AND terminal_reason IS NOT NULL
            AND final_response IS NULL)
    )
);

CREATE TABLE investigation_run_events (
    event_id            uuid PRIMARY KEY,
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    project_id          uuid NOT NULL,
    incident_id         uuid NOT NULL,
    run_id              uuid NOT NULL,
    sequence_no         bigint NOT NULL CHECK (sequence_no > 0),
    event_type          varchar(64) NOT NULL
        CHECK (event_type IN (
            'RUN_STARTED', 'ANALYSIS_ACCEPTED', 'TOOL_REQUESTED',
            'EVIDENCE_APPENDED', 'COMPLETED', 'ABSTAINED',
            'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED'
        )),
    actor_id            uuid NOT NULL,
    occurred_at         timestamptz NOT NULL,
    payload             jsonb NOT NULL,
    UNIQUE (organization_id, run_id, sequence_no),
    FOREIGN KEY (run_id, organization_id, project_id, incident_id)
        REFERENCES investigation_runs(run_id, organization_id, project_id, incident_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships(organization_id, user_id),
    CHECK (jsonb_typeof(payload) = 'object'),
    CHECK (octet_length(convert_to(payload::text, 'UTF8')) <= 1048576)
);

CREATE INDEX investigation_runs_incident_started_idx
    ON investigation_runs (
        organization_id, project_id, incident_id, started_at DESC, run_id
    );
CREATE INDEX investigation_runs_active_deadline_idx
    ON investigation_runs (organization_id, deadline_at, run_id)
    WHERE status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE');
CREATE INDEX investigation_runs_actor_membership_idx
    ON investigation_runs (organization_id, actor_id, started_at DESC);
CREATE INDEX investigation_run_events_incident_sequence_idx
    ON investigation_run_events (
        organization_id, project_id, incident_id, run_id, sequence_no
    );
CREATE INDEX investigation_run_events_run_identity_idx
    ON investigation_run_events (
        run_id, organization_id, project_id, incident_id, sequence_no
    );
CREATE INDEX investigation_run_events_actor_membership_idx
    ON investigation_run_events (organization_id, actor_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION opsmind_validate_investigation_run_write() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
    ) THEN
        RAISE EXCEPTION 'investigation write requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'CREATED'
           OR NEW.revision IS DISTINCT FROM 0
           OR NEW.event_count IS DISTINCT FROM 1
           OR NEW.rounds IS DISTINCT FROM 0
           OR NEW.tool_calls IS DISTINCT FROM 0
           OR NEW.total_tokens IS DISTINCT FROM 0
           OR NEW.requested_fingerprints_state IS DISTINCT FROM '[]'::jsonb
           OR NEW.evidence_ids_state IS DISTINCT FROM '[]'::jsonb
           OR NEW.pending_intents_state IS DISTINCT FROM '[]'::jsonb
           OR NEW.final_response IS NOT NULL
           OR NEW.terminal_reason IS NOT NULL
           OR NEW.ended_at IS NOT NULL THEN
            RAISE EXCEPTION 'investigation must start at the initial reducer snapshot'
                USING ERRCODE = 'P7001';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.project_id IS DISTINCT FROM OLD.project_id
       OR NEW.incident_id IS DISTINCT FROM OLD.incident_id
       OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
       OR NEW.max_rounds IS DISTINCT FROM OLD.max_rounds
       OR NEW.max_tool_calls IS DISTINCT FROM OLD.max_tool_calls
       OR NEW.max_evidence_items IS DISTINCT FROM OLD.max_evidence_items
       OR NEW.max_tokens IS DISTINCT FROM OLD.max_tokens
       OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at THEN
        RAISE EXCEPTION 'investigation identity, actor, budget, and time bounds are immutable'
            USING ERRCODE = 'P7001';
    END IF;
    IF NEW.revision IS DISTINCT FROM OLD.revision + 1 THEN
        RAISE EXCEPTION 'investigation revision must increase by exactly one'
            USING ERRCODE = 'P7002';
    END IF;
    IF NEW.event_count <= OLD.event_count THEN
        RAISE EXCEPTION 'investigation event count must advance'
            USING ERRCODE = 'P7003';
    END IF;
    IF OLD.status IN ('COMPLETED', 'ABSTAINED', 'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED') THEN
        RAISE EXCEPTION 'terminal investigation snapshots are immutable'
            USING ERRCODE = 'P7001';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_validate_investigation_event_append() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    run_row record;
    prior_sequence bigint;
    expected_sequence bigint;
    details jsonb;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
    ) THEN
        RAISE EXCEPTION 'investigation event append requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.organization_id::text || ':' || NEW.run_id::text,
        0
    ));
    SELECT stored.organization_id, stored.project_id, stored.incident_id,
           stored.actor_id, stored.status, stored.event_count, stored.rounds,
           stored.total_tokens, stored.max_rounds, stored.max_tool_calls,
           stored.max_evidence_items, stored.max_tokens,
           stored.pending_intents_state, stored.evidence_ids_state,
           stored.final_response, stored.terminal_reason,
           stored.started_at, stored.ended_at
      INTO run_row
      FROM public.investigation_runs stored
     WHERE stored.run_id = NEW.run_id
       AND stored.organization_id = NEW.organization_id
     FOR UPDATE;
    IF NOT FOUND
       OR NEW.project_id IS DISTINCT FROM run_row.project_id
       OR NEW.incident_id IS DISTINCT FROM run_row.incident_id
       OR NEW.actor_id IS DISTINCT FROM run_row.actor_id THEN
        RAISE EXCEPTION 'investigation event identities must match the authoritative run'
            USING ERRCODE = 'P7004';
    END IF;

    SELECT max(stored.sequence_no)
      INTO prior_sequence
      FROM public.investigation_run_events stored
     WHERE stored.organization_id = NEW.organization_id
       AND stored.run_id = NEW.run_id;
    expected_sequence := coalesce(prior_sequence, 0) + 1;
    IF NEW.sequence_no IS DISTINCT FROM expected_sequence
       OR NEW.sequence_no > run_row.event_count THEN
        RAISE EXCEPTION 'investigation event sequence must be contiguous and covered by the snapshot'
            USING ERRCODE = 'P7003';
    END IF;
    IF (NEW.sequence_no = 1 AND NEW.event_type <> 'RUN_STARTED')
       OR (NEW.sequence_no > 1 AND NEW.event_type = 'RUN_STARTED') THEN
        RAISE EXCEPTION 'only the first investigation event may start the run'
            USING ERRCODE = 'P7004';
    END IF;

    IF NOT NEW.payload ?& ARRAY[
           'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
           'sequenceNo', 'eventType', 'actorId', 'occurredAt', 'details'
       ]
       OR NEW.payload - ARRAY[
           'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
           'sequenceNo', 'eventType', 'actorId', 'occurredAt', 'details'
       ] <> '{}'::jsonb
       OR jsonb_typeof(NEW.payload -> 'sequenceNo') <> 'number'
       OR jsonb_typeof(NEW.payload -> 'occurredAt') <> 'string'
       OR jsonb_typeof(NEW.payload -> 'details') <> 'object'
       OR jsonb_typeof(NEW.payload -> 'details' -> 'occurredAt') <> 'string'
       OR NEW.payload ->> 'eventId' IS DISTINCT FROM NEW.event_id::text
       OR NEW.payload ->> 'organizationId' IS DISTINCT FROM NEW.organization_id::text
       OR NEW.payload ->> 'projectId' IS DISTINCT FROM NEW.project_id::text
       OR NEW.payload ->> 'incidentId' IS DISTINCT FROM NEW.incident_id::text
       OR NEW.payload ->> 'runId' IS DISTINCT FROM NEW.run_id::text
       OR (NEW.payload ->> 'sequenceNo')::bigint IS DISTINCT FROM NEW.sequence_no
       OR NEW.payload ->> 'eventType' IS DISTINCT FROM NEW.event_type
       OR NEW.payload ->> 'actorId' IS DISTINCT FROM NEW.actor_id::text
       OR (NEW.payload ->> 'occurredAt')::timestamptz IS DISTINCT FROM NEW.occurred_at
       OR NEW.payload -> 'details' ->> 'runId' IS DISTINCT FROM NEW.run_id::text
       OR (NEW.payload -> 'details' ->> 'occurredAt')::timestamptz
            IS DISTINCT FROM NEW.occurred_at THEN
        RAISE EXCEPTION 'investigation event payload does not match its authoritative metadata'
            USING ERRCODE = 'P7005';
    END IF;

    details := NEW.payload -> 'details';
    CASE NEW.event_type
        WHEN 'RUN_STARTED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'incidentId', 'budget', 'occurredAt']
                )
                AND public.opsmind_json_object_has_exact_keys(
                    details -> 'budget',
                    ARRAY['maxRounds', 'maxToolCalls', 'maxEvidenceItems', 'maxTokens']
                )
                AND details ->> 'incidentId' = run_row.incident_id::text
                AND jsonb_typeof(details -> 'budget' -> 'maxRounds') = 'number'
                AND details -> 'budget' ->> 'maxRounds' = run_row.max_rounds::text
                AND jsonb_typeof(details -> 'budget' -> 'maxToolCalls') = 'number'
                AND details -> 'budget' ->> 'maxToolCalls' = run_row.max_tool_calls::text
                AND jsonb_typeof(details -> 'budget' -> 'maxEvidenceItems') = 'number'
                AND details -> 'budget' ->> 'maxEvidenceItems' = run_row.max_evidence_items::text
                AND jsonb_typeof(details -> 'budget' -> 'maxTokens') = 'number'
                AND details -> 'budget' ->> 'maxTokens' = run_row.max_tokens::text
                AND run_row.status = 'CREATED'
                AND run_row.event_count = 1
                AND run_row.started_at = NEW.occurred_at
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'run-started event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'ANALYSIS_ACCEPTED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'status', 'round', 'totalTokens', 'occurredAt']
                )
                AND details ->> 'status' IN (
                    'complete', 'need_more_evidence', 'abstain',
                    'provider_unavailable', 'budget_exceeded'
                )
                AND jsonb_typeof(details -> 'round') = 'number'
                AND details ->> 'round' = run_row.rounds::text
                AND jsonb_typeof(details -> 'totalTokens') = 'number'
                AND details ->> 'totalTokens' = run_row.total_tokens::text
                AND NEW.sequence_no < run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'analysis-accepted event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'TOOL_REQUESTED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'intents', 'occurredAt']
                )
                AND jsonb_typeof(details -> 'intents') = 'array'
                AND jsonb_array_length(details -> 'intents') BETWEEN 1 AND 20
                AND details -> 'intents' = run_row.pending_intents_state
                AND run_row.status = 'WAITING_FOR_EVIDENCE'
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'tool-requested event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'EVIDENCE_APPENDED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details,
                    ARRAY['runId', 'intentId', 'evidenceId', 'digest', 'sourceType', 'occurredAt']
                )
                AND details ->> 'intentId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                AND run_row.evidence_ids_state ? (details ->> 'evidenceId')
                AND details ->> 'digest' ~ '^sha256:[0-9a-f]{64}$'
                AND details ->> 'sourceType' IN (
                    'metric', 'log_summary', 'trace', 'change', 'runbook'
                )
                AND run_row.status IN ('ANALYZING', 'WAITING_FOR_EVIDENCE')
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'evidence-appended event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'COMPLETED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'response', 'occurredAt']
                )
                AND public.opsmind_valid_analysis_response(
                    details -> 'response', NEW.run_id, 'complete'
                )
                AND details -> 'response' = run_row.final_response
                AND run_row.status = 'COMPLETED'
                AND run_row.ended_at = NEW.occurred_at
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'completed event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'ABSTAINED', 'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'reason', 'occurredAt']
                )
                AND jsonb_typeof(details -> 'reason') = 'string'
                AND length(trim(details ->> 'reason')) BETWEEN 1 AND 2000
                AND details ->> 'reason' = run_row.terminal_reason
                AND run_row.status = NEW.event_type
                AND run_row.ended_at = NEW.occurred_at
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'terminal event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        ELSE
            RAISE EXCEPTION 'unsupported investigation event type'
                USING ERRCODE = 'P7005';
    END CASE;
    RETURN NEW;
END
$$;

-- Deferred validation lets one reducer transition update its snapshot before
-- appending one or more emitted events while still requiring commit-time parity.
CREATE OR REPLACE FUNCTION opsmind_validate_investigation_event_count() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    persisted_event_count bigint;
BEGIN
    SELECT coalesce(max(stored.sequence_no), 0)
      INTO persisted_event_count
      FROM public.investigation_run_events stored
     WHERE stored.organization_id = NEW.organization_id
       AND stored.run_id = NEW.run_id;
    IF persisted_event_count IS DISTINCT FROM NEW.event_count THEN
        RAISE EXCEPTION 'investigation snapshot event count must match the event ledger'
            USING ERRCODE = 'P7003';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER investigation_runs_validate_write
    BEFORE INSERT OR UPDATE ON investigation_runs
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_investigation_run_write();
CREATE TRIGGER investigation_run_events_validate_append
    BEFORE INSERT ON investigation_run_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_investigation_event_append();
CREATE CONSTRAINT TRIGGER investigation_runs_validate_event_count
    AFTER INSERT OR UPDATE ON investigation_runs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_investigation_event_count();

CREATE TRIGGER investigation_run_events_no_update
    BEFORE UPDATE OR DELETE ON investigation_run_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_audit_mutation();
CREATE TRIGGER investigation_run_events_no_truncate
    BEFORE TRUNCATE ON investigation_run_events
    FOR EACH STATEMENT EXECUTE FUNCTION opsmind_reject_audit_mutation();

ALTER TABLE audit_events
    DROP CONSTRAINT audit_events_schema_version_known,
    ADD CONSTRAINT audit_events_schema_version_known
        CHECK (schema_version IN (
            'legacy-v1', 'incident-audit-v1', 'investigation-audit-v1'
        )),
    ADD CONSTRAINT audit_events_investigation_contract
        CHECK (
            schema_version <> 'investigation-audit-v1'
            OR (
                actor_id IS NOT NULL
                AND action IN (
                    'RUN_STARTED', 'ANALYSIS_ACCEPTED', 'TOOL_REQUESTED',
                    'EVIDENCE_APPENDED', 'COMPLETED', 'ABSTAINED',
                    'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED'
                )
                AND resource_type = 'investigation_run'
                AND resource_id = correlation_id::text
                AND payload ?& ARRAY[
                    'eventId', 'organizationId', 'projectId', 'incidentId',
                    'runId', 'sequenceNo', 'eventType', 'actorId', 'occurredAt',
                    'details'
                ]
                AND payload - ARRAY[
                    'eventId', 'organizationId', 'projectId', 'incidentId',
                    'runId', 'sequenceNo', 'eventType', 'actorId', 'occurredAt',
                    'details'
                ] = '{}'::jsonb
                AND jsonb_typeof(payload -> 'details') = 'object'
                AND payload ->> 'eventId' = event_id::text
                AND payload ->> 'organizationId' = organization_id::text
                AND payload ->> 'runId' = resource_id
                AND payload ->> 'eventType' = action
                AND payload ->> 'actorId' = actor_id::text
            )
        );

-- Preserve the V003 incident contract and tenant digest computation while
-- adding an equally strict authoritative-event path for investigations.
CREATE OR REPLACE FUNCTION opsmind_assign_audit_chain() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    prior_sequence bigint;
    prior_digest bytea;
    timeline_row record;
    investigation_row record;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' THEN
        IF NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
           OR NEW.actor_id IS DISTINCT FROM actor_id
           OR NEW.schema_version IS NULL
           OR NEW.schema_version NOT IN ('incident-audit-v1', 'investigation-audit-v1') THEN
            RAISE EXCEPTION 'audit append requires the bound tenant, actor, and supported schema'
                USING ERRCODE = '42501';
        END IF;
    END IF;

    IF NEW.schema_version = 'incident-audit-v1' THEN
        SELECT timeline.event_kind, timeline.actor_id, timeline.incident_id,
               timeline.operation_id, timeline.occurred_at, timeline.payload
          INTO timeline_row
          FROM public.incident_timeline_events timeline
         WHERE timeline.event_id = NEW.event_id
           AND timeline.organization_id = NEW.organization_id;
        IF NOT FOUND
           OR NEW.action IS DISTINCT FROM timeline_row.event_kind
           OR NEW.actor_id IS DISTINCT FROM timeline_row.actor_id
           OR NEW.resource_type IS DISTINCT FROM 'incident'
           OR NEW.resource_id IS DISTINCT FROM timeline_row.incident_id::text
           OR NEW.correlation_id IS DISTINCT FROM timeline_row.operation_id
           OR NEW.occurred_at IS DISTINCT FROM timeline_row.occurred_at
           OR NEW.payload IS DISTINCT FROM timeline_row.payload THEN
            RAISE EXCEPTION 'incident audit payload must match its authoritative timeline event'
                USING ERRCODE = 'P4005';
        END IF;
    ELSIF NEW.schema_version = 'investigation-audit-v1' THEN
        SELECT event_row.event_type, event_row.actor_id, event_row.run_id,
               event_row.occurred_at, event_row.payload
          INTO investigation_row
          FROM public.investigation_run_events event_row
         WHERE event_row.event_id = NEW.event_id
           AND event_row.organization_id = NEW.organization_id;
        IF NOT FOUND
           OR NEW.action IS DISTINCT FROM investigation_row.event_type
           OR NEW.actor_id IS DISTINCT FROM investigation_row.actor_id
           OR NEW.resource_type IS DISTINCT FROM 'investigation_run'
           OR NEW.resource_id IS DISTINCT FROM investigation_row.run_id::text
           OR NEW.correlation_id IS DISTINCT FROM investigation_row.run_id
           OR NEW.occurred_at IS DISTINCT FROM investigation_row.occurred_at
           OR NEW.payload IS DISTINCT FROM investigation_row.payload THEN
            RAISE EXCEPTION 'investigation audit payload must match its authoritative run event'
                USING ERRCODE = 'P7005';
        END IF;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text, 0));
    SELECT event_row.tenant_sequence_no, event_row.event_digest
      INTO prior_sequence, prior_digest
      FROM public.audit_events event_row
     WHERE event_row.organization_id = NEW.organization_id
     ORDER BY event_row.tenant_sequence_no DESC
     LIMIT 1;

    NEW.tenant_sequence_no := coalesce(prior_sequence, 0) + 1;
    NEW.previous_digest := prior_digest;
    NEW.event_digest := public.opsmind_compute_audit_digest(
        NEW.tenant_sequence_no,
        NEW.schema_version,
        NEW.event_id,
        NEW.organization_id,
        NEW.actor_id,
        NEW.action,
        NEW.resource_type,
        NEW.resource_id,
        NEW.correlation_id,
        NEW.occurred_at,
        NEW.payload,
        NEW.previous_digest
    );
    RETURN NEW;
END
$$;

ALTER TABLE investigation_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY investigation_runs_tenant_isolation ON investigation_runs
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

ALTER TABLE investigation_run_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_run_events FORCE ROW LEVEL SECURITY;
CREATE POLICY investigation_run_events_tenant_isolation ON investigation_run_events
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

REVOKE ALL ON investigation_runs, investigation_run_events
    FROM opsmind_app, opsmind_dispatcher, PUBLIC;

GRANT SELECT, INSERT ON investigation_runs TO opsmind_app;
GRANT UPDATE (
    status, rounds, tool_calls, total_tokens, revision, event_count,
    requested_fingerprints_state, evidence_ids_state, pending_intents_state,
    final_response, terminal_reason, ended_at
) ON investigation_runs TO opsmind_app;
GRANT SELECT, INSERT ON investigation_run_events TO opsmind_app;

REVOKE ALL ON FUNCTION public.opsmind_validate_investigation_run_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_investigation_event_append() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_investigation_event_count() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_assign_audit_chain() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_json_object_has_exact_keys(jsonb, text[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_valid_analysis_response(jsonb, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_json_object_has_exact_keys(jsonb, text[]) TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_valid_analysis_response(jsonb, uuid, text) TO opsmind_app;

COMMENT ON TABLE investigation_runs IS
    'Tenant-owned durable snapshot of the pure investigation reducer state.';
COMMENT ON TABLE investigation_run_events IS
    'Immutable, contiguous investigation reducer events; payloads are audit-chain authoritative.';
COMMENT ON COLUMN investigation_runs.revision IS
    'Optimistic snapshot revision; each reducer transition advances exactly once.';
COMMENT ON COLUMN investigation_runs.event_count IS
    'Number of immutable reducer events represented by this snapshot.';
COMMENT ON COLUMN investigation_runs.requested_fingerprints_state IS
    'Bounded JSON array for duplicate read-intent detection; contains no arguments or credentials.';
COMMENT ON COLUMN investigation_runs.evidence_ids_state IS
    'Bounded JSON array of persisted evidence identifiers.';
COMMENT ON COLUMN investigation_runs.pending_intents_state IS
    'Bounded JSON array of validated read-only intent metadata; contains no bearer authority.';
COMMENT ON COLUMN investigation_runs.final_response IS
    'Bounded structured analysis-v1 response; raw provider reasoning is forbidden.';
COMMENT ON COLUMN investigation_run_events.sequence_no IS
    'Contiguous one-based sequence within a run; commit-time maximum equals snapshot event_count.';
COMMENT ON COLUMN investigation_run_events.payload IS
    'Exact canonical event envelope copied unchanged into investigation-audit-v1.';
COMMENT ON FUNCTION opsmind_validate_investigation_event_count() IS
    'Deferred commit-time parity check between a reducer snapshot and its immutable event ledger.';
