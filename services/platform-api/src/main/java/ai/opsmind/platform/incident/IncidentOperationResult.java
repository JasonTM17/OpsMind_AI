package ai.opsmind.platform.incident;

import java.net.URI;
import java.util.UUID;

public record IncidentOperationResult(
    int responseStatus,
    String responseBody,
    URI location,
    String etag,
    UUID operationId
) {
}
