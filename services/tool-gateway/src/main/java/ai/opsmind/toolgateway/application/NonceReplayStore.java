package ai.opsmind.toolgateway.application;

import java.time.Instant;

@FunctionalInterface
public interface NonceReplayStore {

    default boolean available() {
        return true;
    }

    boolean claim(String nonce, Instant expiresAt);
}
