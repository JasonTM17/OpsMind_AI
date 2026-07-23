package ai.opsmind.toolgateway.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

import javax.sql.DataSource;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

final class ToolGatewayPostgresTestContext {

    private final DataSource runtimeDataSource;
    private final JdbcTemplate runtimeJdbc;
    private final JdbcTemplate migratorJdbc;
    private final TransactionTemplate transactions;
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
        transactions = new TransactionTemplate(
            new DataSourceTransactionManager(runtimeDataSource)
        );
        objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    JdbcTemplate runtimeJdbc() {
        return runtimeJdbc;
    }

    JdbcTemplate migratorJdbc() {
        return migratorJdbc;
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
            transactions,
            objectMapper,
            131_072,
            leaseDuration,
            Clock.systemUTC()
        );
    }

    JdbcToolAuditWriter auditWriter() {
        return new JdbcToolAuditWriter(runtimeJdbc);
    }

    JdbcToolExecutionTransactionRunner transactionRunner() {
        return new JdbcToolExecutionTransactionRunner(transactions);
    }

    void cleanMutableState() {
        migratorJdbc.update("DELETE FROM tool_gateway.execution_receipts");
        migratorJdbc.update("DELETE FROM tool_gateway.capability_nonce_claims");
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
