package ai.opsmind.platform.audit;

import java.sql.Timestamp;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class TransactionalAuditRepository implements AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionalAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(AuditEvent event) {
        requireTransaction();
        if (event == null) {
            throw new IllegalArgumentException("Audit event is required.");
        }
        try {
            jdbcTemplate.update(
                "INSERT INTO audit_events (event_id, organization_id, actor_id, action, resource_type, "
                    + "resource_id, correlation_id, occurred_at, payload, schema_version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                event.eventId(),
                event.organizationId(),
                event.actorId(),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.operationId(),
                Timestamp.from(event.occurredAt()),
                event.payloadJson(),
                event.schemaVersion()
            );
        }
        catch (DataIntegrityViolationException exception) {
            throw new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "audit.persistence-rejected",
                "The audit event did not satisfy its persistence contract.",
                exception
            );
        }
        catch (DataAccessException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "audit.persistence-unavailable",
                "Audit persistence is temporarily unavailable.",
                exception
            );
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit append requires an active database transaction.");
        }
    }
}
