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
@EnableConfigurationProperties(DispatcherDataSourceProperties.class)
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "opsmind.dispatcher", name = "enabled", havingValue = "true")
public class DispatcherDataSourceConfiguration {

    @Bean(
        name = "dispatcherDataSource",
        destroyMethod = "close",
        defaultCandidate = false
    )
    DataSource dispatcherDataSource(DispatcherDataSourceProperties properties) {
        properties.validate();
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("opsmind-dispatcher");
        configuration.setJdbcUrl(properties.getUrl());
        configuration.setUsername(properties.getUsername());
        configuration.setPassword(properties.getPassword());
        configuration.setMaximumPoolSize(properties.getMaximumPoolSize());
        configuration.setMinimumIdle(1);
        configuration.setConnectionTimeout(properties.getConnectionTimeoutMs());
        configuration.setReadOnly(false);
        return new HikariDataSource(configuration);
    }

    @Bean(name = "dispatcherJdbcTemplate", defaultCandidate = false)
    JdbcTemplate dispatcherJdbcTemplate(
        @Qualifier("dispatcherDataSource") DataSource dataSource
    ) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "dispatcherTransactionManager", defaultCandidate = false)
    PlatformTransactionManager dispatcherTransactionManager(
        @Qualifier("dispatcherDataSource") DataSource dataSource
    ) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    DispatcherDatabaseIdentity dispatcherDatabaseIdentity(
        @Qualifier("dispatcherJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("dispatcherTransactionManager") PlatformTransactionManager transactionManager
    ) {
        Map<String, Object> identity = Objects.requireNonNull(
            new TransactionTemplate(transactionManager).execute(status ->
                jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS session_user, current_user::text AS current_user"
                )
            )
        );
        return new DispatcherDatabaseIdentity(
            Objects.toString(identity.get("session_user"), ""),
            Objects.toString(identity.get("current_user"), "")
        );
    }

    @Bean(name = "dispatcherInboxRepository")
    InboxRepository dispatcherInboxRepository(
        @Qualifier("dispatcherJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        return new TransactionalInboxRepository(jdbcTemplate);
    }
}
