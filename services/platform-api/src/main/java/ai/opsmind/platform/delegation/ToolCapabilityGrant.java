package ai.opsmind.platform.delegation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact one-call authority sent separately from the Tool Gateway workload token. */
public record ToolCapabilityGrant(
    String subject,
    UUID organizationId,
    UUID projectId,
    UUID incidentId,
    UUID runId,
    String action,
    String resource,
    Set<String> roles,
    long maximumBytes,
    String requestDigest,
    String policyVersion,
    Instant deadlineAt
) {
    private static final Pattern ACTION = Pattern.compile(
        "[a-z][a-z0-9.-]*:[a-z][a-z0-9._-]*:[0-9]+\\.[0-9]+"
    );
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:@/-]*");

    public ToolCapabilityGrant {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (!safe(subject, 255) || organizationId == null || projectId == null
            || incidentId == null || runId == null || action == null
            || !ACTION.matcher(action).matches() || !safe(resource, 256)
            || roles.isEmpty() || roles.size() > 32
            || roles.stream().anyMatch(role -> !safe(role, 256))
            || maximumBytes < 1 || maximumBytes > 10_485_760
            || requestDigest == null || !DIGEST.matcher(requestDigest).matches()
            || !safe(policyVersion, 64) || deadlineAt == null) {
            throw new IllegalArgumentException("Tool capability grant is invalid.");
        }
    }

    private static boolean safe(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
            && SAFE.matcher(value).matches();
    }
}
