package ai.opsmind.platform.persistence;

import java.util.UUID;

import ai.opsmind.platform.common.api.IdempotencyKey;

public interface IdempotencyRepository {

    IdempotencyClaim claim(UUID organizationId, UUID actorId, IdempotencyKey key, byte[] requestDigest);

    void complete(
        UUID organizationId,
        UUID actorId,
        IdempotencyKey key,
        byte[] requestDigest,
        int responseStatus,
        String responseBody
    );

    enum Disposition {
        ACQUIRED,
        IN_PROGRESS,
        REPLAY
    }

    record IdempotencyClaim(
        Disposition disposition,
        int responseStatus,
        String responseBody
    ) {
        public IdempotencyClaim {
            if (disposition == null) {
                throw new IllegalArgumentException("Idempotency disposition is required.");
            }
            if (disposition == Disposition.REPLAY
                && (responseStatus < 100 || responseStatus > 599 || responseBody == null)) {
                throw new IllegalArgumentException("A replay must contain a valid cached response.");
            }
        }

        public static IdempotencyClaim acquired() {
            return new IdempotencyClaim(Disposition.ACQUIRED, 0, null);
        }

        public static IdempotencyClaim inProgress() {
            return new IdempotencyClaim(Disposition.IN_PROGRESS, 0, null);
        }

        public static IdempotencyClaim replay(int responseStatus, String responseBody) {
            return new IdempotencyClaim(Disposition.REPLAY, responseStatus, responseBody);
        }
    }
}
