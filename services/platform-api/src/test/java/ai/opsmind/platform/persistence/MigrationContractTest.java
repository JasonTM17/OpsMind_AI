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
    private static final String V007_SHA256 =
        "c575328c022433f91610c7631028b8842a543f2fde65b84b07deebba4c59c316";

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
    void artifactMetadataMigrationIsAdditiveTenantBoundAndAuditLinked() throws IOException {
        String migration = readMigration("V014__evidence_artifact_metadata.sql");

        assertThat(migration)
            .contains("CREATE TABLE evidence_artifacts")
            .contains("CREATE TABLE evidence_artifact_events")
            .contains("PRIMARY KEY (organization_id, artifact_id)")
            .contains("UNIQUE (organization_id, run_id, idempotency_key)")
            .contains("REFERENCES investigation_runs(run_id, organization_id, project_id, incident_id)")
            .contains("opsmind_evidence_artifact_id")
            .contains("opsmind_evidence_artifact_initial_event_id")
            .contains("evidence-artifact-audit-v1")
            .contains("ARTIFACT_PENDING_UPLOAD")
            .contains("DEFERRABLE INITIALLY DEFERRED")
            .contains("evidence_artifacts_phase_1_pending_only")
            .contains("ALTER TABLE evidence_artifacts FORCE ROW LEVEL SECURITY")
            .contains("ALTER TABLE evidence_artifact_events FORCE ROW LEVEL SECURITY")
            .contains("REVOKE UPDATE, DELETE, TRUNCATE ON evidence_artifacts, evidence_artifact_events")
            .contains("storage_key IS DISTINCT FROM 'artifacts/v1/'")
            .contains("NEW.actor_id IS DISTINCT FROM run_row.actor_id")
            .doesNotContain("raw_prompt", "chain_of_thought", "api_key", "credential_ref", "signed_url");
    }

    @Test
    void artifactObjectMigrationFencesClaimsAndRequiresStoredAudit() throws IOException {
        String migration = readMigration("V015__evidence_artifact_upload_fencing.sql");

        assertThat(migration)
            .contains("CREATE TABLE evidence_artifact_upload_attempts")
            .contains("evidence_artifacts_phase_2_lifecycle_fence")
            .contains("evidence_artifacts_current_upload_attempt_fk")
            .contains("evidence_artifact_events_phase_2_transition_fence")
            .contains("storage_version_reference varchar(1024)")
            .contains("octet_length(storage_version_reference) <= 1024")
            .contains("opsmind_claim_evidence_artifact_upload")
            .contains("opsmind_settle_evidence_artifact_upload")
            .contains("opsmind_evidence_artifact_lifecycle_event_id")
            .contains("p_lease_duration_ms NOT BETWEEN 5000 AND 300000")
            .contains("upload_attempt_count = 0")
            .contains("upload_attempt_id IS NULL")
            .contains("active_attempt.status = 'ORPHANED'")
            .contains("orphaned artifact object requires operator reconciliation")
            .contains("upload_attempt_count BETWEEN 1 AND 8")
            .contains("upload_attempt_id IS NOT NULL")
            .contains("artifact upload already has an active lease")
            .contains("artifact.lease-expired")
            .contains("probe_required boolean")
            .contains("reconciliation_required boolean")
            .contains("artifact.lease-expired-unsettled")
            .contains("transition_at := GREATEST(db_now, artifact_row.lifecycle_updated_at)")
            .contains("settled_at = transition_at")
            .contains("lifecycle_updated_at = transition_at")
            .contains("CREATE CONSTRAINT TRIGGER evidence_artifacts_require_stored_event")
            .contains("ARTIFACT_STORED")
            .contains("storageGeneration")
            .contains("ALTER TABLE evidence_artifact_upload_attempts FORCE ROW LEVEL SECURITY")
            .contains("REVOKE ALL ON evidence_artifact_upload_attempts")
            .contains("REVOKE UPDATE, DELETE, TRUNCATE ON evidence_artifacts")
            .contains("session_user <> 'opsmind_app'")
            .contains("artifact.actor_id = bound_actor_id")
            .doesNotContain(
                "artifact.actor_id = actor_id",
                "run_actor_id IS DISTINCT FROM actor_id"
            )
            .doesNotContain(
                "raw_prompt",
                "chain_of_thought",
                "api_key",
                "credential_ref",
                "signed_url",
                "presigned_url"
            );
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
        assertThat(sha256("V007__bounded_evidence_records.sql")).isEqualTo(V007_SHA256);
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

    @Test
    void incidentListMigrationBuildsOnlyConcurrentDescendingTupleIndexes() throws IOException {
        String migration = readMigration("V016__incident_list_pagination_indexes.sql");
        String config = new String(
            MigrationContractTest.class.getResourceAsStream(
                "/db/migration/V016__incident_list_pagination_indexes.sql.conf"
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertThat(migration)
            .contains("CREATE INDEX CONCURRENTLY incident_list_order_idx")
            .contains("ON incidents (organization_id, project_id, updated_at DESC, id DESC)")
            .contains("CREATE INDEX CONCURRENTLY incident_list_status_order_idx")
            .contains("ON incidents (organization_id, project_id, status, updated_at DESC, id DESC)")
            .doesNotContain("CREATE TABLE", "CREATE VIEW", "IF NOT EXISTS", "DROP ");
        assertThat(config.trim()).isEqualTo("executeInTransaction=false");
    }

    @Test
    void incidentPatchMigrationLocksOwnerAuthorityAndExtendsAppendContracts()
        throws IOException {
        String migration = readMigration("V017__incident_metadata_patch_event.sql");

        assertThat(migration)
            .contains("opsmind_lock_eligible_incident_owner")
            .contains("session_user <> 'opsmind_app'")
            .contains("FOR SHARE OF member, membership")
            .contains("OWNER TO opsmind_context_resolver")
            .contains("CREATE OR REPLACE FUNCTION opsmind_validate_incident_write()")
            .contains("metadata patch cannot change incident resolution fields")
            .contains("incident owner must be an active organization member")
            .contains("status transition cannot change incident metadata")
            .contains("INCIDENT_METADATA_PATCHED")
            .contains("CREATE OR REPLACE FUNCTION opsmind_validate_timeline_append()")
            .contains("DROP CONSTRAINT audit_events_incident_contract")
            .contains("GRANT UPDATE (title, description, severity, owner_id) ON incidents TO opsmind_app")
            .doesNotContain("CREATE TABLE", "TRUNCATE");
    }

    @Test
    void workflowStartMigrationDefinesTheInitialTenantBoundHandoffContract()
        throws IOException {
        String migrationName = "V010__investigation_workflow_start_handoff.sql";
        assertThat(MigrationContractTest.class.getResource(
            "/db/migration/V009__incident_activity_timeline_indexes.sql"
        )).as("V009 predecessor migration must remain packaged").isNotNull();
        assertThat(MigrationContractTest.class.getResource("/db/migration/" + migrationName))
            .as("initial workflow handoff migration must be packaged")
            .isNotNull();
        String migration = readMigration(migrationName);

        assertThat(migration)
            .contains("CREATE TABLE investigation_workflow_bindings")
            .contains("PRIMARY KEY (organization_id, run_id)")
            .contains("FOREIGN KEY (organization_id, run_id)")
            .contains("client_request_digest bytea NOT NULL")
            .contains("CHECK (octet_length(client_request_digest) = 32)")
            .contains("start_payload_digest  bytea NOT NULL")
            .contains("CHECK (octet_length(start_payload_digest) = 32)")
            .contains("start_event_id        uuid NOT NULL UNIQUE")
            .contains("set_byte(bytes, 6, (get_byte(bytes, 6) & 15) | 48)")
            .contains("8, (get_byte(bytes, 8) & 63) | 128")
            .contains("CHECK (workflow_id =")
            .contains("'opsmind-investigation/' || organization_id::text || '/' || run_id::text")
            .contains("CHECK (status IN ('PENDING', 'STARTED', 'REJECTED'))")
            .contains("temporal_run_id")
            .contains("rejection_code")
            .contains("temporal_started_at")
            .contains("rejected_at")
            .contains("CREATE UNIQUE INDEX investigation_workflow_bindings_temporal_target_idx")
            .contains("CREATE OR REPLACE FUNCTION opsmind_validate_investigation_workflow_binding")
            .contains("workflow binding insert requires the bound tenant and actor")
            .contains("workflow binding requires the initial CREATED reducer state")
            .contains("session_user <> 'opsmind_dispatcher'")
            .contains("workflow binding has no legal reconciliation transition")
            .contains("CREATE TRIGGER investigation_workflow_bindings_validate_write")
            .contains("CREATE OR REPLACE FUNCTION opsmind_validate_investigation_workflow_start_outbox")
            .contains("'investigation.workflow-start.requested'")
            .contains("NEW.schema_version IS DISTINCT FROM '1'")
            .contains("NEW.aggregate_type IS DISTINCT FROM 'investigation-workflow'")
            .contains("NEW.aggregate_sequence IS DISTINCT FROM 1")
            .contains("'organization_id', 'project_id', 'incident_id', 'run_id', 'actor_id'")
            .contains("'authorization_revision',")
            .contains("'request_digest'")
            .contains("NEW.payload_digest IS DISTINCT FROM binding_row.start_payload_digest")
            .contains("opsmind_json_object_has_exact_keys(NEW.payload, expected_keys)")
            .contains("CREATE TRIGGER outbox_events_validate_investigation_workflow_start")
            .contains("CREATE OR REPLACE FUNCTION opsmind_list_investigation_workflow_start_tenants")
            .contains("p_limit IS NULL OR p_limit < 1 OR p_limit > 100")
            .contains("event_row.published_at IS NULL")
            .contains("event_row.poisoned_at IS NULL")
            .contains("event_row.lease_expires_at <= statement_timestamp()")
            .contains("FROM public.outbox_events predecessor")
            .contains("CREATE INDEX outbox_investigation_workflow_start_ready_idx")
            .contains("REVOKE ALL ON investigation_workflow_bindings FROM opsmind_app, opsmind_dispatcher, PUBLIC;")
            .contains("GRANT SELECT, INSERT ON investigation_workflow_bindings TO opsmind_app;")
            .contains("GRANT SELECT ON investigation_workflow_bindings TO opsmind_dispatcher;")
            .contains("GRANT UPDATE (")
            .contains("TO opsmind_dispatcher;")
            .contains("GRANT SELECT, INSERT ON inbox_events TO opsmind_dispatcher;")
            .contains("GRANT UPDATE (status, processed_at, attempts, last_error)")
            .contains("ON inbox_events TO opsmind_dispatcher;")
            .contains("GRANT SELECT (event_id, event_type, schema_version)")
            .contains("ON outbox_events TO opsmind_dispatch_resolver;")
            .contains("ALTER TABLE investigation_workflow_bindings FORCE ROW LEVEL SECURITY")
            .contains("CREATE POLICY investigation_workflow_bindings_tenant_isolation")
            .contains("REVOKE ALL ON FUNCTION public.opsmind_list_investigation_workflow_start_tenants(integer)")
            .contains("GRANT EXECUTE ON FUNCTION public.opsmind_investigation_workflow_start_event_id(uuid, uuid)")
            .contains("TO opsmind_app;")
            .contains("GRANT EXECUTE ON FUNCTION public.opsmind_list_investigation_workflow_start_tenants(integer)")
            .contains("TO opsmind_dispatcher;")
            .doesNotContain("raw_prompt", "chain_of_thought", "api_key", "credential_ref");
    }

    @Test
    void workflowDispatchExclusivityMigrationGuardsBothMembershipDirections()
        throws IOException {
        String migration = readMigration(
            "V012__investigation_workflow_dispatch_exclusivity.sql"
        );

        assertThat(migration)
            .contains("membership.member = role_row.oid")
            .contains("membership.roleid = role_row.oid")
            .contains("member_role.rolname <> session_user")
            .contains("membership.admin_option")
            .contains("membership.inherit_option")
            .contains("membership.set_option")
            .contains("CREATE OR REPLACE FUNCTION opsmind_has_unpublished_outbox_predecessor")
            .contains("outbox predecessor lookup requires its bound tenant")
            .contains("AS RESTRICTIVE")
            .contains("CREATE OR REPLACE FUNCTION opsmind_claim_investigation_workflow_start")
            .contains("SET last_error = 'workflow.temporal-outcome-ambiguous'")
            .contains("event_row.attempts > 0")
            .contains("event_row.last_error = 'workflow.temporal-unavailable'")
            .contains(
                "event_row.last_error IN (\n"
                    + "        'workflow.temporal-outcome-ambiguous',\n"
                    + "        'workflow.temporal-unavailable'\n"
                    + "    )"
            )
            .contains("workflow.ambiguous-retry-allowed")
            .contains("workflow.reconciliation-required");
    }

    @Test
    void workflowReconciliationMigrationExposesOnlyExactCapabilities()
        throws IOException {
        String migration = readMigration(
            "V013__investigation_workflow_exact_reconciliation.sql"
        );

        assertThat(migration)
            .contains("opsmind_workflow_reconciler has unsafe attributes or role memberships")
            .contains(
                "opsmind_workflow_reconciliation_resolver has unsafe attributes "
                    + "or role memberships"
            )
            .contains("membership.member = role_row.oid")
            .contains("membership.roleid = role_row.oid")
            .contains(
                "CREATE OR REPLACE FUNCTION "
                    + "opsmind_workflow_reconciliation_identity_is_safe"
            )
            .contains("safe dedicated workflow reconciler identity is required")
            .contains(
                "CREATE OR REPLACE FUNCTION "
                    + "opsmind_claim_investigation_workflow_reconciliation"
            )
            .contains("FOR UPDATE OF event_row, binding_row SKIP LOCKED")
            .contains("event_row.attempts > 0")
            .contains("p_maximum_attempts > 8")
            .contains("ON CONFLICT ON CONSTRAINT inbox_events_pkey DO UPDATE")
            .contains("ELSE public.inbox_events.processed_at")
            .contains("SET lease_token = p_lease_token")
            .doesNotContain("SET attempts = event_row.attempts + 1")
            .contains(
                "CREATE OR REPLACE FUNCTION "
                    + "opsmind_settle_investigation_workflow_reconciliation"
            )
            .contains(
                "p_outcome NOT IN ('MATCH', 'ABSENT', 'MISMATCH', 'RETRY', 'BLOCKED')"
            )
            .contains("workflow.reconciliation-started")
            .contains("workflow.reconciliation-absence-candidate")
            .contains("workflow.reconciliation-released-to-starter")
            .contains("workflow.reconciliation-verified-absence")
            .contains("workflow.reconciliation-contract-mismatch")
            .contains("workflow.reconciliation-retry-scheduled")
            .contains("workflow.reconciliation-blocked")
            .contains("workflow.reconciliation-lease-lost")
            .contains("workflow.reconciliation-retention-unverifiable")
            .contains("workflow.reconciliation-handoff-age-exceeded")
            .contains(
                "CREATE OR REPLACE FUNCTION "
                    + "opsmind_get_investigation_workflow_reconciliation_status"
            )
            .contains("claim_ready_count bigint")
            .contains("reconciliation_status IN ('received', 'processed')")
            .contains("retention_ineligible_count bigint")
            .contains("oldest_pending_age_seconds bigint")
            .contains(
                "REVOKE ALL ON ALL TABLES IN SCHEMA public "
                    + "FROM opsmind_workflow_reconciler"
            )
            .contains(
                "public.opsmind_validate_investigation_workflow_binding_update()\n"
                    + "    FROM PUBLIC"
            )
            .contains(
                "REVOKE ALL ON FUNCTION public.opsmind_enforce_outbox_sequence() "
                    + "FROM PUBLIC"
            )
            .contains(
                "REVOKE ALL ON FUNCTION public.opsmind_reject_audit_mutation() "
                    + "FROM PUBLIC"
            )
            .contains(
                "REVOKE ALL ON FUNCTION public.opsmind_validate_incident_write() "
                    + "FROM PUBLIC"
            )
            .contains(
                "REVOKE ALL ON FUNCTION public.opsmind_validate_timeline_append() "
                    + "FROM PUBLIC"
            )
            .contains(
                "TO opsmind_workflow_reconciliation_resolver"
            )
            .contains("TO opsmind_workflow_reconciler")
            .doesNotContain(
                "opsmind_set_tenant_context",
                "opsmind_set_dispatcher_tenant_context",
                "StartWorkflowExecution",
                "raw_prompt",
                "chain_of_thought",
                "api_key",
                "credential_ref"
            );
    }

    @Test
    void workflowReconcilerBootstrapAndComposeUseTheFixedRole()
        throws IOException {
        String bootstrap = new String(
            MigrationContractTest.class.getResourceAsStream(
                "/db/bootstrap/001-create-runtime-role.sh"
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );
        String compose = java.nio.file.Files.readString(
            java.nio.file.Path.of("../../compose.yaml"),
            StandardCharsets.UTF_8
        );

        assertThat(bootstrap)
            .contains("POSTGRES_WORKFLOW_RECONCILER_PASSWORD")
            .contains("CREATE ROLE opsmind_workflow_reconciler LOGIN NOSUPERUSER")
            .contains(
                "CREATE ROLE opsmind_workflow_reconciliation_resolver "
                    + "NOLOGIN NOSUPERUSER"
            )
            .contains("\\password opsmind_workflow_reconciler");
        assertThat(compose)
            .contains(
                "POSTGRES_WORKFLOW_RECONCILER_USER: "
                    + "${POSTGRES_WORKFLOW_RECONCILER_USER:-opsmind_workflow_reconciler}"
            )
            .contains(
                "source: ./deploy/prometheus/opsmind-reconciliation-alerts.yml"
            )
            .contains(
                "target: /etc/prometheus/opsmind-reconciliation-alerts.yml"
            )
            .contains("PLATFORM_MANAGEMENT_PORT: 8082")
            .contains("OPSMIND_MANAGEMENT_EXPOSED_ENDPOINTS: health,prometheus")
            .contains("http://127.0.0.1:8082/actuator/health")
            .contains(
                "OPSMIND_WORKFLOW_RECONCILER_DB_USERNAME: "
                    + "${POSTGRES_WORKFLOW_RECONCILER_USER:-opsmind_workflow_reconciler}"
            )
            .contains(
                "OPSMIND_WORKFLOW_RECONCILER_DB_QUERY_TIMEOUT_SECONDS: "
                    + "${OPSMIND_WORKFLOW_RECONCILER_DB_QUERY_TIMEOUT_SECONDS:-1}"
            );
    }
}
