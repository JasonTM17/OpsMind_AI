package ai.opsmind.toolgateway.persistence;

import ai.opsmind.toolgateway.application.TenantProjectScope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds capability-derived scope to the checked-out PostgreSQL connection for
 * the lifetime of the current transaction only.
 */
public final class GatewayTenantContextSql {

    private final JdbcTemplate jdbc;

    public GatewayTenantContextSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void apply(TenantProjectScope scope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Gateway tenant context requires an active transaction.");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Tenant and project scope are required.");
        }
        jdbc.queryForObject(
            "SELECT tool_gateway.set_tenant_context(CAST(? AS uuid), CAST(? AS uuid))",
            (result, ignored) -> Boolean.TRUE,
            scope.tenantId().toString(),
            scope.projectId().toString()
        );
    }
}
