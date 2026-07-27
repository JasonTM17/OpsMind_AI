package ai.opsmind.toolgateway.persistence;

import java.time.Clock;

import ai.opsmind.toolgateway.application.ExecutionReceiptStore;
import ai.opsmind.toolgateway.application.NonceReplayStore;
import ai.opsmind.toolgateway.application.ToolExecutionTransactionRunner;
import ai.opsmind.toolgateway.application.ToolManifestRegistry;
import ai.opsmind.toolgateway.audit.ToolAuditWriter;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnProperty(
    prefix = "opsmind.tool-gateway.persistence",
    name = "enabled",
    havingValue = "true"
)
@EnableConfigurationProperties(GatewayPersistenceProperties.class)
public class GatewayPersistenceConfiguration {

    @Bean
    TransactionTemplate toolGatewayTransactionTemplate(
        PlatformTransactionManager transactionManager
    ) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    GatewayTenantContextSql gatewayTenantContextSql(JdbcTemplate jdbc) {
        return new GatewayTenantContextSql(jdbc);
    }

    @Bean
    NonceReplayStore nonceReplayStore(
        JdbcTemplate jdbc,
        TransactionTemplate toolGatewayTransactionTemplate
    ) {
        return new JdbcNonceReplayStore(jdbc, toolGatewayTransactionTemplate);
    }

    @Bean
    ExecutionReceiptStore executionReceiptStore(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        GatewayPersistenceProperties properties,
        ToolManifestRegistry manifestRegistry,
        Clock gatewayClock
    ) {
        properties.validateEnabled(manifestRegistry.maximumEnabledDuration());
        return new JdbcExecutionReceiptStore(
            jdbc,
            objectMapper,
            properties.maximumResponseBytes(),
            properties.executionLeaseDuration(),
            properties.leaseCompletionMargin(),
            gatewayClock
        );
    }

    @Bean
    ToolAuditWriter toolAuditWriter(JdbcTemplate jdbc) {
        return new JdbcToolAuditWriter(jdbc);
    }

    @Bean
    ToolExecutionTransactionRunner toolExecutionTransactionRunner(
        TransactionTemplate toolGatewayTransactionTemplate,
        GatewayTenantContextSql gatewayTenantContextSql
    ) {
        return new JdbcToolExecutionTransactionRunner(
            toolGatewayTransactionTemplate,
            gatewayTenantContextSql
        );
    }
}
