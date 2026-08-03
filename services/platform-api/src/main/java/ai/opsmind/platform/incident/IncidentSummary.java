package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentSummary(
    UUID id,
    String title,
    IncidentSeverity severity,
    IncidentStatus status,
    Instant updatedAt,
    long version
) {
}
