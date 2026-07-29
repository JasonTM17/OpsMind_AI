package ai.opsmind.platform.evidence.artifact.storage;

import java.nio.charset.StandardCharsets;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;

/** Verified remote result retained only inside the artifact control plane. */
public record ArtifactObjectStored(
    EvidenceArtifactDigest digest,
    long byteCount,
    String versionReference,
    String encryptionMetadataReference
) {
    public ArtifactObjectStored {
        if (digest == null || byteCount < 1
            || !validVersionReference(versionReference)
            || !bounded(encryptionMetadataReference, 256)) {
            throw new IllegalArgumentException("Stored artifact object result is invalid.");
        }
    }

    private static boolean validVersionReference(String value) {
        return value != null && !value.isBlank() && !value.equalsIgnoreCase("null")
            && value.getBytes(StandardCharsets.UTF_8).length <= 1_024;
    }

    private static boolean bounded(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }
}
