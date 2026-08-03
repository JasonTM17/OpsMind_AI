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

class FlywayV016RecoveryHarnessTest {

    private static final String ORDER_INDEX = "incident_list_order_idx";
    private static final String STATUS_INDEX = "incident_list_status_order_idx";

    @Test
    void repairsFailedV016AfterCapturingInvalidConcurrentIndex() throws Exception {
        Assumptions.assumeTrue(
            "true".equals(System.getenv("OPSMIND_PHASE4_LIST_RECOVERY_ENABLED")),
            "The V016 recovery harness is invoked only against a disposable database."
        );
        DatabaseSettings settings = DatabaseSettings.fromEnvironment();
        Flyway flyway = flyway(settings, "15");
        flyway.migrate();

        try (Connection connection = settings.open()) {
            connection.setAutoCommit(true);
            assertThat(successfulVersion(connection)).isEqualTo("15");
            seedDuplicateOrganizationRows(connection);
            createInvalidStatusIndex(connection);

            flyway = flyway(settings, "16");
            assertThatThrownByMigration(flyway);
            assertThat(failedHistory(connection)).isNotEmpty();
            assertThat(indexCatalog(connection)).containsExactlyInAnyOrder(
                ORDER_INDEX + ":true:true", STATUS_INDEX + ":false:false"
            );
            System.out.printf(
                "V016RecoveryFailedHistory=%s%nV016RecoveryIndexCatalog=%s%n",
                String.join(",", failedHistory(connection)),
                String.join(",", indexCatalog(connection))
            );

            dropIndexesConcurrently(connection);
            flyway.repair();
            assertThat(failedHistory(connection)).isEmpty();
            flyway.migrate();
            assertThat(successfulVersion(connection)).isEqualTo("16");
            assertThat(indexCatalog(connection)).containsExactlyInAnyOrder(
                ORDER_INDEX + ":true:true", STATUS_INDEX + ":true:true"
            );
            System.out.println("V016FlywayRecovery=PASS");
        }
    }

    private static void assertThatThrownByMigration(Flyway flyway) {
        boolean failed = false;
        try {
            flyway.migrate();
        }
        catch (FlywayException expected) {
            failed = true;
        }
        assertThat(failed).isTrue();
    }

    private static Flyway flyway(DatabaseSettings settings, String target) {
        FluentConfiguration configuration = Flyway.configure()
            .dataSource(settings.url(), settings.username(), settings.password())
            .locations("classpath:db/migration")
            .target(target);
        PostgreSQLConfigurationExtension postgresql = configuration.getConfigurationExtension(
            PostgreSQLConfigurationExtension.class
        );
        postgresql.setTransactionalLock(false);
        return configuration.load();
    }

    private static void seedDuplicateOrganizationRows(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO organizations (id, slug, name)
            VALUES ('16000000-0000-4000-8000-000000000001', 'v016-recovery', 'V016 Recovery')
            """);
        execute(connection, """
            INSERT INTO platform_users (id, issuer, subject, display_name)
            VALUES ('16000000-0000-4000-8000-000000000002',
                    'https://idp.example.test/opsmind', 'v016-recovery', 'V016 Recovery')
            """);
        execute(connection, """
            INSERT INTO organization_memberships (organization_id, user_id, role)
            VALUES ('16000000-0000-4000-8000-000000000001',
                    '16000000-0000-4000-8000-000000000002', 'SRE')
            """);
        execute(connection, """
            INSERT INTO projects (id, organization_id, slug, name)
            VALUES ('16000000-0000-4000-8000-000000000003',
                    '16000000-0000-4000-8000-000000000001', 'recovery', 'Recovery')
            """);
        execute(connection, """
            INSERT INTO incidents (
                id, organization_id, project_id, title, description, severity, status,
                created_by, updated_by, version
            ) VALUES
                ('16000000-0000-4000-8000-000000000004',
                 '16000000-0000-4000-8000-000000000001',
                 '16000000-0000-4000-8000-000000000003', 'First', 'Recovery fixture',
                 'SEV2', 'OPEN', '16000000-0000-4000-8000-000000000002',
                 '16000000-0000-4000-8000-000000000002', 0),
                ('16000000-0000-4000-8000-000000000005',
                 '16000000-0000-4000-8000-000000000001',
                 '16000000-0000-4000-8000-000000000003', 'Second', 'Recovery fixture',
                 'SEV2', 'OPEN', '16000000-0000-4000-8000-000000000002',
                 '16000000-0000-4000-8000-000000000002', 0)
            """);
    }

    private static void createInvalidStatusIndex(Connection connection) throws SQLException {
        boolean failed = false;
        try {
            execute(connection, "CREATE UNIQUE INDEX CONCURRENTLY " + STATUS_INDEX
                + " ON incidents (organization_id)");
        }
        catch (SQLException expected) {
            failed = true;
        }
        assertThat(failed).isTrue();
        assertThat(indexCatalog(connection)).containsExactly(STATUS_INDEX + ":false:false");
    }

    private static void dropIndexesConcurrently(Connection connection) throws SQLException {
        execute(connection, "DROP INDEX CONCURRENTLY public." + ORDER_INDEX);
        execute(connection, "DROP INDEX CONCURRENTLY public." + STATUS_INDEX);
    }

    private static String successfulVersion(Connection connection) throws SQLException {
        return queryLines(connection,
            "SELECT max(version::integer)::text FROM flyway_schema_history WHERE success"
        ).get(0);
    }

    private static List<String> failedHistory(Connection connection) throws SQLException {
        return queryLines(connection,
            "SELECT installed_rank || ':' || version || ':' || success "
                + "FROM flyway_schema_history WHERE version = '016' AND NOT success "
                + "ORDER BY installed_rank"
        );
    }

    private static List<String> indexCatalog(Connection connection) throws SQLException {
        return queryLines(connection,
            "SELECT class.relname || ':' || index_row.indisvalid || ':' || index_row.indisready "
                + "FROM pg_class class JOIN pg_index index_row ON index_row.indexrelid = class.oid "
                + "WHERE class.relnamespace = 'public'::regnamespace AND class.relname IN ('"
                + ORDER_INDEX + "', '" + STATUS_INDEX + "') ORDER BY class.relname"
        );
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> queryLines(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) rows.add(result.getString(1));
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

        Connection open() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            assertThat(value).as(name + " must be set").isNotBlank();
            return value;
        }
    }
}
