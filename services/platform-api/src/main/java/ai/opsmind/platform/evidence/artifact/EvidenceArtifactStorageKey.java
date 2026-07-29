package ai.opsmind.platform.evidence.artifact;

import java.util.UUID;

/** Internal deterministic object key. It is metadata, never an authorization credential or URL. */
public final class EvidenceArtifactStorageKey {

    private EvidenceArtifactStorageKey() { }

    public static String derive(
        UUID organizationId,
        UUID artifactId,
        EvidenceArtifactDigest digest
    ) {
        if (organizationId == null || artifactId == null || digest == null) {
            throw new IllegalArgumentException("Artifact storage key scope is required.");
        }
        return "artifacts/v1/" + organizationId + "/" + artifactId + "/" + digest.hexadecimal();
    }
}
