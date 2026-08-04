package ai.opsmind.platform.evidence.artifact.lifecycle;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;

/** Explicit reconciliation command shell. It never infers a deletion from a missing object. */
public final class ArtifactReconciliationService {
    private final ArtifactLifecycleService lifecycle = new ArtifactLifecycleService();

    public ArtifactReconciliationResult reconcile(
        EvidenceArtifactMetadata metadata, ArtifactLifecycleCommand command,
        ArtifactReconciliationObservation observation
    ) {
        if (metadata == null || command == null || observation == null) {
            throw new IllegalArgumentException("Artifact reconciliation inputs are required.");
        }
        EvidenceArtifactLifecycleState target = switch (observation) {
            case OBJECT_MATCH -> metadata.lifecycleState() == EvidenceArtifactLifecycleState.PENDING_UPLOAD
                ? EvidenceArtifactLifecycleState.STORED : metadata.lifecycleState();
            case OBJECT_ABSENT, OBJECT_MISMATCH -> metadata.lifecycleState() == EvidenceArtifactLifecycleState.PENDING_UPLOAD
                ? EvidenceArtifactLifecycleState.ORPHANED : metadata.lifecycleState();
            case PURGE_CONFIRMED -> metadata.lifecycleState() == EvidenceArtifactLifecycleState.PURGED
                ? EvidenceArtifactLifecycleState.RECEIPT_RECORDED : metadata.lifecycleState();
        };
        if (command.targetState() != target) {
            throw new IllegalArgumentException("Reconciliation command target does not match observation.");
        }
        if (target == metadata.lifecycleState()) {
            return new ArtifactReconciliationResult(
                observation == ArtifactReconciliationObservation.PURGE_CONFIRMED
                    ? ArtifactReconciliationOutcome.NO_CHANGE_UNCERTAIN
                    : ArtifactReconciliationOutcome.NO_CHANGE_UNCERTAIN, null);
        }
        var transition = lifecycle.transition(metadata,
            new ArtifactLifecycleCommand(command.actorId(), command.authorizationEpoch(),
                command.expectedDigest(), target, command.reason(), command.occurredAt()));
        var outcome = target == EvidenceArtifactLifecycleState.STORED
            ? ArtifactReconciliationOutcome.REBOUND_STORED
            : target == EvidenceArtifactLifecycleState.ORPHANED
                ? ArtifactReconciliationOutcome.MARKED_ORPHANED
                : ArtifactReconciliationOutcome.PURGE_RECEIPT;
        return new ArtifactReconciliationResult(outcome, transition);
    }
}
