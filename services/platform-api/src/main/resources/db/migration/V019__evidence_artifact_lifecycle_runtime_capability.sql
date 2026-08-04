-- Phase 4C: narrow runtime capability for atomic artifact lifecycle transitions.
-- The application role remains unable to update evidence_artifacts directly.

CREATE OR REPLACE FUNCTION opsmind_transition_evidence_artifact(
    p_organization_id uuid,
    p_project_id uuid,
    p_incident_id uuid,
    p_artifact_id uuid,
    p_actor_id uuid,
    p_authorization_epoch bigint,
    p_expected_content_digest bytea,
    p_expected_from_state character varying,
    p_expected_from_version bigint,
    p_target_state character varying,
    p_occurred_at timestamptz
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    updated_rows integer;
BEGIN
    IF session_user <> 'opsmind_app'
       OR p_organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
       OR p_actor_id IS DISTINCT FROM public.opsmind_current_actor_id() THEN
        RAISE EXCEPTION 'artifact lifecycle transition requires the bound application tenant and actor'
            USING ERRCODE = '42501';
    END IF;
    IF p_occurred_at < clock_timestamp() - interval '5 seconds'
       OR p_occurred_at > clock_timestamp() + interval '1 second' THEN
        RAISE EXCEPTION 'artifact lifecycle transition time is outside the admitted database window'
            USING ERRCODE = 'P7106';
    END IF;

    UPDATE public.evidence_artifacts
       SET lifecycle_state = p_target_state,
           lifecycle_version = p_expected_from_version + 1,
           lifecycle_updated_at = p_occurred_at
     WHERE organization_id = p_organization_id
       AND project_id = p_project_id
       AND incident_id = p_incident_id
       AND artifact_id = p_artifact_id
       AND actor_id = p_actor_id
       AND authorization_epoch = p_authorization_epoch
       AND expected_content_digest = p_expected_content_digest
       AND lifecycle_state = p_expected_from_state
       AND lifecycle_version = p_expected_from_version;

    GET DIAGNOSTICS updated_rows = ROW_COUNT;
    RETURN updated_rows = 1;
END
$$;

REVOKE ALL ON FUNCTION public.opsmind_transition_evidence_artifact(
    uuid, uuid, uuid, uuid, uuid, bigint, bytea, character varying, bigint,
    character varying, timestamptz
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_transition_evidence_artifact(
    uuid, uuid, uuid, uuid, uuid, bigint, bytea, character varying, bigint,
    character varying, timestamptz
) TO opsmind_app;
