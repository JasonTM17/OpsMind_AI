package ai.opsmind.platform.investigation.application;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowAdmission;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowHandoffRepository;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;
import ai.opsmind.platform.tenancy.TenantContextSql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE9_DB_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
    "opsmind.investigation.enabled=true",
    "opsmind.investigation.store=postgres",
    "opsmind.investigation.execution-mode=temporal",
    "opsmind.investigation.workflow.cluster-id=temporal-primary",
    "opsmind.investigation.workflow.namespace=opsmind-test",
    "opsmind.investigation.workflow.workflow-type=opsmind-investigation-v1",
    "opsmind.investigation.workflow.task-queue=opsmind-investigation-test"
})
class InvestigationWorkflowHandoffPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private InvestigationWorkflowHandoffRepository repository;
    @Autowired
    private InvestigationRunStore runStore;
    @Autowired
    private JdbcTemplate appJdbc;
    @Autowired
    private TenantContextSql tenantContext;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private InvestigationWorkflowAdmission admission;
    @MockitoBean
    private InvestigationExecutionStarter executionStarter;
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
    void seedIncident() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        incidentId = UUID.randomUUID();
        admin.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                + "status, created_by, updated_by, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'Latency', 'Synthetic regression', 'SEV2', 'OPEN', "
                + "?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A,
            Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void createsAtomicHandoffAndLoadsExactRetryWithoutDuplicateRows() {
        InvestigationCommand.Start first = start(UUID.randomUUID(), NOW, 4);
        InvestigationStateMachine.State created =
            repository.createOrLoad(first, InvestigationTestFixtures.context(first).initialIncident());
        InvestigationCommand.Start retry = start(first.runId(), NOW.plusSeconds(5), 4);
        InvestigationStateMachine.State loaded =
            repository.createOrLoad(retry, InvestigationTestFixtures.context(retry).initialIncident());

        assertThat(created.status()).isEqualTo(InvestigationStateMachine.Status.CREATED);
        assertThat(loaded).isEqualTo(created);
        assertThat(count("investigation_runs", "run_id", first.runId())).isOne();
        assertThat(count("investigation_run_events", "run_id", first.runId())).isOne();
        assertThat(count("investigation_workflow_bindings", "run_id", first.runId())).isOne();
        assertThat(count("outbox_events", "aggregate_id", first.runId())).isOne();
        assertThat(admin.queryForObject(
            "SELECT start_event_id = opsmind_investigation_workflow_start_event_id(organization_id, run_id) "
                + "FROM investigation_workflow_bindings WHERE organization_id = ? AND run_id = ?",
            Boolean.class, TENANT_A, first.runId()
        )).isTrue();

        int otherTenantVisible = inTenant(TENANT_B, USER_B, () -> appJdbc.queryForObject(
            "SELECT count(*) FROM investigation_workflow_bindings WHERE run_id = ?",
            Integer.class, first.runId()
        ));
        assertThat(otherTenantVisible).isZero();

        InvestigationCommand.Start conflicting = start(first.runId(), NOW.plusSeconds(10), 5);
        assertThatThrownBy(() -> repository.createOrLoad(
            conflicting, InvestigationTestFixtures.context(conflicting).initialIncident()
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("investigation.run-conflict")
        );
    }

    @Test
    void rejectsForgedWorkflowStartPayloadEvenWithRecomputedBytesAndDigest() {
        InvestigationCommand.Start first = start(UUID.randomUUID(), NOW, 4);
        repository.createOrLoad(first, InvestigationTestFixtures.context(first).initialIncident());

        Map<String, Object> canonical = admin.queryForMap(
            "SELECT event_id, organization_id, aggregate_type, aggregate_id, "
                + "aggregate_sequence, event_type, schema_version, causation_id, "
                + "correlation_id, occurred_at, payload::text AS payload_text "
                + "FROM outbox_events WHERE organization_id = ? AND aggregate_id = ?",
            TENANT_A, first.runId()
        );
        // The privileged fixture removes the source row only after preserving
        // its values. This lets the forged insert reach the payload-binding
        // trigger instead of the older contiguous-sequence trigger.
        assertThat(admin.update(
            "DELETE FROM outbox_events WHERE organization_id = ? AND aggregate_id = ?",
            TENANT_A, first.runId()
        )).isOne();

        assertThatThrownBy(() -> inTenant(TENANT_A, USER_A, () -> {
            appJdbc.update(
                "WITH forged AS ("
                    + "SELECT jsonb_set(CAST(? AS jsonb), '{actor_id}', "
                    + "to_jsonb(CAST(? AS text)), false) AS payload"
                    + ") INSERT INTO outbox_events "
                    + "(event_id, organization_id, aggregate_type, aggregate_id, "
                    + "aggregate_sequence, event_type, schema_version, causation_id, "
                    + "correlation_id, occurred_at, payload, payload_bytes, payload_digest) "
                    + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, payload, "
                    + "convert_to(payload::text, 'UTF8'), "
                    + "digest(convert_to(payload::text, 'UTF8'), 'sha256') "
                    + "FROM forged",
                canonical.get("payload_text"), USER_B,
                canonical.get("event_id"), canonical.get("organization_id"),
                canonical.get("aggregate_type"), canonical.get("aggregate_id"),
                canonical.get("aggregate_sequence"), canonical.get("event_type"),
                canonical.get("schema_version"), canonical.get("causation_id"),
                canonical.get("correlation_id"), canonical.get("occurred_at")
            );
            return true;
        })).isInstanceOfSatisfying(DataAccessException.class, exception ->
            assertThat(exception.getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sqlException ->
                    assertThat(sqlException.getSQLState()).isEqualTo("P7007")
                )
        );
        assertThat(count("outbox_events", "aggregate_id", first.runId())).isZero();
    }

    @Test
    void unresolvedLegacyRunBlocksNewTemporalAdmission() {
        InvestigationCommand.Start legacy = start(UUID.randomUUID(), NOW, 4);
        InvestigationStateMachine.Step initial = InvestigationStateMachine.start(legacy);
        runStore.create(initial);
        InvestigationCommand.Start next = start(UUID.randomUUID(), NOW.plusSeconds(1), 4);

        try {
            assertThatThrownBy(() -> repository.createOrLoad(
                next, InvestigationTestFixtures.context(next).initialIncident()
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("investigation.workflow-cutover-required")
            );
            assertThat(count("investigation_runs", "run_id", next.runId())).isZero();
        }
        finally {
            runStore.save(
                initial.state(),
                InvestigationStateMachine.apply(
                    initial.state(),
                    new InvestigationCommand.Failed("Test reconciliation completed."),
                    NOW.plusSeconds(2)
                )
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "investigation_runs",
        "investigation_run_events",
        "audit_events",
        "investigation_workflow_bindings",
        "outbox_events"
    })
    void injectedFailureAtEveryInsertBoundaryRollsBackWholeHandoff(String table) {
        InvestigationCommand.Start command = start(UUID.randomUUID(), NOW, 4);
        installFailureTrigger(table);
        try {
            assertThatThrownBy(() -> repository.createOrLoad(
                command, InvestigationTestFixtures.context(command).initialIncident()
            )).isInstanceOf(PlatformProblemException.class);
        }
        finally {
            removeFailureTrigger(table);
        }
        assertThat(count("investigation_runs", "run_id", command.runId())).isZero();
        assertThat(count("investigation_run_events", "run_id", command.runId())).isZero();
        assertThat(count("audit_events", "resource_id", command.runId().toString())).isZero();
        assertThat(count("investigation_workflow_bindings", "run_id", command.runId())).isZero();
        assertThat(count("outbox_events", "aggregate_id", command.runId())).isZero();
    }

    private InvestigationCommand.Start start(UUID runId, Instant startedAt, int maxRounds) {
        return new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(maxRounds, 4, 20, 8_000),
            startedAt, NOW.plusSeconds(120)
        );
    }

    private void installFailureTrigger(String table) {
        admin.execute("CREATE OR REPLACE FUNCTION opsmind_test_fail_handoff() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected handoff failure'; END $$");
        admin.execute("CREATE TRIGGER zz_test_fail_handoff BEFORE INSERT ON " + table
            + " FOR EACH ROW EXECUTE FUNCTION opsmind_test_fail_handoff()");
    }

    private void removeFailureTrigger(String table) {
        admin.execute("DROP TRIGGER IF EXISTS zz_test_fail_handoff ON " + table);
        admin.execute("DROP FUNCTION IF EXISTS opsmind_test_fail_handoff()");
    }

    private int count(String table, String column, Object value) {
        return Objects.requireNonNull(admin.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
            Integer.class, value
        ));
    }

    private <T> T inTenant(UUID organizationId, UUID actorId, java.util.function.Supplier<T> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.apply(organizationId, actorId);
            return work.get();
        }));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
