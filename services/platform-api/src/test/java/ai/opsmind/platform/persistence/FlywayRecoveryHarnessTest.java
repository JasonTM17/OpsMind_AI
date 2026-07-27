package ai.opsmind.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class FlywayRecoveryHarnessTest {

    private static final String INCIDENT_INDEX = "incident_timeline_activity_order_idx";
    private static final String INVESTIGATION_INDEX = "investigation_run_events_activity_order_idx";

    @Test
    void repairsFailedV009AfterCapturingHistoryAndBothExactIndexes() throws Exception {
        Assumptions.assumeTrue(
            "true".equals(System.getenv("OPSMIND_PHASE4B_RECOVERY_ENABLED")),
            "The recovery harness is invoked only by the disposable database gate."
        );

        DatabaseSettings settings = DatabaseSettings.fromEnvironment();
        try (Connection connection = DriverManager.getConnection(
            settings.url(), settings.username(), settings.password()
        )) {
            connection.setAutoCommit(true);
            assertThat(successfulVersion(connection)).isEqualTo("8");
            createInvalidInvestigationIndex(connection);

            FluentConfiguration flywayConfiguration = Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .target("9");
            PostgreSQLConfigurationExtension postgresql =
                flywayConfiguration.getConfigurationExtension(
                    PostgreSQLConfigurationExtension.class
                );
            postgresql.setTransactionalLock(false);
            assertThat(postgresql.isTransactionalLock()).isFalse();
            Flyway flyway = flywayConfiguration.load();

            boolean migrationFailed = false;
            try {
                flyway.migrate();
            } catch (FlywayException expected) {
                migrationFailed = true;
            }
            assertThat(migrationFailed).isTrue();

            List<String> failedHistory = failedV009History(connection);
            List<String> catalogBeforeDrop = exactIndexCatalog(connection);
            assertThat(failedHistory).isNotEmpty();
            assertThat(catalogBeforeDrop).containsExactlyInAnyOrder(
                INCIDENT_INDEX + ":true", INVESTIGATION_INDEX + ":false"
            );
            System.out.printf(
                "V009RecoveryFailedHistory=%s%nV009RecoveryIndexCatalog=%s%n",
                String.join(",", failedHistory), String.join(",", catalogBeforeDrop)
            );

            dropExactIndexesConcurrently(connection);
            flyway.repair();
            assertThat(failedV009History(connection)).isEmpty();
            long successfulRetryStarted = System.nanoTime();
            flyway.migrate();
            double successfulRetryDurationMs =
                (System.nanoTime() - successfulRetryStarted) / 1_000_000.0;
            assertThat(successfulVersion(connection)).isEqualTo("9");
            assertThat(exactIndexCatalog(connection)).containsExactlyInAnyOrder(
                INCIDENT_INDEX + ":true", INVESTIGATION_INDEX + ":true"
            );
            System.out.printf(
                "V009SuccessfulRetryDurationMs=%.3f%nV009FlywayRecovery=PASS%n",
                successfulRetryDurationMs
            );
        }
    }

    private static void createInvalidInvestigationIndex(Connection connection) throws SQLException {
        boolean invalidBuildFailed = false;
        try {
            execute(connection, "CREATE UNIQUE INDEX CONCURRENTLY " + INVESTIGATION_INDEX
                + " ON investigation_run_events (organization_id)");
        } catch (SQLException expected) {
            invalidBuildFailed = true;
        }
        assertThat(invalidBuildFailed).isTrue();
        assertThat(exactIndexCatalog(connection)).containsExactly(
            INVESTIGATION_INDEX + ":false"
        );
    }

    private static void dropExactIndexesConcurrently(Connection connection) throws SQLException {
        execute(connection, "DROP INDEX CONCURRENTLY public." + INCIDENT_INDEX);
        execute(connection, "DROP INDEX CONCURRENTLY public." + INVESTIGATION_INDEX);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String successfulVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
            "SELECT max(version::integer)::text FROM flyway_schema_history WHERE success"
        )) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static List<String> failedV009History(Connection connection) throws SQLException {
        return queryLines(connection,
            "SELECT installed_rank || ':' || coalesce(version, '') || ':' || success "
                + "FROM flyway_schema_history WHERE version = '9' AND NOT success ORDER BY installed_rank"
        );
    }

    private static List<String> exactIndexCatalog(Connection connection) throws SQLException {
        return queryLines(connection,
            "SELECT class.relname || ':' || index.indisvalid FROM pg_class class "
                + "JOIN pg_index index ON index.indexrelid = class.oid "
                + "WHERE class.relnamespace = 'public'::regnamespace "
                + "AND class.relname IN ('" + INCIDENT_INDEX + "', '" + INVESTIGATION_INDEX + "') "
                + "ORDER BY class.relname"
        );
    }

    private static List<String> queryLines(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private record DatabaseSettings(String url, String username, String password) {
        static DatabaseSettings fromEnvironment() {
            return new DatabaseSettings(
                required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD")
            );
        }

        private static String required(String name) {
            String value = System.getenv(name);
            assertThat(value).as(name + " must be set for the recovery harness").isNotBlank();
            return value;
        }
    }
}
