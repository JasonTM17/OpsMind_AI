package ai.opsmind.toolgateway.connectors.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.opsmind.toolgateway.application.ToolManifest;
import ai.opsmind.toolgateway.connectors.ConnectorEvidence;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

/** Deterministic, immutable fixture adapter; it has no network or mutation API. */
public final class FixtureObservabilityConnector implements ToolConnector {

    @Override
    public String id() {
        return "fixture-observability";
    }

    @Override
    public ConnectorEvidence execute(ToolExecutionRequest request, ToolManifest manifest) {
        String service = request.resource().substring(manifest.resourcePrefix().length());
        String metric = request.arguments().getOrDefault("metric", "http_errors_total").toString();
        int maximumPoints = numericBound(request.arguments().get("max_points"), request.resultBudget().maxItems());
        String diagnosticCanary = "api_key=" + "sk-" + "fixture-diagnostic-canary-1234"
            + "; owner=operator@example.invalid";
        List<Map<String, Object>> points = List.of(
            Map.<String, Object>of("timestamp", "2030-01-01T00:00:00Z", "value", 2),
            Map.<String, Object>of("timestamp", "2030-01-01T00:01:00Z", "value", 5),
            Map.<String, Object>of("timestamp", "2030-01-01T00:02:00Z", "value", 3)
        ).subList(0, Math.min(3, maximumPoints));
        return new ConnectorEvidence(
            "fixture-prometheus",
            "prometheus:synthetic/" + service,
            Instant.parse("2030-01-01T00:03:00Z"),
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:03:00Z"),
            "fixture-observability@1",
            "synthetic",
            Map.of(
                "service", service,
                "metric", metric,
                "points", points,
                "authorization", "Bearer " + "fixture" + "-secret-canary",
                "diagnostic", diagnosticCanary
            )
        );
    }

    private int numericBound(Object value, int requestMaximum) {
        if (value == null) return Math.min(3, requestMaximum);
        if (!(value instanceof Number number)) return 1;
        return Math.max(1, Math.min(Math.min(number.intValue(), requestMaximum), 3));
    }
}
