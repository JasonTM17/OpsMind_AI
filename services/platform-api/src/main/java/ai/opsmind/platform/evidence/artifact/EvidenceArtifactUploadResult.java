package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;
import java.util.UUID;

/** Safe upload result; it intentionally excludes object-store references and encryption metadata. */
public record EvidenceArtifactUploadResult(
    UUID artifactId,
    EvidenceArtifactLifecycleState lifecycleState,
    long lifecycleVersion,
    Instant lifecycleUpdatedAt
) {
    public EvidenceArtifactUploadResult {
        if (artifactId == null || lifecycleState == null || lifecycleVersion < 1
            || lifecycleUpdatedAt == null) {
            throw new IllegalArgumentException("Artifact upload result is invalid.");
        }
    }

    static EvidenceArtifactUploadResult from(
        EvidenceArtifactUploadClaim claim,
        EvidenceArtifactUploadSettlement settlement
    ) {
        return new EvidenceArtifactUploadResult(
            claim.artifact().artifactId(), settlement.lifecycleState(), settlement.lifecycleVersion(),
            settlement.lifecycleUpdatedAt()
        );
    }
}
