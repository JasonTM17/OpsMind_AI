package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
    UUID id,
    UUID organizationId,
    UUID projectId,
    String title,
    String summary,
    IncidentSeverity severity,
    IncidentStatus status,
    String rootCause,
    String resolutionSummary,
    UUID createdBy,
    UUID updatedBy,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
    static IncidentResponse from(IncidentSnapshot incident) {
        return new IncidentResponse(
            incident.id(),
            incident.organizationId(),
            incident.projectId(),
            incident.title(),
            incident.summary(),
            incident.severity(),
            incident.status(),
            incident.rootCause(),
            incident.resolutionSummary(),
            incident.createdBy(),
            incident.updatedBy(),
            incident.createdAt(),
            incident.updatedAt(),
            incident.version()
        );
    }
}
