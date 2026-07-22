package ai.opsmind.platform.messaging;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class TransactionalInboxRepository implements InboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionalInboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean claim(UUID organizationId, UUID eventId, String consumer) {
        MessagingTransactionGuard.requireActive();
        MessagingInputValidator.requireIdentity(organizationId, eventId);
        String safeConsumer = MessagingInputValidator.requireConsumer(consumer);
        List<UUID> claimed = jdbcTemplate.query(
            """
            INSERT INTO inbox_events (event_id, organization_id, consumer, attempts)
            VALUES (?, ?, ?, 1)
            ON CONFLICT (organization_id, event_id, consumer) DO UPDATE
               SET attempts = inbox_events.attempts + 1, last_error = NULL
             WHERE inbox_events.status = 'received'
            RETURNING event_id
            """,
            (resultSet, rowNumber) -> resultSet.getObject("event_id", UUID.class),
            eventId,
            organizationId,
            safeConsumer
        );
        return claimed.size() == 1;
    }

    @Override
    public boolean markProcessed(UUID organizationId, UUID eventId, String consumer) {
        MessagingTransactionGuard.requireActive();
        MessagingInputValidator.requireIdentity(organizationId, eventId);
        String safeConsumer = MessagingInputValidator.requireConsumer(consumer);
        return jdbcTemplate.update(
            "UPDATE inbox_events SET status = 'processed', processed_at = clock_timestamp(), "
                + "last_error = NULL WHERE organization_id = ? AND event_id = ? AND consumer = ? "
                + "AND status = 'received'",
            organizationId,
            eventId,
            safeConsumer
        ) == 1;
    }

    @Override
    public boolean markPoisoned(
        UUID organizationId,
        UUID eventId,
        String consumer,
        String errorCode
    ) {
        MessagingTransactionGuard.requireActive();
        MessagingInputValidator.requireIdentity(organizationId, eventId);
        String safeConsumer = MessagingInputValidator.requireConsumer(consumer);
        String safeErrorCode = MessagingInputValidator.requireErrorCode(errorCode);
        return jdbcTemplate.update(
            "UPDATE inbox_events SET status = 'poisoned', last_error = ? "
                + "WHERE organization_id = ? AND event_id = ? AND consumer = ? "
                + "AND status = 'received'",
            safeErrorCode,
            organizationId,
            eventId,
            safeConsumer
        ) == 1;
    }
}
