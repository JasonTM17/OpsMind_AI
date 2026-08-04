package ai.opsmind.platform.evidence.artifact.lifecycle;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;

/** Explicit, authorization-bound lifecycle intent; no object bytes or storage keys are accepted. */
public record ArtifactLifecycleCommand(
    UUID actorId, long authorizationEpoch, EvidenceArtifactDigest expectedDigest,
    EvidenceArtifactLifecycleState targetState, String reason, Instant occurredAt
) {
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    public ArtifactLifecycleCommand {
        if (actorId == null || authorizationEpoch < 0 || expectedDigest == null
            || targetState == null || reason == null || !REASON.matcher(reason).matches()
            || occurredAt == null) {
            throw new IllegalArgumentException("Artifact lifecycle command is invalid.");
        }
    }
}
