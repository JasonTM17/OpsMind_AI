package ai.opsmind.platform.messaging;

import java.time.Instant;
import java.util.UUID;

public record OutboxLease(
    EventEnvelope event,
    UUID leaseToken,
    Instant leaseExpiresAt,
    int attempt
) {
    public OutboxLease {
        if (event == null || leaseToken == null || leaseExpiresAt == null || attempt < 1) {
            throw new IllegalArgumentException("A complete outbox lease is required.");
        }
    }
}
