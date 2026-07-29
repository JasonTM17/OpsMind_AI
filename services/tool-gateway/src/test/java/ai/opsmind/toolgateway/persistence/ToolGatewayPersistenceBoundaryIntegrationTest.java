package ai.opsmind.toolgateway.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.audit.ToolExecutionProvenance;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "OPSMIND_TOOL_GATEWAY_DB_INTEGRATION",
    matches = "true"
)
class ToolGatewayPersistenceBoundaryIntegrationTest {

    private final ToolGatewayPostgresTestContext database =
        new ToolGatewayPostgresTestContext();

    @BeforeEach
    void cleanMutableState() {
        database.cleanMutableState();
    }

    @Test
    void migrationAndRoleBoundariesAreEnforced() {
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.flyway_schema_history "
                + "WHERE script = 'V001__durable_tool_gateway_state.sql' AND success",
            Integer.class
        )).isEqualTo(1);
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.flyway_schema_history "
                + "WHERE script = 'V003__tenant_project_row_security.sql' AND success",
            Integer.class
        )).isEqualTo(1);
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.flyway_schema_history "
                + "WHERE script = 'V002__durable_tool_execution_provenance.sql' AND success",
            Integer.class
        )).isEqualTo(1);
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
                + "WHERE nspname = 'tool_gateway'",
            String.class
        )).isEqualTo("opsmind_tool_gateway_migrator");
        assertThat(database.nonceStore().available()).isTrue();
        assertThat(database.receiptStore(Duration.ofSeconds(5)).available()).isTrue();
        assertThat(database.auditWriter().available()).isTrue();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT relrowsecurity AND relforcerowsecurity "
                + "FROM pg_class table_state "
                + "JOIN pg_namespace schema_state "
                + "ON schema_state.oid = table_state.relnamespace "
                + "WHERE schema_state.nspname = 'tool_gateway' "
                + "AND table_state.relname = 'execution_receipts'",
            Boolean.class
        )).isTrue();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT relrowsecurity AND relforcerowsecurity "
                + "FROM pg_class table_state "
                + "JOIN pg_namespace schema_state "
                + "ON schema_state.oid = table_state.relnamespace "
                + "WHERE schema_state.nspname = 'tool_gateway' "
                + "AND table_state.relname = 'tool_audit_events'",
            Boolean.class
        )).isTrue();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM pg_policies "
                + "WHERE schemaname = 'tool_gateway' "
                + "AND policyname IN ("
                + "'execution_receipts_tenant_project_isolation', "
                + "'tool_audit_events_tenant_project_isolation')",
            Integer.class
        )).isEqualTo(2);

        for (String role : List.of("opsmind_app", "opsmind_ai_runtime")) {
            assertThat(database.migratorJdbc().queryForObject(
                "SELECT has_table_privilege(?, "
                    + "'tool_gateway.execution_receipts', 'SELECT')",
                Boolean.class,
                role
            )).isFalse();
        }
        assertThatThrownBy(() -> database.roleJdbc(
            "POSTGRES_APP_USER", "POSTGRES_APP_PASSWORD"
        ).queryForObject(
            "SELECT count(*) FROM tool_gateway.execution_receipts",
            Integer.class
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> database.roleJdbc(
            "POSTGRES_AI_RUNTIME_USER", "POSTGRES_AI_RUNTIME_PASSWORD"
        ).queryForObject(
            "SELECT count(*) FROM tool_gateway.execution_receipts",
            Integer.class
        )).isInstanceOf(RuntimeException.class);
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.execution_receipts', 'SELECT') "
                + "AND has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.execution_receipts', 'INSERT') "
                + "AND has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.execution_receipts', 'UPDATE') "
                + "AND NOT has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.execution_receipts', 'DELETE,TRUNCATE')",
            Boolean.class
        )).isTrue();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.tool_audit_events', 'SELECT,UPDATE,DELETE,TRUNCATE')",
            Boolean.class
        )).isFalse();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'opsmind_tool_gateway'",
            Boolean.class
        )).isFalse();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT has_function_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.set_tenant_context(uuid,uuid)', 'EXECUTE') "
                + "AND has_function_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.current_tenant_id()', 'EXECUTE') "
                + "AND has_function_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.current_project_id()', 'EXECUTE')",
            Boolean.class
        )).isTrue();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.unverified_tool_audit_events', 'INSERT') "
                + "AND NOT has_table_privilege('opsmind_tool_gateway', "
                + "'tool_gateway.unverified_tool_audit_events', "
                + "'SELECT,UPDATE,DELETE,TRUNCATE')",
            Boolean.class
        )).isTrue();
        assertThatThrownBy(() -> database.runtimeJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.tool_audit_events",
            Integer.class
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void nonceIsHashedAndReplayIsRejected() {
        String nonce = "nonce-" + UUID.randomUUID();
        var store = database.nonceStore();

        assertThat(store.claim(nonce, Instant.now().plusSeconds(30))).isTrue();
        assertThat(store.claim(nonce, Instant.now().plusSeconds(30))).isFalse();
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.capability_nonce_claims "
                + "WHERE nonce_hash = ? AND octet_length(nonce_hash) = 32",
            Integer.class,
            ToolGatewayPostgresTestContext.digestBytes(nonce)
        )).isEqualTo(1);
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'tool_gateway' "
                + "AND table_name = 'capability_nonce_claims' "
                + "AND column_name = 'nonce'",
            Integer.class
        )).isZero();
    }

    @Test
    void readinessRejectsMissingSchemaUsageAndSameNamePolicyDrift() {
        var nonceStore = database.nonceStore();
        var receiptStore = database.receiptStore(Duration.ofSeconds(5));
        var auditWriter = database.auditWriter();

        database.migratorJdbc().execute(
            "REVOKE USAGE ON SCHEMA tool_gateway FROM opsmind_tool_gateway"
        );
        try {
            assertThat(nonceStore.available()).isFalse();
            assertThat(receiptStore.available()).isFalse();
            assertThat(auditWriter.available()).isFalse();
        }
        finally {
            database.migratorJdbc().execute(
                "GRANT USAGE ON SCHEMA tool_gateway TO opsmind_tool_gateway"
            );
        }

        assertThat(nonceStore.available()).isTrue();
        assertThat(receiptStore.available()).isTrue();
        assertThat(auditWriter.available()).isTrue();

        database.migratorJdbc().execute(
            "DROP POLICY execution_receipts_tenant_project_isolation "
                + "ON tool_gateway.execution_receipts"
        );
        try {
            database.migratorJdbc().execute(
                "CREATE POLICY execution_receipts_tenant_project_isolation "
                    + "ON tool_gateway.execution_receipts "
                    + "USING (true) WITH CHECK (true)"
            );
            assertThat(receiptStore.available()).isFalse();
        }
        finally {
            restoreReceiptIsolationPolicy();
        }

        assertThat(receiptStore.available()).isTrue();

        database.migratorJdbc().execute(
            "DROP POLICY tool_audit_events_tenant_project_isolation "
                + "ON tool_gateway.tool_audit_events"
        );
        try {
            database.migratorJdbc().execute(
                "CREATE POLICY tool_audit_events_tenant_project_isolation "
                    + "ON tool_gateway.tool_audit_events "
                    + "USING (true) WITH CHECK (true)"
            );
            assertThat(auditWriter.available()).isFalse();
        }
        finally {
            restoreAuditIsolationPolicy();
        }

        assertThat(auditWriter.available()).isTrue();
    }

    @Test
    void receiptReadinessRejectsDeletePrivilegeDrift() {
        assertReceiptReadinessRejectsPrivilege("DELETE");
    }

    @Test
    void receiptReadinessRejectsTruncatePrivilegeDrift() {
        assertReceiptReadinessRejectsPrivilege("TRUNCATE");
    }

    @Test
    void auditRejectsOwnerMutationAndTruncation() {
        String digest = ToolGatewayPostgresTestContext.digest(UUID.randomUUID().toString());
        TenantProjectScope scope = new TenantProjectScope(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222")
        );
        UUID auditId = database.inScope(
            scope,
            () -> database.auditWriter().recordScoped(
                scope, UUID.randomUUID(), ToolOutcome.SUCCEEDED, digest,
                "capability-test", "manifest-v1",
                new ToolExecutionProvenance(
                    "observability", "metrics.query", "read-only",
                    "prometheus-read-only", "prometheus", "sha256:" + "a".repeat(64)
                ),
                digest, "policy-v1", null
            )
        );
        assertThat(database.adminJdbc().queryForObject(
            "SELECT tenant_id::text || ':' || project_id::text || ':' "
                + "|| connector_id || ':' || connector_profile || ':' || "
                + "connector_manifest_byte_digest "
                + "FROM tool_gateway.tool_audit_events WHERE audit_event_id = ?",
            String.class,
            auditId
        )).isEqualTo(
            scope.tenantId() + ":" + scope.projectId()
                + ":prometheus-read-only:prometheus:sha256:" + "a".repeat(64)
        );

        assertThatThrownBy(() -> database.adminJdbc().update(
            "UPDATE tool_gateway.tool_audit_events SET outcome = 'FAILED' "
                + "WHERE audit_event_id = ?",
            auditId
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> database.adminJdbc().execute(
            "TRUNCATE tool_gateway.tool_audit_events"
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unverifiedAuditHasSeparateTenantFreeAppendOnlyLane() {
        String digest = ToolGatewayPostgresTestContext.digest(UUID.randomUUID().toString());
        UUID auditId = database.auditWriter().recordUnverified(
            UUID.randomUUID(),
            ToolOutcome.DENIED,
            digest,
            DenialCode.CAPABILITY_INVALID
        );

        assertThat(database.adminJdbc().queryForObject(
            "SELECT outcome || ':' || denial_code "
                + "FROM tool_gateway.unverified_tool_audit_events "
                + "WHERE audit_event_id = ?",
            String.class,
            auditId
        )).isEqualTo("DENIED:capability.invalid");
        assertThat(database.adminJdbc().queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'tool_gateway' "
                + "AND table_name = 'unverified_tool_audit_events' "
                + "AND column_name IN ('tenant_id', 'project_id')",
            Integer.class
        )).isZero();
        assertThatThrownBy(() -> database.adminJdbc().update(
            "UPDATE tool_gateway.unverified_tool_audit_events "
                + "SET outcome = 'FAILED' WHERE audit_event_id = ?",
            auditId
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void runtimeCannotAppendLegacyUnscopedVerifiedAuditShape() {
        UUID auditId = UUID.randomUUID();
        assertThatThrownBy(() -> database.runtimeJdbc().update(
            "INSERT INTO tool_gateway.tool_audit_events "
                + "(audit_event_id, execution_id, outcome, request_digest, denial_code) "
                + "VALUES (?, ?, 'DENIED', ?, 'action-disabled')",
            auditId,
            UUID.randomUUID(),
            ToolGatewayPostgresTestContext.digest(UUID.randomUUID().toString())
        )).isInstanceOf(RuntimeException.class);
    }

    private void assertReceiptReadinessRejectsPrivilege(String privilege) {
        var receiptStore = database.receiptStore(Duration.ofSeconds(5));
        database.migratorJdbc().execute(
            "GRANT " + privilege
                + " ON tool_gateway.execution_receipts TO opsmind_tool_gateway"
        );
        try {
            assertThat(receiptStore.available()).isFalse();
        }
        finally {
            database.migratorJdbc().execute(
                "REVOKE " + privilege
                    + " ON tool_gateway.execution_receipts FROM opsmind_tool_gateway"
            );
        }
        assertThat(receiptStore.available()).isTrue();
    }

    private void restoreReceiptIsolationPolicy() {
        database.migratorJdbc().execute(
            "DROP POLICY IF EXISTS execution_receipts_tenant_project_isolation "
                + "ON tool_gateway.execution_receipts"
        );
        database.migratorJdbc().execute(
            "CREATE POLICY execution_receipts_tenant_project_isolation "
                + "ON tool_gateway.execution_receipts "
                + "USING ("
                + "tenant_id = tool_gateway.current_tenant_id() "
                + "AND project_id = tool_gateway.current_project_id()) "
                + "WITH CHECK ("
                + "tenant_id = tool_gateway.current_tenant_id() "
                + "AND project_id = tool_gateway.current_project_id())"
        );
    }

    private void restoreAuditIsolationPolicy() {
        database.migratorJdbc().execute(
            "DROP POLICY IF EXISTS tool_audit_events_tenant_project_isolation "
                + "ON tool_gateway.tool_audit_events"
        );
        database.migratorJdbc().execute(
            "CREATE POLICY tool_audit_events_tenant_project_isolation "
                + "ON tool_gateway.tool_audit_events "
                + "USING ("
                + "tenant_id = tool_gateway.current_tenant_id() "
                + "AND project_id = tool_gateway.current_project_id()) "
                + "WITH CHECK ("
                + "tenant_id = tool_gateway.current_tenant_id() "
                + "AND project_id = tool_gateway.current_project_id())"
        );
    }
}
