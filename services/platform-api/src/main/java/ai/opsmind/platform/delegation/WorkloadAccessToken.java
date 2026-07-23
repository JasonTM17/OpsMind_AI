package ai.opsmind.platform.delegation;

import java.time.Instant;

record WorkloadAccessToken(String value, Instant expiresAt) {

    WorkloadAccessToken {
        if (value == null || value.isBlank() || value.length() > 16_384 || expiresAt == null) {
            throw new IllegalArgumentException("Workload access token is invalid.");
        }
    }
}
