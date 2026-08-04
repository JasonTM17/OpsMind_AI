package ai.opsmind.platform.evidence.artifact.lifecycle;

/** Storage probe fact supplied by an adapter; absence alone never proves purge success. */
public enum ArtifactReconciliationObservation {
    OBJECT_MATCH,
    OBJECT_ABSENT,
    OBJECT_MISMATCH,
    PURGE_CONFIRMED
}
