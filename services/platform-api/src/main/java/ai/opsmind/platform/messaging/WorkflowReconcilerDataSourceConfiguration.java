package ai.opsmind.platform.messaging;

import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowReconcilerDataSourceProperties.class)
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
    prefix = "opsmind.workflow-reconciler", name = "enabled", havingValue = "true"
)
public class WorkflowReconcilerDataSourceConfiguration {

    @Bean(
        name = "workflowReconcilerDataSource",
        destroyMethod = "close",
        defaultCandidate = false
    )
    DataSource workflowReconcilerDataSource(
        WorkflowReconcilerDataSourceProperties properties
    ) {
        properties.validate();
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("opsmind-workflow-reconciler");
        configuration.setJdbcUrl(properties.getUrl());
        configuration.setUsername(properties.getUsername());
        configuration.setPassword(properties.getPassword());
        configuration.setMaximumPoolSize(properties.getMaximumPoolSize());
        configuration.setMinimumIdle(1);
        configuration.setConnectionTimeout(properties.getConnectionTimeoutMs());
        configuration.addDataSourceProperty(
            "socketTimeout", properties.getQueryTimeoutSeconds()
        );
        configuration.setReadOnly(false);
        return new HikariDataSource(configuration);
    }

    @Bean(name = "workflowReconcilerJdbcTemplate", defaultCandidate = false)
    JdbcTemplate workflowReconcilerJdbcTemplate(
        @Qualifier("workflowReconcilerDataSource") DataSource dataSource,
        WorkflowReconcilerDataSourceProperties properties
    ) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(properties.getQueryTimeoutSeconds());
        return jdbcTemplate;
    }

    @Bean(name = "workflowReconcilerTransactionManager", defaultCandidate = false)
    PlatformTransactionManager workflowReconcilerTransactionManager(
        @Qualifier("workflowReconcilerDataSource") DataSource dataSource,
        WorkflowReconcilerDataSourceProperties properties
    ) {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.setDefaultTimeout(properties.getQueryTimeoutSeconds());
        return transactionManager;
    }

    @Bean
    WorkflowReconcilerDatabaseIdentity workflowReconcilerDatabaseIdentity(
        @Qualifier("workflowReconcilerJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("workflowReconcilerTransactionManager")
        PlatformTransactionManager transactionManager
    ) {
        Map<String, Object> identity = Objects.requireNonNull(
            new TransactionTemplate(transactionManager).execute(status ->
                jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS session_user, current_user::text AS current_user"
                )
            )
        );
        return new WorkflowReconcilerDatabaseIdentity(
            Objects.toString(identity.get("session_user"), ""),
            Objects.toString(identity.get("current_user"), "")
        );
    }
}
