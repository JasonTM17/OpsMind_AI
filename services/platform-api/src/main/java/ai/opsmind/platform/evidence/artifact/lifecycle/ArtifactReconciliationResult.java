package ai.opsmind.platform.evidence.artifact.lifecycle;

public record ArtifactReconciliationResult(
    ArtifactReconciliationOutcome outcome, ArtifactLifecycleTransition transition
) { }
