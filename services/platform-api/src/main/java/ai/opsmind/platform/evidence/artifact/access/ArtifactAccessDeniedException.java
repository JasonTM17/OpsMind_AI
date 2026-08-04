package ai.opsmind.platform.evidence.artifact.access;

/** Non-enumerating failure for every artifact authorization or integrity denial. */
public final class ArtifactAccessDeniedException extends RuntimeException {
    public ArtifactAccessDeniedException() {
        super("Evidence artifact was not found or is not available.");
    }
}
