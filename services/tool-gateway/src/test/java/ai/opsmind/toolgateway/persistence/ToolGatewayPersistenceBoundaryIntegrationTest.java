package ai.opsmind.toolgateway.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
            "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
                + "WHERE nspname = 'tool_gateway'",
            String.class
        )).isEqualTo("opsmind_tool_gateway_migrator");
        assertThat(database.nonceStore().available()).isTrue();
        assertThat(database.receiptStore(Duration.ofSeconds(5)).available()).isTrue();
        assertThat(database.auditWriter().available()).isTrue();

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
                + "'tool_gateway.tool_audit_events', 'SELECT,UPDATE,DELETE,TRUNCATE')",
            Boolean.class
        )).isFalse();
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
    void auditRejectsOwnerMutationAndTruncation() {
        String digest = ToolGatewayPostgresTestContext.digest(UUID.randomUUID().toString());
        UUID auditId = database.auditWriter().record(
            UUID.randomUUID(), ToolOutcome.SUCCEEDED, digest,
            "capability-test", "manifest-v1", digest, "policy-v1", null
        );

        assertThatThrownBy(() -> database.migratorJdbc().update(
            "UPDATE tool_gateway.tool_audit_events SET outcome = 'FAILED' "
                + "WHERE audit_event_id = ?",
            auditId
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> database.migratorJdbc().execute(
            "TRUNCATE tool_gateway.tool_audit_events"
        )).isInstanceOf(RuntimeException.class);
    }
}
