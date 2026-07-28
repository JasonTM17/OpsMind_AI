package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.investigation.workflow-starter")
public record InvestigationWorkflowStarterProperties(
    boolean enabled,
    Duration pollInterval,
    Duration leaseDuration,
    Duration rpcSafetyMargin,
    Duration maximumAge,
    Duration initialBackoff,
    Duration maximumBackoff,
    int maximumAttempts,
    int tenantLimit,
    int batchSize
) {
    public InvestigationWorkflowStarterProperties {
        pollInterval = defaultDuration(pollInterval, Duration.ofSeconds(1));
        leaseDuration = defaultDuration(leaseDuration, Duration.ofSeconds(30));
        rpcSafetyMargin = defaultDuration(rpcSafetyMargin, Duration.ofSeconds(5));
        maximumAge = defaultDuration(maximumAge, Duration.ofHours(1));
        initialBackoff = defaultDuration(initialBackoff, Duration.ofSeconds(1));
        maximumBackoff = defaultDuration(maximumBackoff, Duration.ofMinutes(1));
        maximumAttempts = maximumAttempts == 0 ? 8 : maximumAttempts;
        tenantLimit = tenantLimit == 0 ? 25 : tenantLimit;
        batchSize = batchSize == 0 ? 1 : batchSize;
    }

    public void validate() {
        if (!between(pollInterval, Duration.ofMillis(250), Duration.ofMinutes(1))
            || !between(leaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(5))
            || rpcSafetyMargin.isNegative() || rpcSafetyMargin.isZero()
            || rpcSafetyMargin.compareTo(leaseDuration) >= 0
            || !between(maximumAge, Duration.ofMinutes(1), Duration.ofHours(24))
            || !between(initialBackoff, Duration.ofMillis(100), Duration.ofMinutes(1))
            || maximumBackoff.compareTo(initialBackoff) < 0
            || maximumBackoff.compareTo(Duration.ofMinutes(15)) > 0
            || maximumAttempts < 1 || maximumAttempts > 100
            || tenantLimit < 1 || tenantLimit > 100
            || batchSize != 1) {
            throw new IllegalStateException("Workflow starter configuration is outside policy.");
        }
    }

    public void validateRpcEnvelope(Duration rpcTimeout) {
        validate();
        if (rpcTimeout == null || rpcTimeout.isNegative() || rpcTimeout.isZero()
            || rpcTimeout.plus(rpcSafetyMargin).compareTo(leaseDuration) >= 0) {
            throw new IllegalStateException(
                "Temporal RPC timeout and safety margin must fit inside the lease."
            );
        }
    }

    public Duration requiredRpcWindow(Duration rpcTimeout) {
        validateRpcEnvelope(rpcTimeout);
        return rpcTimeout.plus(rpcSafetyMargin);
    }

    public Duration retryDelay(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("Attempt must be positive.");
        Duration delay = initialBackoff;
        for (int index = 1; index < attempt && delay.compareTo(maximumBackoff) < 0; index++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(maximumBackoff) > 0) delay = maximumBackoff;
        }
        return delay;
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private static boolean between(Duration value, Duration minimum, Duration maximum) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
