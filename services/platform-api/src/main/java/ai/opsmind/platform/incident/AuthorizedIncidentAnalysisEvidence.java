package ai.opsmind.platform.incident;

import java.util.UUID;

/** Incident snapshot captured in the same transaction that authorizes analysis access. */
public record AuthorizedIncidentAnalysisEvidence(
    UUID organizationId,
    UUID projectId,
    UUID incidentId,
    UUID actorId,
    String title,
    String summary,
    IncidentSeverity severity,
    IncidentStatus status,
    String rootCause,
    String resolutionSummary,
    long version
) {
    public AuthorizedIncidentAnalysisEvidence {
        if (organizationId == null || projectId == null || incidentId == null || actorId == null
            || title == null || title.isBlank() || summary == null || summary.isBlank()
            || severity == null || status == null || version < 0) {
            throw new IllegalArgumentException("Authorized incident evidence is invalid.");
        }
    }

    static AuthorizedIncidentAnalysisEvidence from(IncidentSnapshot incident, UUID actorId) {
        return new AuthorizedIncidentAnalysisEvidence(
            incident.organizationId(),
            incident.projectId(),
            incident.id(),
            actorId,
            incident.title(),
            incident.summary(),
            incident.severity(),
            incident.status(),
            incident.rootCause(),
            incident.resolutionSummary(),
            incident.version()
        );
    }
}
