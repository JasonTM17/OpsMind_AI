package ai.opsmind.platform.evidence.artifact.storage;

/** Sanitized object-storage failure. Causes stay server-side and are never rendered to callers. */
public final class EvidenceArtifactStorageException extends RuntimeException {

    public enum FailureKind {
        OUTCOME_UNCERTAIN,
        IMMUTABLE_CONFLICT,
        UNAVAILABLE,
        ACCESS_DENIED,
        STREAM_REJECTED,
        REMOTE_METADATA_MISMATCH
    }

    private final FailureKind kind;
    private final boolean objectMayExist;

    public EvidenceArtifactStorageException(
        FailureKind kind,
        boolean objectMayExist,
        Throwable cause
    ) {
        super("Evidence artifact object storage rejected the operation.", cause);
        if (kind == null) throw new IllegalArgumentException("Storage failure kind is required.");
        this.kind = kind;
        this.objectMayExist = objectMayExist;
    }

    public FailureKind kind() {
        return kind;
    }

    public boolean objectMayExist() {
        return objectMayExist;
    }
}
