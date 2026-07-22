package ai.opsmind.platform.messaging;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "opsmind.dispatcher", name = "enabled", havingValue = "true")
public final class OutboxDispatcherTenantScheduler {

    private static final int MAXIMUM_TENANTS = 100;

    private final JdbcTemplate jdbcTemplate;

    public OutboxDispatcherTenantScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UUID> listReadyTenants(int limit) {
        MessagingTransactionGuard.requireActive();
        if (limit < 1 || limit > MAXIMUM_TENANTS) {
            throw new IllegalArgumentException("Dispatcher tenant limit must be between 1 and 100.");
        }
        return List.copyOf(jdbcTemplate.query(
            "SELECT organization_id FROM public.opsmind_list_dispatch_tenants(?)",
            (resultSet, rowNumber) -> resultSet.getObject("organization_id", UUID.class),
            limit
        ));
    }
}
