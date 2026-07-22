package ai.opsmind.platform.testing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresRoleDataSource {

    private PostgresRoleDataSource() {
    }

    public static HikariDataSource open(
        String poolName,
        PostgresIntegrationEnvironment environment,
        String user,
        String password
    ) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName(poolName);
        configuration.setJdbcUrl(environment.jdbcUrl());
        configuration.setUsername(user);
        configuration.setPassword(password);
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(1);
        return new HikariDataSource(configuration);
    }

    public static TransactionTemplate transactions(HikariDataSource dataSource) {
        return new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
}
