package ai.opsmind.toolgateway.application;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local replay protection for deterministic fixture tests only. */
public final class FixtureNonceReplayStore implements NonceReplayStore {

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> claims = new ConcurrentHashMap<>();

    public FixtureNonceReplayStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean claim(String nonce, Instant expiresAt) {
        Instant now = clock.instant();
        claims.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        return claims.putIfAbsent(nonce, expiresAt) == null;
    }
}
