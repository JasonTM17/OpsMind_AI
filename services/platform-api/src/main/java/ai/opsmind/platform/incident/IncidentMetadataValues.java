package ai.opsmind.platform.incident;

import java.util.UUID;

record IncidentMetadataValues(
    String title,
    String summary,
    IncidentSeverity severity,
    UUID ownerId
) {
    static IncidentMetadataValues from(IncidentSnapshot incident) {
        return new IncidentMetadataValues(
            incident.title(), incident.summary(), incident.severity(), incident.ownerId()
        );
    }
}
