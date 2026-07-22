package ai.opsmind.platform.audit;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ai.opsmind.platform.tenancy.TenantContextSql;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresRoleDataSource;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE4_DB_INTEGRATION", matches = "true")
class AuditLedgerIntegrationTest {

    @Test
    void computesLinearTenantChainAndRejectsForgeryAndMutation() throws Exception {
        try (AuditDatabaseContext context = AuditDatabaseContext.open()) {
            assertThatThrownBy(() -> insertAudit(
                context.firstJdbc,
                event(0),
                USER_A,
                operation(0),
                false
            )).isInstanceOf(DataAccessException.class);

            context.inTenant(context.firstTransactions, context.firstTenantContext, () -> {
                prepareSource(context.firstJdbc, event(1), operation(1));
                insertAudit(context.firstJdbc, event(1), USER_A, operation(1), false);
            });
            context.inTenant(context.firstTransactions, context.firstTenantContext, () ->
                prepareSource(context.firstJdbc, event(3), operation(3))
            );
            assertThatThrownBy(() -> context.inTenant(
                context.firstTransactions,
                context.firstTenantContext,
                () -> insertAudit(context.firstJdbc, event(3), USER_B, operation(3), false)
            )).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("bound tenant, actor");

            assertSemanticForgeryRejected(context);
            assertRuntimeCannotForgeChainOrGlobalSequence(context);
            appendConcurrently(context);
            List<AuditRow> rows = context.readTenantRows();
            assertThat(rows).hasSize(3);
            assertLinearAndRecomputable(context, rows);

            assertImmutability(context);
        }
    }

    private static void assertSemanticForgeryRejected(AuditDatabaseContext context) {
        context.inTenant(context.firstTransactions, context.firstTenantContext, () ->
            prepareSource(context.firstJdbc, event(8), operation(8))
        );
        String forgedPayload = auditPayload(event(8), USER_A, operation(8))
            .replace(PROJECT_A.toString(), PROJECT_B.toString());
        assertThatThrownBy(() -> context.inTenant(
            context.firstTransactions,
            context.firstTenantContext,
            () -> insertAuditPayload(
                context.firstJdbc, event(8), USER_A, operation(8), forgedPayload
            )
        )).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("authoritative timeline event");
    }

    private static void assertRuntimeCannotForgeChainOrGlobalSequence(
        AuditDatabaseContext context
    ) {
        context.inTenant(context.firstTransactions, context.firstTenantContext, () ->
            prepareSource(context.firstJdbc, event(2), operation(2))
        );
        assertThatThrownBy(() -> context.inTenant(
            context.firstTransactions,
            context.firstTenantContext,
            () -> insertAudit(context.firstJdbc, event(2), USER_A, operation(2), true)
        )).isInstanceOf(DataAccessException.class);
        context.inTenant(context.firstTransactions, context.firstTenantContext, () ->
            prepareSource(context.firstJdbc, event(9), operation(9))
        );
        assertThatThrownBy(() -> context.inTenant(
            context.firstTransactions,
            context.firstTenantContext,
            () -> insertAuditWithGlobalSequence(context.firstJdbc)
        )).isInstanceOf(DataAccessException.class);
    }

