package ai.opsmind.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class MigrationContractTest {

    private static final String V001_SHA256 =
        "7fce0dc7639490c6a888d949d8857c28f8fb94fc8d4fafbfc7246465115e39f0";
    private static final String V002_SHA256 =
        "809536725bbf37623802531bf0574323c4e3e86513664a8d921c68516c874faf";

    @Test
    void migrationContainsForcedRlsAndTransactionalMessagingTables() throws IOException {
        String migration = new String(
            MigrationContractTest.class.getResourceAsStream(
                "/db/migration/V001__identity_tenant_foundation.sql"
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertThat(migration)
            .contains("CREATE TABLE organizations")
            .contains("CREATE TABLE outbox_events")
            .contains("CREATE TABLE inbox_events")
            .contains("payload_bytes")
            .contains("CREATE TRIGGER outbox_events_enforce_sequence")
            .contains("ERRCODE = 'P3001'")
            .contains("ALTER TABLE %I FORCE ROW LEVEL SECURITY")
            .contains("'organization_memberships', 'projects'")
            .contains("CREATE TRIGGER audit_events_no_update")
            .contains("CREATE OR REPLACE FUNCTION opsmind_resolve_user")
            .contains("ALTER FUNCTION public.opsmind_set_tenant_context(uuid, uuid) OWNER TO opsmind_context_resolver")
            .contains("current_user = 'opsmind_context_resolver'")
            .contains("REVOKE SELECT ON platform_users FROM opsmind_app")
            .contains("set_config('opsmind.tenant_id',")
            .contains("cannot bypass row security");
    }

    @Test
    void dispatcherMigrationSeparatesAppendFromLeaseAuthority() throws IOException {
        String migration = new String(
            MigrationContractTest.class.getResourceAsStream(
                "/db/migration/V002__outbox_dispatcher_workload.sql"
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertThat(migration)
            .contains("required outbox role opsmind_dispatcher is missing")
            .contains("required opsmind_dispatch_resolver role is missing")
            .contains("CREATE POLICY outbox_events_dispatch_resolution")
            .contains("CREATE OR REPLACE FUNCTION opsmind_list_dispatch_tenants")
            .contains("CREATE OR REPLACE FUNCTION opsmind_set_dispatcher_tenant_context")
            .contains("session_user <> 'opsmind_dispatcher'")
            .contains("poisoned_at) ON outbox_events FROM opsmind_app")
            .contains("TO opsmind_dispatcher")
            .contains("dispatcher transaction is already bound to another tenant");
    }

    @Test
    void incidentMigrationEnforcesTenantStateTimelineAndAuditIntegrity() throws IOException {
        String migration = readMigration("V003__incident_control_plane.sql");

        assertThat(migration)
            .contains("CREATE TABLE incidents")
            .contains("CREATE TABLE incident_timeline_events")
            .contains("UNIQUE (organization_id, incident_id, incident_version)")
            .contains("ALTER TABLE incidents FORCE ROW LEVEL SECURITY")
            .contains("ALTER TABLE incident_timeline_events FORCE ROW LEVEL SECURITY")
            .contains("incident version must increase by exactly one")
            .contains("illegal incident status transition from % to %")
            .contains("CREATE TRIGGER incident_timeline_no_truncate")
            .contains("CREATE TRIGGER audit_events_no_truncate")
            .contains("ADD COLUMN tenant_sequence_no bigint")
            .contains("ALTER TABLE audit_events NO FORCE ROW LEVEL SECURITY")
            .contains("audit backfill did not produce a linear recomputable chain")
            .contains("audit_events FORCE RLS was not restored")
            .contains("pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text, 0))")
            .contains("CREATE TRIGGER audit_events_assign_chain")
            .contains("NEW.previous_digest := prior_digest")
            .contains("NEW.event_digest := public.opsmind_compute_audit_digest")
            .contains("REVOKE ALL ON incidents, incident_timeline_events FROM opsmind_dispatcher");
    }

    @Test
    void capabilityProbeAuditIsAppendOnlyAndSecretFree() throws IOException {
        String migration = readMigration("V005__ai_runtime_capability_probe_audit.sql");

        assertThat(migration)
            .contains("CREATE TABLE ai_runtime.provider_capability_probe_events")
            .contains("CREATE UNIQUE INDEX provider_capability_probe_events_type_idx")
            .contains("CREATE INDEX provider_capability_probe_events_started_quota_idx")
            .contains("GRANT INSERT ON ai_runtime.provider_capability_probe_events")
            .contains("GRANT SELECT (")
            .contains("REVOKE UPDATE, DELETE, TRUNCATE")
            .contains("provider_capability_probe_failed")
            .contains("provider_capability_probe_cancelled")
            .doesNotContain("probe_window")
            .doesNotContain("raw_prompt", "api_key", "response_payload");
    }

    @Test
    void investigationMigrationBindsSnapshotEventsRlsAndAuditAtomically() throws IOException {
        String migration = readMigration("V006__investigation_run_persistence.sql");

        assertThat(migration)
            .contains("CREATE TABLE investigation_runs")
            .contains("PRIMARY KEY (organization_id, run_id)")
            .contains("CREATE TABLE investigation_run_events")
            .contains("investigation revision must increase by exactly one")
            .contains("investigation event sequence must be contiguous")
            .contains("DEFERRABLE INITIALLY DEFERRED")
            .contains("snapshot event count must match the event ledger")
            .contains("ALTER TABLE investigation_runs FORCE ROW LEVEL SECURITY")
            .contains("ALTER TABLE investigation_run_events FORCE ROW LEVEL SECURITY")
            .contains("investigation-audit-v1")
            .contains("investigation audit payload must match its authoritative run event")
            .contains("REVOKE ALL ON investigation_runs, investigation_run_events")
            .contains("FROM opsmind_app, opsmind_dispatcher, PUBLIC")
            .doesNotContain("raw_prompt", "chain_of_thought", "api_key", "credential_ref");
    }

    @Test
    void evidenceMigrationBindsCanonicalContentToRunEventAndTenant() throws IOException {
        String migration = readMigration("V007__bounded_evidence_records.sql");

        assertThat(migration)
            .contains("CREATE TABLE evidence_records")
            .contains("CHECK (content_digest = public.digest(convert_to(canonical_content, 'UTF8'), 'sha256'))")
            .contains("UNIQUE (organization_id, run_id, intent_id)")
            .contains("gateway_duplicate           boolean NOT NULL")
            .contains("REFERENCES investigation_run_events(event_id)")
            .contains("evidence record does not match its investigation event")
            .contains("DEFERRABLE INITIALLY DEFERRED")
            .contains("evidence investigation event requires exactly one evidence record")
            .contains("ALTER TABLE evidence_records FORCE ROW LEVEL SECURITY")
            .contains("REVOKE UPDATE, DELETE, TRUNCATE ON evidence_records FROM opsmind_app")
            .contains("REVOKE ALL ON evidence_records FROM opsmind_dispatcher")
            .doesNotContain("raw_prompt", "chain_of_thought", "api_key", "credential_ref");
    }

    @Test
    void acceptedAnalysisMigrationExpandsForRollingWritersWithoutRewritingHistory()
        throws IOException {
        String migration = readMigration("V008__accepted_analysis_event_binding.sql");

        assertThat(migration)
            .contains("CREATE OR REPLACE FUNCTION opsmind_valid_accepted_analysis_response")
            .contains("'status', 'run_id', 'model_id', 'prompt_version', 'schema_version'")
            .contains("'hypotheses', 'counter_evidence', 'missing_evidence', 'citations'")
            .contains("'confidence', 'usage', 'cost_estimate', 'requested_tool_calls'")
            .contains("ARRAY['runId', 'status', 'round', 'totalTokens', 'occurredAt']")
            .contains("'response', 'occurredAt'")
            .contains("details -> 'response', NEW.run_id, details ->> 'status'")
            .contains("run_row.status <> 'COMPLETED'")
            .contains("OR details -> 'response' = run_row.final_response")
            .contains("legacy V007 shape remains writable during rolling deploy")
            .doesNotContain(
                "UPDATE investigation_run_events",
                "DELETE FROM investigation_run_events",
                "raw_prompt",
                "chain_of_thought",
                "api_key",
                "credential_ref"
            );
    }

    @Test
    void predecessorMigrationsRemainByteForByteStable()
        throws IOException, NoSuchAlgorithmException {
        assertThat(sha256("V001__identity_tenant_foundation.sql")).isEqualTo(V001_SHA256);
        assertThat(sha256("V002__outbox_dispatcher_workload.sql")).isEqualTo(V002_SHA256);
    }

    private static String readMigration(String fileName) throws IOException {
        return new String(
            MigrationContractTest.class.getResourceAsStream("/db/migration/" + fileName)
                .readAllBytes(),
            StandardCharsets.UTF_8
        );
    }

    private static String sha256(String fileName)
        throws IOException, NoSuchAlgorithmException {
        byte[] migration = MigrationContractTest.class
            .getResourceAsStream("/db/migration/" + fileName)
            .readAllBytes();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration));
    }

    @Test
    void activityTimelineMigrationBuildsOnlyConcurrentOrderingIndexes() throws IOException {
        String migration = readMigration("V009__incident_activity_timeline_indexes.sql");
        String config = new String(
            MigrationContractTest.class.getResourceAsStream(
                "/db/migration/V009__incident_activity_timeline_indexes.sql.conf"
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertThat(migration)
            .contains("CREATE INDEX CONCURRENTLY incident_timeline_activity_order_idx")
            .contains("ON incident_timeline_events")
            .contains("(organization_id, project_id, incident_id, occurred_at, event_id)")
            .contains("CREATE INDEX CONCURRENTLY investigation_run_events_activity_order_idx")
            .contains("ON investigation_run_events")
            .doesNotContain("CREATE TABLE", "CREATE VIEW", "IF NOT EXISTS", "DROP ");
        assertThat(config.trim()).isEqualTo("executeInTransaction=false");
    }
}
