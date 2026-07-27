package ai.opsmind.toolgateway.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(
    named = "OPSMIND_TOOL_GATEWAY_DB_INTEGRATION",
    matches = "true"
)
class ToolGatewayTenantRlsPoolIntegrationTest {

    private final ToolGatewayPostgresTestContext database =
        new ToolGatewayPostgresTestContext();

    @BeforeEach
    void cleanMutableState() {
        database.cleanMutableState();
    }

    @Test
    void transactionLocalScopeDoesNotLeakAcrossCommitRollbackOrMalformedReuse() {
        try (HikariDataSource pool = database.singleConnectionRuntimePool(
            "tool-gateway-tenant-rls-contract"
        )) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(pool)
            );
            JdbcToolExecutionTransactionRunner runner =
                new JdbcToolExecutionTransactionRunner(
                    transactions,
                    new GatewayTenantContextSql(jdbc)
                );
            JdbcExecutionReceiptStore store = new JdbcExecutionReceiptStore(
                jdbc,
                JsonMapper.builder().findAndAddModules().build(),
                131_072,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Clock.systemUTC()
            );

            TenantProjectScope scopeA = scope("1", "2");
            TenantProjectScope sameTenantOtherProject = new TenantProjectScope(
                scopeA.tenantId(),
                scope("1", "3").projectId()
            );
            TenantProjectScope scopeB = scope("a", "b");
            ToolExecutionRequest requestA = request(scopeA, UUID.randomUUID());
            ToolExecutionRequest requestB = request(scopeB, UUID.randomUUID());

            int backendPid = runner.required(scopeA, () -> {
                assertThat(store.claim(
                    scopeA,
                    requestA,
                    digest(requestA)
                ).lease()).isNotNull();
                assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tool_gateway.execution_receipts",
                    Integer.class
                )).isEqualTo(1);
                return backendPid(jdbc);
            });

            assertNoContext(transactions, jdbc, backendPid);
            assertThat(runner.required(sameTenantOtherProject, () -> {
                assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tool_gateway.execution_receipts",
                    Integer.class
                )).isZero();
                return backendPid(jdbc);
            })).isEqualTo(backendPid);
            assertThat(database.migratorJdbc().queryForObject(
                "SELECT count(*) FROM tool_gateway.execution_receipts",
                Integer.class
            )).isZero();

            assertThat(runner.required(scopeB, () -> {
                assertThat(store.claim(
                    scopeB,
                    requestB,
                    digest(requestB)
                ).lease()).isNotNull();
                assertThat(jdbc.queryForObject(
                    "SELECT tenant_id::text FROM tool_gateway.execution_receipts",
                    String.class
                )).isEqualTo(scopeB.tenantId().toString());
                return backendPid(jdbc);
            })).isEqualTo(backendPid);

            assertThatThrownBy(() -> runner.required(scopeA, () -> {
                assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tool_gateway.execution_receipts",
                    Integer.class
                )).isEqualTo(1);
                throw new IllegalStateException("forced rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertNoContext(transactions, jdbc, backendPid);

            int malformedPid = Objects.requireNonNull(transactions.execute(status -> {
                jdbc.queryForObject(
                    "SELECT set_config("
                        + "'opsmind.tool_gateway_tenant_id', 'malformed', true)",
                    String.class
                );
                jdbc.queryForObject(
                    "SELECT set_config("
                        + "'opsmind.tool_gateway_project_id', ?, true)",
                    String.class,
                    scopeA.projectId().toString()
                );
                assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tool_gateway.execution_receipts",
                    Integer.class
                )).isZero();
                return backendPid(jdbc);
            }));
            assertThat(malformedPid).isEqualTo(backendPid);
            assertNoContext(transactions, jdbc, backendPid);
        }
    }

    private void assertNoContext(
        TransactionTemplate transactions,
        JdbcTemplate jdbc,
        int expectedBackendPid
    ) {
        int actualBackendPid = Objects.requireNonNull(transactions.execute(status -> {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tool_gateway.execution_receipts",
                Integer.class
            )).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT nullif(current_setting("
                    + "'opsmind.tool_gateway_tenant_id', true), '') IS NULL",
                Boolean.class
            )).isTrue();
            assertThat(jdbc.queryForObject(
                "SELECT nullif(current_setting("
                    + "'opsmind.tool_gateway_project_id', true), '') IS NULL",
                Boolean.class
            )).isTrue();
            return backendPid(jdbc);
        }));
        assertThat(actualBackendPid).isEqualTo(expectedBackendPid);
    }

    private ToolExecutionRequest request(TenantProjectScope scope, UUID executionId) {
        return new ToolExecutionRequest(
            executionId,
            scope.tenantId(),
            scope.projectId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "operator-test",
            "observability",
            "metrics.query",
            "1.0",
            "prometheus:test",
            Map.of("service", "opsmind-api"),
            Instant.now().plusSeconds(20),
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

    private TenantProjectScope scope(String tenantDigit, String projectDigit) {
        return new TenantProjectScope(
            UUID.fromString(tenantDigit.repeat(8) + "-"
                + tenantDigit.repeat(4) + "-4" + tenantDigit.repeat(3)
                + "-8" + tenantDigit.repeat(3) + "-" + tenantDigit.repeat(12)),
            UUID.fromString(projectDigit.repeat(8) + "-"
                + projectDigit.repeat(4) + "-4" + projectDigit.repeat(3)
                + "-8" + projectDigit.repeat(3) + "-" + projectDigit.repeat(12))
        );
    }

    private String digest(ToolExecutionRequest request) {
        return ToolGatewayPostgresTestContext.digest(request.executionId().toString());
    }

    private int backendPid(JdbcTemplate jdbc) {
        return Objects.requireNonNull(
            jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)
        );
    }
}
