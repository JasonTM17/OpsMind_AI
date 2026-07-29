package ai.opsmind.platform.evidence.artifact;

import java.util.UUID;
import java.util.regex.Pattern;

/** Metadata-only artifact intent after a caller has received platform authorization. */
public record EvidenceArtifactCreateCommand(
    UUID idempotencyKey,
    UUID runId,
    String sourceType,
    String sourceIdentity,
    String sourceVersion,
    String dataClassification,
    EvidenceArtifactDigest expectedDigest,
    long expectedByteCount
) {
    static final long MAXIMUM_BYTE_COUNT = 1L << 40;
    private static final Pattern SOURCE_TYPE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern SOURCE_IDENTITY =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,255}");
    private static final Pattern SOURCE_VERSION =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}");
    private static final Pattern DATA_CLASSIFICATION = Pattern.compile("[a-z][a-z0-9-]{2,63}");

    public EvidenceArtifactCreateCommand {
        if (idempotencyKey == null || runId == null || expectedDigest == null
            || !SOURCE_TYPE.matcher(required(sourceType)).matches()
            || !SOURCE_IDENTITY.matcher(required(sourceIdentity)).matches()
            || !SOURCE_VERSION.matcher(required(sourceVersion)).matches()
            || !DATA_CLASSIFICATION.matcher(required(dataClassification)).matches()
            || expectedByteCount < 1 || expectedByteCount > MAXIMUM_BYTE_COUNT) {
            throw new IllegalArgumentException("Artifact metadata intent is invalid.");
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Artifact metadata value is required.");
        }
        return value;
    }
}
