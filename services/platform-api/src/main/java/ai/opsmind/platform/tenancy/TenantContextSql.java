package ai.opsmind.platform.tenancy;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class TenantContextSql {

    private final JdbcTemplate jdbcTemplate;

    public TenantContextSql(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Applies transaction-local context after connection checkout. The
     * transaction boundary, not a thread-local, is the cleanup guarantee.
     */
    public void apply(UUID organizationId, UUID actorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Tenant context requires an active transaction.");
        }
        if (organizationId == null || actorId == null) {
            throw new IllegalArgumentException("Tenant and actor context are required.");
        }
        jdbcTemplate.queryForObject(
            "SELECT public.opsmind_set_tenant_context(CAST(? AS uuid), CAST(? AS uuid))",
            (resultSet, rowNumber) -> Boolean.TRUE,
            organizationId.toString(),
            actorId.toString()
        );
    }
}
