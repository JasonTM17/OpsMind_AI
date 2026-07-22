package ai.opsmind.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

final class TransactionalOutboxAppender {

    private final JdbcTemplate jdbcTemplate;
    private final EventPayloadIntegrity payloadIntegrity;

    TransactionalOutboxAppender(JdbcTemplate jdbcTemplate, EventPayloadIntegrity payloadIntegrity) {
        this.jdbcTemplate = jdbcTemplate;
        this.payloadIntegrity = payloadIntegrity;
    }

    void append(EventEnvelope event) {
        MessagingTransactionGuard.requireActive();
        if (event == null) {
            throw new IllegalArgumentException("Event is required.");
        }
        payloadIntegrity.verify(event);

        try {
            jdbcTemplate.update(
                "INSERT INTO outbox_events "
                    + "(event_id, organization_id, aggregate_type, aggregate_id, aggregate_sequence, "
                    + "event_type, schema_version, causation_id, correlation_id, occurred_at, payload, "
                    + "payload_bytes, payload_digest) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                event.eventId(),
                event.organizationId(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateSequence(),
                event.eventType(),
                event.schemaVersion(),
                event.causationId(),
                event.correlationId(),
                Timestamp.from(event.occurredAt()),
                event.payloadJson(),
                event.payloadJson().getBytes(StandardCharsets.UTF_8),
                event.payloadDigest()
            );
        }
        catch (DataAccessException exception) {
            throw persistenceProblem(exception);
        }
    }

    private PlatformProblemException persistenceProblem(DataAccessException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                if ("P3001".equals(sqlException.getSQLState())) {
                    return new PlatformProblemException(
                        HttpStatus.CONFLICT,
                        "event.aggregate-sequence-conflict",
                        "The aggregate event sequence is not contiguous.",
                        exception
                    );
                }
                if ("23505".equals(sqlException.getSQLState())) {
                    return new PlatformProblemException(
                        HttpStatus.CONFLICT,
                        "event.duplicate-conflict",
                        "The event identity or aggregate sequence already exists.",
                        exception
                    );
                }
            }
            current = current.getCause();
        }
        if (exception instanceof DataIntegrityViolationException) {
            return new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "event.persistence-rejected",
                "The event could not be persisted under the current contract.",
                exception
            );
        }
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "event.persistence-unavailable",
            "Event persistence is temporarily unavailable.",
            exception
        );
    }
}
