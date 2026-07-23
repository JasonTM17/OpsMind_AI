package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisContext;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
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
    "opsmind.investigation.enabled=true", "opsmind.investigation.store=postgres"
})
class InvestigationEvidencePersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired private InvestigationRunStore store;
    @Autowired private IncidentAnalysisAuthorizer authorizer;
    @MockitoBean private InvestigationAiRuntimeClient aiRuntimeClient;
    @MockitoBean private InvestigationToolGatewayClient toolGatewayClient;
    private JdbcTemplate admin;
    private UUID incidentId;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("SPRING_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> required("POSTGRES_APP_USER"));
        properties.add("spring.datasource.password", () -> required("POSTGRES_APP_PASSWORD"));
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
                + "VALUES (?, ?, ?, 'Evidence integrity', 'Bounded evidence', 'SEV2', 'OPEN', "
                + "?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A, Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void persistsAuthorizesAndProtectsCanonicalEvidence() {
        UUID runId = UUID.randomUUID();
        InvestigationCommand.Start command = start(runId);
        InvestigationStateMachine.State completed = orchestrator().run(
            command, InvestigationTestFixtures.context(command)
        );
        UUID evidenceId = completed.evidenceIds().iterator().next();

        assertThat(count("evidence_records", runId)).isEqualTo(1);
        assertThat(admin.queryForObject(
            "SELECT 'sha256:' || encode(content_digest, 'hex') FROM evidence_records "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, runId
        )).isEqualTo(FixtureInvestigationAiRuntimeClient.evidenceDigest());
        assertThat(admin.queryForObject(
            "SELECT gateway_duplicate FROM evidence_records WHERE organization_id = ? AND run_id = ?",
            Boolean.class, TENANT_A, runId
        )).isFalse();

        AuthorizedIncidentAnalysisContext context = authorizer.requireEvidenceRecords(
            principal("phase3-operator-a"), TENANT_A, PROJECT_A, incidentId, runId, List.of(evidenceId)
        );
        assertThat(context.evidence()).singleElement().satisfies(record -> {
            assertThat(record.digest()).isEqualTo(FixtureInvestigationAiRuntimeClient.evidenceDigest());
            assertThat(record.canonicalContent())
                .isEqualTo(FixtureInvestigationAiRuntimeClient.evidenceContent());
        });
        assertThat(admin.queryForObject(
            "SELECT payload::text FROM audit_events WHERE resource_type = 'investigation_run' "
                + "AND resource_id = ? AND action = 'EVIDENCE_APPENDED'",
            String.class, runId.toString()
        )).doesNotContain("http_request_duration_seconds", "canonicalContent");

        assertHidden(TENANT_B, PostgresTenantFixtures.PROJECT_B, runId, List.of(evidenceId));
        assertHidden(TENANT_A, PROJECT_A, UUID.randomUUID(), List.of());
        assertImmutable(runId);
    }

    private InvestigationOrchestrator orchestrator() {
        return new InvestigationOrchestrator(
            store, new FixtureInvestigationAiRuntimeClient(),
            new FixtureInvestigationToolGatewayClient(), Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private InvestigationCommand.Start start(UUID runId) {
        return new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(4, 4, 20, 8_000), NOW, NOW.plusSeconds(120)
        );
    }

    private void assertHidden(UUID tenantId, UUID projectId, UUID runId, List<UUID> evidenceIds) {
        String subject = tenantId.equals(TENANT_A) ? "phase3-operator-a" : "phase3-operator-b";
        assertThatThrownBy(() -> authorizer.requireEvidenceRecords(
            principal(subject), tenantId, projectId, incidentId, runId, evidenceIds
        )).isInstanceOf(PlatformProblemException.class);
    }

    private void assertImmutable(UUID runId) {
        assertSqlState(() -> admin.update(
            "UPDATE evidence_records SET canonical_content = '{}' WHERE organization_id = ? AND run_id = ?",
            TENANT_A, runId
        ), "42501");
        assertSqlState(() -> admin.update(
            "DELETE FROM evidence_records WHERE organization_id = ? AND run_id = ?", TENANT_A, runId
        ), "42501");
        assertSqlState(() -> admin.execute("TRUNCATE TABLE evidence_records"), "42501");
    }

    private int count(String table, UUID runId) {
        String predicate = table.equals("audit_events")
            ? "resource_type = 'investigation_run' AND resource_id = ?" : "organization_id = ? AND run_id = ?";
        Object[] arguments = table.equals("audit_events")
            ? new Object[] {runId.toString()} : new Object[] {TENANT_A, runId};
        return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate,
            Integer.class, arguments);
    }

    private void assertSqlState(Runnable operation, String expected) {
        assertThatThrownBy(operation::run).satisfies(error -> {
            String actual = null;
            for (Throwable current = error; current != null; current = current.getCause()) {
                if (current instanceof SQLException sql) {
                    actual = sql.getSQLState();
                    break;
                }
            }
            assertThat(actual).isEqualTo(expected);
        });
    }

    private OpsMindPrincipal principal(String subject) {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"), subject, null, null, Set.of("incident:analyze")
        );
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
