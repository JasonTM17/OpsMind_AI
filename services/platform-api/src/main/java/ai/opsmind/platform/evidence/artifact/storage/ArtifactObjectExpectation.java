package ai.opsmind.platform.evidence.artifact.storage;

import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;

/** Application-owned object identity and integrity contract after authorization. */
public record ArtifactObjectExpectation(
    UUID artifactId,
    String storageKey,
    EvidenceArtifactDigest expectedDigest,
    long expectedByteCount
) {
    public ArtifactObjectExpectation {
        if (artifactId == null || storageKey == null || storageKey.isBlank()
            || storageKey.length() > 512 || expectedDigest == null || expectedByteCount < 1) {
            throw new IllegalArgumentException("Artifact object expectation is invalid.");
        }
    }
}
