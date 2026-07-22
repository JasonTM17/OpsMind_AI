package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationToolGatewayClient;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE7_DB_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
    "opsmind.investigation.enabled=true",
    "opsmind.investigation.store=postgres"
})
class InvestigationPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private InvestigationRunStore store;

    @MockitoBean
    private InvestigationAiRuntimeClient aiRuntimeClient;

    @MockitoBean
    private InvestigationToolGatewayClient toolGatewayClient;

    private JdbcTemplate admin;
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
        admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        incidentId = UUID.randomUUID();
        admin.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                + "status, created_by, updated_by, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'Latency regression', 'Synthetic deployment regression', "
                + "'SEV2', 'OPEN', ?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A,
            Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void completedReducerStateEventsAndAuditRoundTripUnderTenantRls() {
        UUID runId = UUID.randomUUID();
        InvestigationOrchestrator orchestrator = new InvestigationOrchestrator(
            store,
            new FixtureInvestigationAiRuntimeClient(),
            new FixtureInvestigationToolGatewayClient(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        InvestigationStateMachine.State completed = orchestrator.run(start(runId));

        InvestigationStateMachine.State rehydrated = store.require(TENANT_A, USER_A, runId);
        assertThat(rehydrated).isEqualTo(completed);
        assertThat(rehydrated.status()).isEqualTo(InvestigationStateMachine.Status.COMPLETED);
        assertThat(rehydrated.eventCount()).isEqualTo(6);
        assertThat(sequenceNumbers(runId)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(count(
            "SELECT count(*) FROM audit_events WHERE resource_type = 'investigation_run' "
                + "AND resource_id = ? AND schema_version = 'investigation-audit-v1'",
            runId.toString()
        )).isEqualTo(6);

        assertThatThrownBy(() -> store.require(TENANT_B, USER_B, runId))
            .isInstanceOf(PlatformProblemException.class)
            .satisfies(error -> assertThat(((PlatformProblemException) error).code())
                .isEqualTo("investigation.run-not-found"));
    }

    @Test
    void staleSnapshotCannotAppendASecondTerminalEvent() {
        UUID runId = UUID.randomUUID();
        InvestigationStateMachine.Step initial = InvestigationStateMachine.start(start(runId));
        store.create(initial);
        InvestigationStateMachine.Step winner = InvestigationStateMachine.apply(
            initial.state(), new InvestigationCommand.Failed("Dependency unavailable."), NOW.plusSeconds(1)
        );
        InvestigationStateMachine.Step stale = InvestigationStateMachine.apply(
            initial.state(), new InvestigationCommand.Failed("Late competing result."), NOW.plusSeconds(2)
        );

        store.save(initial.state(), winner);
        assertThatThrownBy(() -> store.save(initial.state(), stale))
            .isInstanceOf(PlatformProblemException.class)
            .satisfies(error -> assertThat(((PlatformProblemException) error).code())
                .isEqualTo("investigation.run-conflict"));

        assertThat(count(
            "SELECT count(*) FROM investigation_run_events WHERE organization_id = ? AND run_id = ?",
            TENANT_A,
            runId
        )).isEqualTo(2);
        assertThat(count(
            "SELECT count(*) FROM audit_events WHERE resource_type = 'investigation_run' "
                + "AND resource_id = ?",
            runId.toString()
        )).isEqualTo(2);
    }

    private InvestigationCommand.Start start(UUID runId) {
        return new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(4, 4, 20, 8_000), NOW, NOW.plusSeconds(120)
        );
    }

    private List<Long> sequenceNumbers(UUID runId) {
        return admin.queryForList(
            "SELECT sequence_no FROM investigation_run_events WHERE organization_id = ? "
                + "AND run_id = ? ORDER BY sequence_no",
            Long.class,
            TENANT_A,
            runId
        );
    }

    private int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
