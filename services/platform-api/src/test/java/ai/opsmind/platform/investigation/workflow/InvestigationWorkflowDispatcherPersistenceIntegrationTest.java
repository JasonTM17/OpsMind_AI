package ai.opsmind.platform.investigation.workflow;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.investigation.application.DurableInvestigationAdmissionRepository;
import ai.opsmind.platform.investigation.application.InvestigationExecutionContext;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;
import ai.opsmind.platform.messaging.DispatcherDatabaseIdentity;
import ai.opsmind.platform.messaging.OutboxLease;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE9_DB_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
    "opsmind.investigation.enabled=true",
    "opsmind.investigation.store=postgres",
    "opsmind.investigation.execution-mode=temporal",
    "opsmind.investigation.workflow.cluster-id=temporal-primary",
    "opsmind.investigation.workflow.namespace=opsmind-test",
    "opsmind.investigation.workflow.workflow-type=opsmind-investigation-v1",
    "opsmind.investigation.workflow.task-queue=opsmind-investigation-test",
    "opsmind.investigation.workflow-starter.enabled=true",
    "opsmind.investigation.workflow-starter.poll-interval=PT1M",
    "opsmind.dispatcher.enabled=true"
})
class InvestigationWorkflowDispatcherPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private DurableInvestigationAdmissionRepository handoff;
    @Autowired
    private IncidentAnalysisAuthorizer authorizer;
    @Autowired
    private InvestigationWorkflowDispatchTransactions dispatchTransactions;
    @Autowired
    private InvestigationWorkflowStartDispatcher dispatcher;
    @Autowired
    private InvestigationWorkflowStartTenantScheduler tenantScheduler;
    @Autowired
    private InvestigationWorkflowStarterProperties starterProperties;
    @Autowired
    private InvestigationTemporalClientProperties temporalClientProperties;
    @Autowired
    private DispatcherDatabaseIdentity dispatcherIdentity;
    @Autowired
    private JdbcTemplate appJdbc;
    @Autowired
    @Qualifier("dispatcherJdbcTemplate")
    private JdbcTemplate dispatcherJdbc;
    @MockitoBean
    private InvestigationWorkflowAdmission admission;
    @MockitoBean
    private InvestigationWorkflowClient workflowClient;
    @MockitoBean
    private InvestigationAiRuntimeClient aiRuntimeClient;
    @MockitoBean
    private InvestigationToolGatewayClient toolGatewayClient;

    private JdbcTemplate admin;
    private UUID incidentId;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("SPRING_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> required("POSTGRES_APP_USER"));
        properties.add("spring.datasource.password", () -> required("POSTGRES_APP_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> "false");
        properties.add("opsmind.persistence.enabled", () -> "true");
        properties.add("opsmind.dispatcher.datasource.url", () -> required("SPRING_DATASOURCE_URL"));
        properties.add(
            "opsmind.dispatcher.datasource.username",
            () -> required("POSTGRES_DISPATCHER_USER")
        );
        properties.add(
            "opsmind.dispatcher.datasource.password",
            () -> required("POSTGRES_DISPATCHER_PASSWORD")
        );
    }

    @BeforeEach
    void seed() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        restoreDispatcherAccount();
        quarantinePreviousWorkflowHandoffs();
        incidentId = UUID.randomUUID();
        admin.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                + "status, created_by, updated_by, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'Latency', 'Synthetic', 'SEV2', 'OPEN', ?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A,
            Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void distinctDispatcherRoleClaimsOnlyWorkflowEventAndAcknowledgesAtomically() {
        InvestigationCommand.Start command = createHandoff();
        UUID unrelatedEvent = insertUnrelatedEvent();
        assertThat(dispatcherIdentity.sessionUser()).isEqualTo("opsmind_dispatcher");
        assertThat(appJdbc.queryForObject("SELECT session_user", String.class))
            .isEqualTo("opsmind_app");
        assertThat(dispatcherJdbc.queryForObject("SELECT session_user", String.class))
            .isEqualTo("opsmind_dispatcher");
        assertThat(tenantScheduler.listReadyTenants(10)).contains(TENANT_A);

        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        assertThat(lease.event().eventType())
            .isEqualTo(InvestigationWorkflowStartEnvelopeFactory.EVENT_TYPE);
        assertThat(dispatchTransactions.preflight(
            lease, requiredRpcWindow()
        )).isEqualTo(InvestigationWorkflowDispatchPreflightDecision.ALLOW);
        assertThat(admin.queryForObject(
            "SELECT lease_token IS NULL FROM outbox_events WHERE event_id = ?",
            Boolean.class, unrelatedEvent
        )).isTrue();
        assertThat(dispatchTransactions.claim(
            TENANT_B, UUID.randomUUID(), NOW, starterProperties
        )).isEmpty();

        assertThat(dispatchTransactions.acknowledgeStarted(
            lease, "temporal-run-success"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.STARTED);

        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("STARTED");
        assertThat(admin.queryForObject(
            "SELECT status FROM inbox_events WHERE organization_id = ? AND event_id = ? "
                + "AND consumer = ?",
            String.class, TENANT_A, lease.event().eventId(),
            InvestigationWorkflowDispatchTransactions.CONSUMER
        )).isEqualTo("processed");
        assertThat(admin.queryForObject(
            "SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?",
            Boolean.class, lease.event().eventId()
        )).isTrue();
    }

    @Test
    void databaseClockRatherThanCallerClockFencesAcknowledgement() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE outbox_events SET lease_expires_at = transaction_timestamp() "
                + "+ interval '1 hour' WHERE event_id = ?",
            lease.event().eventId()
        );

        // The claim timestamp is years ahead of the database clock. A live
        // lease must still settle because the database owns lease validity.
        assertThat(dispatchTransactions.acknowledgeStarted(
            lease, "temporal-run-skew-safe"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.STARTED);

        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("STARTED");
        assertThat(admin.queryForObject(
            "SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?",
            Boolean.class, lease.event().eventId()
        )).isTrue();
        assertThat(admin.queryForObject(
            "SELECT temporal_started_at < ? FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            Boolean.class, Timestamp.from(NOW), TENANT_A, command.runId()
        )).isTrue();
    }

    @Test
    void expiredLeaseCannotPartiallyAcknowledgeBindingInboxOrOutbox() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE outbox_events SET lease_expires_at = transaction_timestamp() "
                + "- interval '1 second' WHERE event_id = ?",
            lease.event().eventId()
        );

        assertThat(dispatchTransactions.acknowledgeStarted(
            lease, "temporal-run-stale"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.LEASE_LOST);

        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("PENDING");
        assertThat(admin.queryForObject(
            "SELECT count(*) FROM inbox_events WHERE organization_id = ? AND event_id = ?",
            Integer.class, TENANT_A, lease.event().eventId()
        )).isZero();
        assertThat(admin.queryForObject(
            "SELECT published_at IS NULL FROM outbox_events WHERE event_id = ?",
            Boolean.class, lease.event().eventId()
        )).isTrue();
    }

    @Test
    void permanentFailureRejectsBindingInboxAndOutboxInOneTransaction() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();

        assertThat(dispatchTransactions.reject(
            lease,
            "workflow.event-contract-invalid"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.REJECTED);

        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("REJECTED");
        assertThat(admin.queryForObject(
            "SELECT status FROM inbox_events WHERE organization_id = ? AND event_id = ? "
                + "AND consumer = ?",
            String.class, TENANT_A, lease.event().eventId(),
            InvestigationWorkflowDispatchTransactions.CONSUMER
        )).isEqualTo("poisoned");
        assertThat(admin.queryForObject(
            "SELECT poisoned_at IS NOT NULL AND last_error = ? "
                + "FROM outbox_events WHERE event_id = ?",
            Boolean.class, "workflow.event-contract-invalid", lease.event().eventId()
        )).isTrue();
        assertThat(admin.queryForObject(
            "SELECT rejected_at >= created_at FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            Boolean.class, TENANT_A, command.runId()
        )).isTrue();
    }

    @Test
    void expiredLeaseCannotReleaseOrPoisonAClaimOwnedByAnotherAttempt() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE outbox_events SET lease_expires_at = transaction_timestamp() "
                + "- interval '1 second' WHERE event_id = ?",
            lease.event().eventId()
        );

        assertThat(dispatchTransactions.releaseRetry(
            lease,
            "workflow.temporal-unavailable",
            Duration.ofSeconds(1)
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.LEASE_LOST);

        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("PENDING");
        assertThat(admin.queryForObject(
            "SELECT lease_token = ? AND last_error IS NULL AND poisoned_at IS NULL "
                + "FROM outbox_events WHERE event_id = ?",
            Boolean.class, lease.leaseToken(), lease.event().eventId()
        )).isTrue();
    }

    @Test
    void moreThanOneHundredUnrelatedReadyTenantsCannotStarveWorkflowStarts() {
        InvestigationCommand.Start command = createHandoff();
        insertUnrelatedReadyTenants(101);
        try {
            assertThat(tenantScheduler.listReadyTenants(10))
                .contains(TENANT_A)
                .doesNotContain(TENANT_B);
            assertThat(dispatchTransactions.claim(
                TENANT_A, UUID.randomUUID(), NOW, starterProperties
            )).hasValueSatisfying(lease ->
                assertThat(lease.event().aggregateId()).isEqualTo(command.runId())
            );
        }
        finally {
            deleteUnrelatedReadyTenants();
        }
    }

    @Test
    void databaseClockDeadlineFenceRejectsWithoutTemporalRpc() {
        Instant databaseNow = admin.queryForObject(
            "SELECT clock_timestamp()",
            Timestamp.class
        ).toInstant();
        InvestigationCommand.Start command = createHandoff(
            databaseNow.minusSeconds(1),
            databaseNow.plusSeconds(2)
        );

        assertThat(dispatcher.dispatchTenant(TENANT_A)).isOne();

        verifyNoInteractions(workflowClient);
        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("REJECTED");
        assertThat(admin.queryForObject(
            "SELECT last_error FROM outbox_events WHERE aggregate_id = ?",
            String.class, command.runId()
        )).isEqualTo("workflow.deadline-exhausted");
    }

    @Test
    void remainingLeaseWindowPreventsAnotherTemporalRpc() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE outbox_events SET lease_expires_at = clock_timestamp() + interval '2 seconds' "
                + "WHERE event_id = ?",
            lease.event().eventId()
        );

        assertThat(dispatchTransactions.preflight(
            lease, requiredRpcWindow()
        )).isEqualTo(InvestigationWorkflowDispatchPreflightDecision.LEASE_WINDOW_EXHAUSTED);
        assertThat(dispatchTransactions.releaseRetry(
            lease, "workflow.lease-window-exhausted", Duration.ofSeconds(1)
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);
        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("PENDING");
        assertThat(admin.queryForObject(
            "SELECT lease_token IS NULL AND poisoned_at IS NULL "
                + "AND last_error = 'workflow.lease-window-exhausted' "
                + "FROM outbox_events WHERE event_id = ?",
            Boolean.class, lease.event().eventId()
        )).isTrue();
    }

    @Test
    void dispatcherAuthorizationPreflightDeniesRevokedProjectRole() {
        createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE project_memberships SET status = 'revoked' "
                + "WHERE organization_id = ? AND project_id = ? AND user_id = ?",
            TENANT_A, PROJECT_A, USER_A
        );

        assertThat(dispatchTransactions.preflight(
            lease, requiredRpcWindow()
        )).isEqualTo(InvestigationWorkflowDispatchPreflightDecision.AUTHORIZATION_REVOKED);
    }

    @Test
    void suspendedAccountCanTerminallySettleItsAlreadyClaimedWorkflowLease() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE service_accounts SET status = 'suspended' "
                + "WHERE organization_id = ? AND database_principal = 'opsmind_dispatcher'",
            TENANT_A
        );

        assertThat(dispatchTransactions.preflight(
            lease, requiredRpcWindow()
        )).isEqualTo(InvestigationWorkflowDispatchPreflightDecision.DISPATCHER_INELIGIBLE);
        assertThat(dispatchTransactions.reject(
            lease, "workflow.dispatcher-ineligible"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.REJECTED);
        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("REJECTED");
        assertThat(admin.queryForObject(
            "SELECT poisoned_at IS NOT NULL AND last_error = ? "
                + "FROM outbox_events WHERE event_id = ?",
            Boolean.class, "workflow.dispatcher-ineligible", lease.event().eventId()
        )).isTrue();
    }

    @Test
    void suspendedAccountCanSettleAConfirmedTemporalStart() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE service_accounts SET status = 'suspended' "
                + "WHERE organization_id = ? AND database_principal = 'opsmind_dispatcher'",
            TENANT_A
        );

        assertThat(dispatchTransactions.acknowledgeStarted(
            lease, "temporal-run-after-suspension"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.STARTED);
        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("STARTED");
        assertThat(admin.queryForObject(
            "SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?",
            Boolean.class, lease.event().eventId()
        )).isTrue();
    }

    @Test
    void suspendedAccountPreservesAmbiguousRetryForReconciliation() {
        InvestigationCommand.Start command = createHandoff();
        OutboxLease lease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();
        admin.update(
            "UPDATE service_accounts SET status = 'suspended' "
                + "WHERE organization_id = ? AND database_principal = 'opsmind_dispatcher'",
            TENANT_A
        );

        assertThat(dispatchTransactions.releaseRetry(
            lease, "workflow.temporal-unavailable", Duration.ofSeconds(1)
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);
        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("PENDING");
        assertThat(admin.queryForObject(
            "SELECT last_error FROM outbox_events WHERE event_id = ?",
            String.class, lease.event().eventId()
        )).isEqualTo("workflow.temporal-unavailable");
    }

    @Test
    void terminalizerPoisonsAnUnclaimedStartWithNoEligibleDispatcher() {
        InvestigationCommand.Start command = createHandoff();
        admin.update(
            "UPDATE service_accounts SET status = 'suspended' "
                + "WHERE organization_id = ? AND database_principal = 'opsmind_dispatcher'",
            TENANT_A
        );

        assertThat(dispatchTransactions.terminalizeUnclaimedIneligible(10)).isOne();
        assertThat(admin.queryForObject(
            "SELECT status FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            String.class, TENANT_A, command.runId()
        )).isEqualTo("REJECTED");
        assertThat(admin.queryForObject(
            "SELECT poisoned_at IS NOT NULL AND last_error = ? "
                + "FROM outbox_events WHERE aggregate_id = ?",
            Boolean.class, "workflow.dispatcher-ineligible", command.runId()
        )).isTrue();
    }

    @Test
    void claimProcessesOnlyOneLeasePerTransaction() {
        InvestigationCommand.Start first = createHandoff();
        InvestigationCommand.Start second = createHandoff();

        OutboxLease firstLease = dispatchTransactions.claim(
            TENANT_A, UUID.randomUUID(), NOW, starterProperties
        ).orElseThrow();

        UUID firstClaimedRunId = firstLease.event().aggregateId();
        assertThat(firstClaimedRunId).isIn(first.runId(), second.runId());
        assertThat(admin.queryForObject(
            "SELECT count(*) FROM outbox_events "
                + "WHERE aggregate_id IN (?, ?) AND lease_token IS NOT NULL",
            Integer.class, first.runId(), second.runId()
        )).isOne();
        assertThat(dispatchTransactions.acknowledgeStarted(
            firstLease, "temporal-run-one-lease-proof"
        )).isEqualTo(InvestigationWorkflowDispatchSettlementResult.STARTED);
    }

    private InvestigationCommand.Start createHandoff() {
        return createHandoff(NOW, NOW.plusSeconds(600));
    }

    private InvestigationCommand.Start createHandoff(Instant startedAt, Instant deadlineAt) {
        InvestigationCommand.Start command = new InvestigationCommand.Start(
            UUID.randomUUID(), TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(4, 4, 20, 8_000),
            startedAt, deadlineAt
        );
        OpsMindPrincipal principal = new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "phase3-operator-a",
            null,
            null,
            Set.of("incident:analyze")
        );
        handoff.createOrLoad(command, new InvestigationExecutionContext(
            principal,
            authorizer.requireEvidence(
                principal,
                command.organizationId(),
                command.projectId(),
                command.incidentId()
            )
        ));
        return command;
    }

    private UUID insertUnrelatedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"kind\":\"unrelated\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        admin.update(
            "INSERT INTO outbox_events "
                + "(event_id, organization_id, aggregate_type, aggregate_id, aggregate_sequence, "
                + "event_type, schema_version, correlation_id, occurred_at, payload, "
                + "payload_bytes, payload_digest) "
                + "VALUES (?, ?, 'unrelated', ?, 1, 'unrelated.event', '1', ?, ?, "
                + "CAST(? AS jsonb), ?, ?)",
            eventId, TENANT_A, aggregateId, aggregateId, Timestamp.from(NOW),
            payload, payloadBytes, RequestDigest.sha256(payloadBytes)
        );
        return eventId;
    }

    private void quarantinePreviousWorkflowHandoffs() {
        admin.update(
            "UPDATE outbox_events SET poisoned_at = COALESCE(poisoned_at, statement_timestamp()), "
                + "last_error = COALESCE(last_error, 'test.previous-handoff'), "
                + "lease_token = NULL, lease_expires_at = NULL "
                + "WHERE organization_id = ? "
                + "AND event_type = ? AND published_at IS NULL",
            TENANT_A,
            InvestigationWorkflowStartEnvelopeFactory.EVENT_TYPE
        );
    }

    private void insertUnrelatedReadyTenants(int count) {
        admin.update(
            "INSERT INTO organizations (id, slug, name) "
                + "SELECT md5('phase9-unrelated-org-' || item)::uuid, "
                + "'phase9-unrelated-' || item, 'Phase 9 unrelated ' || item "
                + "FROM generate_series(1, ?) item ON CONFLICT (id) DO NOTHING",
            count
        );
        admin.update(
            "INSERT INTO service_accounts "
                + "(id, organization_id, name, credential_ref, allowed_audiences, "
                + "allowed_scopes, database_principal) "
                + "SELECT md5('phase9-unrelated-account-' || item)::uuid, "
                + "md5('phase9-unrelated-org-' || item)::uuid, 'outbox-dispatcher', "
                + "'secret-manager://phase9/unrelated/' || item, "
                + "'[\"opsmind-outbox-dispatcher\"]'::jsonb, "
                + "'[\"outbox:dispatch\"]'::jsonb, 'opsmind_dispatcher' "
                + "FROM generate_series(1, ?) item ON CONFLICT (id) DO NOTHING",
            count
        );
        admin.update(
            "INSERT INTO outbox_events "
                + "(event_id, organization_id, aggregate_type, aggregate_id, "
                + "aggregate_sequence, event_type, schema_version, correlation_id, "
                + "occurred_at, payload, payload_bytes, payload_digest) "
                + "SELECT md5('phase9-unrelated-event-' || item)::uuid, "
                + "md5('phase9-unrelated-org-' || item)::uuid, 'unrelated', "
                + "md5('phase9-unrelated-aggregate-' || item)::uuid, 1, "
                + "'unrelated.event', '1', "
                + "md5('phase9-unrelated-aggregate-' || item)::uuid, ?, "
                + "'{\"kind\":\"unrelated\"}'::jsonb, "
                + "convert_to('{\"kind\":\"unrelated\"}', 'UTF8'), "
                + "digest(convert_to('{\"kind\":\"unrelated\"}', 'UTF8'), 'sha256') "
                + "FROM generate_series(1, ?) item ON CONFLICT (event_id) DO NOTHING",
            Timestamp.from(NOW.minusSeconds(60)), count
        );
    }

    private void deleteUnrelatedReadyTenants() {
        admin.update(
            "DELETE FROM outbox_events "
                + "WHERE event_id IN (SELECT md5('phase9-unrelated-event-' || item)::uuid "
                + "FROM generate_series(1, 101) item)"
        );
        admin.update(
            "DELETE FROM service_accounts "
                + "WHERE id IN (SELECT md5('phase9-unrelated-account-' || item)::uuid "
                + "FROM generate_series(1, 101) item)"
        );
        admin.update(
            "DELETE FROM organizations "
                + "WHERE id IN (SELECT md5('phase9-unrelated-org-' || item)::uuid "
                + "FROM generate_series(1, 101) item)"
        );
    }

    private void restoreDispatcherAccount() {
        assertThat(admin.update(
            "UPDATE service_accounts SET status = 'active', "
                + "allowed_audiences = '[\"opsmind-outbox-dispatcher\"]'::jsonb, "
                + "allowed_scopes = '[\"outbox:dispatch\"]'::jsonb "
                + "WHERE organization_id = ? "
                + "AND database_principal = 'opsmind_dispatcher'",
            TENANT_A
        )).isOne();
    }

    private Duration requiredRpcWindow() {
        return starterProperties.requiredRpcWindow(temporalClientProperties.rpcTimeout());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
