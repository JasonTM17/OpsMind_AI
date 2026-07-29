package ai.opsmind.toolgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connector admission bounds. Null means an omitted property; explicit zero is
 * invalid so an operator cannot accidentally disable a safety boundary.
 */
@ConfigurationProperties("opsmind.tool-gateway.connector-bulkhead")
public record ConnectorBulkheadProperties(
    Integer globalConcurrency,
    Integer perTenantConcurrency
) {

    private static final int DEFAULT_GLOBAL_CONCURRENCY = 32;
    private static final int DEFAULT_PER_TENANT_CONCURRENCY = 4;
    private static final int MAXIMUM_GLOBAL_CONCURRENCY = 1_024;

    public ConnectorBulkheadProperties {
        globalConcurrency = globalConcurrency == null
            ? DEFAULT_GLOBAL_CONCURRENCY : globalConcurrency;
        perTenantConcurrency = perTenantConcurrency == null
            ? DEFAULT_PER_TENANT_CONCURRENCY : perTenantConcurrency;

        if (globalConcurrency < 1 || globalConcurrency > MAXIMUM_GLOBAL_CONCURRENCY) {
            throw new IllegalArgumentException("Global connector concurrency is invalid.");
        }
        if (perTenantConcurrency < 1 || perTenantConcurrency > globalConcurrency) {
            throw new IllegalArgumentException("Per-tenant connector concurrency is invalid.");
        }
    }
}
