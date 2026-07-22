package ai.opsmind.platform.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxLeaseRepository {

    List<OutboxLease> claimBatch(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        Duration leaseDuration,
        int limit
    );

    boolean markPublished(
        UUID organizationId,
        UUID eventId,
        UUID leaseToken,
        Instant publishedAt
    );

    boolean releaseAfterFailure(
        UUID organizationId,
        UUID eventId,
        UUID leaseToken,
        String errorCode,
        Instant failedAt,
        Instant nextAttemptAt,
        boolean poison
    );
}
