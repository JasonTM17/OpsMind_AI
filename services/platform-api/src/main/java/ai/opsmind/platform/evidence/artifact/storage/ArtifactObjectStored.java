package ai.opsmind.platform.evidence.artifact.storage;

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
            || !bounded(versionReference, 256)
            || !bounded(encryptionMetadataReference, 256)) {
            throw new IllegalArgumentException("Stored artifact object result is invalid.");
        }
    }

    private static boolean bounded(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }
}
