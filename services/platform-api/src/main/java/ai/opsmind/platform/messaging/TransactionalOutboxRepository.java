package ai.opsmind.platform.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class TransactionalOutboxRepository implements OutboxRepository {

    private final TransactionalOutboxAppender appender;

    public TransactionalOutboxRepository(
        JdbcTemplate jdbcTemplate,
        EventPayloadIntegrity payloadIntegrity
    ) {
        this.appender = new TransactionalOutboxAppender(jdbcTemplate, payloadIntegrity);
    }

    @Override
    public void append(EventEnvelope event) {
        appender.append(event);
    }
}
