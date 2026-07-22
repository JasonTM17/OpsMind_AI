package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

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
class IncidentControlPlaneIntegrationTest {

    private static final UUID INCIDENT =
        UUID.fromString("4a000001-4444-4444-8444-444444444444");
    private static final UUID WRONG_PROJECT_INCIDENT =
        UUID.fromString("4a000002-4444-4444-8444-444444444444");
    private static final UUID SEMANTIC_FORGERY_INCIDENT =
        UUID.fromString("4a000003-4444-4444-8444-444444444444");

    @Test
    void enforcesTenantProjectTransitionVersionAndAppendOnlyBoundaries() throws SQLException {
        try (IncidentDatabaseContext context = IncidentDatabaseContext.open()) {
            assertThat(context.appJdbc.queryForObject(
                "SELECT count(*) FROM incidents", Integer.class
            )).isZero();
            assertThatThrownBy(() -> context.appJdbc.update(
                insertIncidentSql(),
                INCIDENT, TENANT_A, PROJECT_A, USER_A, USER_A
            )).isInstanceOf(DataAccessException.class);

            context.inTenant(TENANT_A, USER_A, () -> {
                assertThat(context.appJdbc.update(
                    insertIncidentSql(),
                    INCIDENT, TENANT_A, PROJECT_A, USER_A, USER_A
                )).isEqualTo(1);
                appendTimeline(context.appJdbc, 0, "INCIDENT_CREATED", operation(0));
            });

            assertThat(context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.queryForObject(
                    "SELECT count(*) FROM incidents WHERE id = ? AND project_id = ?",
                    Integer.class,
                    INCIDENT,
                    PROJECT_A
                )
            )).isEqualTo(1);
            assertThat(context.inTenant(TENANT_B, USER_B, () ->
                context.appJdbc.queryForObject(
                    "SELECT count(*) FROM incidents WHERE id = ?",
                    Integer.class,
                    INCIDENT
                )
            )).isZero();

            assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.update(
                    insertIncidentSql(),
                    WRONG_PROJECT_INCIDENT, TENANT_A, PROJECT_B, USER_A, USER_A
                )
            )).isInstanceOf(DataAccessException.class);

            transition(context, 0, "INVESTIGATING", null, null, operation(1));
            assertThat(context.inTenant(TENANT_A, USER_A, () -> context.appJdbc.update(
                "UPDATE incidents SET status = 'MITIGATING', updated_by = ?, "
                    + "updated_at = clock_timestamp(), version = version + 1 "
                    + "WHERE id = ? AND version = 0",
                USER_A,
                INCIDENT
            ))).isZero();

            assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.update(
                    "UPDATE incidents SET status = 'RESOLVED', updated_by = ?, "
                        + "updated_at = clock_timestamp(), version = 2 WHERE id = ?",
                    USER_A,
                    INCIDENT
                )
            )).isInstanceOf(DataAccessException.class);
            transition(
                context,
                1,
                "RESOLVED",
                "A saturated dependency pool exhausted callers.",
                "Bound concurrency and drained the queue.",
                operation(2)
            );
            transition(context, 2, "INVESTIGATING", null, null, operation(3));

            assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.update(
                    "UPDATE incidents SET status = 'CLOSED', updated_by = ?, "
                        + "updated_at = clock_timestamp(), version = 4 WHERE id = ?",
                    USER_A,
                    INCIDENT
                )
            )).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("illegal incident status transition");
            assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.update(
                    "UPDATE incidents SET status = 'MITIGATING', updated_by = ?, "
                        + "updated_at = clock_timestamp(), version = 7 WHERE id = ?",
                    USER_A,
                    INCIDENT
                )
            )).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("increase by exactly one");
            assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
                appendTimeline(context.appJdbc, 4, "INCIDENT_STATUS_TRANSITIONED", operation(4))
            )).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("must match the current incident version");

            assertThat(context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.queryForMap(
                    "SELECT status, version, root_cause, resolution_summary "
                        + "FROM incidents WHERE id = ?",
                    INCIDENT
                )
            )).containsEntry("status", "INVESTIGATING")
                .containsEntry("version", 3L)
                .containsEntry("root_cause", null)
                .containsEntry("resolution_summary", null);
            assertThat(context.inTenant(TENANT_A, USER_A, () ->
                context.appJdbc.queryForObject(
                    "SELECT count(*) FROM incident_timeline_events WHERE incident_id = ?",
                    Integer.class,
                    INCIDENT
                )
            )).isEqualTo(4);

            context.inTenant(TENANT_A, USER_A, () -> context.appJdbc.update(
                insertIncidentSql(),
                SEMANTIC_FORGERY_INCIDENT, TENANT_A, PROJECT_A, USER_A, USER_A
            ));
            assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
                appendTimeline(
                    context.appJdbc,
                    SEMANTIC_FORGERY_INCIDENT,
                    0,
                    "INCIDENT_CREATED",
                    operation(6),
                    PROJECT_B
                )
            )).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("authoritative incident event");
            context.inTenant(TENANT_A, USER_A, () -> appendTimeline(
                context.appJdbc,
                SEMANTIC_FORGERY_INCIDENT,
                0,
                "INCIDENT_CREATED",
                operation(7),
                PROJECT_A
            ));

            assertTimelineIsImmutable(context);
            assertThatThrownBy(() -> context.dispatcherJdbc.queryForObject(
                "SELECT count(*) FROM incidents", Integer.class
            )).isInstanceOf(DataAccessException.class);
        }
    }

    private static void transition(
        IncidentDatabaseContext context,
        long expectedVersion,
        String targetStatus,
        String rootCause,
        String resolutionSummary,
        UUID operationId
    ) {
        context.inTenant(TENANT_A, USER_A, () -> {
            int changed = context.appJdbc.update(
                "UPDATE incidents SET status = ?, root_cause = ?, resolution_summary = ?, "
                    + "updated_by = ?, updated_at = clock_timestamp(), version = version + 1 "
                    + "WHERE id = ? AND version = ?",
                targetStatus,
                rootCause,
                resolutionSummary,
                USER_A,
                INCIDENT,
                expectedVersion
            );
            assertThat(changed).isEqualTo(1);
            appendTimeline(
                context.appJdbc,
                expectedVersion + 1,
                "INCIDENT_STATUS_TRANSITIONED",
                operationId
            );
        });
    }

    private static void appendTimeline(
        JdbcTemplate jdbc,
        long incidentVersion,
        String eventKind,
        UUID operationId
    ) {
        appendTimeline(jdbc, INCIDENT, incidentVersion, eventKind, operationId, PROJECT_A);
    }

    private static void appendTimeline(
        JdbcTemplate jdbc,
        UUID incidentId,
        long incidentVersion,
        String eventKind,
        UUID operationId,
        UUID payloadProjectId
    ) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO incident_timeline_events "
                + "(event_id, organization_id, project_id, incident_id, incident_version, "
                + "event_kind, actor_id, operation_id, external_trace_id, reason, payload, occurred_at) "
                + "SELECT ?, stored.organization_id, stored.project_id, stored.id, ?, "
                + "?, stored.updated_by, ?, 'phase4-trace-id', "
                + "'Database integration transition', jsonb_build_object("
                + "'eventId', ?::text, 'organizationId', stored.organization_id::text, "
                + "'projectId', ?::text, 'incidentId', stored.id::text, "
                + "'incidentVersion', stored.version, 'eventType', ?, "
                + "'actorId', stored.updated_by::text, 'operationId', ?::text, "
                + "'occurredAt', stored.updated_at, 'reason', 'Database integration transition', "
                + "'fromStatus', CASE WHEN stored.version = 0 THEN NULL ELSE ("
                + "SELECT prior.payload ->> 'toStatus' FROM incident_timeline_events prior "
                + "WHERE prior.organization_id = stored.organization_id "
                + "AND prior.project_id = stored.project_id AND prior.incident_id = stored.id "
                + "AND prior.incident_version = stored.version - 1) END, "
                + "'toStatus', stored.status, 'rootCause', stored.root_cause, "
                + "'resolutionSummary', stored.resolution_summary), stored.updated_at "
                + "FROM incidents stored WHERE stored.id = ? AND stored.organization_id = ? "
                + "AND stored.project_id = ?",
            eventId,
            incidentVersion,
            eventKind,
            operationId,
            eventId,
            payloadProjectId,
            eventKind,
            operationId,
            incidentId,
            TENANT_A,
            PROJECT_A
        );
    }

    private static void assertTimelineIsImmutable(IncidentDatabaseContext context) {
        assertThatThrownBy(() -> context.inTenant(TENANT_A, USER_A, () ->
            context.appJdbc.update(
                "UPDATE incident_timeline_events SET reason = 'changed' WHERE incident_id = ?",
                INCIDENT
            )
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> context.adminJdbc.update(
            "DELETE FROM incident_timeline_events WHERE incident_id = ?",
            INCIDENT
        )).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> context.adminJdbc.execute(
            "TRUNCATE TABLE incident_timeline_events"
        )).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("append-only");
    }

    private static String insertIncidentSql() {
        return "INSERT INTO incidents "
            + "(id, organization_id, project_id, title, description, severity, status, "
            + "created_by, updated_by, version) "
            + "VALUES (?, ?, ?, 'Checkout latency', 'Requests exceed the latency SLO.', "
            + "'SEV2', 'OPEN', ?, ?, 0)";
    }

    private static UUID operation(int value) {
        return UUID.fromString("4a10000" + value + "-4444-4444-8444-444444444444");
    }

    private static final class IncidentDatabaseContext implements AutoCloseable {
        private final HikariDataSource appDataSource;
        private final HikariDataSource dispatcherDataSource;
        private final JdbcTemplate appJdbc;
        private final JdbcTemplate dispatcherJdbc;
        private final JdbcTemplate adminJdbc;
        private final TransactionTemplate transactions;
        private final TenantContextSql tenantContext;

        private IncidentDatabaseContext(
            PostgresIntegrationEnvironment environment,
            HikariDataSource appDataSource,
            HikariDataSource dispatcherDataSource
        ) {
            this.appDataSource = appDataSource;
            this.dispatcherDataSource = dispatcherDataSource;
            this.appJdbc = new JdbcTemplate(appDataSource);
            this.dispatcherJdbc = new JdbcTemplate(dispatcherDataSource);
            DriverManagerDataSource adminDataSource = new DriverManagerDataSource(
                environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
            );
            this.adminJdbc = new JdbcTemplate(adminDataSource);
            this.transactions = PostgresRoleDataSource.transactions(appDataSource);
            this.tenantContext = new TenantContextSql(appJdbc);
        }

        static IncidentDatabaseContext open() throws SQLException {
            PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
            PostgresTenantFixtures.seed(environment);
            HikariDataSource app = PostgresRoleDataSource.open(
                "phase4-incident-app", environment, environment.appUser(), environment.appPassword()
            );
            try {
                HikariDataSource dispatcher = PostgresRoleDataSource.open(
                    "phase4-incident-dispatcher",
                    environment,
                    environment.dispatcherUser(),
                    environment.dispatcherPassword()
                );
                return new IncidentDatabaseContext(environment, app, dispatcher);
            }
            catch (RuntimeException exception) {
                app.close();
                throw exception;
            }
        }

        void inTenant(UUID tenantId, UUID actorId, Runnable action) {
            transactions.executeWithoutResult(status -> {
                tenantContext.apply(tenantId, actorId);
                action.run();
            });
        }

        <T> T inTenant(UUID tenantId, UUID actorId, java.util.function.Supplier<T> action) {
            return transactions.execute(status -> {
                tenantContext.apply(tenantId, actorId);
                return action.get();
            });
        }

        @Override
        public void close() {
            dispatcherDataSource.close();
            appDataSource.close();
        }
    }
}
