package ai.opsmind.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE3_DB_INTEGRATION", matches = "true")
class TenantRlsPoolIntegrationTest {

    @Test
    void transactionContextDoesNotLeakAcrossPhysicalConnectionReuseAndFailurePaths() throws SQLException {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);

        HikariConfig poolConfiguration = new HikariConfig();
        poolConfiguration.setPoolName("phase3-tenant-rls-contract");
        poolConfiguration.setJdbcUrl(environment.jdbcUrl());
        poolConfiguration.setUsername(environment.appUser());
        poolConfiguration.setPassword(environment.appPassword());
        poolConfiguration.setMaximumPoolSize(1);
        poolConfiguration.setMinimumIdle(1);
        poolConfiguration.setConnectionTimeout(3_000);
        poolConfiguration.setInitializationFailTimeout(5_000);

        try (HikariDataSource dataSource = new HikariDataSource(poolConfiguration)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
            TenantContextSql tenantContext = new TenantContextSql(jdbcTemplate);

            int backendPid = inTenantTransaction(
                transactions,
                tenantContext,
                jdbcTemplate,
                TENANT_A,
                USER_A,
                "aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            );

            assertNoTenantContext(transactions, jdbcTemplate, backendPid);

            assertThat(inTenantTransaction(
                transactions,
                tenantContext,
                jdbcTemplate,
                TENANT_B,
                USER_B,
                "bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            )).isEqualTo(backendPid);

            assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                tenantContext.apply(TENANT_B, USER_A)
            )).isInstanceOf(DataAccessException.class);
            assertNoTenantContext(transactions, jdbcTemplate, backendPid);

            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                tenantContext.apply(TENANT_A, USER_A);
                jdbcTemplate.execute("SET LOCAL statement_timeout = '50ms'");
                jdbcTemplate.execute("SELECT pg_sleep(1)");
            })).isInstanceOf(DataAccessException.class);
            assertNoTenantContext(transactions, jdbcTemplate, backendPid);

            assertThat(inTenantTransaction(
                transactions,
                tenantContext,
                jdbcTemplate,
                TENANT_B,
                USER_B,
                "bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            )).isEqualTo(backendPid);
        }
    }

    private static int inTenantTransaction(
        TransactionTemplate transactions,
        TenantContextSql tenantContext,
        JdbcTemplate jdbcTemplate,
        UUID tenantId,
        UUID actorId,
        String expectedProjectId
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            tenantContext.apply(tenantId, actorId);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM projects", Integer.class))
                .isEqualTo(1);
            assertThat(jdbcTemplate.queryForList("SELECT id::text FROM projects", String.class))
                .isEqualTo(List.of(expectedProjectId));
            return backendPid(jdbcTemplate);
        }));
    }

    private static void assertNoTenantContext(
        TransactionTemplate transactions,
        JdbcTemplate jdbcTemplate,
        int expectedBackendPid
    ) {
        int actualBackendPid = Objects.requireNonNull(transactions.execute(status -> {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM projects", Integer.class))
                .isZero();
            return backendPid(jdbcTemplate);
        }));
        assertThat(actualBackendPid).isEqualTo(expectedBackendPid);
    }

    private static int backendPid(JdbcTemplate jdbcTemplate) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
    }

}
