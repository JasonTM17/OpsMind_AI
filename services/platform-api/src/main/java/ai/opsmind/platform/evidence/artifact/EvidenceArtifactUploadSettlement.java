package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;

/** The database-authoritative result of settling one upload attempt. */
public record EvidenceArtifactUploadSettlement(
    boolean transitionApplied,
    EvidenceArtifactLifecycleState lifecycleState,
    long lifecycleVersion,
    long storageGeneration,
    Instant lifecycleUpdatedAt
) {
    public EvidenceArtifactUploadSettlement {
        if (lifecycleState == null || lifecycleVersion < 1 || storageGeneration < 0
            || lifecycleUpdatedAt == null) {
            throw new IllegalArgumentException("Artifact upload settlement is invalid.");
        }
    }

    public boolean isStored() {
        return lifecycleState == EvidenceArtifactLifecycleState.STORED;
    }
}
