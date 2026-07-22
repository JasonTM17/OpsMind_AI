package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.tenancy.TenantContextSql;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresRoleDataSource;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE4_DB_INTEGRATION", matches = "true")
class IncidentAuthorizationRevocationIntegrationTest {

    private static final UUID INCIDENT =
        UUID.fromString("4c000001-4444-4444-8444-444444444444");

    @Test
    void serializesRevocationAfterAnAlreadyAuthorizedMutation() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        try (HikariDataSource appDataSource = PostgresRoleDataSource.open(
            "phase4-revocation-app", environment, environment.appUser(), environment.appPassword()
        )) {
            JdbcTemplate app = new JdbcTemplate(appDataSource);
            JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
            ));
            TransactionTemplate transactions = PostgresRoleDataSource.transactions(appDataSource);
            JdbcIncidentAccessRepository access = new JdbcIncidentAccessRepository(
                app, new TenantContextSql(app)
            );
            CountDownLatch authorized = new CountDownLatch(1);
            CountDownLatch finishMutation = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> mutation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                    IncidentActor actor = access.requireAccess(
                        principal(), TENANT_A, PROJECT_A, IncidentAccessMode.MUTATE
                    );
                    authorized.countDown();
                    await(finishMutation);
                    app.update(
                        "INSERT INTO incidents (id, organization_id, project_id, title, description, "
                            + "severity, status, created_by, updated_by, version) VALUES "
                            + "(?, ?, ?, 'Revocation race', 'Authorization lock proof.', 'SEV4', "
                            + "'OPEN', ?, ?, 0)",
                        INCIDENT, TENANT_A, PROJECT_A, actor.id(), actor.id()
                    );
                }));
                if (!authorized.await(5, TimeUnit.SECONDS)) {
                    assertThat(mutation).as("authorization task completed before acquiring its lock")
                        .isDone();
                    mutation.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("Authorization lock was not acquired within five seconds.");
                }
                Future<Integer> revocation = executor.submit(() -> admin.update(
                    "UPDATE project_memberships SET status = 'suspended' "
                        + "WHERE organization_id = ? AND project_id = ? AND user_id = ?",
                    TENANT_A, PROJECT_A, USER_A
                ));

                assertThatThrownBy(() -> revocation.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                finishMutation.countDown();
                mutation.get(5, TimeUnit.SECONDS);
                assertThat(revocation.get(5, TimeUnit.SECONDS)).isEqualTo(1);

                assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                    access.requireAccess(principal(), TENANT_A, PROJECT_A, IncidentAccessMode.MUTATE)
                )).isInstanceOf(PlatformProblemException.class)
                    .extracting(error -> ((PlatformProblemException) error).status().value())
                    .isEqualTo(404);
            }
            finally {
                finishMutation.countDown();
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                admin.update(
                    "UPDATE project_memberships SET status = 'active' "
                        + "WHERE organization_id = ? AND project_id = ? AND user_id = ?",
                    TENANT_A, PROJECT_A, USER_A
                );
                admin.update("DELETE FROM incidents WHERE id = ?", INCIDENT);
            }
        }
    }

    private static OpsMindPrincipal principal() {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "phase3-operator-a",
            null,
            null,
            Set.of("incident:write")
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Mutation release timed out.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mutation interrupted.", exception);
        }
    }
}
