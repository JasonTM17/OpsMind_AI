package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.CollectedEvidence;
import ai.opsmind.platform.evidence.EvidenceIdentity;
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
class InvestigationEvidenceReplayIntegrationTest {

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
                + "VALUES (?, ?, ?, 'Evidence replay', 'Exact transition replay', 'SEV2', 'OPEN', "
                + "?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A, Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void exactReplayIsANoOpWhileEvidenceProvenanceDriftConflicts() {
        UUID runId = UUID.randomUUID();
        InvestigationStateMachine.Step waiting = waiting(runId);
        InvestigationToolGatewayClient.ToolEvidence toolEvidence = collect(waiting.state());
        InvestigationStateMachine.Step appended = evidenceStep(waiting, toolEvidence.collectedEvidence());
        store.save(waiting.state(), appended);

        int events = count("investigation_run_events", runId);
        int audits = count("audit_events", runId);
        assertThatCode(() -> store.save(waiting.state(), appended)).doesNotThrowAnyException();
        assertThat(store.require(TENANT_A, USER_A, runId).evidenceIds())
            .containsExactly(toolEvidence.evidenceId());
        assertCounts(runId, events, audits);

        CollectedEvidence original = toolEvidence.collectedEvidence();
        assertConflict(waiting, copy(original, "sha256:" + "1".repeat(64),
            original.gatewayRequestDigest(), original.executionId(), original.sourceProvenance()));
        assertConflict(waiting, copy(original, original.contentDigest(), "1".repeat(64),
            original.executionId(), original.sourceProvenance()));
        assertConflict(waiting, copy(original, original.contentDigest(),
            original.gatewayRequestDigest(), UUID.randomUUID(), original.sourceProvenance()));
        assertConflict(waiting, copy(original, original.contentDigest(),
            original.gatewayRequestDigest(), original.executionId(), original.sourceProvenance() + "/drift"));
        assertCounts(runId, events, audits);
    }

    private InvestigationStateMachine.Step waiting(UUID runId) {
        InvestigationStateMachine.Step initial = InvestigationStateMachine.start(new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(4, 4, 20, 8_000), NOW, NOW.plusSeconds(120)
        ));
        store.create(initial);
        AnalysisRuntimeResponse response = new FixtureInvestigationAiRuntimeClient()
            .analyze(runId, Set.of(), 1);
        InvestigationStateMachine.Step waiting = InvestigationStateMachine.apply(
            initial.state(), new InvestigationCommand.AnalysisReceived(response), NOW.plusSeconds(1)
        );
        store.save(initial.state(), waiting);
        return waiting;
    }

    private InvestigationToolGatewayClient.ToolEvidence collect(InvestigationStateMachine.State state) {
        AnalysisRuntimeResponse.ToolIntent intent = state.pendingIntents().get(0);
        return new FixtureInvestigationToolGatewayClient().execute(intent,
            new InvestigationToolGatewayClient.ToolExecutionContext(
                state.organizationId(), state.projectId(), state.incidentId(), state.runId(),
                state.actorId(), state.deadlineAt()
            ));
    }

    private InvestigationStateMachine.Step evidenceStep(
        InvestigationStateMachine.Step waiting,
        CollectedEvidence evidence
    ) {
        AnalysisRuntimeResponse.ToolIntent intent = waiting.state().pendingIntents().get(0);
        return InvestigationStateMachine.apply(waiting.state(),
            new InvestigationCommand.ToolEvidenceReceived(
                intent.intentId(), EvidenceIdentity.evidenceId(
                    waiting.state().organizationId(), waiting.state().runId(), intent.intentId()
                ), evidence.contentDigest(), evidence.sourceType(), evidence
            ), NOW.plusSeconds(2)
        );
    }

    private void assertConflict(InvestigationStateMachine.Step waiting, CollectedEvidence evidence) {
        assertThatThrownBy(() -> store.save(waiting.state(), evidenceStep(waiting, evidence)))
            .isInstanceOf(PlatformProblemException.class)
            .satisfies(error -> assertThat(((PlatformProblemException) error).code())
                .isEqualTo("investigation.run-conflict"));
    }

    private void assertCounts(UUID runId, int events, int audits) {
        assertThat(count("investigation_run_events", runId)).isEqualTo(events);
        assertThat(count("audit_events", runId)).isEqualTo(audits);
        assertThat(count("evidence_records", runId)).isEqualTo(1);
    }

    private int count(String table, UUID runId) {
        String predicate = table.equals("audit_events")
            ? "resource_type = 'investigation_run' AND resource_id = ?" : "organization_id = ? AND run_id = ?";
        Object[] arguments = table.equals("audit_events")
            ? new Object[] {runId.toString()} : new Object[] {TENANT_A, runId};
        return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate,
            Integer.class, arguments);
    }

    private CollectedEvidence copy(
        CollectedEvidence source,
        String contentDigest,
        String requestDigest,
        UUID executionId,
        String provenance
    ) {
        return new CollectedEvidence(
            executionId, source.gatewayAuditEventId(), requestDigest, source.sourceType(), source.source(),
            source.targetIdentity(), source.observedAt(), source.windowStart(), source.windowEnd(),
            source.connectorVersion(), source.manifestVersion(), source.policyVersion(), provenance,
            source.trustClass(), contentDigest, source.canonicalContent(), source.redactedFields(),
            source.truncated(), source.artifactReference(), source.gatewayDuplicate()
        );
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
