package ai.opsmind.platform.evidence.artifact.lifecycle;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;
import ai.opsmind.platform.evidence.artifact.access.ArtifactAccessDeniedException;

import org.springframework.stereotype.Component;

/** Pure lifecycle policy; persistence and object deletion remain explicit external operations. */
@Component
public final class ArtifactLifecycleService {
    public ArtifactLifecycleTransition transition(
        EvidenceArtifactMetadata metadata, ArtifactLifecycleCommand command
    ) {
        if (metadata == null || command == null || !metadata.actorId().equals(command.actorId())
            || metadata.authorizationEpoch() != command.authorizationEpoch()
            || !metadata.expectedDigest().equals(command.expectedDigest())) {
            throw new ArtifactAccessDeniedException();
        }
        var current = metadata.lifecycleState();
        var target = command.targetState();
        if (current == target && target == ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState.RECEIPT_RECORDED) {
            return new ArtifactLifecycleTransition(metadata.artifactId(), current, target,
                metadata.lifecycleVersion(), command.actorId(), command.reason(), command.occurredAt(), true);
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException("Artifact lifecycle transition is not permitted.");
        }
        return new ArtifactLifecycleTransition(metadata.artifactId(), current, target,
            metadata.lifecycleVersion() + 1, command.actorId(), command.reason(), command.occurredAt(), false);
    }
}
