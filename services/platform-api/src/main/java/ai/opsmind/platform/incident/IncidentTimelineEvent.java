package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

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
    String resolutionSummary,
    @JsonInclude(JsonInclude.Include.NON_NULL) IncidentMetadataValues metadata
) {
    public static final String CREATED = "INCIDENT_CREATED";
    public static final String STATUS_TRANSITIONED = "INCIDENT_STATUS_TRANSITIONED";
    public static final String METADATA_PATCHED = "INCIDENT_METADATA_PATCHED";

    public IncidentTimelineEvent(
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
        this(
            eventId, organizationId, projectId, incidentId, incidentVersion, eventType,
            actorId, operationId, occurredAt, reason, fromStatus, toStatus,
            rootCause, resolutionSummary, null
        );
    }
}
