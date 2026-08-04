package ai.opsmind.platform.evidence.artifact.access;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;

/** Verified adapter metadata; it deliberately carries no object key or bytes. */
public record ArtifactObjectProbeFacts(boolean present, EvidenceArtifactDigest digest, long byteCount) {
    public ArtifactObjectProbeFacts {
        if (present && (digest == null || byteCount < 0)) {
            throw new IllegalArgumentException("Present artifact probe facts are invalid.");
        }
        if (!present && (digest != null || byteCount != 0)) {
            throw new IllegalArgumentException("Absent artifact probe facts must be empty.");
        }
    }
}
