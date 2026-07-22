package ai.opsmind.toolgateway.application;

import java.time.Instant;

/** Default adapter until a durable, shared nonce store is configured. */
public final class FailClosedNonceReplayStore implements NonceReplayStore {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean claim(String nonce, Instant expiresAt) {
        throw new IllegalStateException("Durable nonce replay storage is unavailable.");
    }
}
