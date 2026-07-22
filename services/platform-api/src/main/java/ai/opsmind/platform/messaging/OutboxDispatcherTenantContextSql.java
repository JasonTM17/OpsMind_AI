package ai.opsmind.platform.messaging;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "opsmind.dispatcher", name = "enabled", havingValue = "true")
public final class OutboxDispatcherTenantContextSql {

    private final JdbcTemplate jdbcTemplate;

    public OutboxDispatcherTenantContextSql(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID apply(UUID organizationId) {
        MessagingTransactionGuard.requireActive();
        if (organizationId == null) {
            throw new IllegalArgumentException("Dispatcher tenant is required.");
        }
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT public.opsmind_set_dispatcher_tenant_context(CAST(? AS uuid))",
            UUID.class,
            organizationId.toString()
        ));
    }
}
