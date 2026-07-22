package ai.opsmind.platform.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import ai.opsmind.platform.common.api.IdempotencyKey;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class TransactionalIdempotencyRepository implements IdempotencyRepository {

    private static final int MAX_RESPONSE_BYTES = 1_000_000;

    private final JdbcTemplate jdbcTemplate;

    public TransactionalIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IdempotencyClaim claim(
        UUID organizationId,
        UUID actorId,
        IdempotencyKey key,
        byte[] requestDigest
    ) {
        requireTransaction();
        requireIdentity(organizationId, key);
        byte[] digest = RequestDigest.copyAndValidate(requestDigest);
        try {
            int inserted = jdbcTemplate.update(
                "INSERT INTO public.idempotency_records "
                    + "(organization_id, idempotency_key, actor_id, request_digest, status) "
                    + "VALUES (?, ?, ?, ?, 'in_progress') ON CONFLICT DO NOTHING",
                organizationId,
                key.value(),
                actorId,
                digest
            );
            if (inserted == 1) {
                return IdempotencyClaim.acquired();
            }
            ExistingRecord existing = jdbcTemplate.queryForObject(
                "SELECT actor_id, request_digest, status, response_status, response_body::text "
                    + "FROM public.idempotency_records "
                    + "WHERE organization_id = ? AND idempotency_key = ? FOR UPDATE",
                (resultSet, rowNumber) -> new ExistingRecord(
                    resultSet.getObject("actor_id", UUID.class),
                    resultSet.getBytes("request_digest"),
                    resultSet.getString("status"),
                    (Integer) resultSet.getObject("response_status"),
                    resultSet.getString("response_body")
                ),
                organizationId,
                key.value()
            );
            if (!Objects.equals(existing.actorId(), actorId)
                || !RequestDigest.constantTimeEquals(existing.requestDigest(), digest)) {
                throw conflict(
                    "idempotency.request-mismatch",
                    "The Idempotency-Key was already used for a different request."
                );
            }
            if ("in_progress".equals(existing.status())) {
                return IdempotencyClaim.inProgress();
            }
            if (("succeeded".equals(existing.status()) || "failed".equals(existing.status()))
                && existing.responseStatus() != null && existing.responseBody() != null) {
                return IdempotencyClaim.replay(existing.responseStatus(), existing.responseBody());
            }
            throw conflict(
                "idempotency.record-invalid",
                "The stored idempotency record cannot be replayed safely."
            );
        }
        catch (EmptyResultDataAccessException exception) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                "idempotency.record-disappeared",
                "The idempotency record changed before it could be read.",
                exception
            );
        }
        catch (DataIntegrityViolationException exception) {
            throw new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency.persistence-rejected",
                "The idempotency record did not satisfy its contract.",
                exception
            );
        }
        catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void complete(
        UUID organizationId,
        UUID actorId,
        IdempotencyKey key,
        byte[] requestDigest,
        int responseStatus,
        String responseBody
    ) {
        requireTransaction();
        requireIdentity(organizationId, key);
        byte[] digest = RequestDigest.copyAndValidate(requestDigest);
        if (responseStatus < 100 || responseStatus > 599 || responseBody == null
            || responseBody.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Idempotency response is invalid.");
        }
        try {
            int updated = jdbcTemplate.update(
                "UPDATE public.idempotency_records "
                    + "SET status = CASE WHEN ? >= 400 THEN 'failed' ELSE 'succeeded' END, "
                    + "response_status = ?, response_body = CAST(? AS jsonb), completed_at = clock_timestamp() "
                    + "WHERE organization_id = ? AND idempotency_key = ? AND status = 'in_progress' "
                    + "AND actor_id IS NOT DISTINCT FROM ? AND request_digest = ?",
                responseStatus,
                responseStatus,
                responseBody,
                organizationId,
                key.value(),
                actorId,
                digest
            );
            if (updated != 1) {
                throw conflict(
                    "idempotency.complete-conflict",
                    "The idempotency record is missing or was already completed."
                );
            }
        }
        catch (DataIntegrityViolationException exception) {
            throw new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency.response-rejected",
                "The cached response is not valid JSON under the API contract.",
                exception
            );
        }
        catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void requireIdentity(UUID organizationId, IdempotencyKey key) {
        if (organizationId == null || key == null) {
            throw new IllegalArgumentException("Idempotency organization and key are required.");
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotency operations require an active database transaction.");
        }
    }

    private PlatformProblemException conflict(String code, String detail) {
        return new PlatformProblemException(HttpStatus.CONFLICT, code, detail);
    }

    private PlatformProblemException unavailable(DataAccessException cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "idempotency.persistence-unavailable",
            "Idempotency persistence is temporarily unavailable.",
            cause
        );
    }

    private record ExistingRecord(
        UUID actorId,
        byte[] requestDigest,
        String status,
        Integer responseStatus,
        String responseBody
    ) {
    }
}
