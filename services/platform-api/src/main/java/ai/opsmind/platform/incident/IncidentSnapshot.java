package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentSnapshot(
    UUID id,
    UUID organizationId,
    UUID projectId,
    String title,
    String summary,
    IncidentSeverity severity,
    IncidentStatus status,
    UUID ownerId,
    String rootCause,
    String resolutionSummary,
    UUID createdBy,
    UUID updatedBy,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
    public IncidentSnapshot(
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
        this(
            id, organizationId, projectId, title, summary, severity, status, null,
            rootCause, resolutionSummary, createdBy, updatedBy, createdAt, updatedAt, version
        );
    }
}
