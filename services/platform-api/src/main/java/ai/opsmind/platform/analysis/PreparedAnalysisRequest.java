package ai.opsmind.platform.analysis;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record PreparedAnalysisRequest(
    byte[] body,
    String requestDigest,
    UUID tenantId,
    UUID incidentId,
    UUID runId,
    String promptVersion,
    String purpose,
    Set<String> dataClassifications,
    Instant deadlineAt
) {
    private static final int MAXIMUM_BODY_BYTES = 1_048_576;
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    public PreparedAnalysisRequest {
        if (body == null || body.length == 0 || body.length > MAXIMUM_BODY_BYTES
            || requestDigest == null || !DIGEST.matcher(requestDigest).matches()
            || tenantId == null || incidentId == null || runId == null
            || promptVersion == null || promptVersion.isBlank()
            || purpose == null || purpose.isBlank() || deadlineAt == null
            || dataClassifications == null || dataClassifications.isEmpty()
            || dataClassifications.size() > 5
            || dataClassifications.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Prepared analysis request is invalid.");
        }
        body = body.clone();
        dataClassifications = Set.copyOf(dataClassifications);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
