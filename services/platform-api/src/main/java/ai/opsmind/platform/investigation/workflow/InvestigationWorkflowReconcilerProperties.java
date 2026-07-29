package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.investigation.workflow-reconciler")
public record InvestigationWorkflowReconcilerProperties(
    boolean enabled,
    Duration pollInterval,
    Duration leaseDuration,
    Duration settlementMargin,
    int maximumAttempts,
    Duration maximumAge,
    Duration maximumHandoffAge,
    Duration initialBackoff,
    Duration maximumBackoff,
    Duration absenceConfirmationDelay,
    Duration namespaceRetention,
    Duration retentionSafetyMargin
) {
    public InvestigationWorkflowReconcilerProperties {
        pollInterval = fallback(pollInterval, Duration.ofSeconds(1));
        leaseDuration = fallback(leaseDuration, Duration.ofSeconds(30));
        settlementMargin = fallback(settlementMargin, Duration.ofSeconds(5));
        maximumAttempts = maximumAttempts == 0 ? 8 : maximumAttempts;
        maximumAge = fallback(maximumAge, Duration.ofHours(1));
        maximumHandoffAge = fallback(maximumHandoffAge, Duration.ofHours(1));
        initialBackoff = fallback(initialBackoff, Duration.ofSeconds(1));
        maximumBackoff = fallback(maximumBackoff, Duration.ofMinutes(1));
        absenceConfirmationDelay = fallback(
            absenceConfirmationDelay, Duration.ofSeconds(10)
        );
        namespaceRetention = fallback(namespaceRetention, Duration.ofDays(30));
        retentionSafetyMargin = fallback(retentionSafetyMargin, Duration.ofDays(1));
    }

    public void validate(Duration rpcTimeout) {
        Duration twoRpcs = requirePositive(rpcTimeout, "Temporal observer RPC timeout")
            .multipliedBy(2);
        if (!between(pollInterval, Duration.ofMillis(250), Duration.ofMinutes(1))
            || !between(leaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(5))
            || !between(settlementMargin, Duration.ofMillis(100), Duration.ofMinutes(1))
            || twoRpcs.plus(settlementMargin).compareTo(leaseDuration) >= 0
            || maximumAttempts < 1 || maximumAttempts > 8
            || !between(maximumAge, Duration.ofMinutes(1), Duration.ofHours(24))
            || !between(maximumHandoffAge, Duration.ofMinutes(1), Duration.ofDays(7))
            || !between(initialBackoff, Duration.ofMillis(100), Duration.ofMinutes(1))
            || maximumBackoff.compareTo(initialBackoff) < 0
            || maximumBackoff.compareTo(Duration.ofMinutes(15)) > 0
            || absenceConfirmationDelay.compareTo(twoRpcs) < 0
            || retentionSafetyMargin.isNegative() || retentionSafetyMargin.isZero()
            || !between(
                namespaceRetention, Duration.ofHours(1), Duration.ofDays(365)
            )
            || retentionSafetyMargin.compareTo(namespaceRetention) >= 0
            || maximumVerifiableAge().isNegative() || maximumVerifiableAge().isZero()
            || maximumHandoffAge.plus(maximumAge).plus(retentionSafetyMargin)
                .compareTo(namespaceRetention) >= 0) {
            throw new IllegalStateException(
                "Workflow reconciler configuration is outside policy."
            );
        }
    }

    public Duration retryDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Reconciliation attempt must be positive.");
        }
        Duration delay = initialBackoff;
        for (int index = 1; index < attempt && delay.compareTo(maximumBackoff) < 0; index++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(maximumBackoff) > 0) {
                delay = maximumBackoff;
            }
        }
        return delay;
    }

    public Duration maximumVerifiableAge() {
        return namespaceRetention.minus(retentionSafetyMargin);
    }

    private static Duration fallback(Duration value, Duration defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalStateException(field + " is outside policy.");
        }
        return value;
    }

    private static boolean between(Duration value, Duration minimum, Duration maximum) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
