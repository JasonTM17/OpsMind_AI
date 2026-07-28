package ai.opsmind.platform.messaging;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.dispatcher", name = "enabled", havingValue = "true")
public final class TransactionalOutboxLeaseStore implements OutboxLeaseRepository {

    private static final Duration MAXIMUM_LEASE = Duration.ofMinutes(5);
    private static final Comparator<OutboxLease> DELIVERY_ORDER = Comparator
        .comparing((OutboxLease lease) -> lease.event().occurredAt())
        .thenComparing(lease -> lease.event().eventId());

    private final JdbcTemplate jdbcTemplate;
    private final TransactionalOutboxClaimer claimer;
    private final EventPayloadIntegrity payloadIntegrity;

    public TransactionalOutboxLeaseStore(
        @Qualifier("dispatcherJdbcTemplate") JdbcTemplate jdbcTemplate,
        EventPayloadIntegrity payloadIntegrity
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.claimer = new TransactionalOutboxClaimer(jdbcTemplate);
        this.payloadIntegrity = payloadIntegrity;
    }

    @Override
    public List<OutboxLease> claimBatch(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        Duration leaseDuration,
        int limit
    ) {
        return claimValidatedBatch(
            organizationId, leaseToken, now, leaseDuration, limit, null
        );
    }

    @Override
    public List<OutboxLease> claimBatchForEventType(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        Duration leaseDuration,
        int limit,
        String eventType
    ) {
        return claimValidatedBatch(
            organizationId,
            leaseToken,
            now,
            leaseDuration,
            limit,
            MessagingInputValidator.requireEventType(eventType)
        );
    }

    private List<OutboxLease> claimValidatedBatch(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        Duration leaseDuration,
        int limit,
        String eventType
    ) {
        MessagingTransactionGuard.requireActive();
        requireClaimArguments(organizationId, leaseToken, now, leaseDuration, limit);
        List<OutboxLease> claimed =
            claimer.claim(organizationId, leaseToken, now, leaseDuration, limit, eventType);

        List<OutboxLease> valid = new ArrayList<>(claimed.size());
        for (OutboxLease lease : claimed) {
            if (isPayloadValid(lease)) {
                valid.add(lease);
            }
            else if (!releaseAfterFailure(
                organizationId,
                lease.event().eventId(),
                leaseToken,
                "event.payload-integrity",
                now,
                now,
                true
            )) {
                throw new IllegalStateException("Corrupt outbox event could not be quarantined.");
            }
        }
        valid.sort(DELIVERY_ORDER);
        return List.copyOf(valid);
    }

    @Override
    public boolean markPublished(
        UUID organizationId,
        UUID eventId,
        UUID leaseToken,
        Instant publishedAt
    ) {
        MessagingTransactionGuard.requireActive();
        MessagingInputValidator.requireIdentity(organizationId, eventId);
        if (leaseToken == null || publishedAt == null) {
            throw new IllegalArgumentException("Lease token and publication time are required.");
        }
        return jdbcTemplate.update(
            "UPDATE outbox_events SET published_at = ?, lease_token = NULL, lease_expires_at = NULL, "
                + "last_error = NULL WHERE organization_id = ? AND event_id = ? AND lease_token = ? "
                + "AND lease_expires_at > transaction_timestamp() "
                + "AND published_at IS NULL AND poisoned_at IS NULL",
            Timestamp.from(publishedAt),
            organizationId,
            eventId,
            leaseToken
        ) == 1;
    }

    @Override
    public boolean releaseAfterFailure(
        UUID organizationId,
        UUID eventId,
        UUID leaseToken,
        String errorCode,
        Instant failedAt,
        Instant nextAttemptAt,
        boolean poison
    ) {
        MessagingTransactionGuard.requireActive();
        MessagingInputValidator.requireIdentity(organizationId, eventId);
        String safeErrorCode = MessagingInputValidator.requireErrorCode(errorCode);
        if (leaseToken == null || failedAt == null || nextAttemptAt == null
            || (!poison && nextAttemptAt.isBefore(failedAt))) {
            throw new IllegalArgumentException("Failure lease and retry timing are invalid.");
        }
        return jdbcTemplate.update(
            "UPDATE outbox_events SET lease_token = NULL, lease_expires_at = NULL, last_error = ?, "
                + "next_attempt_at = CAST(? AS timestamptz), "
                + "poisoned_at = CASE WHEN ? THEN CAST(? AS timestamptz) ELSE NULL::timestamptz END "
                + "WHERE organization_id = ? AND event_id = ? AND lease_token = ? "
                + "AND lease_expires_at > transaction_timestamp() "
                + "AND published_at IS NULL AND poisoned_at IS NULL",
            safeErrorCode,
            Timestamp.from(nextAttemptAt),
            poison,
            Timestamp.from(failedAt),
            organizationId,
            eventId,
            leaseToken
        ) == 1;
    }

    private boolean isPayloadValid(OutboxLease lease) {
        try {
            payloadIntegrity.verify(lease.event());
            return true;
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireClaimArguments(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        Duration leaseDuration,
        int limit
    ) {
        if (organizationId == null || leaseToken == null || now == null || leaseDuration == null
            || leaseDuration.isZero() || leaseDuration.isNegative()
            || leaseDuration.compareTo(MAXIMUM_LEASE) > 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Outbox claim arguments are invalid.");
        }
    }
}
