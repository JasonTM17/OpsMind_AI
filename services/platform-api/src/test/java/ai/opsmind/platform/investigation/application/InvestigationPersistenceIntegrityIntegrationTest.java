package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE7_DB_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
    "opsmind.investigation.enabled=true",
    "opsmind.investigation.store=postgres"
})
class InvestigationPersistenceIntegrityIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private InvestigationRunStore store;

    @Autowired
    private InvestigationPersistenceJsonCodec jsonCodec;

    @MockitoBean
    private InvestigationAiRuntimeClient aiRuntimeClient;

    @MockitoBean
    private InvestigationToolGatewayClient toolGatewayClient;

    private JdbcTemplate admin;
    private TransactionTemplate adminTransactions;
    private UUID incidentId;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> requiredEnvironment("SPRING_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> requiredEnvironment("POSTGRES_APP_USER"));
        properties.add("spring.datasource.password", () -> requiredEnvironment("POSTGRES_APP_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> "false");
        properties.add("opsmind.persistence.enabled", () -> "true");
    }

    @BeforeEach
    void seedAuthorizedIncident() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        );
        admin = new JdbcTemplate(dataSource);
        adminTransactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        incidentId = UUID.randomUUID();
        admin.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                + "status, created_by, updated_by, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'Payload integrity', 'Reject forged reducer data', "
                + "'SEV2', 'OPEN', ?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A,
            Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void emptyCompletedResponseCannotBecomeADurableSnapshot() {
        UUID runId = createRun();

        assertSqlState(() -> adminTransactions.executeWithoutResult(ignored -> admin.update(
            "UPDATE investigation_runs SET status = 'COMPLETED', revision = 1, event_count = 2, "
                + "final_response = '{}'::jsonb, ended_at = ? "
                + "WHERE organization_id = ? AND run_id = ?",
            Timestamp.from(NOW.plusSeconds(1)), TENANT_A, runId
        )), "23514");

        assertThat(store.require(TENANT_A, USER_A, runId).status())
            .isEqualTo(InvestigationStateMachine.Status.CREATED);
    }

    @Test
    void incompleteTerminalEventCannotEnterTheEventOrAuditLedgers() {
        UUID runId = createRun();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = NOW.plusSeconds(1);
        String payload = jsonCodec.write(Map.ofEntries(
            Map.entry("eventId", eventId),
            Map.entry("organizationId", TENANT_A),
            Map.entry("projectId", PROJECT_A),
            Map.entry("incidentId", incidentId),
            Map.entry("runId", runId),
            Map.entry("sequenceNo", 2),
            Map.entry("eventType", "FAILED"),
            Map.entry("actorId", USER_A),
            Map.entry("occurredAt", occurredAt),
            Map.entry("details", Map.of("runId", runId, "occurredAt", occurredAt))
        ));

        assertSqlState(() -> adminTransactions.executeWithoutResult(ignored -> {
            admin.update(
                "UPDATE investigation_runs SET status = 'FAILED', revision = 1, event_count = 2, "
                    + "terminal_reason = 'Dependency failed.', ended_at = ? "
                    + "WHERE organization_id = ? AND run_id = ?",
                Timestamp.from(occurredAt), TENANT_A, runId
            );
            admin.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 2, 'FAILED', ?, ?, CAST(? AS jsonb))",
                eventId, TENANT_A, PROJECT_A, incidentId, runId, USER_A,
                Timestamp.from(occurredAt), payload
            );
        }), "P7005");

        assertThat(count("investigation_run_events", runId)).isEqualTo(1);
        assertThat(count("audit_events", runId)).isEqualTo(1);
    }

    private UUID createRun() {
        UUID runId = UUID.randomUUID();
        store.create(InvestigationStateMachine.start(new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(4, 4, 20, 8_000), NOW, NOW.plusSeconds(120)
        )));
        return runId;
    }

    private int count(String table, UUID runId) {
        String predicate = table.equals("audit_events")
            ? "resource_type = 'investigation_run' AND resource_id = ?"
            : "organization_id = ? AND run_id = ?";
        Object[] arguments = table.equals("audit_events")
            ? new Object[] {runId.toString()}
            : new Object[] {TENANT_A, runId};
        return admin.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + predicate,
            Integer.class,
            arguments
        );
    }

    private void assertSqlState(Runnable operation, String expected) {
        assertThatThrownBy(operation::run).satisfies(error ->
            assertThat(findSqlState(error)).isEqualTo(expected)
        );
    }

    private String findSqlState(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException.getSQLState();
        }
        return null;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
