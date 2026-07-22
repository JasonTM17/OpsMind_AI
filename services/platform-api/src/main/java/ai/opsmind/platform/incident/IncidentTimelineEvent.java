package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentTimelineEvent(
    UUID eventId,
    UUID organizationId,
    UUID projectId,
    UUID incidentId,
    long incidentVersion,
    String eventType,
    UUID actorId,
    UUID operationId,
    Instant occurredAt,
    String reason,
    IncidentStatus fromStatus,
    IncidentStatus toStatus,
    String rootCause,
    String resolutionSummary
) {
    public static final String CREATED = "INCIDENT_CREATED";
    public static final String STATUS_TRANSITIONED = "INCIDENT_STATUS_TRANSITIONED";
}
