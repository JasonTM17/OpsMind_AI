package ai.opsmind.platform.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
    UUID eventId,
    UUID organizationId,
    UUID actorId,
    String action,
    String schemaVersion,
    String resourceType,
    String resourceId,
    UUID operationId,
    Instant occurredAt,
    String payloadJson
) {
    public static final String INCIDENT_SCHEMA_VERSION = "incident-audit-v1";
    public static final String INVESTIGATION_SCHEMA_VERSION = "investigation-audit-v1";
}
