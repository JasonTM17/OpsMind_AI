package ai.opsmind.toolgateway.persistence;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opsmind.tool-gateway.persistence")
public record GatewayPersistenceProperties(
    boolean enabled,
    int maximumResponseBytes,
    Duration executionLeaseDuration
) {
    private static final Duration LEASE_COMPLETION_MARGIN = Duration.ofSeconds(5);

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

    public void validateEnabled(Duration maximumEnabledActionDuration) {
        validateEnabled();
        if (maximumEnabledActionDuration == null
            || maximumEnabledActionDuration.isNegative()) {
            throw new IllegalStateException("Enabled tool action duration is invalid.");
        }
        if (!maximumEnabledActionDuration.isZero()
            && executionLeaseDuration.compareTo(
                maximumEnabledActionDuration.plus(LEASE_COMPLETION_MARGIN)
            ) < 0) {
            throw new IllegalStateException(
                "Tool execution lease does not cover the enabled connector duration."
            );
        }
    }

    Duration leaseCompletionMargin() {
        return LEASE_COMPLETION_MARGIN;
    }
}
