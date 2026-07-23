package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.CollectedEvidence;
import ai.opsmind.platform.evidence.EvidenceIdentity;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationAiRuntimeClient;
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
class InvestigationEvidenceRollbackIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired private InvestigationRunStore store;
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
                + "VALUES (?, ?, ?, 'Evidence rollback', 'Reject digest drift', 'SEV2', 'OPEN', "
                + "?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A, Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void invalidCanonicalDigestRollsBackSnapshotEventEvidenceAndAudit() {
        UUID runId = UUID.randomUUID();
        InvestigationStateMachine.Step waiting = waiting(runId);
        AnalysisRuntimeResponse.ToolIntent intent = waiting.state().pendingIntents().get(0);
        InvestigationStateMachine.Step next = evidenceStep(
            waiting, invalidEvidence(runId, intent.intentId())
        );

        assertThatThrownBy(() -> store.save(waiting.state(), next))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
        assertPriorBoundary(waiting.state());
    }

    @Test
    void auditConflictRollsBackSnapshotEventAndEvidence() {
        UUID runId = UUID.randomUUID();
        InvestigationStateMachine.Step waiting = waiting(runId);
        AnalysisRuntimeResponse.ToolIntent intent = waiting.state().pendingIntents().get(0);
        UUID eventId = InvestigationEventLedger.eventId(
            TENANT_A, runId, waiting.state().eventCount() + 1
        );
        admin.update(
            "INSERT INTO audit_events (event_id, organization_id, actor_id, action, resource_type, "
                + "resource_id, correlation_id, occurred_at, payload, schema_version) "
                + "VALUES (?, ?, ?, 'TEST_SENTINEL', 'sentinel', 'sentinel', ?, ?, '{}'::jsonb, "
                + "'legacy-v1')",
            eventId, TENANT_A, USER_A, runId, Timestamp.from(NOW.plusSeconds(2))
        );

        InvestigationStateMachine.Step next = evidenceStep(
            waiting, validEvidence(runId, intent.intentId())
        );
        assertThatThrownBy(() -> store.save(waiting.state(), next))
            .isInstanceOf(PlatformProblemException.class)
            .satisfies(error -> assertThat(
                ((PlatformProblemException) error).code()
            ).isEqualTo("audit.persistence-rejected"));
        assertPriorBoundary(waiting.state());
    }

    private InvestigationStateMachine.Step waiting(UUID runId) {
        InvestigationStateMachine.Step initial = InvestigationStateMachine.start(start(runId));
        store.create(initial);
        AnalysisRuntimeResponse response = new FixtureInvestigationAiRuntimeClient().analyze(
            InvestigationTestFixtures.analysisRequest(start(runId), java.util.Set.of(), 1)
        );
        InvestigationStateMachine.Step waiting = InvestigationStateMachine.apply(
            initial.state(), new InvestigationCommand.AnalysisReceived(response), NOW.plusSeconds(1)
        );
        store.save(initial.state(), waiting);
        return waiting;
    }

    private InvestigationStateMachine.Step evidenceStep(
        InvestigationStateMachine.Step waiting,
        CollectedEvidence evidence
    ) {
        AnalysisRuntimeResponse.ToolIntent intent = waiting.state().pendingIntents().get(0);
        return InvestigationStateMachine.apply(
            waiting.state(), new InvestigationCommand.ToolEvidenceReceived(
                intent.intentId(), EvidenceIdentity.evidenceId(
                    TENANT_A, waiting.state().runId(), intent.intentId()
                ), evidence.contentDigest(), evidence.sourceType(), evidence
            ), NOW.plusSeconds(2)
        );
    }

    private void assertPriorBoundary(InvestigationStateMachine.State waiting) {
        UUID runId = waiting.runId();
        assertThat(store.require(TENANT_A, USER_A, runId)).isEqualTo(waiting);
        assertThat(count("investigation_run_events", runId)).isEqualTo(3);
        assertThat(count("audit_events", runId)).isEqualTo(3);
        assertThat(count("evidence_records", runId)).isZero();
    }

    private InvestigationCommand.Start start(UUID runId) {
        return new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(4, 4, 20, 8_000), NOW, NOW.plusSeconds(120)
        );
    }

    private CollectedEvidence invalidEvidence(UUID runId, UUID intentId) {
        return evidence(runId, intentId, "{}");
    }

    private CollectedEvidence validEvidence(UUID runId, UUID intentId) {
        return evidence(runId, intentId, FixtureInvestigationAiRuntimeClient.evidenceContent());
    }

    private CollectedEvidence evidence(UUID runId, UUID intentId, String canonicalContent) {
        return new CollectedEvidence(
            EvidenceIdentity.executionId(TENANT_A, runId, intentId), UUID.randomUUID(), "0".repeat(64),
            "metric", "fixture-prometheus", "prometheus:synthetic/opsmind-api", NOW,
            NOW.minusSeconds(180), NOW, "fixture-observability@1", "observability.metrics.query@1",
            "policy-fixture-v1", "fixture-prometheus/fixture-observability@1", "synthetic",
            FixtureInvestigationAiRuntimeClient.evidenceDigest(), canonicalContent,
            0, false, null, false
        );
    }

    private int count(String table, UUID runId) {
        String predicate = table.equals("audit_events")
            ? "resource_type = 'investigation_run' AND resource_id = ?" : "organization_id = ? AND run_id = ?";
        Object[] arguments = table.equals("audit_events")
            ? new Object[] {runId.toString()} : new Object[] {TENANT_A, runId};
        return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate,
            Integer.class, arguments);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
