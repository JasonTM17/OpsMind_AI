package ai.opsmind.toolgateway.connectors;

import java.time.Instant;
import java.util.Map;

public record ConnectorEvidence(
    String source,
    String targetIdentity,
    Instant observedAt,
    Instant windowStart,
    Instant windowEnd,
    String connectorVersion,
    String trustClass,
    Map<String, Object> content
) {
    public ConnectorEvidence {
        content = Map.copyOf(content);
    }
}
