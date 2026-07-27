package ai.opsmind.toolgateway.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

import javax.sql.DataSource;

import ai.opsmind.toolgateway.application.TenantProjectScope;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

final class ToolGatewayPostgresTestContext {

    private final DataSource runtimeDataSource;
    private final JdbcTemplate runtimeJdbc;
    private final JdbcTemplate migratorJdbc;
    private final JdbcTemplate adminJdbc;
    private final TransactionTemplate transactions;
    private final GatewayTenantContextSql tenantContext;
    private final JdbcToolExecutionTransactionRunner transactionRunner;
    private final JsonMapper objectMapper;

    ToolGatewayPostgresTestContext() {
        String url = required("TOOL_GATEWAY_DATABASE_URL");
        runtimeDataSource = dataSource(
            url,
            required("POSTGRES_TOOL_GATEWAY_USER"),
            required("POSTGRES_TOOL_GATEWAY_PASSWORD")
        );
        runtimeJdbc = new JdbcTemplate(runtimeDataSource);
        migratorJdbc = new JdbcTemplate(dataSource(
            url,
            required("POSTGRES_TOOL_GATEWAY_MIGRATOR_USER"),
            required("POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD")
        ));
        adminJdbc = new JdbcTemplate(dataSource(
            url,
            required("POSTGRES_USER"),
            required("POSTGRES_PASSWORD")
        ));
        transactions = new TransactionTemplate(
            new DataSourceTransactionManager(runtimeDataSource)
        );
        tenantContext = new GatewayTenantContextSql(runtimeJdbc);
        transactionRunner = new JdbcToolExecutionTransactionRunner(
            transactions,
            tenantContext
        );
        objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    JdbcTemplate runtimeJdbc() {
        return runtimeJdbc;
    }

    JdbcTemplate migratorJdbc() {
        return migratorJdbc;
    }

    JdbcTemplate adminJdbc() {
        return adminJdbc;
    }

    JdbcTemplate roleJdbc(String userVariable, String passwordVariable) {
        return new JdbcTemplate(dataSource(
            required("TOOL_GATEWAY_DATABASE_URL"),
            required(userVariable),
            required(passwordVariable)
        ));
    }

    JdbcNonceReplayStore nonceStore() {
        return new JdbcNonceReplayStore(runtimeJdbc, transactions);
    }

    JdbcExecutionReceiptStore receiptStore(Duration leaseDuration) {
        return new JdbcExecutionReceiptStore(
            runtimeJdbc,
            objectMapper,
            131_072,
            leaseDuration,
            Duration.ofSeconds(5),
            Clock.systemUTC()
        );
    }

    JdbcToolAuditWriter auditWriter() {
        return new JdbcToolAuditWriter(runtimeJdbc);
    }

    JdbcToolExecutionTransactionRunner transactionRunner() {
        return transactionRunner;
    }

    <T> T inScope(TenantProjectScope scope, Supplier<T> operation) {
        return transactionRunner.required(scope, operation);
    }

    void inScope(TenantProjectScope scope, Runnable operation) {
        transactionRunner.required(scope, () -> {
            operation.run();
            return Boolean.TRUE;
        });
    }

    HikariDataSource singleConnectionRuntimePool(String poolName) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName(poolName);
        configuration.setJdbcUrl(required("TOOL_GATEWAY_DATABASE_URL"));
        configuration.setUsername(required("POSTGRES_TOOL_GATEWAY_USER"));
        configuration.setPassword(required("POSTGRES_TOOL_GATEWAY_PASSWORD"));
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(1);
        configuration.setConnectionTimeout(3_000);
        configuration.setInitializationFailTimeout(5_000);
        return new HikariDataSource(configuration);
    }

    void cleanMutableState() {
        adminJdbc.update("DELETE FROM tool_gateway.execution_receipts");
        adminJdbc.update("DELETE FROM tool_gateway.capability_nonce_claims");
    }

    static byte[] digestBytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8)
            );
        }
        catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    static String digest(String value) {
        return HexFormat.of().formatHex(digestBytes(value));
    }

    private DataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for PostgreSQL integration tests.");
        }
        return value;
    }
}
