package ai.opsmind.platform.evidence.artifact;

/** Outcomes accepted by the fenced database settlement capability. */
enum EvidenceArtifactUploadOutcome {
    STORED,
    FAILED,
    UNCERTAIN,
    ORPHANED
}
