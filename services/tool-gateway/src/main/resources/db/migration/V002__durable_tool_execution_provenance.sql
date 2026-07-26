-- Expand phase: new writers persist a complete observed-provenance tuple.
-- V001 writers may keep all six columns null during rolling deployment.

DO $$
BEGIN
    IF current_user IS DISTINCT FROM 'opsmind_tool_gateway_migrator'
       OR pg_get_userbyid(
           (SELECT nspowner FROM pg_namespace WHERE nspname = 'tool_gateway')
       ) IS DISTINCT FROM current_user THEN
        RAISE EXCEPTION 'Tool Gateway migration must run as the dedicated schema owner';
    END IF;
END;
$$;

ALTER TABLE tool_gateway.tool_audit_events
    ADD COLUMN tool text,
    ADD COLUMN action text,
    ADD COLUMN risk_class text,
    ADD COLUMN connector_id text,
    ADD COLUMN connector_profile text,
    ADD COLUMN connector_manifest_byte_digest text,
    ADD CONSTRAINT tool_audit_provenance_all_or_none CHECK (
        (
            num_nonnulls(
                tool, action, risk_class, connector_id, connector_profile,
                connector_manifest_byte_digest
            ) = 0
        )
        OR
        (
            num_nonnulls(
                tool, action, risk_class, connector_id, connector_profile,
                connector_manifest_byte_digest
            ) = 6
            AND
            length(tool) BETWEEN 1 AND 128
            AND length(action) BETWEEN 1 AND 128
            AND length(risk_class) BETWEEN 1 AND 128
            AND length(connector_id) BETWEEN 1 AND 128
            AND length(connector_profile) BETWEEN 1 AND 128
            AND tool ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
            AND action ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
            AND risk_class ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
            AND connector_id ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
            AND connector_profile ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
            AND connector_manifest_byte_digest ~ '^sha256:[0-9a-f]{64}$'
        )
    );

COMMENT ON COLUMN tool_gateway.tool_audit_events.connector_manifest_byte_digest IS
    'Raw SHA-256 digest of the manifest bytes selected by the executing runtime.';
