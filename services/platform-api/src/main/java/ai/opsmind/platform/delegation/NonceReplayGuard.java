package ai.opsmind.platform.delegation;

import java.time.Instant;

public interface NonceReplayGuard {

    /** Atomically reserves a nonce until the capability expiry. */
    boolean reserve(String nonce, Instant expiresAt);
}
