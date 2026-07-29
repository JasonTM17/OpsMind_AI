package ai.opsmind.platform.incident;

import java.util.UUID;

/** Minimum transaction-bound scope that internal analyze-only collaborators may receive. */
public record AuthorizedIncidentAnalysisScope(
    UUID organizationId,
    UUID projectId,
    UUID incidentId,
    UUID actorId,
    long authorizationEpoch
) {
    public AuthorizedIncidentAnalysisScope {
        if (organizationId == null || projectId == null || incidentId == null || actorId == null
            || authorizationEpoch < 0) {
            throw new IllegalArgumentException("Authorized analysis scope is invalid.");
        }
    }

    static AuthorizedIncidentAnalysisScope from(IncidentSnapshot incident, UUID actorId) {
        return new AuthorizedIncidentAnalysisScope(
            incident.organizationId(),
            incident.projectId(),
            incident.id(),
            actorId,
            incident.version()
        );
    }
}
