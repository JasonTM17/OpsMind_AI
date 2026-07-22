package ai.opsmind.platform.analysis;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record AnalysisCapabilityGrant(
    String subject,
    UUID tenantId,
    UUID incidentId,
    UUID runId,
    String purpose,
    Set<String> allowedDataClasses,
    String requestDigest,
    Instant deadlineAt
) {
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> PURPOSES = Set.of(
        "incident_investigation",
        "incident_summary"
    );
    private static final Set<String> DATA_CLASSES = Set.of(
        "redacted_metrics",
        "redacted_log_summary",
        "redacted_incident_summary",
        "raw_log",
        "secret",
        "personal_data"
    );

    public AnalysisCapabilityGrant {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("Capability subject is invalid.");
        }
        if (tenantId == null || incidentId == null || runId == null) {
            throw new IllegalArgumentException("Capability resource scope is required.");
        }
        if (!PURPOSES.contains(purpose)) {
            throw new IllegalArgumentException("Capability purpose is invalid.");
        }
        allowedDataClasses = allowedDataClasses == null ? Set.of() : Set.copyOf(allowedDataClasses);
        if (allowedDataClasses.isEmpty() || allowedDataClasses.size() > 5
            || !DATA_CLASSES.containsAll(allowedDataClasses)) {
            throw new IllegalArgumentException("Capability data-class scope is invalid.");
        }
        if (requestDigest == null || !DIGEST.matcher(requestDigest).matches()) {
            throw new IllegalArgumentException("Capability request digest is invalid.");
        }
        if (deadlineAt == null) {
            throw new IllegalArgumentException("Capability deadline is required.");
        }
    }
}
