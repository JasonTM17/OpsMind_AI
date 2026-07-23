package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;

/** Verified caller and immutable incident snapshot captured before a synchronous run starts. */
public record InvestigationExecutionContext(
    OpsMindPrincipal principal,
    AuthorizedIncidentAnalysisEvidence initialIncident
) {
    public InvestigationExecutionContext {
        if (principal == null || initialIncident == null) {
            throw new IllegalArgumentException("Investigation execution context is incomplete.");
        }
    }
}
