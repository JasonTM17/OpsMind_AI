package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class IncidentListPlanHarnessTest {

    private static final Instant CURSOR_TIME = Instant.parse("2035-01-01T00:41:40Z");
    private static final UUID CURSOR_ID =
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

    @Test
    void provesBothProductionQueryShapesUseReadyV016Indexes() throws Exception {
        Assumptions.assumeTrue(
            "true".equals(System.getenv("OPSMIND_PHASE4_LIST_PLAN_ENABLED")),
            "The V016 plan harness is invoked only by the disposable database gate."
        );
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        seedHighCardinalityRows(environment);
        assertIndexesReady(environment);

        try (Connection connection = DriverManager.getConnection(
            environment.jdbcUrl(), environment.appUser(), environment.appPassword()
        )) {
            connection.setAutoCommit(false);
            applyTenantContext(connection);
            validatePlan(connection, null, "incident_list_order_idx", false);
            validatePlan(
                connection,
                IncidentStatus.OPEN,
                "incident_list_status_order_idx",
                true
            );
            connection.rollback();
        }
        System.out.println("V016IncidentListPlanResult=PASS");
    }

    private void validatePlan(
        Connection connection,
        IncidentStatus status,
        String expectedIndex,
        boolean filtered
    ) throws Exception {
        IncidentListQuery.Prepared query = IncidentListQuery.build(
            TENANT_A,
            PROJECT_A,
            status,
            new IncidentListPageToken.Cursor(CURSOR_TIME, CURSOR_ID),
            26
        );
        String json = explain(connection, query);
        IncidentListPlanAssertions.validate(json, expectedIndex, filtered, true);
    }

    private String explain(Connection connection, IncidentListQuery.Prepared query) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query.sql()
        )) {
            for (int index = 0; index < query.parameters().size(); index++) {
                statement.setObject(index + 1, query.parameters().get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void applyTenantContext(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT public.opsmind_set_tenant_context(CAST(? AS uuid), CAST(? AS uuid))"
        )) {
            statement.setString(1, TENANT_A.toString());
            statement.setString(2, USER_A.toString());
            statement.executeQuery();
        }
    }

    private void seedHighCardinalityRows(PostgresIntegrationEnvironment environment) throws Exception {
        try (Connection connection = DriverManager.getConnection(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO incidents (
                    id, organization_id, project_id, title, description, severity, status,
                    created_by, updated_by, created_at, updated_at, version
                )
                SELECT md5('v016-incident-list-' || sample)::uuid,
                       'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
                       'aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
                       'V016 plan incident ' || sample,
                       'High-cardinality incident list plan fixture',
                       'SEV2', 'OPEN',
                       '11111111-1111-4111-8111-111111111111'::uuid,
                       '11111111-1111-4111-8111-111111111111'::uuid,
                       '2035-01-01T00:00:00Z'::timestamptz + sample * interval '1 second',
                       '2035-01-01T00:00:00Z'::timestamptz + sample * interval '1 second',
                       0
                  FROM generate_series(1, 5000) sample
                ON CONFLICT (id) DO NOTHING
                """);
            statement.executeUpdate("""
                UPDATE incidents incident
                   SET status = 'INVESTIGATING', version = 1
                  FROM generate_series(2, 5000, 2) sample
                 WHERE incident.id = md5('v016-incident-list-' || sample)::uuid
                   AND incident.status = 'OPEN'
                """);
            statement.execute("ANALYZE incidents");
        }
    }

    private void assertIndexesReady(PostgresIntegrationEnvironment environment) throws Exception {
        try (Connection connection = DriverManager.getConnection(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
            SELECT count(*)
              FROM pg_class class
              JOIN pg_index index_row ON index_row.indexrelid = class.oid
             WHERE class.relnamespace = 'public'::regnamespace
               AND class.relname IN ('incident_list_order_idx', 'incident_list_status_order_idx')
               AND index_row.indisvalid
               AND index_row.indisready
            """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
        }
    }
}
