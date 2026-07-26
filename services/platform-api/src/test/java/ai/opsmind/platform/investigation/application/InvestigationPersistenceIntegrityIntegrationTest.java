package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationEvent;
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

    @Test
    void acceptedAnalysisValidatorRejectsUnknownFieldsStatusRunAndSemanticDrift() {
        UUID runId = createRun();
        String response = jsonCodec.write(abstain(runId));
        String incoherentAbstain = admin.queryForObject(
            "SELECT jsonb_set(CAST(? AS jsonb), '{missing_evidence}', '[]'::jsonb)::text",
            String.class, response
        );
        String incoherentProviderFailure = admin.queryForObject(
            "SELECT jsonb_set(CAST(? AS jsonb), '{status}', "
                + "'\"provider_unavailable\"'::jsonb)::text",
            String.class, jsonCodec.write(complete(runId, "Unsupported provider claim."))
        );

        assertThat(validAcceptedResponse(response, runId, "abstain")).isTrue();
        assertThat(admin.queryForObject(
            "SELECT opsmind_valid_accepted_analysis_response("
                + "CAST(? AS jsonb) || '{\"unexpected\":true}'::jsonb, ?, ?)",
            Boolean.class, response, runId, "abstain"
        )).isFalse();
        assertThat(validAcceptedResponse(response, runId, "complete")).isFalse();
        assertThat(validAcceptedResponse(response, UUID.randomUUID(), "abstain")).isFalse();
        assertThat(validAcceptedResponse(incoherentAbstain, runId, "abstain")).isFalse();
        assertThat(validAcceptedResponse(
            incoherentProviderFailure, runId, "provider_unavailable"
        )).isFalse();
    }

    @Test
    void completedAcceptedEventMustEqualTheDurableFinalResponse() {
        UUID runId = createRun();
        Instant occurredAt = NOW.plusSeconds(1);
        AnalysisRuntimeResponse finalResponse = complete(runId, "Final supported claim.");
        AnalysisRuntimeResponse driftedResponse = complete(runId, "Different accepted claim.");
        UUID eventId = UUID.randomUUID();
        String payload = jsonCodec.eventPayload(
            eventId, TENANT_A, PROJECT_A, incidentId, runId, 2, USER_A,
            new InvestigationEvent.AnalysisAccepted(
                runId, "complete", 1, 15, driftedResponse, occurredAt
            )
        );

        assertSqlState(() -> adminTransactions.executeWithoutResult(ignored -> {
            admin.update(
                "UPDATE investigation_runs SET status = 'COMPLETED', revision = 1, "
                    + "event_count = 3, rounds = 1, total_tokens = 15, "
                    + "final_response = CAST(? AS jsonb), ended_at = ? "
                    + "WHERE organization_id = ? AND run_id = ?",
                jsonCodec.write(finalResponse), Timestamp.from(occurredAt), TENANT_A, runId
            );
            admin.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 2, 'ANALYSIS_ACCEPTED', ?, ?, CAST(? AS jsonb))",
                eventId, TENANT_A, PROJECT_A, incidentId, runId, USER_A,
                Timestamp.from(occurredAt), payload
            );
        }), "P7005");

        assertThat(store.require(TENANT_A, USER_A, runId).status())
            .isEqualTo(InvestigationStateMachine.Status.CREATED);
        assertThat(count("investigation_run_events", runId)).isEqualTo(1);
    }

    @Test
    void legacyAcceptedEventRemainsWritableDuringV008ExpansionWindow() {
        UUID runId = createRun();
        Instant acceptedAt = NOW.plusSeconds(1);
        Instant terminalAt = NOW.plusSeconds(2);
        String reason = "Legacy writer abstained during rolling deployment.";
        UUID acceptedEventId = UUID.randomUUID();
        UUID terminalEventId = UUID.randomUUID();
        String acceptedPayload = jsonCodec.write(Map.ofEntries(
            Map.entry("eventId", acceptedEventId),
            Map.entry("organizationId", TENANT_A),
            Map.entry("projectId", PROJECT_A),
            Map.entry("incidentId", incidentId),
            Map.entry("runId", runId),
            Map.entry("sequenceNo", 2),
            Map.entry("eventType", "ANALYSIS_ACCEPTED"),
            Map.entry("actorId", USER_A),
            Map.entry("occurredAt", acceptedAt),
            Map.entry("details", Map.of(
                "runId", runId,
                "status", "abstain",
                "round", 1,
                "totalTokens", 15,
                "occurredAt", acceptedAt
            ))
        ));
        String terminalPayload = jsonCodec.write(Map.ofEntries(
            Map.entry("eventId", terminalEventId),
            Map.entry("organizationId", TENANT_A),
            Map.entry("projectId", PROJECT_A),
            Map.entry("incidentId", incidentId),
            Map.entry("runId", runId),
            Map.entry("sequenceNo", 3),
            Map.entry("eventType", "ABSTAINED"),
            Map.entry("actorId", USER_A),
            Map.entry("occurredAt", terminalAt),
            Map.entry("details", Map.of(
                "runId", runId,
                "reason", reason,
                "occurredAt", terminalAt
            ))
        ));

        adminTransactions.executeWithoutResult(ignored -> {
            admin.update(
                "UPDATE investigation_runs SET status = 'ABSTAINED', revision = 1, "
                    + "event_count = 3, rounds = 1, total_tokens = 15, "
                    + "terminal_reason = ?, ended_at = ? "
                    + "WHERE organization_id = ? AND run_id = ?",
                reason, Timestamp.from(terminalAt), TENANT_A, runId
            );
            admin.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 2, 'ANALYSIS_ACCEPTED', ?, ?, CAST(? AS jsonb))",
                acceptedEventId, TENANT_A, PROJECT_A, incidentId, runId, USER_A,
                Timestamp.from(acceptedAt), acceptedPayload
            );
            admin.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 3, 'ABSTAINED', ?, ?, CAST(? AS jsonb))",
                terminalEventId, TENANT_A, PROJECT_A, incidentId, runId, USER_A,
                Timestamp.from(terminalAt), terminalPayload
            );
        });

        assertThat(store.require(TENANT_A, USER_A, runId).status())
            .isEqualTo(InvestigationStateMachine.Status.ABSTAINED);
        assertThat(count("investigation_run_events", runId)).isEqualTo(3);
        assertThat(admin.queryForObject(
            "SELECT jsonb_exists(payload -> 'details', 'response') "
                + "FROM investigation_run_events WHERE organization_id = ? AND event_id = ?",
            Boolean.class, TENANT_A, acceptedEventId
        )).isFalse();
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

    private boolean validAcceptedResponse(String response, UUID runId, String status) {
        return Boolean.TRUE.equals(admin.queryForObject(
            "SELECT opsmind_valid_accepted_analysis_response(CAST(? AS jsonb), ?, ?)",
            Boolean.class, response, runId, status
        ));
    }

    private AnalysisRuntimeResponse abstain(UUID runId) {
        return new AnalysisRuntimeResponse(
            "abstain", runId, "deepseek-v4-flash", "prompt-incident-investigation-v1",
            "analysis-v1", List.of(), List.of(), List.of("Deployment change record"),
            List.of(), 0.0, new AnalysisRuntimeResponse.Usage(10, 5, 15),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), List.of()
        );
    }

    private AnalysisRuntimeResponse complete(UUID runId, String claim) {
        AnalysisRuntimeResponse.Citation citation = new AnalysisRuntimeResponse.Citation(
            UUID.randomUUID(), "sha256:" + "1".repeat(64), claim
        );
        return new AnalysisRuntimeResponse(
            "complete", runId, "deepseek-v4-flash", "prompt-incident-investigation-v1",
            "analysis-v1", List.of(new AnalysisRuntimeResponse.Hypothesis(
                "Deployment regression", "The deployment correlates with the latency increase.",
                0.8, List.of(citation)
            )), List.of(), List.of(), List.of(citation), 0.8,
            new AnalysisRuntimeResponse.Usage(10, 5, 15),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), List.of()
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
