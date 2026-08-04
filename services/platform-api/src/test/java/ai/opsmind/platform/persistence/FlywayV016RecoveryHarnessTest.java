package ai.opsmind.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
        Flyway flyway = flyway(
            settings.url(), settings.adminUsername(), settings.adminPassword(), "15"
        );
        flyway.migrate();

        try (Connection connection = settings.openAdmin()) {
            connection.setAutoCommit(true);
            assertThat(successfulVersion(connection)).isEqualTo("15");
            assertRestrictedMigrationRole(connection, settings.migrationUsername());
            grantMigrationResolverRoles(connection, settings.migrationUsername());
            transferV016Ownership(connection, settings.migrationUsername());
            seedDuplicateOrganizationRows(connection);
            createInvalidStatusIndex(connection);

            flyway = flyway(
                settings.url(), settings.migrationUsername(), settings.migrationPassword(), "16"
            );
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

            LegacyIncidentSnapshot legacy = seedLegacyIncident(connection);
            transferV017Ownership(connection, settings.migrationUsername());
            flyway = flyway(
                settings.url(), settings.migrationUsername(), settings.migrationPassword(), "17"
            );
            flyway.migrate();
            assertThat(successfulVersion(connection)).isEqualTo("17");
            assertThat(legacyPayload(connection)).isEqualTo(legacy.payload());
            assertThat(legacyDigest(connection)).isEqualTo(legacy.digest());
            appendLegacyTransitionAfterV017(connection);
            assertThat(legacyTimelineCount(connection)).isEqualTo(2);
            System.out.println("V017LegacyIncidentUpgrade=PASS");
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

    private static Flyway flyway(String url, String username, String password, String target) {
        FluentConfiguration configuration = Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .target(target);
        PostgreSQLConfigurationExtension postgresql = configuration.getConfigurationExtension(
            PostgreSQLConfigurationExtension.class
        );
        postgresql.setTransactionalLock(false);
        return configuration.load();
    }

    private static void assertRestrictedMigrationRole(
        Connection connection,
        String migrationUsername
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
                AND NOT rolreplication AND NOT rolbypassrls
            FROM pg_roles
            WHERE rolname = ?
            """)) {
            statement.setString(1, migrationUsername);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
        }
    }

    private static void grantMigrationResolverRoles(
        Connection connection,
        String migrationUsername
    ) throws SQLException {
        String quotedRole;
        try (PreparedStatement statement = connection.prepareStatement("SELECT quote_ident(?)")) {
            statement.setString(1, migrationUsername);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                quotedRole = result.getString(1);
            }
        }
        execute(connection, "GRANT opsmind_context_resolver, opsmind_dispatch_resolver TO "
            + quotedRole + " WITH INHERIT TRUE, SET TRUE");
        execute(connection, "GRANT USAGE, CREATE ON SCHEMA public TO "
            + "opsmind_context_resolver, opsmind_dispatch_resolver");
    }

    private static void transferV016Ownership(
        Connection connection,
        String migrationUsername
    ) throws SQLException {
        String quotedOwner;
        try (PreparedStatement statement = connection.prepareStatement("SELECT quote_ident(?)")) {
            statement.setString(1, migrationUsername);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                quotedOwner = result.getString(1);
            }
        }
        execute(connection, "ALTER TABLE public.incidents OWNER TO " + quotedOwner);
        execute(connection, "ALTER TABLE public.flyway_schema_history OWNER TO " + quotedOwner);
    }

    private static void transferV017Ownership(
        Connection connection,
        String migrationUsername
    ) throws SQLException {
        String quotedOwner;
        try (PreparedStatement statement = connection.prepareStatement("SELECT quote_ident(?)")) {
            statement.setString(1, migrationUsername);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                quotedOwner = result.getString(1);
            }
        }
        execute(connection, "ALTER TABLE public.incident_timeline_events OWNER TO " + quotedOwner);
        execute(connection, "ALTER TABLE public.audit_events OWNER TO " + quotedOwner);
        execute(connection, "ALTER FUNCTION public.opsmind_validate_incident_write() OWNER TO "
            + quotedOwner);
        execute(connection, "ALTER FUNCTION public.opsmind_validate_timeline_append() OWNER TO "
            + quotedOwner);
    }

    private static LegacyIncidentSnapshot seedLegacyIncident(Connection connection)
        throws SQLException {
        execute(connection, """
            INSERT INTO organizations (id, slug, name)
            VALUES ('17000000-0000-4000-8000-000000000001', 'v017-legacy', 'V017 Legacy')
            """);
        execute(connection, """
            INSERT INTO platform_users (id, issuer, subject, display_name)
            VALUES ('17000000-0000-4000-8000-000000000002',
                    'https://idp.example.test/opsmind', 'v017-legacy', 'V017 Legacy')
            """);
        execute(connection, """
            INSERT INTO organization_memberships (organization_id, user_id, role)
            VALUES ('17000000-0000-4000-8000-000000000001',
                    '17000000-0000-4000-8000-000000000002', 'SRE')
            """);
        execute(connection, """
            INSERT INTO projects (id, organization_id, slug, name)
            VALUES ('17000000-0000-4000-8000-000000000003',
                    '17000000-0000-4000-8000-000000000001', 'legacy', 'V017 Legacy')
            """);
        execute(connection, """
            INSERT INTO incidents (
                id, organization_id, project_id, title, description, severity, status,
                created_by, updated_by, created_at, updated_at, version
            ) VALUES ('17000000-0000-4000-8000-000000000004',
                '17000000-0000-4000-8000-000000000001',
                '17000000-0000-4000-8000-000000000003', 'Legacy incident',
                'Pre-V017 incident', 'SEV2', 'OPEN',
                '17000000-0000-4000-8000-000000000002',
                '17000000-0000-4000-8000-000000000002',
                '2030-01-01T00:00:00Z', '2030-01-01T00:00:00Z', 0)
            """);
        String payload = legacyCreatedPayload();
        execute(connection, "INSERT INTO incident_timeline_events ("
            + "event_id, organization_id, project_id, incident_id, incident_version, event_kind, "
            + "actor_id, operation_id, reason, payload, occurred_at) VALUES ("
            + "'17000000-0000-4000-8000-000000000005',"
            + "'17000000-0000-4000-8000-000000000001',"
            + "'17000000-0000-4000-8000-000000000003',"
            + "'17000000-0000-4000-8000-000000000004', 0, 'INCIDENT_CREATED',"
            + "'17000000-0000-4000-8000-000000000002',"
            + "'17000000-0000-4000-8000-000000000006', 'legacy create', CAST('"
            + payload + "' AS jsonb), '2030-01-01T00:00:00Z')");
        execute(connection, "INSERT INTO audit_events ("
            + "event_id, organization_id, actor_id, action, resource_type, resource_id, "
            + "correlation_id, occurred_at, payload, schema_version) VALUES ("
            + "'17000000-0000-4000-8000-000000000005',"
            + "'17000000-0000-4000-8000-000000000001',"
            + "'17000000-0000-4000-8000-000000000002', 'INCIDENT_CREATED', 'incident',"
            + "'17000000-0000-4000-8000-000000000004',"
            + "'17000000-0000-4000-8000-000000000006', '2030-01-01T00:00:00Z', CAST('"
            + payload + "' AS jsonb), 'incident-audit-v1')");
        return new LegacyIncidentSnapshot(payload, legacyDigest(connection));
    }

    private static void appendLegacyTransitionAfterV017(Connection connection) throws SQLException {
        execute(connection, "UPDATE incidents SET status = 'INVESTIGATING', version = 1, "
            + "updated_by = '17000000-0000-4000-8000-000000000002', "
            + "updated_at = '2030-01-01T00:01:00Z' "
            + "WHERE id = '17000000-0000-4000-8000-000000000004'");
        String payload = "{" +
            "\"eventId\":\"17000000-0000-4000-8000-000000000007\","
            + "\"organizationId\":\"17000000-0000-4000-8000-000000000001\","
            + "\"incidentId\":\"17000000-0000-4000-8000-000000000004\","
            + "\"projectId\":\"17000000-0000-4000-8000-000000000003\","
            + "\"incidentVersion\":1,\"eventType\":\"INCIDENT_STATUS_TRANSITIONED\","
            + "\"actorId\":\"17000000-0000-4000-8000-000000000002\","
            + "\"operationId\":\"17000000-0000-4000-8000-000000000008\","
            + "\"occurredAt\":\"2030-01-01T00:01:00Z\",\"reason\":\"legacy transition\","
            + "\"fromStatus\":\"OPEN\",\"toStatus\":\"INVESTIGATING\","
            + "\"rootCause\":null,\"resolutionSummary\":null}";
        execute(connection, "INSERT INTO incident_timeline_events ("
            + "event_id, organization_id, project_id, incident_id, incident_version, event_kind, "
            + "actor_id, operation_id, reason, payload, occurred_at) VALUES ("
            + "'17000000-0000-4000-8000-000000000007',"
            + "'17000000-0000-4000-8000-000000000001',"
            + "'17000000-0000-4000-8000-000000000003',"
            + "'17000000-0000-4000-8000-000000000004', 1, 'INCIDENT_STATUS_TRANSITIONED',"
            + "'17000000-0000-4000-8000-000000000002',"
            + "'17000000-0000-4000-8000-000000000008', 'legacy transition', CAST('"
            + payload + "' AS jsonb), '2030-01-01T00:01:00Z')");
    }

    private static String legacyCreatedPayload() {
        return "{" +
            "\"eventId\":\"17000000-0000-4000-8000-000000000005\","
            + "\"organizationId\":\"17000000-0000-4000-8000-000000000001\","
            + "\"incidentId\":\"17000000-0000-4000-8000-000000000004\","
            + "\"projectId\":\"17000000-0000-4000-8000-000000000003\","
            + "\"incidentVersion\":0,\"eventType\":\"INCIDENT_CREATED\","
            + "\"actorId\":\"17000000-0000-4000-8000-000000000002\","
            + "\"operationId\":\"17000000-0000-4000-8000-000000000006\","
            + "\"occurredAt\":\"2030-01-01T00:00:00Z\",\"reason\":\"legacy create\","
            + "\"fromStatus\":null,\"toStatus\":\"OPEN\","
            + "\"rootCause\":null,\"resolutionSummary\":null}";
    }

    private static String legacyPayload(Connection connection) throws SQLException {
        return queryLines(connection, "SELECT payload::text FROM incident_timeline_events "
            + "WHERE event_id = '17000000-0000-4000-8000-000000000005'").getFirst();
    }

    private static String legacyDigest(Connection connection) throws SQLException {
        return queryLines(connection, "SELECT encode(event_digest, 'hex') FROM audit_events "
            + "WHERE event_id = '17000000-0000-4000-8000-000000000005'").getFirst();
    }

    private static int legacyTimelineCount(Connection connection) throws SQLException {
        return Integer.parseInt(queryLines(connection, "SELECT count(*)::text "
            + "FROM incident_timeline_events WHERE incident_id = "
            + "'17000000-0000-4000-8000-000000000004'").getFirst());
    }

    private record LegacyIncidentSnapshot(String payload, String digest) {
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

    private record DatabaseSettings(
        String url,
        String migrationUsername,
        String migrationPassword,
        String adminUsername,
        String adminPassword
    ) {
        static DatabaseSettings fromEnvironment() {
            return new DatabaseSettings(
                required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"),
                required("POSTGRES_USER"),
                required("POSTGRES_PASSWORD")
            );
        }

        Connection openAdmin() throws SQLException {
            return DriverManager.getConnection(url, adminUsername, adminPassword);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            assertThat(value).as(name + " must be set").isNotBlank();
            return value;
        }
    }
}
