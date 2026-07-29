package ai.opsmind.platform.evidence.artifact;

/** Approved Phase 1 lifecycle policy constants, intentionally not caller-controlled. */
final class EvidenceArtifactPolicy {

    static final String RETENTION_CLASS = "evidence-90d";
    static final String RESIDENCY_CLASS = "singapore";
    static final String DELETION_CLASS = "delete-within-24h";
    static final long INITIAL_LIFECYCLE_VERSION = 1L;

    private EvidenceArtifactPolicy() { }
}
