package ai.opsmind.platform.incident;

import java.util.List;

import ai.opsmind.platform.evidence.ResolvedEvidenceRecord;

/** Incident snapshot and evidence resolved in one authorization transaction. */
public record AuthorizedIncidentAnalysisContext(
    AuthorizedIncidentAnalysisEvidence incident,
    List<ResolvedEvidenceRecord> evidence
) {
    public AuthorizedIncidentAnalysisContext {
        if (incident == null || evidence == null) {
            throw new IllegalArgumentException("Authorized analysis context is incomplete.");
        }
        evidence = List.copyOf(evidence);
    }
}
