package ai.opsmind.toolgateway.persistence;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opsmind.tool-gateway.persistence")
public record GatewayPersistenceProperties(
    boolean enabled,
    int maximumResponseBytes,
    Duration executionLeaseDuration
) {
    public GatewayPersistenceProperties {
        maximumResponseBytes = maximumResponseBytes == 0 ? 131_072 : maximumResponseBytes;
        executionLeaseDuration = executionLeaseDuration == null
            ? Duration.ofSeconds(30) : executionLeaseDuration;
    }

    public void validateEnabled() {
        if (!enabled) throw new IllegalStateException("Tool Gateway persistence is disabled.");
        if (maximumResponseBytes < 65_536 || maximumResponseBytes > 131_072) {
            throw new IllegalStateException("Persisted Tool Gateway response bound is invalid.");
        }
        if (executionLeaseDuration.isNegative()
            || executionLeaseDuration.isZero()
            || executionLeaseDuration.compareTo(Duration.ofMillis(100)) < 0
            || executionLeaseDuration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("Tool execution lease duration is invalid.");
        }
    }
}
