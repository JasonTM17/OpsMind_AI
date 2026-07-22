package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE3_DB_INTEGRATION", matches = "true")
class PlatformUserStatusVerifierIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String ISSUER = "https://idp.example.test/opsmind";
    private static final String SUBJECT = "phase3-status-user";

    @Test
    void activeStatusIsCheckedOnEveryRequestBoundary() throws SQLException {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        seedUser(environment);

        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(environment.jdbcUrl());
        configuration.setUsername(environment.appUser());
        configuration.setPassword(environment.appPassword());
        configuration.setMaximumPoolSize(1);

        try (HikariDataSource dataSource = new HikariDataSource(configuration)) {
            PlatformUserStatusVerifier verifier = new PlatformUserStatusVerifier(
                new JdbcTemplate(dataSource),
                new JdbcTransactionManager(dataSource)
            );
            OpsMindPrincipal principal = principal(SUBJECT);

            assertThat(verifier.requireActive(principal)).isEqualTo(USER_ID);
            setStatus(environment, "deprovisioned");
            assertThatThrownBy(() -> verifier.requireActive(principal))
                .isInstanceOfSatisfying(PlatformProblemException.class, problem -> {
                    assertThat(problem.status().value()).isEqualTo(403);
                    assertThat(problem.code()).isEqualTo("identity.deprovisioned");
                });
            assertThatThrownBy(() -> verifier.requireActive(principal("unknown-subject")))
                .isInstanceOfSatisfying(PlatformProblemException.class, problem ->
                    assertThat(problem.code()).isEqualTo("identity.not-provisioned")
                );
        }
    }

    private static OpsMindPrincipal principal(String subject) {
        return new OpsMindPrincipal(URI.create(ISSUER), subject, null, null, Set.of("project:read"));
    }

    private static void seedUser(PostgresIntegrationEnvironment environment) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO platform_users (id, issuer, subject, display_name, status)
                VALUES ('88888888-8888-4888-8888-888888888888',
                        'https://idp.example.test/opsmind',
                        'phase3-status-user', 'Phase 3 Status User', 'active')
                ON CONFLICT (id) DO UPDATE SET status = 'active'
                """);
        }
    }

    private static void setStatus(
        PostgresIntegrationEnvironment environment,
        String status
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ); PreparedStatement statement = connection.prepareStatement(
            "UPDATE platform_users SET status = ? WHERE id = ?"
        )) {
            statement.setString(1, status);
            statement.setObject(2, USER_ID);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Status fixture was not updated.");
            }
        }
    }
}