    private static void appendConcurrently(AuditDatabaseContext context) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> context.concurrentAppend(
                context.firstTransactions,
                context.firstTenantContext,
                context.firstJdbc,
                event(4),
                operation(4),
                ready,
                start
            ));
            Future<?> second = executor.submit(() -> context.concurrentAppend(
                context.secondTransactions,
                context.secondTenantContext,
                context.secondJdbc,
                event(5),
                operation(5),
                ready,
                start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertLinearAndRecomputable(
        AuditDatabaseContext context,
        List<AuditRow> rows
    ) {
        byte[] previousDigest = null;
        long expectedTenantSequence = 1;
        for (AuditRow row : rows) {
            assertThat(row.tenantSequence()).isEqualTo(expectedTenantSequence++);
            assertThat(row.schemaVersion()).isEqualTo("incident-audit-v1");
            assertThat(row.previousDigest()).isEqualTo(previousDigest);
            assertThat(row.eventDigest()).hasSize(32);
            assertThat(context.adminJdbc.queryForObject(
                "SELECT event_digest = public.opsmind_compute_audit_digest(" +
                    "tenant_sequence_no, schema_version, event_id, organization_id, actor_id, " +
                    "action, resource_type, resource_id, correlation_id, occurred_at, payload, " +
                    "previous_digest) FROM audit_events WHERE sequence_no = ?",
                Boolean.class,
                row.globalSequence()
            )).isTrue();
            previousDigest = row.eventDigest();
        }
    }

    private static void assertImmutability(AuditDatabaseContext context) {
        assertThatThrownBy(() -> context.firstJdbc.queryForObject(
            "SELECT count(*) FROM audit_events", Integer.class
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> context.inTenant(
            context.firstTransactions,
            context.firstTenantContext,
            () -> context.firstJdbc.update(
                "UPDATE audit_events SET action = 'incident.created' WHERE organization_id = ?",
                TENANT_A
            )
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> context.adminJdbc.update(
            "DELETE FROM audit_events WHERE organization_id = ?",
            TENANT_A
        )).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> context.adminJdbc.execute(
            "TRUNCATE TABLE audit_events"
        )).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("append-only");
    }

    private static void insertAudit(
        JdbcTemplate jdbc,
        UUID eventId,
        UUID actorId,
        UUID correlationId,
        boolean forgedChain
    ) {
        if (forgedChain) {
            jdbc.update(
                "INSERT INTO audit_events "
                    + "(event_id, organization_id, actor_id, action, resource_type, resource_id, "
                    + "correlation_id, occurred_at, payload, schema_version, tenant_sequence_no, "
                    + "previous_digest, event_digest) VALUES (?, ?, ?, "
                    + "'INCIDENT_CREATED', 'incident', ?, ?, "
                    + "CAST('2030-01-01T00:00:00Z' AS timestamptz), CAST(? AS jsonb), "
                    + "'incident-audit-v1', 999, decode(repeat('aa', 32), 'hex'), "
                    + "decode(repeat('bb', 32), 'hex'))",
                eventId,
                TENANT_A,
                actorId,
                resource(eventId).toString(),
                correlationId,
                auditPayload(eventId, actorId, correlationId)
            );
            return;
        }
        insertAuditPayload(
            jdbc,
            eventId,
            actorId,
            correlationId,
            auditPayload(eventId, actorId, correlationId)
        );
    }

    private static void insertAuditPayload(
        JdbcTemplate jdbc,
        UUID eventId,
        UUID actorId,
        UUID correlationId,
        String payload
    ) {
        jdbc.update(
            "INSERT INTO audit_events "
                + "(event_id, organization_id, actor_id, action, resource_type, resource_id, "
                + "correlation_id, occurred_at, payload, schema_version) VALUES (?, ?, ?, "
                + "'INCIDENT_CREATED', 'incident', ?, ?, "
                + "CAST('2030-01-01T00:00:00Z' AS timestamptz), CAST(? AS jsonb), "
                + "'incident-audit-v1')",
            eventId,
            TENANT_A,
            actorId,
            resource(eventId).toString(),
            correlationId,
            payload
        );
    }

    private static void insertAuditWithGlobalSequence(JdbcTemplate jdbc) {
        UUID eventId = event(9);
        UUID correlationId = operation(9);
        jdbc.update(
            "INSERT INTO audit_events "
                + "(sequence_no, event_id, organization_id, actor_id, action, schema_version, "
                + "resource_type, resource_id, correlation_id, occurred_at, payload) "
                + "OVERRIDING SYSTEM VALUE VALUES (999999, ?, ?, ?, "
                + "'INCIDENT_CREATED', 'incident-audit-v1', 'incident', ?, ?, "
                + "CAST('2030-01-01T00:00:00Z' AS timestamptz), CAST(? AS jsonb))",
            eventId,
            TENANT_A,
            USER_A,
            resource(eventId).toString(),
            correlationId,
            auditPayload(eventId, USER_A, correlationId)
        );
    }

    private static String auditPayload(UUID eventId, UUID actorId, UUID operationId) {
        return "{\"eventId\":\"" + eventId + "\","
            + "\"organizationId\":\"" + TENANT_A + "\","
            + "\"projectId\":\"" + PROJECT_A + "\","
            + "\"incidentId\":\"" + resource(eventId) + "\",\"incidentVersion\":0,"
            + "\"eventType\":\"INCIDENT_CREATED\","
            + "\"actorId\":\"" + actorId + "\","
            + "\"operationId\":\"" + operationId + "\","
            + "\"occurredAt\":\"2030-01-01T00:00:00Z\",\"reason\":\"triage\","
            + "\"fromStatus\":null,\"toStatus\":\"OPEN\","
            + "\"rootCause\":null,\"resolutionSummary\":null}";
    }

    private static void prepareSource(JdbcTemplate jdbc, UUID eventId, UUID operationId) {
        UUID incidentId = resource(eventId);
        jdbc.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                + "status, created_by, updated_by, created_at, updated_at, version) VALUES "
                + "(?, ?, ?, 'Audit source', 'Authoritative audit test source.', 'SEV4', "
                + "'OPEN', ?, ?, CAST('2030-01-01T00:00:00Z' AS timestamptz), "
                + "CAST('2030-01-01T00:00:00Z' AS timestamptz), 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A
        );
        jdbc.update(
            "INSERT INTO incident_timeline_events (event_id, organization_id, project_id, "
                + "incident_id, incident_version, event_kind, actor_id, operation_id, "
                + "external_trace_id, reason, payload, occurred_at) VALUES "
                + "(?, ?, ?, ?, 0, 'INCIDENT_CREATED', ?, ?, 'phase4-audit-trace', "
                + "'triage', CAST(? AS jsonb), CAST('2030-01-01T00:00:00Z' AS timestamptz))",
            eventId, TENANT_A, PROJECT_A, incidentId, USER_A, operationId,
            auditPayload(eventId, USER_A, operationId)
        );
    }

    private static UUID resource(UUID eventId) {
        return UUID.nameUUIDFromBytes(
            ("phase4-audit-resource:" + eventId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static UUID event(int value) {
        return UUID.fromString("4b10000" + value + "-4444-4444-8444-444444444444");
    }

    private static UUID operation(int value) {
        return UUID.fromString("4b20000" + value + "-4444-4444-8444-444444444444");
    }

    private record AuditRow(
        long globalSequence,
        long tenantSequence,
        String schemaVersion,
        UUID eventId,
        byte[] previousDigest,
        byte[] eventDigest
    ) {
    }

    private static final class AuditDatabaseContext implements AutoCloseable {
        private final HikariDataSource firstDataSource;
        private final HikariDataSource secondDataSource;
        private final JdbcTemplate firstJdbc;
        private final JdbcTemplate secondJdbc;
        private final JdbcTemplate adminJdbc;
        private final TransactionTemplate firstTransactions;
        private final TransactionTemplate secondTransactions;
        private final TenantContextSql firstTenantContext;
        private final TenantContextSql secondTenantContext;

        private AuditDatabaseContext(
            PostgresIntegrationEnvironment environment,
            HikariDataSource firstDataSource,
            HikariDataSource secondDataSource
        ) {
            this.firstDataSource = firstDataSource;
            this.secondDataSource = secondDataSource;
            this.firstJdbc = new JdbcTemplate(firstDataSource);
            this.secondJdbc = new JdbcTemplate(secondDataSource);
            this.firstTransactions = PostgresRoleDataSource.transactions(firstDataSource);
            this.secondTransactions = PostgresRoleDataSource.transactions(secondDataSource);
            this.firstTenantContext = new TenantContextSql(firstJdbc);
            this.secondTenantContext = new TenantContextSql(secondJdbc);
            this.adminJdbc = new JdbcTemplate(new DriverManagerDataSource(
                environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
            ));
        }

        static AuditDatabaseContext open() throws SQLException {
            PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
            PostgresTenantFixtures.seed(environment);
            HikariDataSource first = PostgresRoleDataSource.open(
                "phase4-audit-first", environment, environment.appUser(), environment.appPassword()
            );
            try {
                HikariDataSource second = PostgresRoleDataSource.open(
                    "phase4-audit-second", environment, environment.appUser(), environment.appPassword()
                );
                return new AuditDatabaseContext(environment, first, second);
            }
            catch (RuntimeException exception) {
                first.close();
                throw exception;
            }
        }

        void inTenant(
            TransactionTemplate transactions,
            TenantContextSql tenantContext,
            Runnable action
        ) {
            transactions.executeWithoutResult(status -> {
                tenantContext.apply(TENANT_A, USER_A);
                action.run();
            });
        }

        void concurrentAppend(
            TransactionTemplate transactions,
            TenantContextSql tenantContext,
            JdbcTemplate jdbc,
            UUID eventId,
            UUID operationId,
            CountDownLatch ready,
            CountDownLatch start
        ) {
            inTenant(transactions, tenantContext, () -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent audit start timed out.");
                    }
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Concurrent audit append interrupted.", exception);
                }
                prepareSource(jdbc, eventId, operationId);
                insertAudit(jdbc, eventId, USER_A, operationId, false);
            });
        }

        List<AuditRow> readTenantRows() {
            return adminJdbc.query(
                "SELECT sequence_no, tenant_sequence_no, schema_version, event_id, "
                    + "previous_digest, event_digest "
                    + "FROM audit_events WHERE organization_id = ? "
                    + "ORDER BY tenant_sequence_no",
                (resultSet, rowNumber) -> new AuditRow(
                    resultSet.getLong("sequence_no"),
                    resultSet.getLong("tenant_sequence_no"),
                    resultSet.getString("schema_version"),
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getBytes("previous_digest"),
                    resultSet.getBytes("event_digest")
                ),
                TENANT_A
            );
        }

        @Override
        public void close() {
            secondDataSource.close();
            firstDataSource.close();
        }
    }
}
