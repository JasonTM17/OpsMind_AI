package ai.opsmind.platform.evidence.artifact.lifecycle;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;

/** Explicit, authorization-bound lifecycle intent; no object bytes or storage keys are accepted. */
public record ArtifactLifecycleCommand(
    UUID actorId, long authorizationEpoch, EvidenceArtifactDigest expectedDigest,
    EvidenceArtifactLifecycleState targetState, String reason, Instant occurredAt
) {
    public ArtifactLifecycleCommand {
        if (actorId == null || authorizationEpoch < 0 || expectedDigest == null
            || targetState == null || reason == null || reason.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("Artifact lifecycle command is invalid.");
        }
    }
}
